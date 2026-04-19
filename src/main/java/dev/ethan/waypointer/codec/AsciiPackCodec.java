package dev.ethan.waypointer.codec;

/**
 * Base-84 binary-to-text codec, derived from the Z85 alphabet (ZeroMQ RFC 32)
 * with the ASCII period removed.
 *
 * History:
 *
 * The codec originally shipped as CJK base-16384 (3 UTF-8 bytes per glyph,
 * ~4.67 bits per wire byte), chosen to maximise density in the 256-character
 * chat textbox. The real bottleneck turned out to be the 256-UTF-8-byte cap
 * on {@code ServerboundChatCommandPacket}: Watchdog closes the connection
 * the moment that cap is exceeded. Switching to a single-byte-per-char ASCII
 * base (Z85, log2(85) = 6.41 bits/byte) shipped ~37% more compressed DEFLATE
 * bytes through the same 256-byte envelope.
 *
 * Hypixel's advertising filter, however, flags payloads that look URL-shaped.
 * Z85's alphabet includes {@code .}, and messages like {@code H.vD}
 * (base-85 digits happening to land that way) kick in the
 * "Advertising is against the rules" disconnect even when no actual URL is
 * present. Removing {@code .} brings the alphabet to 84 characters:
 *
 *   log2(84) = 6.392 bits/wire byte   (was 6.409)
 *
 * That's a ~0.27% density loss -- under one byte of DEFLATE payload across a
 * full 250-char body -- in exchange for never triggering the ad filter via a
 * coincidentally-URL-shaped digit sequence. The {@code 4 input bytes -> 5
 * output chars} packing invariant still holds because {@code 84^5} ({@code
 * 4_182_119_424}) is greater than {@code 2^32}.
 *
 * Alphabet:
 *
 * 84 printable ASCII characters, contiguous with respect to the Z85 ordering
 * except {@code '.'} is skipped:
 * {@code 0..9 a..z A..Z -:+=^!/*?&<>()[]{}@%$#}. Every character:
 *
 *   - is a single UTF-8 byte, so 256-byte wire budgets are exactly character
 *     budgets,
 *   - is not {@code '.'}, so digit sequences cannot resemble a domain name,
 *   - prints fine in Minecraft chat (every remaining character has been
 *     verified on a live Hypixel server through a {@code /pc} command),
 *   - is not equal to U+00A7 ({@code §}), so the chat validator never treats
 *     a body character as a formatting escape,
 *   - is not whitespace, so paste can't collapse runs of them,
 *   - is not the space character, so word splits on the magic prefix still
 *     correctly extract the full body.
 *
 * Packing:
 *
 * Four input bytes (32 bits) pack into a big-endian unsigned integer and are
 * emitted as five base-84 digits, most-significant digit first. Arbitrary
 * input lengths are supported by zero-padding the tail and appending a single
 * trailer character whose digit value (0..3) records how many pad bytes to
 * discard on decode. The trailer is itself a valid alphabet character, so
 * {@link #isValidBody(String)} is a straight alphabet-range check.
 *
 * Output length is {@code ceil(n / 4) * 5 + 1} characters for n input bytes,
 * or {@code 1} when n = 0 -- identical to the previous base-85 packing since
 * both bases clear the {@code 2^32} threshold for 5 digits.
 *
 * Bit budget reminder: a full 256-byte command packet with a 3-byte
 * {@code "pc "} prefix and 3-byte {@code "WP:"} magic leaves 250 body
 * characters (= 49 complete groups + 1 trailer), carrying 196 bytes of
 * compressed DEFLATE -- unchanged from the base-85 layout because the
 * character count for a given byte count is identical.
 */
public final class AsciiPackCodec {

