package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.Waypointer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Minecraft 26.2 render-pass compatibility. */
final class OverlayPassCompat {

    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector4f WHITE = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
    private static final int FOG_UBO_BYTES = 48;

    private OverlayPassCompat() {}

    static void registerEndMain(Consumer<LevelRenderContext> callback) {
        LevelRenderEvents.END_MAIN.register(callback::accept);
    }

    static GpuBufferSlice projectionBuffer(LevelRenderContext ctx) {
        return RenderSystem.getProjectionMatrixBuffer();
    }

    /** Uses the level view matrix; RenderSystem's model-view stack stays at identity. */
    static Matrix4f positionMatrix(LevelRenderContext ctx) {
        return new Matrix4f(ctx.levelState().cameraRenderState.viewRotationMatrix);
    }

    private static RenderTarget mainTarget() {
        return Minecraft.getInstance().gameRenderer.mainRenderTarget();
    }

    static GpuTextureView mainColorView() {
        return mainTarget().getColorTextureView();
    }

    static GpuTextureView mainDepthView() {
        return mainTarget().getDepthTextureView();
    }

    static GpuTexture mainDepthTexture() {
        return mainTarget().getDepthTexture();
    }

    static int mainWidth() {
        return mainTarget().width;
    }

    static int mainHeight() {
        return mainTarget().height;
    }

    static RenderPass beginPass(Supplier<String> label, GpuTextureView color, GpuTextureView depth) {
        Optional<Vector4fc> noClear = Optional.empty();
        return encoder().createRenderPass(label, color, noClear, depth, OptionalDouble.empty());
    }

    static GpuBufferSlice writeTransform(Matrix4fc modelView, Vector3fc modelOffset) {
        return RenderSystem.getDynamicUniforms()
                .writeTransform(new Matrix4f(modelView), WHITE,
                        new org.joml.Vector3f(modelOffset), IDENTITY);
    }

    static void bindUniforms(RenderPass pass, GpuBufferSlice transforms,
                             GpuBufferSlice projection, GpuBufferSlice fog) {
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", transforms);
        pass.setUniform("Projection", projection);
        pass.setUniform("Fog", fog);
    }

    static GpuBufferSlice currentFog() {
        return RenderSystem.getShaderFog();
    }

    static boolean bindTexture(RenderPass pass, String sampler, AbstractTexture texture) {
        return RenderPassTextureBinder.bind(pass, sampler, texture);
    }

    static GpuBuffer createUniformBuffer(Supplier<String> label, int bytes) {
        return RenderSystem.getDevice().createBuffer(label,
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, bytes);
    }

    static GpuBuffer createNoFogBuffer(Supplier<String> label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            float far = Float.MAX_VALUE;
            ByteBuffer bytes = Std140Builder.onStack(stack, FOG_UBO_BYTES)
                    .putVec4(0.0f, 0.0f, 0.0f, 0.0f)
                    .putFloat(far).putFloat(far)
                    .putFloat(far).putFloat(far)
                    .putFloat(far).putFloat(far)
                    .get();
            GpuBuffer buffer = createUniformBuffer(label, FOG_UBO_BYTES);
            encoder().writeToBuffer(buffer.slice(), bytes);
            return buffer;
        } catch (RuntimeException failure) {
            Waypointer.LOGGER.warn("Could not build no-fog uniform block; using level fog", failure);
            return null;
        }
    }

    static GpuTexture createDepthTexture(Supplier<String> label, int width, int height) {
        return RenderSystem.getDevice().createTexture(label,
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                mainDepthTexture().getFormat(), width, height, 1, 1);
    }

    static GpuTextureView createTextureView(GpuTexture texture) {
        return RenderSystem.getDevice().createTextureView(texture);
    }

    static void copyDepth(GpuTexture source, GpuTexture destination, int width, int height) {
        encoder().copyTextureToTexture(source, destination, 0, 0, 0, 0, 0, width, height);
    }

    private static CommandEncoder encoder() {
        return RenderSystem.getDevice().createCommandEncoder();
    }
}
