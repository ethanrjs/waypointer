package com.babbur.waypointer.screen.preview;

import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import static org.junit.jupiter.api.Assertions.*;

class WaypointInspectorProjectionTest {
    @Test void sampleUsesPerspectiveWithoutOverviewMinimumSize() {
        var near = new WaypointInspectorProjection(8, 0, 0, 90, 200);
        var far = new WaypointInspectorProjection(80, 0, 0, 90, 200);
        assertEquals(0, near.project(0, 0, 0).x(), 1e-9);
        assertEquals(12.5, near.project(1, 0, 0).x(), 1e-9);
        assertEquals(-12.5, near.project(0, 1, 0).y(), 1e-9);
        assertEquals(10, near.project(1, 0, 2).x(), 1e-9);
        assertEquals(1.25, far.project(1, 0, 0).x(), 1e-9);
        assertTrue(far.project(1.0 / 16, 0, 0).x() < 0.1);
        var rotated = new WaypointInspectorProjection(8, Math.PI / 2, 0, 90, 200);
        assertEquals(12.5, rotated.project(0, 0, 1).x(), 1e-9);
        assertEquals(7, rotated.project(1, 0, 0).depth(), 1e-9);
    }

    @Test void nativePerspectiveAndPanKeepGeometryAlignedWithCpuLabelsAtEveryGuiScale() {
        int width = 320, height = 180;
        for (int guiScale : new int[]{1, 2, 4}) {
            for (double pitch : new double[]{-0.6, 0, 0.6}) {
                for (double yaw : new double[]{-1.2, 0, 1.2}) {
                    for (boolean zeroToOne : new boolean[]{false, true}) {
                        var camera = new WaypointInspectorProjection(8, yaw, pitch, 70, height, 37, -19);
                        Matrix4f matrix = new Matrix4f().setPerspective((float) Math.toRadians(70),
                                (float) (width * guiScale) / (height * guiScale), .05f, 8192, zeroToOne);
                        camera.panProjection(matrix, width);
                        for (double[] point : new double[][]{{0, 0, 0}, {1, 1.1, 2}, {-3, 0, 4}}) {
                            var cpu = camera.project(point[0], point[1], point[2]);
                            var view = camera.view(point[0], point[1], point[2]);
                            var clip = matrix.transform(new Vector4f((float) view.x(), (float) view.y(), (float) -view.depth(), 1));
                            assertEquals(view.depth(), clip.w, 1e-5, "GPU clip W must retain camera depth for perspective UVs");
                            assertEquals(cpu.x(), clip.x / clip.w * width / 2, 1e-4);
                            assertEquals(cpu.y(), -clip.y / clip.w * height / 2, 1e-4);
                        }
                    }
                }
            }
        }
    }
}
