package com.babbur.waypointer.codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * Shared wire-v10 framing and text transport.
 *
 * <p>Every V10 share is one contextual base-91 string of a single payload:
 *
 * <pre>{@code
 * payload = H' || content || CRC16_BE(H' || body)
 *
 * H'      = semantic header byte with bit 7 set to the transport mode
 *           (0 direct, 1 raw DEFLATE); bits 0..3 = 10, bits 4..6 = kind
 * body    = semantic bytes after the header
 * content = body            (mode 0)
 *         | rawDeflate(body) (mode 1)
 * }</pre>
 *
 * <p>The mode therefore costs no characters, the checksum is bound to the
 * mode and the header, and a kind codec never sees the mode bit: the
 * {@link CheckedFrame#semantic()} handed to it always starts with the plain
 * semantic header. This class does not interpret the body. Every kind shares
 * this envelope and performs its own bounded parse after unsealing.
 */
final class V10Transport {

    static final int MODE_DIRECT = 0;
    static final int MODE_DEFLATE = 1;
    /** Complete payload bound, including the header byte and the checksum. */
    static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;
    static final int MAX_TRANSPORT_CHARS = 3 * 1024 * 1024;
    static final int CHECKSUM_BYTES = 2;
    /** Largest semantic body (header included) that still fits a frame. */
    static final int MAX_SEMANTIC_BYTES = MAX_FRAME_BYTES - CHECKSUM_BYTES;

    static final int HEADER_MODE_BIT = 0x80;
    private static final int HEADER_VERSION_MASK = 0x0F;
    /** CRC-16/CCITT-FALSE: polynomial 0x1021, initial 0xFFFF, no reflection, no final XOR. */
    private static final int CRC16_POLYNOMIAL = 0x1021;
    private static final int CRC16_INITIAL = 0xFFFF;

    private V10Transport() {}

    /**
     * Cheap exact peek at the first payload byte: true when its version nibble
     * is 10. About one legacy V1-V9 code in sixteen also passes; the full probe
     * then rejects it through the checksum, so callers must treat this as a
     * filter, never as a commitment.
     */
    static boolean looksLikeV10(String transport) {
        if (transport == null || transport.length() < 2) return false;
        String raw = unescapeContextual(transport.substring(0, Math.min(transport.length(), 4)));
        if (raw.length() < 2) return false;
        int first = AsciiStreamCodec.alphabetIndex(raw.charAt(0));
        int second = AsciiStreamCodec.alphabetIndex(raw.charAt(1));
        if (first < 0 || second < 0) return false;
        int pair = first + second * AsciiStreamCodec.alphabetSize();
        return (pair & HEADER_VERSION_MASK) == WaypointCodec.V10_WIRE_VERSION;
    }

    /** Text-encode a complete payload whose first byte already carries the mode bit. */
    static String encode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("empty v10 payload");
        }
        if (payload.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("v10 payload exceeds frame limit");
        }
        String transport = escapeContextual(AsciiStreamCodec.encode(payload));
        if (transport.length() > MAX_TRANSPORT_CHARS) {
            throw new IllegalArgumentException("v10 transport exceeds text limit");
        }
        return transport;
    }

    /** {@link #encode(byte[])} with a guard that the payload header agrees with {@code mode}. */
    static String encode(int mode, byte[] payload) {
        validateMode(mode);
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("empty v10 payload");
        }
        if (modeOf(payload[0]) != mode) {
            throw new IllegalArgumentException("v10 payload header does not carry mode " + mode);
        }
        return encode(payload);
    }

    /** Physical decode: canonical text, bounded length, mode from the header bit. No version check. */
    static Frame decode(String transport) throws IOException {
        if (transport == null || transport.isEmpty()
                || transport.length() > MAX_TRANSPORT_CHARS) {
            throw new IOException("v10 transport length is outside limit");
        }
        byte[] payload;
        try {
            payload = AsciiStreamCodec.decode(unescapeContextual(transport));
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid v10 base-91 payload: " + failure.getMessage(), failure);
        }
        if (payload.length == 0 || payload.length > MAX_FRAME_BYTES) {
            throw new IOException("decoded v10 payload length is outside limit");
        }
        if (!encode(payload).equals(transport)) {
            throw new IOException("non-canonical v10 text transport");
        }
        return new Frame(modeOf(payload[0]), payload);
    }

    /**
     * Complete the V10 probe shared by every kind: canonical text, version
     * nibble, optional inflate, and the header-bound checksum. A returned frame
     * is a validated V10 envelope; the kind body has not been parsed yet.
     */
    static CheckedFrame probe(String transport) throws IOException {
        Frame frame = decode(transport);
        byte[] payload = frame.payload();
        if ((payload[0] & HEADER_VERSION_MASK) != WaypointCodec.V10_WIRE_VERSION) {
            throw new IOException("v10 version nibble did not match");
        }
        byte[] semantic = frame.mode() == MODE_DIRECT
                ? unseal(MODE_DIRECT, payload)
                : inflateAndVerify(payload);
        return new CheckedFrame(frame.mode(), semantic);
    }

    /** Build a direct payload: header with mode bit clear, body, checksum. */
    static byte[] seal(int mode, byte[] semanticBody) {
        validateMode(mode);
        if (mode != MODE_DIRECT) {
            throw new IllegalArgumentException("seal builds direct payloads; use deflateAndSeal");
        }
        validateSemantic(semanticBody);
        byte[] sealed = Arrays.copyOf(semanticBody, semanticBody.length + CHECKSUM_BYTES);
        sealed[0] = (byte) transmittedHeader(semanticBody[0], MODE_DIRECT);
        writeChecksum(sealed, semanticBody.length, checksum(sealed, 0, semanticBody.length));
        return sealed;
    }

    /** Verify a direct payload's mode bit and checksum; return the plain semantic body. */
    static byte[] unseal(int mode, byte[] sealed) throws IOException {
        validateMode(mode);
        if (sealed == null || sealed.length < CHECKSUM_BYTES + 1
                || sealed.length > MAX_FRAME_BYTES) {
            throw new IOException("v10 inner frame length is outside limit");
        }
        if (modeOf(sealed[0]) != mode) {
            throw new IOException("v10 header mode bit does not match transport mode");
        }
        int bodyLength = sealed.length - CHECKSUM_BYTES;
        if (checksum(sealed, 0, bodyLength) != readChecksum(sealed, bodyLength)) {
            throw new IOException("v10 CRC-16 mismatch");
        }
        byte[] semantic = Arrays.copyOf(sealed, bodyLength);
        semantic[0] = (byte) (semantic[0] & ~HEADER_MODE_BIT);
        return semantic;
    }

    /** Build a DEFLATE payload: header with the mode bit set, raw DEFLATE of the body, checksum. */
    static byte[] deflateAndSeal(byte[] semanticBody, int strategy) throws IOException {
        validateSemantic(semanticBody);
        byte[] compressed = deflate(
                Arrays.copyOfRange(semanticBody, 1, semanticBody.length), strategy);
        return sealCompressed(semanticBody, compressed);
    }

    /**
     * Assemble a DEFLATE payload from an already compressed body. Any standards-valid
     * raw DEFLATE stream of the body is acceptable to the decoder; this lets tests and
     * alternate encoders frame their own stream with the correct header and checksum.
     */
    static byte[] sealCompressed(byte[] semanticBody, byte[] compressed) throws IOException {
        validateSemantic(semanticBody);
        if (compressed == null || compressed.length == 0) {
            throw new IllegalArgumentException("empty v10 compressed body");
        }
        if ((long) compressed.length + 1 + CHECKSUM_BYTES > MAX_FRAME_BYTES) {
            throw new V10ProfileLimitException("v10 compressed payload exceeds frame limit");
        }
        byte[] payload = new byte[1 + compressed.length + CHECKSUM_BYTES];
        payload[0] = (byte) transmittedHeader(semanticBody[0], MODE_DEFLATE);
        System.arraycopy(compressed, 0, payload, 1, compressed.length);
        int crc = checksum(payload[0], semanticBody, 1, semanticBody.length - 1);
        writeChecksum(payload, 1 + compressed.length, crc);
        return payload;
    }

    /** Decode exactly one raw-DEFLATE stream and verify the header-bound checksum. */
    static byte[] inflateAndVerify(byte[] payload) throws IOException {
        if (payload == null || payload.length < CHECKSUM_BYTES + 2
                || payload.length > MAX_FRAME_BYTES) {
            throw new IOException("v10 deflate frame length is outside limit");
        }
        if (modeOf(payload[0]) != MODE_DEFLATE) {
            throw new IOException("v10 header mode bit does not match transport mode");
        }
        int compressedLength = payload.length - 1 - CHECKSUM_BYTES;
        byte[] body = inflate(Arrays.copyOfRange(payload, 1, 1 + compressedLength));
        if ((long) body.length + 1 + CHECKSUM_BYTES > MAX_FRAME_BYTES) {
            throw new IOException("v10 semantic body exceeds frame limit");
        }
        int expected = readChecksum(payload, 1 + compressedLength);
        if (checksum(payload[0], body, 0, body.length) != expected) {
            throw new IOException("v10 CRC-16 mismatch");
        }
        byte[] semantic = new byte[body.length + 1];
        semantic[0] = (byte) (payload[0] & ~HEADER_MODE_BIT);
        System.arraycopy(body, 0, semantic, 1, body.length);
        return semantic;
    }

    static byte[] deflate(byte[] input, int strategy) throws IOException {
        if (input == null || input.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("v10 deflate input length is outside limit");
        }
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setStrategy(strategy);
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(output, deflater)) {
            stream.write(input);
        } finally {
            deflater.end();
        }
        byte[] compressed = output.toByteArray();
        if (compressed.length > MAX_FRAME_BYTES) {
            throw new V10ProfileLimitException(
                    "v10 compressed payload exceeds frame limit");
        }
        return compressed;
    }

    static byte[] inflate(byte[] compressed) throws IOException {
        return inflate(compressed, MAX_FRAME_BYTES, true);
    }

    /** Inflate a bounded prefix without a dictionary or EOF requirement. */
    static byte[] inflatePrefix(byte[] compressed, int maximumOutputBytes) throws IOException {
        if (maximumOutputBytes <= 0 || maximumOutputBytes > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("v10 prefix output limit is outside frame bounds");
        }
        return inflate(compressed, maximumOutputBytes, false);
    }

    private static byte[] inflate(
            byte[] compressed, int maximumOutputBytes, boolean requireFinished)
            throws IOException {
        if (compressed == null || compressed.length == 0
                || compressed.length > MAX_FRAME_BYTES) {
            throw new IOException("v10 compressed payload length is outside limit");
        }
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            int initialSize = Math.min(maximumOutputBytes,
                    Math.max(32, compressed.length * 2));
            ByteArrayOutputStream output = new ByteArrayOutputStream(initialSize);
            byte[] buffer = new byte[512];
            while (!inflater.finished()) {
                int remaining = maximumOutputBytes + 1 - output.size();
                if (remaining <= 0) {
                    if (!requireFinished) return output.toByteArray();
                    throw new IOException("v10 inflated payload exceeds frame limit");
                }
                int count = inflater.inflate(buffer, 0, Math.min(buffer.length, remaining));
                if (count > 0) {
                    output.write(buffer, 0, count);
                    if (output.size() > maximumOutputBytes) {
                        if (!requireFinished) {
                            return Arrays.copyOf(output.toByteArray(), maximumOutputBytes);
                        }
                        throw new IOException("v10 inflated payload exceeds frame limit");
                    }
                    continue;
                }
                // An empty body deflates to a stream that finishes on a call
                // returning zero bytes; that is completion, not starvation.
                if (inflater.finished()) break;
                if (inflater.needsInput()) {
                    if (!requireFinished) return output.toByteArray();
                    throw new IOException("truncated v10 deflate stream");
                }
                if (inflater.needsDictionary()) {
                    throw new IOException("unexpected v10 deflate dictionary request");
                }
                throw new IOException("v10 deflate stream made no progress");
            }
            if (requireFinished && inflater.getRemaining() != 0) {
                throw new IOException("trailing v10 compressed bytes: " + inflater.getRemaining());
            }
            return output.toByteArray();
        } catch (DataFormatException failure) {
            throw new IOException("malformed v10 deflate stream: " + failure.getMessage(), failure);
        } finally {
            inflater.end();
        }
    }

    static Outbound direct(byte[] semantic) {
        return new Outbound(MODE_DIRECT, seal(MODE_DIRECT, semantic));
    }

    static Outbound deflated(byte[] semantic, int strategy) throws IOException {
        return new Outbound(MODE_DEFLATE, deflateAndSeal(semantic, strategy));
    }

    /** CRC-16/CCITT-FALSE over {@code header} followed by {@code length} bytes of {@code body} from {@code offset}. */
    static int checksum(byte header, byte[] body, int offset, int length) {
        int crc = crc16Update(CRC16_INITIAL, header);
        for (int index = offset; index < offset + length; index++) {
            crc = crc16Update(crc, body[index]);
        }
        return crc;
    }

    private static int checksum(byte[] bytes, int offset, int length) {
        int crc = CRC16_INITIAL;
        for (int index = offset; index < offset + length; index++) {
            crc = crc16Update(crc, bytes[index]);
        }
        return crc;
    }

    private static int crc16Update(int crc, byte value) {
        crc ^= (value & 0xFF) << 8;
        for (int bit = 0; bit < 8; bit++) {
            crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ CRC16_POLYNOMIAL) : (crc << 1);
        }
        return crc & 0xFFFF;
    }

    private static void writeChecksum(byte[] target, int offset, int crc) {
        target[offset] = (byte) (crc >>> 8);
        target[offset + 1] = (byte) crc;
    }

    private static int readChecksum(byte[] source, int offset) {
        return ((source[offset] & 0xFF) << 8) | (source[offset + 1] & 0xFF);
    }

    static int modeOf(byte transmittedHeader) {
        return (transmittedHeader & HEADER_MODE_BIT) != 0 ? MODE_DEFLATE : MODE_DIRECT;
    }

    private static int transmittedHeader(byte semanticHeader, int mode) {
        return (semanticHeader & ~HEADER_MODE_BIT) | (mode == MODE_DEFLATE ? HEADER_MODE_BIT : 0);
    }

    private static void validateSemantic(byte[] semantic) {
        if (semantic == null || semantic.length == 0) {
            throw new IllegalArgumentException("empty v10 semantic body");
        }
        if ((semantic[0] & HEADER_MODE_BIT) != 0) {
            throw new IllegalArgumentException("v10 semantic header bit 7 is reserved for the transport mode");
        }
        if ((long) semantic.length + CHECKSUM_BYTES > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("v10 semantic body exceeds frame limit");
        }
    }

    /** Insert an escape only where Hypixel would consume an emote-like pair. */
    static String escapeContextual(String body) {
        StringBuilder output = new StringBuilder(body.length());
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            output.append(current);
            char following = index + 1 < body.length() ? body.charAt(index + 1) : '\0';
            if ((current == '<' && (following == '3' || following == '~'))
                    || (current == 'o' && (following == '/' || following == '~'))) {
                output.append('~');
            }
        }
        return output.toString();
    }

    /** Remove only contextual markers; every other tilde remains a base-91 digit. */
    static String unescapeContextual(String body) {
        StringBuilder output = new StringBuilder(body.length());
        int index = 0;
        while (index < body.length()) {
            char current = body.charAt(index);
            output.append(current);
            char following = index + 1 < body.length() ? body.charAt(index + 1) : '\0';
            char after = index + 2 < body.length() ? body.charAt(index + 2) : '\0';
            if (following == '~'
                    && ((current == '<' && (after == '3' || after == '~'))
                    || (current == 'o' && (after == '/' || after == '~')))) {
                index++;
            }
            index++;
        }
        return output.toString();
    }

    private static void validateMode(int mode) {
        if (mode != MODE_DIRECT && mode != MODE_DEFLATE) {
            throw new IllegalArgumentException("unknown v10 compression mode: " + mode);
        }
    }

    /**
     * One deterministic outbound representation. Comparing these values applies
     * the envelope-wide selection contract: shortest final contextual text,
     * direct before DEFLATE, shorter binary payload, then unsigned lexical bytes.
     */
    static final class Outbound implements Comparable<Outbound> {
        private final int mode;
        private final byte[] payload;
        private final String transport;

        Outbound(int mode, byte[] payload) {
            validateMode(mode);
            if (payload == null) throw new IllegalArgumentException("null v10 outbound payload");
            this.mode = mode;
            this.payload = payload.clone();
            this.transport = encode(mode, this.payload);
        }

        int mode() {
            return mode;
        }

        byte[] payload() {
            return payload.clone();
        }

        String transport() {
            return transport;
        }

        @Override
        public int compareTo(Outbound other) {
            int compared = Integer.compare(transport.length(), other.transport.length());
            if (compared != 0) return compared;
            compared = Integer.compare(mode, other.mode);
            if (compared != 0) return compared;
            compared = Integer.compare(payload.length, other.payload.length);
            if (compared != 0) return compared;
            for (int index = 0; index < payload.length; index++) {
                compared = Integer.compare(payload[index] & 0xFF, other.payload[index] & 0xFF);
                if (compared != 0) return compared;
            }
            return 0;
        }
    }

    /** Physical frame: the complete decoded payload (header byte with mode bit, content, checksum). */
    record Frame(int mode, byte[] payload) {
        Frame {
            validateMode(mode);
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    /** Verified envelope: the plain semantic body (header without the mode bit) and its transport mode. */
    record CheckedFrame(int mode, byte[] semantic) {
        CheckedFrame {
            validateMode(mode);
            semantic = semantic.clone();
        }

        int header() {
            return semantic[0] & 0xFF;
        }

        int contentKind() {
            return (header() >>> 4) & 0b111;
        }

        @Override
        public byte[] semantic() {
            return semantic.clone();
        }
    }
}
