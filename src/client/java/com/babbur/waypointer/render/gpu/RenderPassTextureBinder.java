package com.babbur.waypointer.render.gpu;

import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

final class RenderPassTextureBinder {

    private RenderPassTextureBinder() {}

    static AbstractTexture resolve(Identifier textureId) {
        if (textureId == null) return null;
        return Minecraft.getInstance().getTextureManager().getTexture(textureId);
    }

    static boolean bind(RenderPass pass, String samplerName, AbstractTexture texture) {
        if (texture == null) return false;
        OverlayPassCompat.bindTextureUniform(pass, samplerName, texture);
        return true;
    }
}
