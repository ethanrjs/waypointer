package com.babbur.waypointer.render;

import com.babbur.waypointer.compat.MinecraftCompat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

final class ViewCuller {

    private static final double MARGIN = 2.0;

    private final Matrix4f viewProjection = new Matrix4f();
    private final double[] planes = new double[16];
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private boolean ready;

    void prepare(GameRenderer renderer, Vec3 camera) {
        CameraRenderState state = MinecraftCompat.gameRenderState(renderer).levelRenderState.cameraRenderState;
        set(viewProjection.set(state.projectionMatrix).mul(state.viewRotationMatrix), camera.x, camera.y, camera.z);
    }

    void set(Matrix4f matrix, double cameraX, double cameraY, double cameraZ) {
        plane(0, matrix.m03() + matrix.m00(), matrix.m13() + matrix.m10(), matrix.m23() + matrix.m20(), matrix.m33() + matrix.m30());
        plane(1, matrix.m03() - matrix.m00(), matrix.m13() - matrix.m10(), matrix.m23() - matrix.m20(), matrix.m33() - matrix.m30());
        plane(2, matrix.m03() + matrix.m01(), matrix.m13() + matrix.m11(), matrix.m23() + matrix.m21(), matrix.m33() + matrix.m31());
        plane(3, matrix.m03() - matrix.m01(), matrix.m13() - matrix.m11(), matrix.m23() - matrix.m21(), matrix.m33() - matrix.m31());
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        ready = true;
    }

    boolean mayBeVisible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!ready) return true;
        minX -= cameraX + MARGIN;
        minY -= cameraY + MARGIN;
        minZ -= cameraZ + MARGIN;
        maxX += MARGIN - cameraX;
        maxY += MARGIN - cameraY;
        maxZ += MARGIN - cameraZ;
        for (int p = 0; p < 16; p += 4) {
            double a = planes[p], b = planes[p + 1], c = planes[p + 2];
            double farthest = a * (a > 0 ? maxX : minX) + b * (b > 0 ? maxY : minY) + c * (c > 0 ? maxZ : minZ);
            if (farthest + planes[p + 3] < 0) return false;
        }
        return true;
    }

    private void plane(int index, double a, double b, double c, double d) {
        planes[index * 4] = a;
        planes[index * 4 + 1] = b;
        planes[index * 4 + 2] = c;
        planes[index * 4 + 3] = d;
    }
}
