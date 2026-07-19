package com.babbur.waypointer.render;

import com.babbur.waypointer.core.WaypointPaint;
import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HappySnowmanSessionTest {
    @Test
    void compositesHatLayerAndDoublesFacePixelsOntoEveryWaypointFace() {
        try (NativeImage skin = new NativeImage(64, 64, true)) {
            for (int y = 8; y < 16; y++) {
                for (int x = 8; x < 16; x++) skin.setPixel(x, y, 0xFF112233);
            }
            skin.setPixel(40, 8, 0xFFABCDEF);

            WaypointPaint paint = HappySnowmanSession.facePaint(skin);

            for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
                assertEquals(0xABCDEF, paint.color(face, 0, 0));
                assertEquals(0xABCDEF, paint.color(face, 1, 1));
                assertEquals(0x112233, paint.color(face, 2, 0));
            }
        }
    }
}
