package dev.ethan.waypointer.render;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Projects world-space label anchors with interpolated sprint FOV.
 *
 * <p>Mojang's {@link GameRenderer#projectPointToScreen(Vec3)} passes a fixed
 * {@code 0} tick progress to its private FOV calculation. That is fine for
 * static FOV, but sprinting animates the FOV multiplier, so labels visibly step
 * at 20Hz while the world renders with partial-tick interpolation.
 */
public final class WorldScreenProjector {

    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f rotationMatrix = new Matrix4f();
    private final Quaternionf inverseCameraRotation = new Quaternionf();
    private final Vector3f ndcScratch = new Vector3f();

    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private float forwardX;
    private float forwardY;
    private float forwardZ;
    private float fovDegrees = 70.0f;

    public void prepare(GameRenderer renderer, Camera camera) {
        Vec3 pos = camera.position();

        cameraX = pos.x;
        cameraY = pos.y;
        cameraZ = pos.z;
        forwardX = camera.forwardVector().x();
        forwardY = camera.forwardVector().y();
        forwardZ = camera.forwardVector().z();

        float fov = renderer.getFov(camera, camera.getPartialTickTime(), true);
        fovDegrees = fov;
        camera.rotation().conjugate(inverseCameraRotation);
        rotationMatrix.rotation(inverseCameraRotation);
        viewProjection.set(renderer.getProjectionMatrix(fov)).mul(rotationMatrix);
    }

    public boolean project(double x, double y, double z,
                           int screenW, int screenH, double[] out) {
        double rx = x - cameraX;
        double ry = y - cameraY;
        double rz = z - cameraZ;
        if (rx * forwardX + ry * forwardY + rz * forwardZ <= 0.0) return false;

        viewProjection.transformProject((float) rx, (float) ry, (float) rz, ndcScratch);
        if (!Float.isFinite(ndcScratch.x) || !Float.isFinite(ndcScratch.y)) return false;

        out[0] = (ndcScratch.x * 0.5 + 0.5) * screenW;
        out[1] = (0.5 - ndcScratch.y * 0.5) * screenH;
        return true;
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
