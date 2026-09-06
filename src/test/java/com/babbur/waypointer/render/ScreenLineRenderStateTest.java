package com.babbur.waypointer.render;

import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ScreenLineRenderStateTest {
    @Test
    void widthStaysInScreenPixelsForShortHeadOnAndDiagonalTracers() {
        for (double guiScale : new double[]{1, 2, 4}) {
            for (double[] delta : new double[][]{{0.01, 0.102}, {0, 100}, {100, 0}, {300, 400}}) {
                for (boolean antialiasing : new boolean[]{false, true}) {
                    var line = ScreenLineRenderState.create(new Matrix3x2f(), 400, 300,
                            400 + delta[0], 300 + delta[1], 0xFF00FF00, 6, guiScale, antialiasing);
                    assertNotNull(line);
                    assertEquals(antialiasing ? 7 : 6,
                            2 * Math.hypot(line.offsetX(), line.offsetY()) * guiScale, 1e-5);
                    assertEquals(0, line.offsetX() * delta[0] + line.offsetY() * delta[1], 1e-4);
                    assertEquals(3, line.halfWidth());
                }
            }
        }
    }
}
