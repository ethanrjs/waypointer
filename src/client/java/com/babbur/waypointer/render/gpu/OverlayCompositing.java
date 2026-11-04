package com.babbur.waypointer.render.gpu;

public enum OverlayCompositing {

    IN_WORLD,

    POST_WORLD,

    LEGACY_SUBMIT;

    public boolean usesGpuRenderer() {
        return this != LEGACY_SUBMIT;
    }

    public boolean needsDepthSnapshot() {
        return this == POST_WORLD;
    }
}
