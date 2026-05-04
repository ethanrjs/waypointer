package dev.ethan.waypointer.codec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Safety net for the v3 chat text layer. The codec sits outside DEFLATE, so a
 * single bit-packing regression makes every otherwise-valid waypoint share fail
 * before the binary body version guard can explain what happened.
 */
class AsciiStreamCodecTest {

    @Test
    void round_trips_arbitrary_byte_patterns() {
        Random r = new Random(0xA511C0DE);
        for (int trial = 0; trial < 500; trial++) {
            byte[] input = new byte[r.nextInt(500)];
            r.nextBytes(input);

            byte[] decoded = AsciiStreamCodec.decode(AsciiStreamCodec.encode(input));
            assertArrayEquals(input, decoded, "trial " + trial + " len=" + input.length);
        }
    }

    @Test
    void empty_input_round_trips_without_a_trailer() {
        assertEquals("", AsciiStreamCodec.encode(new byte[0]));
        assertArrayEquals(new byte[0], AsciiStreamCodec.decode(""));
    }

    @Test
    void alphabet_is_printable_ascii_without_space_or_period() {
        byte[] input = new byte[512];
        new Random(0x5AFE).nextBytes(input);

        String encoded = AsciiStreamCodec.encode(input);
        assertFalse(encoded.contains(" "));
        assertFalse(encoded.contains("."));
        assertFalse(encoded.contains("\u00A7"));
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            assertTrue(c >= 0x21 && c <= 0x7E,
                    "non-printable output at " + i + ": 0x" + Integer.toHexString(c));
            assertTrue(AsciiStreamCodec.isAlphabetChar(c),
                    "output char missing from alphabet at " + i + ": '" + c + "'");
        }
    }

    @Test
    void uses_all_printable_ascii_except_space_and_period() {
        assertEquals(93, AsciiStreamCodec.alphabetSize());
        assertFalse(AsciiStreamCodec.isAlphabetChar(' '));
        assertFalse(AsciiStreamCodec.isAlphabetChar('.'));
        assertTrue(AsciiStreamCodec.isAlphabetChar('~'));
        assertTrue(AsciiStreamCodec.isAlphabetChar('_'));
        assertTrue(AsciiStreamCodec.isAlphabetChar('"'));
    }

    @Test
    void rejects_out_of_alphabet_characters() {
        String valid = AsciiStreamCodec.encode("payload".getBytes(StandardCharsets.UTF_8));
        String bad = valid.substring(0, 1) + "." + valid.substring(2);

        assertThrows(IllegalArgumentException.class, () -> AsciiStreamCodec.decode(bad));
    }

    @Test
    void beats_base85_on_representative_payloads() {
        byte[] input = "The quick brown fox jumps over the lazy dog's back 0123456789"
                .getBytes(StandardCharsets.UTF_8);

        assertTrue(AsciiStreamCodec.encode(input).length() < AsciiPackCodec.encode(input).length());
    }
}