    /**
     * Base-84 alphabet, indexed by digit value (0..83). Ordering preserves
     * the Z85 scheme minus {@code '.'} so the mapping is easy to audit against
     * the reference spec.
     */
    private static final char[] ALPHABET = {
            '0','1','2','3','4','5','6','7','8','9',
            'a','b','c','d','e','f','g','h','i','j',
            'k','l','m','n','o','p','q','r','s','t',
            'u','v','w','x','y','z',
            'A','B','C','D','E','F','G','H','I','J',
            'K','L','M','N','O','P','Q','R','S','T',
            'U','V','W','X','Y','Z',
            '-',':','+','=','^','!','/','*','?',
            '&','<','>','(',')','[',']','{','}','@',
            '%','$','#'
    };

    /** Reverse lookup from ASCII code point to digit value; -1 for non-alphabet chars. */
    private static final int[] DECODE_TABLE = new int[128];

    static {
        java.util.Arrays.fill(DECODE_TABLE, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            DECODE_TABLE[ALPHABET[i]] = i;
        }
    }

    /** 84^4. Computed once so the hot path doesn't re-multiply. */
    private static final long P4 = 84L * 84L * 84L * 84L;
    /** 84^3. */
    private static final long P3 = 84L * 84L * 84L;
    /** 84^2. */
    private static final long P2 = 84L * 84L;
    /** Radix. */
    private static final long BASE = 84L;

    /** Bytes per pack group. */
    private static final int GROUP_BYTES = 4;
    /** Chars per pack group (not counting the trailer). */
    private static final int GROUP_CHARS = 5;
    /** Max number of pad bytes appended to a tail group. */
    private static final int MAX_PAD = GROUP_BYTES - 1;

    private AsciiPackCodec() {}

    /** Number of output characters produced for {@code inputByteCount} input bytes. */
    public static int encodedLength(int inputByteCount) {
        if (inputByteCount == 0) return 1;
        int groups = (inputByteCount + GROUP_BYTES - 1) / GROUP_BYTES;
        return groups * GROUP_CHARS + 1;
    }

    public static String encode(byte[] input) {
        if (input == null) throw new IllegalArgumentException("null input");
        if (input.length == 0) {
            // One trailer character encoding pad=0. Keeps the "always has a
            // trailer" invariant so decode can be branch-free on that side.
            return String.valueOf(ALPHABET[0]);
        }

        int pad = (GROUP_BYTES - (input.length % GROUP_BYTES)) % GROUP_BYTES;
        int totalLen = input.length + pad;

        StringBuilder out = new StringBuilder(totalLen / GROUP_BYTES * GROUP_CHARS + 1);
        for (int off = 0; off < totalLen; off += GROUP_BYTES) {
            // Pack 4 bytes into a 32-bit unsigned integer, MSB-first. Bytes
            // past the real input length are implicitly zero (pad).
            long v = 0L;
            for (int i = 0; i < GROUP_BYTES; i++) {
                int idx = off + i;
                int b = idx < input.length ? (input[idx] & 0xFF) : 0;
                v = (v << 8) | b;
            }
            // Emit 5 base-84 digits MSB-first. Hand-unrolled: this runs for
            // every shared codec string, and the compiler can't eliminate
            // the per-iteration divmod overhead of a loop here.
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
        if (input.isEmpty()) throw new IllegalArgumentException("empty base-84 string");

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
            // 84^5 = 4_182_119_424, which exceeds 2^32. Any group whose
            // digits produce v >= 2^32 would silently overflow the output
            // integer; reject loudly instead of truncating.
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

    /** True iff every character in {@code s} is part of the base-84 alphabet. */
    public static boolean isValidBody(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!isAlphabetChar(s.charAt(i))) return false;
        }
        return true;
    }

    /** True iff {@code c} is one of the 84 valid alphabet characters. */
    public static boolean isAlphabetChar(char c) {
        return c < DECODE_TABLE.length && DECODE_TABLE[c] >= 0;
    }

    private static int digitOf(char c) {
        return c < DECODE_TABLE.length ? DECODE_TABLE[c] : -1;
    }
}
