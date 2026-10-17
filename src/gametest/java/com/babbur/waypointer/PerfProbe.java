package com.babbur.waypointer;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.screen.WaypointerScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plays a real route library as the Crystal Hollows ({@code -PperfProbe=<config dir>}) and writes
 * frame-time percentiles per scenario to build/reports/perf/frames.txt; the Flight Recorder
 * profile of the same run sits beside it.
 */
@SuppressWarnings("UnstableApiUsage")
final class PerfProbe {
    private static final int FRAMES = 1500;
    private static final long[] frameNanos = new long[FRAMES + 1];
    private static volatile int recorded = -1;

    private PerfProbe() {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        LevelRenderEvents.END_MAIN.register(renderContext -> {
            int index = recorded;
            if (index >= 0 && index <= FRAMES) {
                frameNanos[index] = System.nanoTime();
                recorded = index + 1;
            }
        });
        context.waitFor(client -> client.player != null && WaypointerClient.api() != null
                && WaypointerClient.api().currentZone() != null
                && "crystal_hollows".equals(WaypointerClient.api().currentZone().id()), 600);
        world.getServer().runCommand("gamemode spectator @a");
        world.getServer().runCommand("tp @a 484 88 271 0 10");
        context.runOnClient(client -> {
            client.options.framerateLimit().set(260);
            client.options.enableVsync().set(false);
            // The probe sends no input; keep the idle-player limit from throttling menu frames.
            client.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        });
        context.waitTicks(60);
        world.getClientLevel().waitForChunksRender();
        context.waitForScreen(null);

        List<String> report = new ArrayList<>();
        ActiveGroupManager manager = WaypointerClient.manager();
        WaypointGroup coal = context.computeOnClient(client -> manager.allGroups().stream()
                .filter(group -> group.name().startsWith("#1 Coal Miner"))
                .findFirst().orElse(null));
        List<WaypointGroup> enabled = context.computeOnClient(client -> manager.allGroups().stream()
                .filter(WaypointGroup::enabled).toList());
        report.add("library: " + manager.allGroups().size() + " routes, active here: "
                + context.computeOnClient(client -> manager.activeGroups().size())
                + ", coal: " + (coal == null ? "missing" : coal.size() + " points"));

        change(context, manager, groups -> enabled.forEach(group -> group.setEnabled(false)));
        report.add(measure(context, "no routes"));
        change(context, manager, groups -> enabled.forEach(group -> group.setEnabled(true)));
        report.add(measure(context, "library as-is"));
        context.runOnClient(client -> WaypointerClient.config().setCrystalHollowsMinimap(false));
        report.add(measure(context, "library as-is, minimap off"));
        context.runOnClient(client -> WaypointerClient.config().setCrystalHollowsMinimap(true));
        if (coal != null) {
            change(context, manager, groups -> coal.setLoadMode(WaypointGroup.LoadMode.STATIC));
            report.add(measure(context, "coal route static"));
            context.runOnClient(client -> WaypointerClient.config().setShowLabels(false));
            report.add(measure(context, "coal route static, labels off"));
            context.runOnClient(client -> WaypointerClient.config().setShowLabels(true));
            change(context, manager, groups -> coal.setLoadMode(WaypointGroup.LoadMode.SEQUENCE));
        }
        if (coal != null) {
            context.runOnClient(client -> MinecraftCompat.setScreen(client,
                    new com.babbur.waypointer.screen.PreviewProbeScreen(coal)));
            context.waitTicks(10);
            report.add(measure(context, "catalog map of coal route"));
        }
        context.runOnClient(client -> WaypointerScreen.open(manager, WaypointerClient.config()));
        context.waitTicks(10);
        report.add(measure(context, "route hub open"));
        context.runOnClient(client -> MinecraftCompat.setScreen(client, null));

        try {
            Path out = Path.of(System.getProperty("waypointer.test.perfProbe"), "frames.txt");
            Files.write(out, report, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void change(ClientGameTestContext context, ActiveGroupManager manager,
                               Consumer<ActiveGroupManager> edit) {
        context.runOnClient(client -> {
            edit.accept(manager);
            manager.fireTransientDataChanged();
        });
        context.waitTicks(10);
    }

    private static String measure(ClientGameTestContext context, String name) {
        context.waitTicks(20);
        recorded = 0;
        context.waitFor(client -> recorded > FRAMES, 4000);
        recorded = -1;
        double[] ms = new double[FRAMES];
        for (int i = 0; i < FRAMES; i++) ms[i] = (frameNanos[i + 1] - frameNanos[i]) / 1e6;
        double mean = Arrays.stream(ms).average().orElse(0);
        Arrays.sort(ms);
        return String.format("%-32s mean %6.2f ms (%5.0f fps)  p50 %6.2f  p95 %6.2f  p99 %6.2f  max %6.2f",
                name, mean, 1000 / mean, ms[FRAMES / 2], ms[FRAMES * 95 / 100], ms[FRAMES * 99 / 100], ms[FRAMES - 1]);
    }
}
