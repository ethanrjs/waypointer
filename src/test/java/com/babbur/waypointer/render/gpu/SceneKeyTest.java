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
        assertAll(
                () -> assertNotEquals(defaults, configKey(dimming)),
                () -> assertNotEquals(defaults, configKey(scale)),
                () -> assertNotEquals(defaults, configKey(outline)));
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
