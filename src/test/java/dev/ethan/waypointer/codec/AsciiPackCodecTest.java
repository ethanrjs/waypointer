package dev.ethan.waypointer.codec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness tests for the base-84 binary-to-text codec. A single mispacked
 * bit silently corrupts every shared waypoint route, so we lean hard on fuzzing
 * across every residue class rather than hand-picking cases.
 */
class AsciiPackCodecTest {

    @Test
    void round_trips_arbitrary_byte_patterns() {
        Random r = new Random(0xCA7CAFE);
        // Exercise every residue class mod 4 (the pad classes) across many
        // lengths so both short-tail and long-body paths see coverage.
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
        // For each residue class 0..3 verify the documented formula
        // ceil(n/4) * 5 + 1 (and the empty-input special case of 1).
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
    void output_contains_only_alphabet_chars() {
        // Any character outside the alphabet would either fail Minecraft's
        // chat validator (e.g. if § somehow snuck in) or collapse during paste
        // (e.g. a whitespace run). Both silently corrupt the export.
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
        // The whole reason for base-84: Hypixel's advertising filter flags
        // messages containing URL-shaped substrings, and in Z85 bodies we
        // regularly saw runs like "H.vD" or ".BeOz" (just digit coincidences)
        // kick in "Advertising is against the rules" disconnects. Fuzz a big
        // pile of random inputs and assert no body ever contains '.'.
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
        // The alphabet is defined precisely so it survives Minecraft chat
        // paste: no whitespace, no control chars, no section sign, no quotes
        // that shells (or macro pasters) might strip.
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
        // Chat paste corruption typically replaces a character with a blank
        // or an outside-alphabet glyph. Decode must raise loudly, never
        // silently produce wrong bytes.
        String valid = AsciiPackCodec.encode(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        // Replace the second body char with a non-alphabet ASCII byte.
        String garbled = valid.charAt(0) + "~" + valid.substring(2);
        assertThrows(IllegalArgumentException.class, () -> AsciiPackCodec.decode(garbled));
    }

    @Test
    void rejects_period_as_alphabet_member() {
        // Regression guard: if someone re-adds '.' to the alphabet, the first
        // thing the ad filter will do is disconnect the sender. Explicit test
        // so the intent is documented in code, not just in the commit log.
        assertFalse(AsciiPackCodec.isAlphabetChar('.'),
                "'.' must not be in the alphabet -- it trips Hypixel's advertising filter");
        String dotted = "0000.0";
        assertThrows(IllegalArgumentException.class, () -> AsciiPackCodec.decode(dotted),
                "a '.' inside a body must be rejected as an invalid character");
    }

    @Test
    void rejects_malformed_pad_marker() {
        // Pad marker digit must be in [0, 3]. A trailer with digit 4..83 is
        // a hard error -- accepting it would silently trim the wrong number
        // of bytes.
        String s = AsciiPackCodec.encode(new byte[]{1});
        // Replace trailer with the digit-value-4 char ('4'): pad=4, which is out of range.
        String bad = s.substring(0, s.length() - 1) + '4';
        assertThrows(IllegalArgumentException.class, () -> AsciiPackCodec.decode(bad));
    }

    @Test
    void rejects_body_length_not_multiple_of_five() {
        // Truncated paste: one character got lost somewhere. Reject loudly
        // rather than decoding the remaining prefix and returning junk.
        String s = AsciiPackCodec.encode(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        String truncated = s.substring(0, s.length() - 2) + s.charAt(s.length() - 1);
        assertThrows(IllegalArgumentException.class, () -> AsciiPackCodec.decode(truncated));
    }

    // NOTE: base-85 had a "reject 5-digit group whose integer value >= 2^32"
    // test because 85^5 = 4,437,053,125 leaves some group values above the
    // 32-bit unsigned ceiling. Base-84 happens to have max group value
    // 83 * (84^4 + 84^3 + 84^2 + 84 + 1) = 4,182,119,423, which is strictly
    // below 2^32 (4,294,967,296). No digit sequence can overflow, so the
    // overflow-rejection guard in decode() is defense-in-depth only and
    // cannot be hit by any well-formed input. The guard stays (cheap,
    // future-proof if we ever bump the base back up), but there's no useful
    // test to write for it.

    @Test
    void wire_density_beats_cjk_on_utf8_bytes() {
        // The whole reason we switched: base-84 should carry more compressed
        // DEFLATE per UTF-8 byte than the old CJK base-16384 alphabet on a
        // typical payload. CJK is 4.67 bits per wire byte; base-84 is ~6.39.
        byte[] input = "The quick brown fox jumps over the lazy dog's back 0123456789"
                .getBytes(StandardCharsets.UTF_8);
        String packed = AsciiPackCodec.encode(input);
        // Single-byte ASCII so char count == UTF-8 byte count.
        int packWireBytes = packed.length();
        // For reference: CJK would emit ceil(n/7)*4 + 1 chars at 3 UTF-8 bytes each.
        int cjkWireBytes = (((input.length + 6) / 7) * 4 + 1) * 3;
        assertTrue(packWireBytes < cjkWireBytes,
                "expected base-84 wire bytes (" + packWireBytes + ") < CJK wire bytes (" + cjkWireBytes + ")");
    }

    @Test
    void isValidBody_matches_alphabet() {
        assertTrue(AsciiPackCodec.isValidBody(AsciiPackCodec.encode(new byte[]{42})));
        assertTrue(AsciiPackCodec.isValidBody("0"));  // minimal valid empty-input output
        assertFalse(AsciiPackCodec.isValidBody("hello world"));  // contains space
        assertFalse(AsciiPackCodec.isValidBody(""));
        assertFalse(AsciiPackCodec.isValidBody(null));
        assertFalse(AsciiPackCodec.isValidBody("abc~"), "~ is outside the base-84 alphabet");
        assertFalse(AsciiPackCodec.isValidBody("abc.def"), "'.' is outside the base-84 alphabet");
    }

    @Test
    void decodes_all_residue_classes() {
        // Each residue (len mod 4) picks a different pad value on encode; decode
        // must trim exactly the right number of trailing bytes in every case.
        for (int n = 0; n <= 12; n++) {
            byte[] input = new byte[n];
            for (int i = 0; i < n; i++) input[i] = (byte) (0x80 | i);  // high-bit bytes
            byte[] decoded = AsciiPackCodec.decode(AsciiPackCodec.encode(input));
            assertArrayEquals(input, decoded, "residue " + (n % 4) + " (n=" + n + ")");
        }
    }

    @Test
    void alphabet_excludes_hostile_characters() {
        // Minecraft's chat validator rejects § (U+00A7) and any control char.
        // Shell-style pasters might strip whitespace or backslashes. Hypixel's
        // ad filter rejects anything URL-shaped (which is why '.' is banned).
        // Verify none of those are present in the alphabet.
        char[] hostile = { ' ', '\t', '\n', '\r', '\u00A7', '\\', '\'', '"', '|', ',', ';', '`', '~', '.' };
        for (char c : hostile) {
            assertFalse(AsciiPackCodec.isAlphabetChar(c),
                    "base-84 alphabet must not contain hostile char 0x" + Integer.toHexString(c));
        }
    }
}
