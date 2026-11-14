package com.babbur.waypointer;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import com.babbur.waypointer.render.RenderCost;
import com.babbur.waypointer.render.gpu.OverlayRenderer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.InactivityFpsLimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Renderer benchmark on a synthetic, deterministic Crystal Hollows library, so every build is
 * measured on identical input. Run with {@code -PrenderBench} (see build.gradle); results go to
 * {@code build/reports/render-bench/results.txt}. Waypointer time is the CPU time its render
 * hooks spend per frame (RenderCost); frame times are whole-client wall time.
 */
@SuppressWarnings("UnstableApiUsage")
final class RenderBench {
    private static final int FRAMES = 1200;
    private static final double CX = 484, CY = 88, CZ = 271;
    private static final long[] frameNanos = new long[FRAMES + 1];
    private static final int[] frameDraws = new int[FRAMES + 1];
    private static final long[] frameRebuilds = new long[FRAMES + 1];
    private static final long[] frameRouteRebuilds = new long[FRAMES + 1];
    private static final long[] frameCost = new long[FRAMES + 1];
    private static volatile int recorded = -1;
    private static volatile boolean turning;
    private static volatile boolean moving;
    private static volatile WaypointGroup advancing;

    private RenderBench() {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        LevelRenderEvents.END_MAIN.register(renderContext -> {
            int index = recorded;
            if (index >= 0 && index <= FRAMES) {
                frameNanos[index] = System.nanoTime();
                OverlayRenderer overlay = OverlayRenderer.activeOrNull();
                frameDraws[index] = overlay == null ? 0 : overlay.lastDrawCalls();
                frameRebuilds[index] = overlay == null ? 0 : overlay.fullRebuilds();
                frameRouteRebuilds[index] = overlay == null ? 0 : overlay.routeRebuilds();
                frameCost[index] = RenderCost.totalNanos();
                recorded = index + 1;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) return;
            long time = client.level.getGameTime();
            if (turning) {
                client.player.setYRot((float) ((time * 3) % 360));
                client.player.setXRot(10);
            }
            if (moving) {
                double angle = time * 0.05;
                client.player.setPos(CX + Math.cos(angle) * 24, CY, CZ + Math.sin(angle) * 24);
                client.player.setYRot((float) Math.toDegrees(angle));
            }
            WaypointGroup route = advancing;
            if (route != null && time % 5 == 0) route.setCurrentIndex((route.currentIndex() + 1) % route.size());
        });
        context.waitFor(client -> client.player != null && WaypointerClient.api() != null
                && WaypointerClient.api().currentZone() != null
                && "crystal_hollows".equals(WaypointerClient.api().currentZone().id()), 600);
        world.getServer().runCommand("gamemode spectator @a");
        world.getServer().runCommand("tp @a " + (int) CX + " " + (int) CY + " " + (int) CZ + " 0 10");
        context.runOnClient(client -> {
            client.options.framerateLimit().set(260);
            client.options.enableVsync().set(false);
            client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        });
        context.waitTicks(60);
        GameTestCompat.waitForChunksRender(world);
        context.waitForScreen(null);

        ActiveGroupManager manager = WaypointerClient.manager();
        WaypointerConfig config = context.computeOnClient(client -> WaypointerClient.config());
        context.runOnClient(client -> {
            config.setCrystalHollowsMinimap(false);
            for (WaypointGroup group : List.copyOf(manager.allGroups())) group.setEnabled(false);
            manager.fireTransientDataChanged();
        });

        List<String> report = new ArrayList<>();
        report.add("render bench: " + FRAMES + " frames per case, synthetic library around "
                + (int) CX + " " + (int) CY + " " + (int) CZ);
        report.add(measure(context, "empty"));

        List<WaypointGroup> statics = new ArrayList<>();
        for (int i = 0; i < 4; i++) statics.add(staticRoute("Static " + i, 2000, 11L + i));
        WaypointGroup sequence = sequenceRoute("Sequence", 240, 99L);
        context.runOnClient(client -> {
            for (WaypointGroup group : statics) manager.add(group);
            manager.fireTransientDataChanged();
        });
        context.waitTicks(10);
        report.add(measure(context, "8k static, still"));
        turning = true;
        report.add(measure(context, "8k static, turning"));
        turning = false;

        context.runOnClient(client -> {
            manager.add(sequence);
            manager.fireTransientDataChanged();
        });
        advancing = sequence;
        report.add(measure(context, "8k static + sequence advancing"));
        advancing = null;
        context.takeScreenshot("bench-static-sequence");

        context.runOnClient(client -> {
            config.setShowRouteLines(true);
            config.setRouteLineDashed(true);
        });
        report.add(measure(context, "+ dashed route lines"));
        context.takeScreenshot("bench-dashed-lines");
        context.runOnClient(client -> config.setRouteLineDashed(false));
        report.add(measure(context, "+ solid route lines"));
        context.takeScreenshot("bench-solid-lines");
        moving = true;
        report.add(measure(context, "+ solid route lines, moving"));
        moving = false;
        world.getServer().runCommand("tp @a " + (int) CX + " " + (int) CY + " " + (int) CZ + " 0 10");
        context.waitTicks(10);
        context.takeScreenshot("bench-solid-lines-after-moving");
        context.runOnClient(client -> config.setShowRouteLines(false));

        context.runOnClient(client -> {
            config.setShowRouteProgress(true);
            config.setPreventLabelOverlap(true);
        });
        report.add(measure(context, "progress labels + overlap"));
        context.runOnClient(client -> {
            config.setShowRouteProgress(false);
            config.setPreventLabelOverlap(false);
        });

        context.runOnClient(client -> {
            for (WaypointGroup group : statics) {
                group.setWaypointStyle(7, WaypointStyle.EMPTY.with("waypointOutlineOpacity", 0.5));
            }
            manager.fireTransientDataChanged();
        });
        report.add(measure(context, "one point style per route"));

        WaypointerConfig.BeaconBeamMode beams = config.beaconBeamMode();
        context.runOnClient(client -> config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.CURRENT));
        report.add(measure(context, "beams current only"));
        context.runOnClient(client -> config.setBeaconBeamMode(beams));

