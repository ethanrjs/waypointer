package com.babbur.waypointer.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

final class ScreenLinePipelineCompat {
    private ScreenLinePipelineCompat() {}

    static RenderPipeline.Builder worldLines(RenderPipeline.Builder builder) {
        return builder.withVertexShader(Identifier.fromNamespaceAndPath("waypointer", "core/lines"));
    }

    static RenderPipeline.Builder vertexFormat(RenderPipeline.Builder builder) {
        return builder.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR);
    }
}
