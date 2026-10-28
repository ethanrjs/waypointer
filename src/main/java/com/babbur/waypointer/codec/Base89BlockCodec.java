package com.babbur.waypointer.codec;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Bracket-free text for V10/V11 frames: the base-91 alphabet without {@code <} and {@code >}, which
 * YouTube and some other sites refuse. Base-91's 13/14-bit pairs need 91 symbols, so this spelling
 * writes 21-byte blocks as 26 big-endian base-89 digits instead (about 0.6% longer than base-91).
 */
final class Base89BlockCodec {

    static final String ALPHABET =
            "!\"#$%&'()*+-/0123456789:;=?@ABCDEFGHIJKLMNOPQRSTUVWXYZ"
          + "[\\]^_abcdefghijklmnopqrstuvwxyz{|}~";
    static final int BASE = ALPHABET.length();
    static final int BLOCK_BYTES = 21;

    private static final BigInteger BIG_BASE = BigInteger.valueOf(BASE);
    private static final int[] DIGITS_FOR_BYTES = new int[BLOCK_BYTES + 1];
    private static final int[] BYTES_FOR_DIGITS;
    private static final int[] DECODE = new int[128];

    static {
        Arrays.fill(DECODE, -1);
        for (int i = 0; i < BASE; i++) DECODE[ALPHABET.charAt(i)] = i;
        for (int bytes = 1; bytes <= BLOCK_BYTES; bytes++) {
            BigInteger limit = BigInteger.ONE.shiftLeft(8 * bytes);
            BigInteger capacity = BigInteger.ONE;
            int digits = 0;
            while (capacity.compareTo(limit) < 0) {
                capacity = capacity.multiply(BIG_BASE);
                digits++;
            }
            DIGITS_FOR_BYTES[bytes] = digits;
        }
        BYTES_FOR_DIGITS = new int[DIGITS_FOR_BYTES[BLOCK_BYTES] + 1];
        Arrays.fill(BYTES_FOR_DIGITS, -1);
        for (int bytes = 1; bytes <= BLOCK_BYTES; bytes++) {
            if (BYTES_FOR_DIGITS[DIGITS_FOR_BYTES[bytes]] >= 0) throw new IllegalStateException("ambiguous tail");
            BYTES_FOR_DIGITS[DIGITS_FOR_BYTES[bytes]] = bytes;
        }
    }

    private Base89BlockCodec() {}

    static int blockDigits() {
        return DIGITS_FOR_BYTES[BLOCK_BYTES];
    }

    static boolean isAlphabetChar(char c) {
        return c < DECODE.length && DECODE[c] >= 0;
    }

    static String encode(byte[] input) {
        if (input == null) throw new IllegalArgumentException("null input");
        StringBuilder out = new StringBuilder(input.length / BLOCK_BYTES * blockDigits() + blockDigits());
        for (int offset = 0; offset < input.length; offset += BLOCK_BYTES) {
            int bytes = Math.min(BLOCK_BYTES, input.length - offset);
            BigInteger value = new BigInteger(1, Arrays.copyOfRange(input, offset, offset + bytes));
            char[] digits = new char[DIGITS_FOR_BYTES[bytes]];
            for (int i = digits.length - 1; i >= 0; i--) {
                BigInteger[] divided = value.divideAndRemainder(BIG_BASE);
                digits[i] = ALPHABET.charAt(divided[1].intValue());
                value = divided[0];
            }
            out.append(digits);
        }
        return out.toString();
    }

    static byte[] decode(String text) {
        if (text == null) throw new IllegalArgumentException("null input");
        int fullBlocks = text.length() / blockDigits();
        int tailDigits = text.length() % blockDigits();
        int tailBytes = tailDigits == 0 ? 0 : BYTES_FOR_DIGITS[tailDigits];
        if (tailBytes < 0) {
            throw new IllegalArgumentException("base-89 text length " + text.length() + " has no block layout");
        }
        byte[] out = new byte[fullBlocks * BLOCK_BYTES + tailBytes];
        int written = 0;
        for (int start = 0; start < text.length(); ) {
            int digits = Math.min(blockDigits(), text.length() - start);
            int bytes = BYTES_FOR_DIGITS[digits];
            BigInteger value = BigInteger.ZERO;
            for (int i = start; i < start + digits; i++) {
                char c = text.charAt(i);
                int digit = c < DECODE.length ? DECODE[c] : -1;
                if (digit < 0) throw new IllegalArgumentException("invalid character at " + i + ": '" + c + "'");
                value = value.multiply(BIG_BASE).add(BigInteger.valueOf(digit));
            }
            if (value.bitLength() > bytes * 8) {
                throw new IllegalArgumentException("base-89 block at " + start + " overflows " + bytes + " bytes");
            }
            byte[] magnitude = value.toByteArray();
            int copy = Math.min(bytes, magnitude.length);
            System.arraycopy(magnitude, magnitude.length - copy, out, written + bytes - copy, copy);
            written += bytes;
            start += digits;
        }
        return out;
    }
}
