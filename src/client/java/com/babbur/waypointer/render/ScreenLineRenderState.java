package com.babbur.waypointer.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

record ScreenLineRenderState(Matrix3x2fc pose, float x1, float y1, float x2, float y2,
                             float offsetX, float offsetY, float halfWidth, float outerWidth,
                             int argb, boolean antialiasing, ScreenRectangle bounds)
        implements GuiElementRenderState {

    static ScreenLineRenderState create(Matrix3x2fc pose, double x1, double y1,
                                         double x2, double y2, int argb, double thickness,
                                         double guiScale, boolean antialiasing) {
        double length = Math.hypot(x2 - x1, y2 - y1);
        if (!Double.isFinite(length) || length < 1.0e-5 || !Double.isFinite(thickness)
                || !Double.isFinite(guiScale) || guiScale <= 0 || (argb >>> 24) == 0) return null;
        float halfWidth = (float) Math.max(1, thickness) * 0.5f;
        float outerWidth = halfWidth + (antialiasing ? 0.5f : 0);
        float offsetX = (float) (-(y2 - y1) / length * outerWidth / guiScale);
        float offsetY = (float) ((x2 - x1) / length * outerWidth / guiScale);
        int minX = (int) Math.floor(Math.min(x1, x2) - Math.abs(offsetX));
        int minY = (int) Math.floor(Math.min(y1, y2) - Math.abs(offsetY));
        int maxX = (int) Math.ceil(Math.max(x1, x2) + Math.abs(offsetX));
        int maxY = (int) Math.ceil(Math.max(y1, y2) + Math.abs(offsetY));
        Matrix3x2f copiedPose = new Matrix3x2f(pose);
        ScreenRectangle bounds = new ScreenRectangle(minX, minY, maxX - minX, maxY - minY)
                .transformMaxBounds(copiedPose);
        return new ScreenLineRenderState(copiedPose, (float) x1, (float) y1, (float) x2, (float) y2,
                offsetX, offsetY, halfWidth, outerWidth, argb, antialiasing, bounds);
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        vertex(consumer, x1 - offsetX, y1 - offsetY, -outerWidth);
        vertex(consumer, x1 + offsetX, y1 + offsetY, outerWidth);
        vertex(consumer, x2 + offsetX, y2 + offsetY, outerWidth);
        vertex(consumer, x2 - offsetX, y2 - offsetY, -outerWidth);
    }

    private void vertex(VertexConsumer consumer, float x, float y, float distance) {
        consumer.addVertexWith2DPose(pose, x, y).setUv(distance, halfWidth).setColor(argb);
    }

    @Override
    public RenderPipeline pipeline() {
        return antialiasing ? Pipelines.SMOOTH : Pipelines.CRISP;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    @Override
    public ScreenRectangle scissorArea() {
        return null;
    }

    private static final class Pipelines {
        static final RenderPipeline SMOOTH = build(true);
        static final RenderPipeline CRISP = build(false);

        private static RenderPipeline build(boolean antialiasing) {
            Identifier shader = Identifier.fromNamespaceAndPath("waypointer", "core/screen_line");
            RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("waypointer",
                            antialiasing ? "screen_line_smooth" : "screen_line_crisp"))
                    .withVertexShader(shader).withFragmentShader(shader).withCull(false);
            if (antialiasing) builder.withShaderDefine("ANTIALIASING");
            return ScreenLinePipelineCompat.vertexFormat(builder).build();
        }
    }
}
