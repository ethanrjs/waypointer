package dev.ethan.waypointer.codec;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Trailer-free binary-to-text codec for Waypointer chat payloads.
 *
 * <p>The alphabet is every printable one-byte ASCII character except space and
 * {@code '.'}. Space would split/collapse during chat paste, and the period has
 * repeatedly tripped Hypixel's URL-shaped advertising filter. Keeping the
 * alphabet entirely below {@code 0x80} preserves the important invariant that
 * one visible character is one UTF-8 wire byte.
 *
 * <p>The bit-packing is the basE91 streaming scheme generalized to a 93-symbol
 * alphabet. It emits 13 or 14 source bits per two output characters depending
 * on whether the current 14-bit value fits in {@code 93^2}. Unlike the old
 * 4-byte/5-char base-85 packer, this has no pad trailer and round-trips arbitrary
 * byte arrays exactly.
 */
public final class AsciiStreamCodec {

    private static final char[] ALPHABET = (
            "!\"#$%&'()*+,-/0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ"
          + "[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
    ).toCharArray();

    private static final int BASE = ALPHABET.length;
    private static final int TWO_CHAR_VALUES = BASE * BASE;
    private static final int THIRTEEN_BITS = 1 << 13;
    private static final int FOURTEEN_BIT_THRESHOLD = TWO_CHAR_VALUES - THIRTEEN_BITS - 1;

    private static final int[] DECODE_TABLE = new int[128];

    static {
        Arrays.fill(DECODE_TABLE, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            DECODE_TABLE[ALPHABET[i]] = i;
        }
    }

    private AsciiStreamCodec() {}

    public static String encode(byte[] input) {
        if (input == null) throw new IllegalArgumentException("null input");
        if (input.length == 0) return "";

        StringBuilder out = new StringBuilder((input.length * 123 + 99) / 100);
        long bitBuffer = 0L;
        int bitCount = 0;

        for (byte value : input) {
            bitBuffer |= (long) (value & 0xFF) << bitCount;
            bitCount += 8;

            if (bitCount > 13) {
                int encoded = (int) (bitBuffer & (THIRTEEN_BITS - 1));
                if (encoded > FOURTEEN_BIT_THRESHOLD) {
                    bitBuffer >>>= 13;
                    bitCount -= 13;
                } else {
                    encoded = (int) (bitBuffer & ((1 << 14) - 1));
                    bitBuffer >>>= 14;
                    bitCount -= 14;
                }
                out.append(ALPHABET[encoded % BASE]);
                out.append(ALPHABET[encoded / BASE]);
            }
        }

        if (bitCount > 0) {
            out.append(ALPHABET[(int) (bitBuffer % BASE)]);
            if (bitCount > 7 || bitBuffer >= BASE) {
                out.append(ALPHABET[(int) (bitBuffer / BASE)]);
            }
        }
        return out.toString();
    }

    public static byte[] decode(String input) {
        if (input == null) throw new IllegalArgumentException("null input");
        if (input.isEmpty()) return new byte[0];

        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length());
        long bitBuffer = 0L;
        int bitCount = 0;
        int pending = -1;

        for (int i = 0; i < input.length(); i++) {
            int digit = digitOf(input.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException(
                        "invalid character at " + i + ": '" + input.charAt(i) + "'");
            }

            if (pending < 0) {
                pending = digit;
                continue;
            }

            int encoded = pending + digit * BASE;
            bitBuffer |= (long) encoded << bitCount;
            bitCount += (encoded & (THIRTEEN_BITS - 1)) > FOURTEEN_BIT_THRESHOLD ? 13 : 14;

            while (bitCount >= 8) {
                out.write((int) (bitBuffer & 0xFF));
                bitBuffer >>>= 8;
                bitCount -= 8;
            }
            pending = -1;
        }

        if (pending >= 0) {
            out.write((int) ((bitBuffer | ((long) pending << bitCount)) & 0xFF));
        }
        return out.toByteArray();
    }

    public static boolean isValidBody(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!isAlphabetChar(s.charAt(i))) return false;
        }
        return true;
    }

    public static boolean isAlphabetChar(char c) {
        return c < DECODE_TABLE.length && DECODE_TABLE[c] >= 0;
    }

    public static int alphabetSize() {
        return BASE;
    }

    private static int digitOf(char c) {
        return c < DECODE_TABLE.length ? DECODE_TABLE[c] : -1;
    }
}
