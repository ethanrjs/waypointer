package com.babbur.waypointer.render.gpu;

import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** System-property options for the retained renderer. */
public record OverlayRendererOptions(OverlayCompositing compositing,
                                     boolean assignIrisPrograms,
                                     boolean debugLogging,
                                     OcclusionMode occlusion) {

    public enum OcclusionMode {
        GPU_DEPTH_TEST,
        RAYCAST_LINE_OF_SIGHT,
        BOTH;

        public boolean usesRaycast() {
            return this != GPU_DEPTH_TEST;
        }
    }

    public static final String PROPERTY_RENDERER = "waypointer.renderer";
    public static final String PROPERTY_COMPOSITING = "waypointer.renderer.compositing";
    public static final String PROPERTY_IRIS_ASSIGN = "waypointer.renderer.irisAssign";
    public static final String PROPERTY_DEBUG = "waypointer.renderer.debug";
    public static final String PROPERTY_OCCLUSION = "waypointer.renderer.occlusion";

    public OverlayRendererOptions {
        Objects.requireNonNull(compositing, "compositing");
        Objects.requireNonNull(occlusion, "occlusion");
    }

    public static OverlayRendererOptions defaults() {
        return new OverlayRendererOptions(
                OverlayCompositing.IN_WORLD, true, false, OcclusionMode.BOTH);
    }

    public static OverlayRendererOptions fromSystemProperties() {
        return fromProperties(System::getProperty);
    }

    public static OverlayRendererOptions fromProperties(UnaryOperator<String> lookup) {
        Objects.requireNonNull(lookup, "lookup");
        OverlayRendererOptions defaults = defaults();

        OverlayCompositing compositing = defaults.compositing();
        String renderer = normalise(lookup.apply(PROPERTY_RENDERER));
        if ("legacy".equals(renderer) || "off".equals(renderer) || "false".equals(renderer)) {
            compositing = OverlayCompositing.LEGACY_SUBMIT;
        } else {
            String mode = normalise(lookup.apply(PROPERTY_COMPOSITING));
            if ("post_world".equals(mode) || "post".equals(mode) || "overlay".equals(mode)) {
                compositing = OverlayCompositing.POST_WORLD;
            } else if ("in_world".equals(mode) || "world".equals(mode) || "composited".equals(mode)) {
                compositing = OverlayCompositing.IN_WORLD;
            }
        }

        boolean assignIris = parseBoolean(lookup.apply(PROPERTY_IRIS_ASSIGN), defaults.assignIrisPrograms());
        boolean debug = parseBoolean(lookup.apply(PROPERTY_DEBUG), defaults.debugLogging());
        OcclusionMode occlusion = parseOcclusion(lookup.apply(PROPERTY_OCCLUSION), defaults.occlusion());
        return new OverlayRendererOptions(compositing, assignIris, debug, occlusion);
    }

    private static OcclusionMode parseOcclusion(String value, OcclusionMode fallback) {
        String v = normalise(value);
        if (v == null || v.isEmpty()) return fallback;
        return switch (v) {
            case "depth", "gpu", "gpu_depth_test" -> OcclusionMode.GPU_DEPTH_TEST;
            case "raycast", "los", "raycast_line_of_sight" -> OcclusionMode.RAYCAST_LINE_OF_SIGHT;
            case "both", "legacy" -> OcclusionMode.BOTH;
            default -> fallback;
        };
    }

    private static String normalise(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        String v = normalise(value);
        if (v == null || v.isEmpty()) return fallback;
        return switch (v) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> fallback;
        };
    }

}
