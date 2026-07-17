package com.babbur.waypointer.codec;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Trailer-free binary-to-text codec for Waypointer chat payloads.
 *
 * <p>The current alphabet is every printable one-byte ASCII character except
 * space, comma, {@code '.'}, and backtick. Space would split/collapse during
 * chat paste, comma is common prose punctuation that can stick to a shared
 * route, period trips Hypixel's URL-shaped advertising filter, and backticks
 * make shared payloads awkward in Markdown-heavy surfaces like Discord. Keeping
 * the alphabet entirely below {@code 0x80} preserves the important invariant
 * that one visible character is one UTF-8 wire byte.
 *
 * <p>The bit-packing is the basE91 streaming scheme generalized to an arbitrary
 * alphabet. It emits 13 or 14 source bits per two output characters depending
 * on whether the current 14-bit value fits in {@code alphabetSize^2}. Unlike
 * the old 4-byte/5-char base-85 packer, this has no pad trailer and round-trips
 * arbitrary byte arrays exactly.
 */
public final class AsciiStreamCodec {

    private static final Alphabet CURRENT = new Alphabet(
            "!\"#$%&'()*+-/0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ"
          + "[\\]^_abcdefghijklmnopqrstuvwxyz{|}~"
    );

    private static final Alphabet LEGACY_V4 = new Alphabet(
            "!\"#$%&'()*+,-/0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ"
          + "[\\]^_abcdefghijklmnopqrstuvwxyz{|}~"
    );

    private static final Alphabet LEGACY_V3 = new Alphabet(
            "!\"#$%&'()*+,-/0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ"
          + "[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
    );

    private static final int THIRTEEN_BITS = 1 << 13;

    private AsciiStreamCodec() {}

    public static String encode(byte[] input) {
        return encode(input, CURRENT);
    }

    static String encodeLegacyV3(byte[] input) {
        return encode(input, LEGACY_V3);
    }

    static String encodeLegacyV4(byte[] input) {
        return encode(input, LEGACY_V4);
    }

    public static byte[] decode(String input) {
        return decode(input, CURRENT);
    }

    static byte[] decodeLegacyV4(String input) {
        return decode(input, LEGACY_V4);
    }

    static byte[] decodeLegacyV3(String input) {
        return decode(input, LEGACY_V3);
    }

    private static String encode(byte[] input, Alphabet alphabet) {
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
                if (encoded > alphabet.fourteenBitThreshold()) {
                    bitBuffer >>>= 13;
                    bitCount -= 13;
                } else {
                    encoded = (int) (bitBuffer & ((1 << 14) - 1));
                    bitBuffer >>>= 14;
                    bitCount -= 14;
                }
                out.append(alphabet.charAt(encoded % alphabet.base()));
                out.append(alphabet.charAt(encoded / alphabet.base()));
            }
        }

        if (bitCount > 0) {
            out.append(alphabet.charAt((int) (bitBuffer % alphabet.base())));
            if (bitCount > 7 || bitBuffer >= alphabet.base()) {
                out.append(alphabet.charAt((int) (bitBuffer / alphabet.base())));
            }
        }
        return out.toString();
    }

    private static byte[] decode(String input, Alphabet alphabet) {
        if (input == null) throw new IllegalArgumentException("null input");
        if (input.isEmpty()) return new byte[0];

        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length());
        long bitBuffer = 0L;
        int bitCount = 0;
        int pending = -1;

        for (int i = 0; i < input.length(); i++) {
            int digit = alphabet.digitOf(input.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException(
                        "invalid character at " + i + ": '" + input.charAt(i) + "'");
            }

            if (pending < 0) {
                pending = digit;
                continue;
            }

            int encoded = pending + digit * alphabet.base();
            bitBuffer |= (long) encoded << bitCount;
            bitCount += (encoded & (THIRTEEN_BITS - 1)) > alphabet.fourteenBitThreshold()
                    ? 13
                    : 14;

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
            if (!CURRENT.has(s.charAt(i))) return false;
        }
        return true;
    }

    public static boolean isAlphabetChar(char c) {
        return CURRENT.has(c) || LEGACY_V4.has(c) || LEGACY_V3.has(c);
    }

    public static int alphabetSize() {
        return CURRENT.base();
    }

    static int legacyV4AlphabetSize() {
        return LEGACY_V4.base();
    }

    static int legacyV3AlphabetSize() {
        return LEGACY_V3.base();
    }

    private record Alphabet(char[] chars, int[] decodeTable, int fourteenBitThreshold) {
        Alphabet(String chars) {
            this(chars.toCharArray(), buildDecodeTable(chars),
                    chars.length() * chars.length() - THIRTEEN_BITS - 1);
        }

        int base() {
            return chars.length;
        }

        char charAt(int digit) {
            return chars[digit];
        }

        boolean has(char c) {
            return digitOf(c) >= 0;
        }

        int digitOf(char c) {
            return c < decodeTable.length ? decodeTable[c] : -1;
        }

        private static int[] buildDecodeTable(String chars) {
            int[] table = new int[128];
            Arrays.fill(table, -1);
            for (int i = 0; i < chars.length(); i++) {
                table[chars.charAt(i)] = i;
            }
            return table;
        }
    }
}
