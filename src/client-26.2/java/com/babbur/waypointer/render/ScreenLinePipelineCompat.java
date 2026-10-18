package com.babbur.waypointer.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

final class ScreenLinePipelineCompat {
    private ScreenLinePipelineCompat() {}

    static RenderPipeline.Builder vertexFormat(RenderPipeline.Builder builder) {
        return builder.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR);
    }
}
