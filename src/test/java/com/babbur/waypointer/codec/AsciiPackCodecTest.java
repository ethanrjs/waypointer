package com.babbur.waypointer.codec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AsciiPackCodecTest {

    @Test
    void round_trips_arbitrary_byte_patterns() {
        Random r = new Random(0xCA7CAFE);

        for (int trial = 0; trial < 300; trial++) {
            int len = r.nextInt(400);
            byte[] input = new byte[len];
            r.nextBytes(input);

            String encoded = AsciiPackCodec.encode(input);
            byte[] decoded = AsciiPackCodec.decode(encoded);
            assertArrayEquals(input, decoded,
                    "mismatch at trial " + trial + " len=" + len);
        }
    }

    @Test
    void empty_input_round_trips() {
        String s = AsciiPackCodec.encode(new byte[0]);
        byte[] decoded = AsciiPackCodec.decode(s);
        assertArrayEquals(new byte[0], decoded);
    }

    @Test
    void encoded_length_matches_formula() {
        for (int n = 0; n < 21; n++) {
            byte[] input = new byte[n];
            int expected = n == 0 ? 1 : ((n + 3) / 4) * 5 + 1;
            assertEquals(expected, AsciiPackCodec.encode(input).length(),
                    "length mismatch for n=" + n);
            assertEquals(expected, AsciiPackCodec.encodedLength(n),
                    "encodedLength mismatch for n=" + n);
        }
    }

    @Test
    void encoded_length_rejects_negative_and_overflowing_counts() {
        assertThrows(IllegalArgumentException.class, () -> AsciiPackCodec.encodedLength(-1));
        assertThrows(IllegalArgumentException.class,
                () -> AsciiPackCodec.encodedLength(Integer.MAX_VALUE));
    }

    @Test
    void output_contains_only_alphabet_chars() {
        Random r = new Random(0x70DA);
        byte[] input = new byte[512];
        r.nextBytes(input);
        String s = AsciiPackCodec.encode(input);
        for (int i = 0; i < s.length(); i++) {
            assertTrue(AsciiPackCodec.isAlphabetChar(s.charAt(i)),
                    "out-of-alphabet char at " + i + ": '" + s.charAt(i) + "'");
        }
    }

    @Test
    void output_never_contains_period() {

        Random r = new Random(0x4D4F4453);
        for (int trial = 0; trial < 200; trial++) {
            byte[] input = new byte[256 + r.nextInt(256)];
            r.nextBytes(input);
            String s = AsciiPackCodec.encode(input);
            assertFalse(s.contains("."),
                    "trial " + trial + ": output contains '.' which the ad filter flags: " + s);
        }
    }

    @Test
    void output_is_chat_paste_safe() {
        Random r = new Random(0xBEAF);
        byte[] input = new byte[256];
        r.nextBytes(input);
        String s = AsciiPackCodec.encode(input);
        assertFalse(s.contains(" "),    "must not contain spaces");
        assertFalse(s.contains("\t"),   "must not contain tabs");
        assertFalse(s.contains("\n"),   "must not contain newlines");
        assertFalse(s.contains("\u00A7"), "must not contain section sign");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            assertTrue(c >= 0x20 && c < 0x7F, "non-printable ASCII at " + i + ": 0x" + Integer.toHexString(c));
        }
    }

    @Test
    void rejects_out_of_alphabet_character() {
        String valid = AsciiPackCodec.encode(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        String garbled = valid.charAt(0) + "~" + valid.substring(2);
        assertThrows(IllegalArgumentException.class, () -> AsciiPackCodec.decode(garbled));
    }

    @Test
    void rejects_period_as_alphabet_member() {
        assertFalse(AsciiPackCodec.isAlphabetChar('.'),
                "'.' must not be in the alphabet -- it trips Hypixel's advertising filter");
        String dotted = "0000.0";
        assertThrows(IllegalArgumentException.class, () -> AsciiPackCodec.decode(dotted),
                "a '.' inside a body must be rejected as an invalid character");
    }

    @Test
    void rejects_malformed_pad_marker() {

        String s = AsciiPackCodec.encode(new byte[]{1});
        String bad = s.substring(0, s.length() - 1) + '4';
        assertThrows(IllegalArgumentException.class, () -> AsciiPackCodec.decode(bad));
    }

    @Test
    void rejects_body_length_not_multiple_of_five() {
        String s = AsciiPackCodec.encode(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        String truncated = s.substring(0, s.length() - 2) + s.charAt(s.length() - 1);
        assertThrows(IllegalArgumentException.class, () -> AsciiPackCodec.decode(truncated));
    }

    @Test
    void rejects_overflowing_group() {

        String overflow = "#####" + AsciiPackCodec.encode(new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> AsciiPackCodec.decode(overflow),
                "a 5-digit group whose integer value exceeds 2^32 must be rejected");
    }

    @Test
    void wire_density_beats_cjk_on_utf8_bytes() {

        byte[] input = "The quick brown fox jumps over the lazy dog's back 0123456789"
                .getBytes(StandardCharsets.UTF_8);
        String packed = AsciiPackCodec.encode(input);

        int packWireBytes = packed.length();

        int cjkWireBytes = (((input.length + 6) / 7) * 4 + 1) * 3;
        assertTrue(packWireBytes < cjkWireBytes,
                "expected base-85 wire bytes (" + packWireBytes + ") < CJK wire bytes (" + cjkWireBytes + ")");
    }

    @Test
    void isValidBody_matches_alphabet() {
        assertTrue(AsciiPackCodec.isValidBody(AsciiPackCodec.encode(new byte[]{42})));
        assertTrue(AsciiPackCodec.isValidBody("0"));
        assertFalse(AsciiPackCodec.isValidBody("hello world"));
        assertFalse(AsciiPackCodec.isValidBody(""));
        assertFalse(AsciiPackCodec.isValidBody(null));
        assertFalse(AsciiPackCodec.isValidBody("abc~"), "~ is outside the base-85 alphabet");
        assertFalse(AsciiPackCodec.isValidBody("abc.def"), "'.' is outside the base-85 alphabet");
        assertTrue(AsciiPackCodec.isValidBody("abc;def"), "';' IS inside the base-85 alphabet (replaces '.')");
    }

    @Test
    void decodes_all_residue_classes() {
        for (int n = 0; n <= 12; n++) {
            byte[] input = new byte[n];
            for (int i = 0; i < n; i++) input[i] = (byte) (0x80 | i);
            byte[] decoded = AsciiPackCodec.decode(AsciiPackCodec.encode(input));
            assertArrayEquals(input, decoded, "residue " + (n % 4) + " (n=" + n + ")");
        }
    }

    @Test
    void alphabet_excludes_hostile_characters() {

        char[] hostile = { ' ', '\t', '\n', '\r', '\u00A7', '\\', '\'', '"', '|', ',', '`', '~', '.' };
        for (char c : hostile) {
            assertFalse(AsciiPackCodec.isAlphabetChar(c),
                    "base-85 alphabet must not contain hostile char 0x" + Integer.toHexString(c));
        }
        assertTrue(AsciiPackCodec.isAlphabetChar(';'),
                "';' must be in the alphabet -- it is the replacement for '.' that still fits the 85-digit packing math");
    }
}
