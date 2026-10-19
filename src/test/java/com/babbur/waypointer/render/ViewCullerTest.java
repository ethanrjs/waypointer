package com.babbur.waypointer.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewCullerTest {

    @Test
    void keepsWhatIsInFrontAndDropsWhatIsBehindOrBesideTheViewForEitherDepthRange() {
        for (boolean zeroToOne : new boolean[]{false, true}) {
            Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 1000f, zeroToOne);
            ViewCuller culler = new ViewCuller();
            culler.set(projection, 100, 64, 100);

            assertTrue(culler.mayBeVisible(99, 63, 89, 101, 65, 91), "a box ahead");
            assertTrue(culler.mayBeVisible(99.5, -64, 79.5, 100.5, 320, 80.5), "a beam ahead, taller than the view");
            assertTrue(culler.mayBeVisible(112.5, 64, 89.5, 113, 65, 90.5), "a box just past the edge of the view, within the margin");
            assertFalse(culler.mayBeVisible(99, 63, 109, 101, 65, 111), "a box behind the camera");
            assertFalse(culler.mayBeVisible(-200, 63, 89, -198, 65, 91), "a box far to the left");
        }
    }

    @Test
    void cullsNothingBeforeItKnowsTheCamera() {
        assertTrue(new ViewCuller().mayBeVisible(-1e6, -1e6, -1e6, -1e6 + 1, -1e6 + 1, -1e6 + 1));
    }
}
