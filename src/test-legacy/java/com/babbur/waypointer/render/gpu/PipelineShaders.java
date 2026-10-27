package com.babbur.waypointer.render.gpu;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.resources.Identifier;

final class PipelineShaders {
    private PipelineShaders() {}
    static Identifier vertex(RenderPipeline pipeline) { return pipeline.getVertexShader(); }
    static Identifier fragment(RenderPipeline pipeline) { return pipeline.getFragmentShader(); }
}