        try {
            Path out = Path.of(System.getProperty("waypointer.test.renderBench"), "results.txt");
            Files.createDirectories(out.getParent());
            Files.write(out, report, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Points scattered all around the camera, so most are off screen in any one direction. */
    private static WaypointGroup staticRoute(String name, int size, long seed) {
        Random random = new Random(seed);
        WaypointGroup group = WaypointGroup.create(name, "crystal_hollows");
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        for (int i = 0; i < size; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = 6 + random.nextDouble() * 120;
            group.add(Waypoint.at((int) (CX + Math.cos(angle) * distance), (int) (CY - 20 + random.nextInt(50)),
                    (int) (CZ + Math.sin(angle) * distance)).withName(name + " #" + i));
        }
        group.setEnabled(true);
        return group;
    }

    private static WaypointGroup sequenceRoute(String name, int size, long seed) {
        Random random = new Random(seed);
        WaypointGroup group = WaypointGroup.create(name, "crystal_hollows");
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        double x = CX, z = CZ;
        for (int i = 0; i < size; i++) {
            x += random.nextInt(11) - 5;
            z += random.nextInt(11) - 5;
            group.add(Waypoint.at((int) x, (int) (CY - 5 + random.nextInt(10)), (int) z).withName(Integer.toString(i + 1)));
        }
        group.setEnabled(true);
        return group;
    }

    private static String measure(ClientGameTestContext context, String name) {
        context.waitTicks(40);
        recorded = 0;
        context.waitFor(client -> recorded > FRAMES, 4000);
        recorded = -1;
        double[] ms = new double[FRAMES];
        for (int i = 0; i < FRAMES; i++) ms[i] = (frameNanos[i + 1] - frameNanos[i]) / 1e6;
        double mean = Arrays.stream(ms).average().orElse(0);
        double draws = Arrays.stream(frameDraws, 1, FRAMES + 1).average().orElse(0);
        long rebuilds = frameRebuilds[FRAMES] - frameRebuilds[0];
        long routeRebuilds = frameRouteRebuilds[FRAMES] - frameRouteRebuilds[0];
        double[] cost = new double[FRAMES];
        for (int i = 0; i < FRAMES; i++) cost[i] = (frameCost[i + 1] - frameCost[i]) / 1e6;
        double costMean = Arrays.stream(cost).average().orElse(0);
        Arrays.sort(cost);
        Arrays.sort(ms);
        return String.format(java.util.Locale.ROOT,
                "%-34s waypointer mean %6.3f p50 %6.3f p95 %6.3f max %7.3f ms | frame mean %6.2f p50 %6.2f p95 %6.2f max %7.2f ms (%4.0f fps) | draws %5.0f rebuilds %d route-only %d",
                name, costMean, cost[FRAMES / 2], cost[FRAMES * 95 / 100], cost[FRAMES - 1],
                mean, ms[FRAMES / 2], ms[FRAMES * 95 / 100], ms[FRAMES - 1], 1000 / mean, draws, rebuilds, routeRebuilds);
    }
}
