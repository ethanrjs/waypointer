package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.core.Waypoint;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SceneKeyTest {

    private static SceneKey key(double camX, double camY, double camZ, long extra) {
        return SceneKey.builder()
                .origin(SceneKey.originFor(camX), SceneKey.originFor(camY), SceneKey.originFor(camZ))
                .mix(extra)
                .finish();
    }

    private static SceneKey configKey(WaypointerConfig config) {
        SceneKey.Builder builder = SceneKey.builder();
        SceneKeyFactory.mixConfig(builder, config);
        return builder.finish();
    }

    private static SceneKey keyWithBlockShapeFingerprint(long fingerprint) {
        return new SceneKeyFactory(OverlayRendererOptions.defaults()).build(
                List.of(), new WaypointerConfig(),
                new Vec3(0.0, 64.0, 0.0), null, -64, 320,
                0L, 0L, fingerprint, null);
    }

    private static SceneKey keyWithRuntimePaint(WaypointPaint paint) {
        return new SceneKeyFactory(OverlayRendererOptions.defaults()).build(
                List.of(), new WaypointerConfig(),
                new Vec3(0.0, 64.0, 0.0), null, -64, 320,
                0L, 0L, 0L, paint);
    }

    private static SceneKey keyWithWorldVisibility(long fingerprint) {
        return new SceneKeyFactory(OverlayRendererOptions.defaults()).build(
                List.of(), new WaypointerConfig(),
                new Vec3(0.0, 64.0, 0.0), null, -64, 320,
                0L, fingerprint, 0L, null);
    }

    private static SceneKey retainedKey(SceneKeyFactory factory, Object level, double cameraX) {
        return factory.build(List.of(), new WaypointerConfig(),
                new Vec3(cameraX, 64.0, 0.0), level, -64, 320,
                0L, 0L, 0L, null);
    }

    @Test
    void identicalInputsProduceEqualKeys() {
        assertEquals(key(10.4, 64.0, -3.2, 42L), key(10.4, 64.0, -3.2, 42L));
    }

    @Test
    void movingInsideTheSameOriginRegionDoesNotChangeTheKey() {
        assertEquals(key(1.1, 64.0, -15.9, 1L), key(15.9, 79.9, -0.1, 1L));
    }

    @Test
    void crossingAnOriginBoundaryOrChangingAnInputChangesTheKey() {
        assertAll(
                () -> assertNotEquals(key(15.9, 64.0, -3.9, 1L), key(16.1, 64.0, -3.9, 1L)),
                () -> assertNotEquals(key(10.1, 64.0, -3.9, 1L), key(10.1, 64.0, -3.9, 2L)),
                () -> assertNotEquals(SceneKey.NONE, key(0, 0, 0, 0L)));
    }

    @Test
    void originSnapsToSixteenBlocks() {
        assertAll(
                () -> assertEquals(16, SceneKey.originFor(17.9)),
                () -> assertEquals(-16, SceneKey.originFor(-0.1)));
    }

    @Test
    void retainedOriginDoesNotRebuildWhenPacingAcrossCellBoundary() {
        SceneKeyFactory factory = new SceneKeyFactory(OverlayRendererOptions.defaults());
        Object level = new Object();
        SceneKey previous = SceneKey.NONE;
        int rebuilds = 0;
        int cellSnappedRebuilds = 0;
        int previousCell = Integer.MIN_VALUE;

        for (int crossing = 0; crossing < 64; crossing++) {
            double cameraX = crossing % 2 == 0 ? 15.75 : 16.25;
            SceneKey next = retainedKey(factory, level, cameraX);
            if (!next.equals(previous)) rebuilds++;
            previous = next;
            int cell = SceneKey.originFor(cameraX);
            if (cell != previousCell) cellSnappedRebuilds++;
            previousCell = cell;
        }

        assertEquals(1, rebuilds);
        assertEquals(64, cellSnappedRebuilds);
        assertEquals(0, previous.originX());
    }

    @Test
    void retainedOriginRebasesAtSafeDistance() {
        SceneKeyFactory factory = new SceneKeyFactory(OverlayRendererOptions.defaults());
        Object level = new Object();
        SceneKey initial = retainedKey(factory, level, 0.25);
        SceneKey inside = retainedKey(factory, level,
                SceneKeyFactory.REBASE_DISTANCE_BLOCKS - 0.01);
        SceneKey rebased = retainedKey(factory, level,
                SceneKeyFactory.REBASE_DISTANCE_BLOCKS);

        assertEquals(initial, inside);
        assertNotEquals(inside, rebased);
        assertEquals(128, rebased.originX());
    }

    @Test
    void retainedOriginHandlesNegativeBoundaryWithoutChurn() {
        SceneKeyFactory factory = new SceneKeyFactory(OverlayRendererOptions.defaults());
        Object level = new Object();
        SceneKey negative = retainedKey(factory, level, -0.25);
        SceneKey positive = retainedKey(factory, level, 0.25);
        SceneKey rebased = retainedKey(factory, level, -144.0);

        assertEquals(negative, positive);
        assertEquals(-16, positive.originX());
        assertNotEquals(positive, rebased);
        assertEquals(-144, rebased.originX());
    }

    @Test
    void levelChangeAndResetChooseFreshSnappedOrigin() {
        SceneKeyFactory factory = new SceneKeyFactory(OverlayRendererOptions.defaults());
        SceneKey firstLevel = retainedKey(factory, new Object(), 15.75);
        Object secondLevel = new Object();
        SceneKey changedLevel = retainedKey(factory, secondLevel, 16.25);

        assertNotEquals(firstLevel, changedLevel);
        assertEquals(16, changedLevel.originX());

        SceneKey beforeReset = retainedKey(factory, secondLevel, 80.25);
        assertEquals(16, beforeReset.originX());
        factory.reset();
        SceneKey afterReset = retainedKey(factory, secondLevel, 80.25);
        assertEquals(80, afterReset.originX());
    }

    @Test
    void mixerIsSensitiveToSingleBitChanges() {
        long a = SceneKey.builder().mix(0x1L).finish().hash();
        long b = SceneKey.builder().mix(0x3L).finish().hash();
        assertNotEquals(a, b);
        assertNotEquals(SceneKey.builder().mix(true).finish(), SceneKey.builder().mix(false).finish());
    }

    @Test
    void staticRendererConfigChangesInvalidateTheKey() {
        SceneKey defaults = configKey(new WaypointerConfig());
        WaypointerConfig dimming = new WaypointerConfig();
        dimming.setDimSequenceContextWaypoints(false);
        WaypointerConfig scale = new WaypointerConfig();
        scale.setWaypointMarkerScale(2.0);
        WaypointerConfig outline = new WaypointerConfig();
        outline.setMatchWaypointOutlineToWaypointColor(false);
        WaypointerConfig roleColors = new WaypointerConfig();
        roleColors.setColorSequenceWaypointsByRole(true);
        assertAll(
                () -> assertNotEquals(defaults, configKey(dimming)),
                () -> assertNotEquals(defaults, configKey(scale)),
                () -> assertNotEquals(defaults, configKey(outline)),
                () -> assertNotEquals(defaults, configKey(roleColors)));
    }

    @Test
    void blockShapeChangesInvalidateTheKey() {
        assertNotEquals(keyWithBlockShapeFingerprint(1L), keyWithBlockShapeFingerprint(2L));
    }

    @Test
    void runtimePaintOverrideChangesInvalidateTheKey() {
        assertNotEquals(keyWithRuntimePaint(null), keyWithRuntimePaint(WaypointPaint.solid(0x123456)));
    }

    @Test
    void exactWorldVisibilityChangesInvalidateTheKey() {
        assertNotEquals(keyWithWorldVisibility(1L), keyWithWorldVisibility(2L));
    }

    @Test
    void waypointHashCollisionDoesNotReuseStaticGeometry() {
        Waypoint left = new Waypoint(0, 64, 0, "", 0, 31, 0.0);
        Waypoint right = new Waypoint(0, 64, 0, "", 1, 0, 0.0);
        assertEquals(left.hashCode(), right.hashCode());

        SceneKey.Builder leftKey = SceneKey.builder();
        SceneKey.Builder rightKey = SceneKey.builder();
        SceneKeyFactory.mixWaypoint(leftKey, left);
        SceneKeyFactory.mixWaypoint(rightKey, right);

        assertNotEquals(leftKey.finish(), rightKey.finish());
    }
}
