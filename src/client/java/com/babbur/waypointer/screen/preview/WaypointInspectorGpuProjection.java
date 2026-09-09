package com.babbur.waypointer.screen.preview;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Native perspective and framebuffer-sized line uniforms for both Minecraft PiP adapters. */
final class WaypointInspectorGpuProjection implements AutoCloseable {
    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer buffer = new ProjectionMatrixBuffer("Waypointer appearance inspector");
    private final GlobalSettingsUniform globals = new GlobalSettingsUniform();
    private final Matrix4f matrix = new Matrix4f();

    void install(RoutePreviewRenderState state) {
        WaypointInspectorProjection camera = state.scene().inspector().camera();
        int width = Math.max(1, state.x1() - state.x0());
        int height = Math.max(1, state.y1() - state.y0());
        projection.setupPerspective(0.05f, 8192, (float) camera.verticalFov(), width, height);
        camera.panProjection(projection.getMatrix(matrix), width);
        RenderSystem.setProjectionMatrix(buffer.getBuffer(matrix), ProjectionType.PERSPECTIVE);
        Minecraft mc = Minecraft.getInstance();
        // The line shader measures physical pixels; the window's globals would shrink lines in this inset.
        globals.update(width * state.guiScale(), height * state.guiScale(), 0,
                mc.level == null ? 0 : mc.level.getGameTime(), mc.getDeltaTracker(), 0, Vec3.ZERO, false);
    }

    @Override public void close() {
        try { buffer.close(); }
        finally { globals.close(); }
    }
}
