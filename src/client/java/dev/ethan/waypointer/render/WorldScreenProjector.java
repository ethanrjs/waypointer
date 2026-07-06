package dev.ethan.waypointer.render;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;

/**
 * Projects world-space label anchors with interpolated sprint FOV.
 *
 * <p>Mojang's {@link GameRenderer#projectPointToScreen(Vec3)} passes a fixed
 * {@code 0} tick progress to its private FOV calculation. That is fine for
 * static FOV, but sprinting animates the FOV multiplier, so labels visibly step
 * at 20Hz while the world renders with partial-tick interpolation.
 */
public final class WorldScreenProjector {

    private GameRenderer renderer;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private float forwardX;
    private float forwardY;
    private float forwardZ;
    private float fovDegrees = 70.0f;

    public void prepare(GameRenderer renderer, Camera camera) {
        this.renderer = renderer;
        Vec3 pos = camera.position();

        cameraX = pos.x;
        cameraY = pos.y;
        cameraZ = pos.z;
        forwardX = camera.forwardVector().x();
        forwardY = camera.forwardVector().y();
        forwardZ = camera.forwardVector().z();
        fovDegrees = camera.getFov();
    }

    public boolean project(double x, double y, double z,
                           int screenW, int screenH, double[] out) {
        if (renderer == null) return false;
        double rx = x - cameraX;
        double ry = y - cameraY;
        double rz = z - cameraZ;
        if (rx * forwardX + ry * forwardY + rz * forwardZ <= 0.0) return false;

        Vec3 ndc = renderer.projectPointToScreen(new Vec3(x, y, z));
        if (!Double.isFinite(ndc.x) || !Double.isFinite(ndc.y)) return false;

        out[0] = (ndc.x * 0.5 + 0.5) * screenW;
        out[1] = (0.5 - ndc.y * 0.5) * screenH;
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
