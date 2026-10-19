package com.babbur.waypointer;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.render.RenderCost;
import com.babbur.waypointer.render.gpu.OverlayRenderer;
import com.babbur.waypointer.screen.GroupEditScreen;
import com.babbur.waypointer.screen.PreviewProbeScreen;
import com.babbur.waypointer.screen.WaypointerScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.InactivityFpsLimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

@SuppressWarnings("UnstableApiUsage")
final class PerfProbe {
    private static final int FRAMES = 1500;
    private static final long[] frameNanos = new long[FRAMES + 1];
    private static final int[] frameDraws = new int[FRAMES + 1];
    private static final long[] frameRebuilds = new long[FRAMES + 1];
    private static final long[] frameCost = new long[FRAMES + 1];
    private static volatile int recorded = -1;
    private static volatile boolean moving;

    private PerfProbe() {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        LevelRenderEvents.END_MAIN.register(renderContext -> {
            int index = recorded;
            if (index >= 0 && index <= FRAMES) {
                frameNanos[index] = System.nanoTime();
                OverlayRenderer overlay = OverlayRenderer.activeOrNull();
                frameDraws[index] = overlay == null ? 0 : overlay.lastDrawCalls();
                frameRebuilds[index] = overlay == null ? 0 : overlay.fullRebuilds();
                frameCost[index] = RenderCost.totalNanos();
                recorded = index + 1;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!moving || client.player == null) return;
            double angle = client.level.getGameTime() * 0.05;
            client.player.setPos(484 + Math.cos(angle) * 24, 88, 271 + Math.sin(angle) * 24);
            client.player.setYRot((float) Math.toDegrees(angle));
        });
        context.waitFor(client -> client.player != null && WaypointerClient.api() != null
                && WaypointerClient.api().currentZone() != null
                && "crystal_hollows".equals(WaypointerClient.api().currentZone().id()), 600);
        world.getServer().runCommand("gamemode spectator @a");
        world.getServer().runCommand("tp @a 484 88 271 0 10");
        context.runOnClient(client -> {
            client.options.framerateLimit().set(260);
            client.options.enableVsync().set(false);
            client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
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
        context.takeScreenshot("probe-library");
        context.runOnClient(client -> WaypointerClient.config().setCrystalHollowsMinimap(false));
        report.add(measure(context, "library as-is, minimap off"));
        moving = true;
        report.add(measure(context, "library, camera moving"));
        moving = false;
        world.getServer().runCommand("tp @a 484 88 271 0 10");
        context.waitTicks(10);
        context.runOnClient(client -> WaypointerClient.config().setCrystalHollowsMinimap(true));
        WaypointerConfig config = context.computeOnClient(client -> WaypointerClient.config());
        WaypointerConfig.BeaconBeamMode beams = config.beaconBeamMode();
        context.runOnClient(client -> config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE));
        report.add(measure(context, "library, all beams on"));
        double motion = config.beaconMotionSpeed();
        context.runOnClient(client -> config.setBeaconMotionSpeed(0));
        context.waitTicks(5);
        context.takeScreenshot("probe-beams");
        context.runOnClient(client -> config.setBeaconMotionSpeed(motion));
        context.runOnClient(client -> config.setBeaconBeamMode(beams));
        boolean routeLines = config.showRouteLines();
        context.runOnClient(client -> config.setShowRouteLines(true));
        report.add(measure(context, "library, route lines on"));
        context.takeScreenshot("probe-route-lines");
        context.runOnClient(client -> config.setShowRouteLines(routeLines));
        WaypointerConfig.BoxStyle boxes = config.boxStyle();
        context.runOnClient(client -> config.setBoxStyle(WaypointerConfig.BoxStyle.OUTLINED));
        report.add(measure(context, "library, outlines only"));
        context.runOnClient(client -> config.setBoxStyle(boxes));
        if (coal != null) {
            change(context, manager, groups -> coal.setLoadMode(WaypointGroup.LoadMode.STATIC));
            report.add(measure(context, "coal route static"));
            context.runOnClient(client -> WaypointerClient.config().setShowLabels(false));
            report.add(measure(context, "coal route static, labels off"));
            context.runOnClient(client -> WaypointerClient.config().setShowLabels(true));
            change(context, manager, groups -> coal.setLoadMode(WaypointGroup.LoadMode.SEQUENCE));
        }
        List<WaypointGroup> sequenced = context.computeOnClient(client -> manager.allGroups().stream()
                .filter(group -> group.loadMode() != WaypointGroup.LoadMode.STATIC).toList());
        change(context, manager, groups -> sequenced.forEach(group -> group.setLoadMode(WaypointGroup.LoadMode.STATIC)));
        report.add(measure(context, "every route static"));
        context.takeScreenshot("probe-every-route-static");
        moving = true;
        report.add(measure(context, "every route static, moving"));
        moving = false;
        world.getServer().runCommand("tp @a 484 88 271 0 10");
        context.waitTicks(10);
        change(context, manager, groups -> sequenced.forEach(group -> group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE)));
        if (coal != null) {
            context.runOnClient(client -> MinecraftCompat.setScreen(client,
                    new PreviewProbeScreen(coal)));
            context.waitTicks(10);
            report.add(measure(context, "catalog map of coal route"));
        }
        if (coal != null) {
            context.runOnClient(client -> MinecraftCompat.setScreen(client,
                    new GroupEditScreen(null, manager, WaypointerClient.config(), coal)));
            context.waitTicks(10);
            report.add(measure(context, "route editor, coal route"));
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
        LocalTime from = LocalTime.now().withNano(0);
        recorded = 0;
        context.waitFor(client -> recorded > FRAMES, 4000);
        recorded = -1;
        LocalTime to = LocalTime.now().withNano(0);
        double[] ms = new double[FRAMES];
        for (int i = 0; i < FRAMES; i++) ms[i] = (frameNanos[i + 1] - frameNanos[i]) / 1e6;
        double mean = Arrays.stream(ms).average().orElse(0);
        double draws = Arrays.stream(frameDraws, 1, FRAMES + 1).average().orElse(0);
        long rebuilds = frameRebuilds[FRAMES] - frameRebuilds[0];
        double waypointerMs = (frameCost[FRAMES] - frameCost[0]) / 1e6 / FRAMES;
        double[] waypointerFrames = new double[FRAMES];
        for (int i = 0; i < FRAMES; i++) waypointerFrames[i] = (frameCost[i + 1] - frameCost[i]) / 1e6;
        Arrays.sort(waypointerFrames);
        double waypointerMedian = waypointerFrames[FRAMES / 2];
        Arrays.sort(ms);
        return String.format("%-28s waypointer %5.2f ms (median %5.2f)  frame %5.2f ms (%4.0f fps)  p50 %5.2f  p95 %5.2f  max %6.2f  draws %4.0f  rebuilds %d",
                name, waypointerMs, waypointerMedian, mean, 1000 / mean, ms[FRAMES / 2], ms[FRAMES * 95 / 100], ms[FRAMES - 1],
                draws, rebuilds) + "  " + from + "-" + to;
    }
}
