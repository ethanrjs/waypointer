package dev.ethan.waypointer.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MathUtilTest {

    @Test
    void clampBoundsIntegersInclusively() {
        assertEquals(0, MathUtil.clamp(-1, 0, 10));
        assertEquals(5, MathUtil.clamp(5, 0, 10));
        assertEquals(10, MathUtil.clamp(50, 0, 10));
    }

    @Test
    void clampBoundsDoublesInclusively() {
        assertEquals(0.0, MathUtil.clamp(-0.5, 0.0, 1.0));
        assertEquals(0.25, MathUtil.clamp(0.25, 0.0, 1.0));
        assertEquals(1.0, MathUtil.clamp(2.0, 0.0, 1.0));
    }

    @Test
    void clampByteBoundsColorChannels() {
        assertEquals(0, MathUtil.clampByte(-20));
        assertEquals(128, MathUtil.clampByte(128));
        assertEquals(255, MathUtil.clampByte(300));
    }
}
