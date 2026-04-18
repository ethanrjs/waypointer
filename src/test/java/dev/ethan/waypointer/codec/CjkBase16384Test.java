package dev.ethan.waypointer.codec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the CJK base-16384 binary-to-text codec. Critical correctness because
 * a single mispacked bit silently corrupts every shared waypoint route.
 */
class CjkBase16384Test {

    @Test
    void round_trips_arbitrary_byte_patterns() {
        Random r = new Random(0xCA7CAFE);
        // Exercise every residue class mod 7 so the pad logic is covered.
        for (int trial = 0; trial < 300; trial++) {
            int len = r.nextInt(400);
            byte[] input = new byte[len];
            r.nextBytes(input);

            String encoded = CjkBase16384.encode(input);
            byte[] decoded = CjkBase16384.decode(encoded);
            assertArrayEquals(input, decoded,
                    "mismatch at trial " + trial + " len=" + len);
        }
    }

    @Test
    void empty_input_round_trips() {
        String s = CjkBase16384.encode(new byte[0]);
        byte[] decoded = CjkBase16384.decode(s);
        assertArrayEquals(new byte[0], decoded);
    }

    @Test
    void encoded_length_matches_formula() {
        // For each residue class 0..6, verify the output length matches the documented
        // formula ceil(n/7) * 4 + 1. This is the contract callers use to budget chat space.
        for (int n = 0; n < 21; n++) {
            byte[] input = new byte[n];
            int expected = (n == 0 ? 0 : ((n + 6) / 7) * 4) + 1;
            assertEquals(expected, CjkBase16384.encode(input).length(),
                    "length mismatch for n=" + n);
            assertEquals(expected, CjkBase16384.encodedLength(n),
                    "encodedLength mismatch for n=" + n);
        }
    }

    @Test
    void output_contains_only_alphabet_chars() {
        // Any character outside the CJK range would fail Minecraft's chat validator
        // or collapse during paste, silently corrupting the export.
        Random r = new Random(0x70DA);
        byte[] input = new byte[512];
        r.nextBytes(input);
        String s = CjkBase16384.encode(input);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            assertTrue(c >= 0x4E00 && c < 0x4E00 + 16384,
                    "out-of-range char at " + i + ": U+" + Integer.toHexString(c));
        }
    }

    @Test
    void output_is_chat_paste_safe() {
        // The CJK alphabet is defined precisely so it survives Minecraft chat paste:
        // no whitespace, no control chars, no section sign.
        Random r = new Random(0xBEAF);
        byte[] input = new byte[256];
        r.nextBytes(input);
        String s = CjkBase16384.encode(input);
        assertFalse(s.contains(" "),  "must not contain spaces");
        assertFalse(s.contains("\t"), "must not contain tabs");
        assertFalse(s.contains("\n"), "must not contain newlines");
        assertFalse(s.contains("\u00A7"), "must not contain section sign");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            assertTrue(c >= 0x20 && c != 0x7F, "non-printable at " + i);
        }
    }

    @Test
    void rejects_out_of_range_character() {
        // Minecraft chat paste corruption typically replaces a CJK glyph with a
        // blank or an ASCII fallback. Either way decode must raise, never silently
        // produce wrong bytes.
        String valid = CjkBase16384.encode(new byte[]{1, 2, 3, 4, 5, 6, 7});
        // Replace the second body char with an ASCII letter.
        String garbled = valid.charAt(0) + "A" + valid.substring(2);
        assertThrows(IllegalArgumentException.class, () -> CjkBase16384.decode(garbled));
    }

    @Test
    void rejects_malformed_pad_marker() {
        // Pad marker out of [0, 6] must not round-trip as a silently-accepted value.
        String s = CjkBase16384.encode(new byte[]{1});
        // Trailer is the last char; swap it for a CJK char whose digit value is > 6.
        char badTrailer = (char) (CjkBase16384.ALPHABET_BASE + 7);
        String bad = s.substring(0, s.length() - 1) + badTrailer;
        assertThrows(IllegalArgumentException.class, () -> CjkBase16384.decode(bad));
    }

    @Test
    void rejects_body_length_not_multiple_of_four() {
        // Truncated paste: one char got lost somewhere in the middle. Reject loudly.
        String s = CjkBase16384.encode(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        String truncated = s.substring(0, s.length() - 2) + s.charAt(s.length() - 1);
        assertThrows(IllegalArgumentException.class, () -> CjkBase16384.decode(truncated));
    }

    @Test
    void output_is_denser_than_z85() {
        // This test exists to catch regressions if someone later swaps the alphabet.
        // CJK base-16384 should produce roughly 45% fewer characters than Z85 for
        // meaningful payloads. Concretely: 14 bits/char vs 6.41 bits/char.
        byte[] input = "The quick brown fox jumps over the lazy dog's back 0123456789".getBytes(StandardCharsets.UTF_8);
        String cjk = CjkBase16384.encode(input);
        // Z85 encodes 4 bytes into 5 chars + 1 pad marker.
        int z85Len = ((input.length + 3) / 4) * 5 + 1;
        assertTrue(cjk.length() < z85Len,
                "expected CJK (" + cjk.length() + ") shorter than Z85 (" + z85Len + ")");
        // Tighter: strictly less than Z85 * 0.7 (14 / 6.41 * 5 / 4 ≈ 2.73 density ratio).
        assertTrue(cjk.length() * 10 < z85Len * 7,
                "expected CJK significantly denser than Z85; cjk=" + cjk.length() + " z85=" + z85Len);
    }

    @Test
    void isValidBody_matches_alphabet() {
        assertTrue(CjkBase16384.isValidBody(CjkBase16384.encode(new byte[]{42})));
        assertFalse(CjkBase16384.isValidBody("hello"));
        assertFalse(CjkBase16384.isValidBody(""));
        assertFalse(CjkBase16384.isValidBody(null));
        // Char just past the 16384 alphabet must be rejected.
        char beyond = (char) (CjkBase16384.ALPHABET_BASE + 16384);
        assertFalse(CjkBase16384.isValidBody(String.valueOf(beyond)));
    }

    @Test
    void decodes_all_residue_classes() {
        // Each residue (len mod 7) picks a different pad value on encode; decode
        // must trim exactly the right number of trailing bytes in every case.
        for (int n = 0; n <= 14; n++) {
            byte[] input = new byte[n];
            for (int i = 0; i < n; i++) input[i] = (byte) (0x80 | i);  // high-bit bytes
            byte[] decoded = CjkBase16384.decode(CjkBase16384.encode(input));
            assertArrayEquals(input, decoded, "residue " + (n % 7) + " (n=" + n + ")");
        }
    }
}
