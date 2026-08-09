package com.babbur.waypointer.screen.preview;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;

import java.util.Optional;

/** Preview pipeline compatibility. */
final class RoutePreviewPipelineCompat {

    private RoutePreviewPipelineCompat() {}

    static ColorTargetState noColorWrites() {
        return new ColorTargetState(
                Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE);
    }

    static DepthStencilState outlineDepthState() {
        return new DepthStencilState(
                DepthStencilState.DEFAULT.depthTest(), false, 1.0f, 1.0f);
    }
}
