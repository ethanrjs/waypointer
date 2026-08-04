package com.babbur.waypointer.codec;

/**
 * Legacy base-16384 binary-to-text codec using a contiguous CJK Unified
 * Ideographs range. This class exists only so current imports can still read
 * v1 Waypointer exports.
 */
public final class CjkBase16384 {

    /** First code point in the 2^14 alphabet. */
    static final char ALPHABET_BASE = 0x4E00;
    /** One past the last code point, exclusive. */
    static final char ALPHABET_END_EXCLUSIVE = (char) (ALPHABET_BASE + 16384);

    private static final int GROUP_BYTES = 7;
    private static final int GROUP_CHARS = 4;
    private static final int BITS_PER_DIGIT = 14;
    private static final int MAX_PAD = GROUP_BYTES - 1;

    private CjkBase16384() {}

    public static int encodedLength(int inputByteCount) {
        if (inputByteCount < 0) {
            throw new IllegalArgumentException("negative input byte count");
        }
        long groups = ((long) inputByteCount + GROUP_BYTES - 1) / GROUP_BYTES;
        long length = groups * GROUP_CHARS + 1;
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("encoded length exceeds integer range");
        }
        return (int) length;
    }

    public static String encode(byte[] input) {
        if (input == null) throw new IllegalArgumentException("null input");

        int pad = (GROUP_BYTES - (input.length % GROUP_BYTES)) % GROUP_BYTES;
        int totalLen = input.length + pad;

        StringBuilder out = new StringBuilder(totalLen / GROUP_BYTES * GROUP_CHARS + 1);
        for (int off = 0; off < totalLen; off += GROUP_BYTES) {
            long value = 0L;
            for (int i = 0; i < GROUP_BYTES; i++) {
                int idx = off + i;
                int b = idx < input.length ? (input[idx] & 0xFF) : 0;
                value = (value << 8) | b;
            }

            int d0 = (int) ((value >>> (BITS_PER_DIGIT * 3)) & 0x3FFF);
            int d1 = (int) ((value >>> (BITS_PER_DIGIT * 2)) & 0x3FFF);
            int d2 = (int) ((value >>> BITS_PER_DIGIT) & 0x3FFF);
            int d3 = (int) (value & 0x3FFF);
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

        char padChar = input.charAt(input.length() - 1);
        int pad = padChar - ALPHABET_BASE;
        if (pad < 0 || pad > MAX_PAD) {
            throw new IllegalArgumentException(
                    "invalid pad marker: U+" + Integer.toHexString(padChar).toUpperCase());
        }

        int bodyLen = input.length() - 1;
        if ((bodyLen % GROUP_CHARS) != 0) {
            throw new IllegalArgumentException(
                    "body length must be a multiple of " + GROUP_CHARS + ", got " + bodyLen);
        }

        int groups = bodyLen / GROUP_CHARS;
        byte[] out = new byte[groups * GROUP_BYTES];
        if (pad > out.length) throw new IllegalArgumentException("invalid padding");
        for (int group = 0; group < groups; group++) {
            long value = 0L;
            for (int i = 0; i < GROUP_CHARS; i++) {
                int charIndex = group * GROUP_CHARS + i;
                char c = input.charAt(charIndex);
                int digit = c - ALPHABET_BASE;
                if (digit < 0 || digit >= 16384) {
                    throw new IllegalArgumentException(
                            "invalid character at " + charIndex
                                    + ": U+" + Integer.toHexString(c).toUpperCase());
                }
                value = (value << BITS_PER_DIGIT) | digit;
            }

            int base = group * GROUP_BYTES;
            for (int i = 0; i < GROUP_BYTES; i++) {
                out[base + i] = (byte) ((value >>> ((GROUP_BYTES - 1 - i) * 8)) & 0xFF);
            }
        }
        if (pad == 0) return out;
        byte[] trimmed = new byte[out.length - pad];
        System.arraycopy(out, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    public static boolean isValidBody(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!isAlphabetChar(s.charAt(i))) return false;
        }
        return true;
    }

    public static boolean isAlphabetChar(char c) {
        return c >= ALPHABET_BASE && c < ALPHABET_END_EXCLUSIVE;
    }
}
