package com.babbur.waypointer.render.gpu;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverlayPassTest {

    @Test
    void retainedModelViewAppliesOriginTranslationToCapturedVertices() {
        Matrix4f view = new Matrix4f().rotateXYZ(0.31f, -0.72f, 0.18f);
        int[][] origins = {{16, 64, -16}, {-48, 96, 64}};
        double[][] cameras = {{20.25, 68.75, -12.5}, {-31.125, 92.5, 50.375}};
        float[][] worlds = {{17.25f, 65.5f, -15.75f}, {-90.5f, 80.125f, 42.0f},
                {3.0f, -12.0f, -101.25f}};

        for (int i = 0; i < origins.length; i++) {
            SceneKey key = SceneKey.builder().origin(origins[i][0], origins[i][1], origins[i][2])
                    .finish();
            Matrix4f modelView = OverlayPass.modelViewFor(new Matrix4f(), view, key,
                    cameras[i][0], cameras[i][1], cameras[i][2]);
            for (float[] world : worlds) {
                Vector4f transformed = modelView.transform(new Vector4f(
                        world[0] - key.originX(), world[1] - key.originY(),
                        world[2] - key.originZ(), 1.0f));
                Vector4f expected = view.transform(new Vector4f(
                        (float) (world[0] - cameras[i][0]),
                        (float) (world[1] - cameras[i][1]),
                        (float) (world[2] - cameras[i][2]), 1.0f));

                assertEquals(expected.x, transformed.x, 1.0E-5f);
                assertEquals(expected.y, transformed.y, 1.0E-5f);
                assertEquals(expected.z, transformed.z, 1.0E-5f);
                assertEquals(expected.w, transformed.w, 1.0E-5f);
            }
        }
    }
}
