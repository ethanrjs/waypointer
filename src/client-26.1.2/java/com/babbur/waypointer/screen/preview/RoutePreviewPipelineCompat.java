package com.babbur.waypointer.screen.preview;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;

import java.util.Optional;

/** Minecraft 26.1.2 color-target construction for preview pipelines. */
final class RoutePreviewPipelineCompat {

    private RoutePreviewPipelineCompat() {}

    static ColorTargetState noColorWrites() {
        return new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_NONE);
    }

    static DepthStencilState outlineDepthState() {
        return new DepthStencilState(
                DepthStencilState.DEFAULT.depthTest(), false, -1.0f, -1.0f);
    }
}
