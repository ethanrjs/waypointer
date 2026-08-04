package com.babbur.waypointer.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CjkBase16384Test {

    @Test
    void encodedLengthUsesCheckedArithmetic() {
        assertEquals(1, CjkBase16384.encodedLength(0));
        assertEquals(5, CjkBase16384.encodedLength(1));
        assertEquals(5, CjkBase16384.encodedLength(7));
        assertEquals(9, CjkBase16384.encodedLength(8));
        assertThrows(IllegalArgumentException.class, () -> CjkBase16384.encodedLength(-1));
        assertEquals(1_227_133_517, CjkBase16384.encodedLength(Integer.MAX_VALUE));
    }
}
