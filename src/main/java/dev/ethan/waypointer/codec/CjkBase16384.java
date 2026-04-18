package dev.ethan.waypointer.codec;

/**
 * Base-16384 binary-to-text codec using a contiguous CJK Unified Ideographs range.
 *
 * Each output character encodes 14 bits -- more than 2x the density of Z85's
 * ~6.4 bits per character -- letting the Waypointer codec pack routes roughly
 * 2.18x more payload into the 256-char Minecraft chat limit without overflow.
 *
 * Alphabet:
 *
 * The 16384 code points U+4E00 through U+8DFF (the first 2^14 CJK Unified
 * Ideographs) are all:
 *
 *   - printable in Minecraft chat via the bundled Unifont fallback -- no tofu,
 *   - free of combining marks, ZWJ glyphs, and variation selectors, so they
 *     never merge with surrounding text or shift normalization,
 *   - outside every ASCII whitespace class, so chat paste cannot collapse them,
 *   - not equal to U+00A7 ('§'), so Minecraft's chat validator never rejects
 *     them as a formatting escape.
 *
 * Packing:
 *
 * Seven input bytes (56 bits) pack into four 14-bit digits emitted as
 * {@code (char)(0x4E00 + digit)}. Arbitrary input lengths are supported by
 * zero-padding the tail and appending a single trailer character whose digit
 * value (0..6) records how many pad bytes to discard on decode. The trailer is
 * itself a valid alphabet character, so {@link #isValidBody(String)} is a
 * straight range check.
 *
 * Output length is {@code ceil(n / 7) * 4 + 1} characters for n input bytes.
 */
public final class CjkBase16384 {

    /** First code point in the 2^14 alphabet. */
    static final char ALPHABET_BASE = 0x4E00;
    /** One past the last code point (exclusive upper bound for validation). */
    static final char ALPHABET_END_EXCLUSIVE = (char) (ALPHABET_BASE + 16384);

    /** Bytes per pack group. */
    private static final int GROUP_BYTES = 7;
    /** Chars per pack group (not counting the trailer). */
    private static final int GROUP_CHARS = 4;
    /** Bits per output digit. */
    private static final int BITS_PER_DIGIT = 14;
    /** Max number of pad bytes appended to a tail group. */
    private static final int MAX_PAD = GROUP_BYTES - 1;

    private CjkBase16384() {}

    /** Number of output characters produced for {@code inputByteCount} input bytes. */
    public static int encodedLength(int inputByteCount) {
        int groups = (inputByteCount + GROUP_BYTES - 1) / GROUP_BYTES;
        return groups * GROUP_CHARS + 1;
    }

    public static String encode(byte[] input) {
        if (input == null) throw new IllegalArgumentException("null input");

        int pad = (GROUP_BYTES - (input.length % GROUP_BYTES)) % GROUP_BYTES;
        int totalLen = input.length + pad;

        StringBuilder out = new StringBuilder(totalLen / GROUP_BYTES * GROUP_CHARS + 1);
        for (int off = 0; off < totalLen; off += GROUP_BYTES) {
            // Pack 7 bytes into a 56-bit long, MSB-first. Bytes past the real
            // input length are implicitly zero (pad).
            long v = 0L;
            for (int i = 0; i < GROUP_BYTES; i++) {
                int idx = off + i;
                int b = idx < input.length ? (input[idx] & 0xFF) : 0;
                v = (v << 8) | b;
            }
            // Emit 4 base-16384 digits MSB-first. Hand-unrolled to avoid loop
            // overhead on a hot path (every shared codec string).
            int d0 = (int) ((v >>> (BITS_PER_DIGIT * 3)) & 0x3FFF);
            int d1 = (int) ((v >>> (BITS_PER_DIGIT * 2)) & 0x3FFF);
            int d2 = (int) ((v >>> BITS_PER_DIGIT) & 0x3FFF);
            int d3 = (int) (v & 0x3FFF);
            out.append((char) (ALPHABET_BASE + d0))
               .append((char) (ALPHABET_BASE + d1))
               .append((char) (ALPHABET_BASE + d2))
               .append((char) (ALPHABET_BASE + d3));
        }
        out.append((char) (ALPHABET_BASE + pad));
        return out.toString();
    }

    public static byte[] decode(String input) {
        if (input == null) throw new IllegalArgumentException("null input");
        if (input.isEmpty()) throw new IllegalArgumentException("empty CJK base-16384 string");

        char padCh = input.charAt(input.length() - 1);
        int pad = padCh - ALPHABET_BASE;
        if (pad < 0 || pad > MAX_PAD) {
            throw new IllegalArgumentException(
                    "invalid pad marker: U+" + Integer.toHexString(padCh).toUpperCase());
        }

        int bodyLen = input.length() - 1;
        if ((bodyLen % GROUP_CHARS) != 0) {
            throw new IllegalArgumentException(
                    "body length must be a multiple of " + GROUP_CHARS + ", got " + bodyLen);
        }

        int groups = bodyLen / GROUP_CHARS;
        byte[] out = new byte[groups * GROUP_BYTES];
        for (int g = 0; g < groups; g++) {
            long v = 0L;
            for (int i = 0; i < GROUP_CHARS; i++) {
                char c = input.charAt(g * GROUP_CHARS + i);
                int digit = c - ALPHABET_BASE;
                if (digit < 0 || digit >= 16384) {
                    throw new IllegalArgumentException(
                            "invalid character at " + (g * GROUP_CHARS + i)
                                    + ": U+" + Integer.toHexString(c).toUpperCase());
                }
                v = (v << BITS_PER_DIGIT) | digit;
            }
            // Unpack 7 bytes from the 56-bit accumulator, MSB-first.
            int base = g * GROUP_BYTES;
            for (int i = 0; i < GROUP_BYTES; i++) {
                out[base + i] = (byte) ((v >>> ((GROUP_BYTES - 1 - i) * 8)) & 0xFF);
            }
        }
        if (pad == 0) return out;
        byte[] trimmed = new byte[out.length - pad];
        System.arraycopy(out, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    /** True iff every character in {@code s} is part of the CJK base-16384 alphabet. */
    public static boolean isValidBody(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!isAlphabetChar(s.charAt(i))) return false;
        }
        return true;
    }

    /** True iff {@code c} is one of the 16384 valid alphabet characters. */
    public static boolean isAlphabetChar(char c) {
        return c >= ALPHABET_BASE && c < ALPHABET_END_EXCLUSIVE;
    }
}
