package com.babbur.waypointer.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

final class ScreenLinePipelineCompat {
    private ScreenLinePipelineCompat() {}

    static RenderPipeline.Builder vertexFormat(RenderPipeline.Builder builder) {
        return builder.withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS);
    }
}
