package com.babbur.waypointer.codec;

/**
 * Packs four bytes into five printable base-85 characters. The alphabet is
 * Z85 with {@code .} replaced by {@code ;} so route codes do not look like
 * URLs to Hypixel's chat filter. A final character stores the number of padded
 * bytes, so inputs of any length round-trip exactly.
 */
public final class AsciiPackCodec {

    /**
     * Base-85 alphabet, indexed by digit value (0..84). Ordering preserves
     * the Z85 scheme except digit 62 is {@code ';'} instead of {@code '.'}
     * so the mapping is easy to audit against the reference spec.
     */
    private static final char[] ALPHABET = {
            '0','1','2','3','4','5','6','7','8','9',
            'a','b','c','d','e','f','g','h','i','j',
            'k','l','m','n','o','p','q','r','s','t',
            'u','v','w','x','y','z',
            'A','B','C','D','E','F','G','H','I','J',
            'K','L','M','N','O','P','Q','R','S','T',
            'U','V','W','X','Y','Z',
            ';','-',':','+','=','^','!','/','*','?',
            '&','<','>','(',')','[',']','{','}','@',
            '%','$','#'
    };

    private static final int[] DECODE_TABLE = new int[128];

    static {
        java.util.Arrays.fill(DECODE_TABLE, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            DECODE_TABLE[ALPHABET[i]] = i;
        }
    }

    private static final long P4 = 85L * 85L * 85L * 85L;
    private static final long P3 = 85L * 85L * 85L;
    private static final long P2 = 85L * 85L;
    private static final long BASE = 85L;

    private static final int GROUP_BYTES = 4;
    private static final int GROUP_CHARS = 5;
    private static final int MAX_PAD = GROUP_BYTES - 1;

    private AsciiPackCodec() {}

    public static int encodedLength(int inputByteCount) {
        if (inputByteCount < 0) {
            throw new IllegalArgumentException("negative input byte count");
        }
        if (inputByteCount == 0) return 1;
        long groups = ((long) inputByteCount + GROUP_BYTES - 1) / GROUP_BYTES;
        long length = groups * GROUP_CHARS + 1;
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("encoded length exceeds integer range");
        }
        return (int) length;
    }

    public static String encode(byte[] input) {
        if (input == null) throw new IllegalArgumentException("null input");
        if (input.length == 0) {
            // Every payload has a trailer, including an empty payload.
            return String.valueOf(ALPHABET[0]);
        }

        int pad = (GROUP_BYTES - (input.length % GROUP_BYTES)) % GROUP_BYTES;
        int totalLen = input.length + pad;

        StringBuilder out = new StringBuilder(totalLen / GROUP_BYTES * GROUP_CHARS + 1);
        for (int off = 0; off < totalLen; off += GROUP_BYTES) {
            long v = 0L;
            for (int i = 0; i < GROUP_BYTES; i++) {
                int idx = off + i;
                int b = idx < input.length ? (input[idx] & 0xFF) : 0;
                v = (v << 8) | b;
            }
            // This hot loop is unrolled to avoid repeated division setup.
            int d0 = (int) (v / P4);
            long r  = v - d0 * P4;
            int d1 = (int) (r / P3);
            r -= d1 * P3;
            int d2 = (int) (r / P2);
            r -= d2 * P2;
            int d3 = (int) (r / BASE);
            int d4 = (int) (r - d3 * BASE);
            out.append(ALPHABET[d0])
               .append(ALPHABET[d1])
               .append(ALPHABET[d2])
               .append(ALPHABET[d3])
               .append(ALPHABET[d4]);
        }
        out.append(ALPHABET[pad]);
        return out.toString();
    }

    public static byte[] decode(String input) {
        if (input == null) throw new IllegalArgumentException("null input");
        if (input.isEmpty()) throw new IllegalArgumentException("empty base-85 string");

        char padCh = input.charAt(input.length() - 1);
        int pad = digitOf(padCh);
        if (pad < 0 || pad > MAX_PAD) {
            throw new IllegalArgumentException(
                    "invalid pad marker: '" + padCh + "'");
        }

        int bodyLen = input.length() - 1;
        if (bodyLen == 0) {
            if (pad != 0) throw new IllegalArgumentException("empty body but pad=" + pad);
            return new byte[0];
        }
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
                int digit = digitOf(c);
                if (digit < 0) {
                    throw new IllegalArgumentException(
                            "invalid character at " + (g * GROUP_CHARS + i) + ": '" + c + "'");
                }
                v = v * BASE + digit;
            }
            // Five base-85 digits can exceed 32 bits, so reject overflow.
            if (v > 0xFFFFFFFFL) {
                throw new IllegalArgumentException(
                        "group " + g + " overflows 32 bits (v=" + v + ")");
            }
            int base = g * GROUP_BYTES;
            out[base]     = (byte) ((v >>> 24) & 0xFF);
            out[base + 1] = (byte) ((v >>> 16) & 0xFF);
            out[base + 2] = (byte) ((v >>>  8) & 0xFF);
            out[base + 3] = (byte) ( v         & 0xFF);
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
        return c < DECODE_TABLE.length && DECODE_TABLE[c] >= 0;
    }

    private static int digitOf(char c) {
        return c < DECODE_TABLE.length ? DECODE_TABLE[c] : -1;
    }
}
