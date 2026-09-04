package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Opt-in same-JVM scene-key workload; values are evidence, never CI thresholds. */
class RendererSceneKeyTimingTest {

    private static final int MARKERS = 1_024;
    private static final int WARMUP_SAMPLES = 100;
    private static final int TIMED_SAMPLES = 1_000;
    private static final int BOUNDARY_CROSSINGS = 2_000;

    @Test
    @EnabledIfSystemProperty(named = "renderer.timing", matches = "true")
    void sceneKeyConstructionAndBoundaryChurn() {
        WaypointGroup group = WaypointGroup.create("Renderer timing", "hub");
        List<Waypoint> waypoints = new ArrayList<>(MARKERS);
        for (int marker = 0; marker < MARKERS; marker++) {
            int x = marker & 31;
            int z = marker >>> 5;
            waypoints.add(new Waypoint(x, 64, z, "",
                    marker * 0x9E3779 & 0xFFFFFF, 0, Waypoint.DEFAULT_REACH_RADIUS));
        }
        group.addAll(waypoints);
        List<WaypointGroup> groups = List.of(group);
        WaypointerConfig config = new WaypointerConfig();
        Object level = new Object();
        SceneKeyFactory factory = new SceneKeyFactory(OverlayRendererOptions.defaults());

        for (int sample = 0; sample < WARMUP_SAMPLES; sample++) {
            build(factory, groups, config, level, sample % 2 == 0 ? 15.75 : 16.25);
        }

        long[] samples = new long[TIMED_SAMPLES];
        long checksum = 0L;
        for (int sample = 0; sample < samples.length; sample++) {
            long started = System.nanoTime();
            SceneKey key = build(factory, groups, config, level,
                    sample % 2 == 0 ? 15.75 : 16.25);
            samples[sample] = System.nanoTime() - started;
            checksum = checksum * 31L + key.hash();
        }

        factory.reset();
        SceneKey previous = SceneKey.NONE;
        int retainedRebuilds = 0;
        int cellSnappedRebuilds = 0;
        int previousCell = Integer.MIN_VALUE;
        for (int crossing = 0; crossing < BOUNDARY_CROSSINGS; crossing++) {
            double cameraX = crossing % 2 == 0 ? 15.75 : 16.25;
            SceneKey next = build(factory, groups, config, level, cameraX);
            if (!next.equals(previous)) retainedRebuilds++;
            previous = next;
            int cell = SceneKey.originFor(cameraX);
            if (cell != previousCell) cellSnappedRebuilds++;
            previousCell = cell;
        }

        assertEquals(1, retainedRebuilds);
        assertEquals(BOUNDARY_CROSSINGS, cellSnappedRebuilds);
        System.out.printf("Renderer scene-key timing markers=%d samples=%d %s checksum=%x; "
                        + "boundaryCrossings=%d retainedRebuilds=%d cellSnappedRebuilds=%d%n",
                MARKERS, TIMED_SAMPLES, summary(samples), checksum, BOUNDARY_CROSSINGS,
                retainedRebuilds, cellSnappedRebuilds);
    }

    private static SceneKey build(SceneKeyFactory factory, List<WaypointGroup> groups,
                                  WaypointerConfig config, Object level, double cameraX) {
        return factory.build(groups, config, new Vec3(cameraX, 64.0, 0.0),
                level, -64, 320, 0L, 0L, 0L, null);
    }

    private static String summary(long[] nanos) {
        Arrays.sort(nanos);
        return String.format("p50=%.3f,p95=%.3f,max=%.3fms",
                nanos[nanos.length / 2] / 1_000_000.0,
                nanos[(int) Math.ceil(nanos.length * 0.95) - 1] / 1_000_000.0,
                nanos[nanos.length - 1] / 1_000_000.0);
    }
}
