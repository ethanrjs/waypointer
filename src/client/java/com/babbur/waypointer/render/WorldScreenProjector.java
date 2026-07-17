package com.babbur.waypointer.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.babbur.waypointer.compat.MinecraftCompat;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Projects world-space label anchors with the same extracted projection,
 * hurt-camera tilt, view bob, and view rotation used by the level renderer.
 * Keeping that full transform together prevents HUD labels from sliding away
 * from their world markers while the camera animates.
 *
 * <p>ponytail: Vanilla applies a later portal/nausea spin using private
 * animation state. If labels need to track those effects too, widen and mirror
 * that final projection mutation here; damage tilt and view bob are covered.
 */
public final class WorldScreenProjector {

    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector3f projected = new Vector3f();
    private boolean prepared;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private float forwardX;
    private float forwardY;
    private float forwardZ;
    private float fovDegrees = 70.0f;

    public void prepare(GameRenderer renderer, Camera camera) {
        GameRenderState gameState = MinecraftCompat.gameRenderState(renderer);
        CameraRenderState cameraState = gameState.levelRenderState.cameraRenderState;
        PoseStack cameraEffects = new PoseStack();
        renderer.bobHurt(cameraState, cameraEffects);
        if (gameState.optionsRenderState.bobView) {
            renderer.bobView(cameraState, cameraEffects);
        }
        composeViewProjection(
                cameraState.projectionMatrix,
                cameraEffects.last().pose(),
                cameraState.viewRotationMatrix,
                viewProjection);

        Vec3 pos = camera.position();

        cameraX = pos.x;
        cameraY = pos.y;
        cameraZ = pos.z;
        forwardX = camera.forwardVector().x();
        forwardY = camera.forwardVector().y();
        forwardZ = camera.forwardVector().z();
        fovDegrees = camera.getFov();
        prepared = true;
    }

    public boolean project(double x, double y, double z,
                           int screenW, int screenH, double[] out) {
        if (!prepared) return false;
        double rx = x - cameraX;
        double ry = y - cameraY;
        double rz = z - cameraZ;
        if (!isInFront(rx, ry, rz, forwardX, forwardY, forwardZ)) return false;

        projected.set((float) rx, (float) ry, (float) rz);
        viewProjection.transformProject(projected);
        if (!Float.isFinite(projected.x) || !Float.isFinite(projected.y)) return false;

        out[0] = (projected.x * 0.5 + 0.5) * screenW;
        out[1] = (0.5 - projected.y * 0.5) * screenH;
        return true;
    }

    static Matrix4f composeViewProjection(Matrix4fc projection,
                                          Matrix4fc cameraEffects,
                                          Matrix4fc viewRotation,
                                          Matrix4f destination) {
        return destination.set(projection).mul(cameraEffects).mul(viewRotation);
    }

    static boolean isInFront(double x, double y, double z,
                             float forwardX, float forwardY, float forwardZ) {
        return x * forwardX + y * forwardY + z * forwardZ > 0.0;
    }

    public double depth(double x, double y, double z) {
        double rx = x - cameraX;
        double ry = y - cameraY;
        double rz = z - cameraZ;
        return rx * forwardX + ry * forwardY + rz * forwardZ;
    }

    public float fovDegrees() {
        return fovDegrees;
    }
}
