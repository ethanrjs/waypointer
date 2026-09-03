package com.babbur.waypointer.render.gpu;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayRendererOptionsTest {

    @Test
    void defaultsUseInWorldCompositingWithIrisAssignment() {
        OverlayRendererOptions options = OverlayRendererOptions.fromProperties(key -> null);
        assertAll(
                () -> assertEquals(OverlayCompositing.IN_WORLD, options.compositing()),
                () -> assertTrue(options.assignIrisPrograms()),
                () -> assertFalse(options.debugLogging()));
    }

    @Test
    void legacySwitchWinsOverCompositingMode() {
        Map<String, String> props = Map.of(
                OverlayRendererOptions.PROPERTY_RENDERER, "legacy",
                OverlayRendererOptions.PROPERTY_COMPOSITING, "post_world");
        OverlayRendererOptions options = OverlayRendererOptions.fromProperties(props::get);
        assertEquals(OverlayCompositing.LEGACY_SUBMIT, options.compositing());
        assertFalse(options.compositing().usesGpuRenderer());
    }

    @Test
    void postWorldAliasesAreHonoured() {
        Map<String, String> props = Map.of(
                OverlayRendererOptions.PROPERTY_COMPOSITING, "Overlay",
                OverlayRendererOptions.PROPERTY_IRIS_ASSIGN, "off",
                OverlayRendererOptions.PROPERTY_DEBUG, "1");
        OverlayRendererOptions options = OverlayRendererOptions.fromProperties(props::get);
        assertAll(
                () -> assertEquals(OverlayCompositing.POST_WORLD, options.compositing()),
                () -> assertTrue(options.compositing().needsDepthSnapshot()),
                () -> assertFalse(options.assignIrisPrograms()),
                () -> assertTrue(options.debugLogging()));
    }

    @Test
    void garbageValuesFallBackToDefaults() {
        Map<String, String> props = Map.of(
                OverlayRendererOptions.PROPERTY_COMPOSITING, "sideways",
                OverlayRendererOptions.PROPERTY_IRIS_ASSIGN, "maybe");
        OverlayRendererOptions options = OverlayRendererOptions.fromProperties(props::get);
        assertEquals(OverlayRendererOptions.defaults(), options);
    }

    @Test
    void occlusionDefaultsToLegacyFaithfulBothAndParsesAliases() {
        assertEquals(OverlayRendererOptions.OcclusionMode.BOTH,
                OverlayRendererOptions.fromProperties(key -> null).occlusion());
        assertEquals(OverlayRendererOptions.OcclusionMode.GPU_DEPTH_TEST,
                OverlayRendererOptions.fromProperties(
                        Map.of(OverlayRendererOptions.PROPERTY_OCCLUSION, "depth")::get).occlusion());
        assertEquals(OverlayRendererOptions.OcclusionMode.RAYCAST_LINE_OF_SIGHT,
                OverlayRendererOptions.fromProperties(
                        Map.of(OverlayRendererOptions.PROPERTY_OCCLUSION, "LOS")::get).occlusion());
        assertFalse(OverlayRendererOptions.OcclusionMode.GPU_DEPTH_TEST.usesRaycast());
        assertTrue(OverlayRendererOptions.OcclusionMode.BOTH.usesRaycast());
    }
}
