package com.babbur.waypointer.render.gpu;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.resources.Identifier;

final class PipelineShaders {
    private PipelineShaders() {}
    static Identifier vertex(RenderPipeline pipeline) { return pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX); }
    static Identifier fragment(RenderPipeline pipeline) { return pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT); }
}
