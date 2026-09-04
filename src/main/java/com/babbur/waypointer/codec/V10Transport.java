package com.babbur.waypointer.codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * Shared wire-v10 framing and text transport.
 *
 * <p>The bytes protected by the CRC are {@code selector || semanticBody}. The
 * selector is carried as the literal outer {@code A}/{@code B} character and
 * is deliberately omitted from the base-91 payload:
 *
 * <pre>{@code
 * WP: A contextual-base91(semanticBody || crc32(0 || semanticBody))
 * WP: B contextual-base91(raw-deflate(semanticBody) || crc32(1 || semanticBody))
 * }</pre>
 *
 * <p>This class does not interpret the semantic header. Every v10 content kind
 * shares this envelope and performs its own bounded body parse after unsealing.
 */
final class V10Transport {

    static final int MODE_DIRECT = 0;
    static final int MODE_DEFLATE = 1;
    static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;
    static final int MAX_TRANSPORT_CHARS = 3 * 1024 * 1024;

    private static final char[] MODE_CHARACTERS = {'A', 'B'};
    private static final int CHECKSUM_BYTES = Integer.BYTES;

    private V10Transport() {}

    static boolean hasModeSelector(String payload) {
        return payload != null && !payload.isEmpty()
                && (payload.charAt(0) == MODE_CHARACTERS[MODE_DIRECT]
                || payload.charAt(0) == MODE_CHARACTERS[MODE_DEFLATE]);
    }

    static String encode(int mode, byte[] modePayload) {
        validateMode(mode);
        if (modePayload == null) throw new IllegalArgumentException("null v10 mode payload");
        if (modePayload.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("v10 mode payload exceeds frame limit");
        }
        String encoded = AsciiStreamCodec.encode(modePayload);
        String transport = MODE_CHARACTERS[mode] + escapeContextual(encoded);
        if (transport.length() > MAX_TRANSPORT_CHARS) {
            throw new IllegalArgumentException("v10 transport exceeds text limit");
        }
        return transport;
    }

    static Frame decode(String transport) throws IOException {
        if (transport == null || transport.isEmpty()
                || transport.length() > MAX_TRANSPORT_CHARS) {
            throw new IOException("v10 transport length is outside limit");
        }
        int mode = switch (transport.charAt(0)) {
            case 'A' -> MODE_DIRECT;
            case 'B' -> MODE_DEFLATE;
            default -> throw new IOException("unknown v10 compression selector");
        };

        byte[] payload;
        try {
            payload = AsciiStreamCodec.decode(unescapeContextual(transport.substring(1)));
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid v10 base-91 payload: " + failure.getMessage(), failure);
        }
        if (payload.length > MAX_FRAME_BYTES) {
            throw new IOException("decoded v10 payload exceeds frame limit");
        }
        if (!encode(mode, payload).equals(transport)) {
            throw new IOException("non-canonical v10 text transport");
        }
        return new Frame(mode, payload);
    }

    /**
     * Complete the ambiguity probe shared by every V10 kind. A returned value
     * is a commitment to V10: transport, optional inflate, mode-bound CRC, and
     * the version nibble have all validated. Kind-body failures after this
     * point must not fall back to a legacy decoder.
     */
    static CheckedFrame probe(String transport) throws IOException {
        Frame frame = decode(transport);
        byte[] modePayload = frame.payload();
        byte[] semantic = frame.mode() == MODE_DIRECT
                ? unseal(frame.mode(), modePayload)
                : inflateAndVerify(modePayload);
        if (semantic.length == 0 || (semantic[0] & 0x0F) != WaypointCodec.V10_WIRE_VERSION) {
            throw new IOException("v10 semantic version probe did not commit");
        }
        return new CheckedFrame(frame.mode(), semantic);
    }

    static byte[] seal(int mode, byte[] semanticBody) {
        validateMode(mode);
        if (semanticBody == null) throw new IllegalArgumentException("null v10 semantic body");
        if ((long) semanticBody.length + CHECKSUM_BYTES > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("v10 semantic body exceeds frame limit");
        }

        CRC32 checksum = new CRC32();
        checksum.update(mode);
        checksum.update(semanticBody);
        long value = checksum.getValue();
        byte[] sealed = Arrays.copyOf(semanticBody, semanticBody.length + CHECKSUM_BYTES);
        int offset = semanticBody.length;
        sealed[offset] = (byte) (value >>> 24);
        sealed[offset + 1] = (byte) (value >>> 16);
        sealed[offset + 2] = (byte) (value >>> 8);
        sealed[offset + 3] = (byte) value;
        return sealed;
    }

    static byte[] unseal(int mode, byte[] sealed) throws IOException {
        validateMode(mode);
        if (sealed == null || sealed.length < CHECKSUM_BYTES + 1
                || sealed.length > MAX_FRAME_BYTES) {
            throw new IOException("v10 inner frame length is outside limit");
        }
        int bodyLength = sealed.length - CHECKSUM_BYTES;
        long expected = ((long) (sealed[bodyLength] & 0xFF) << 24)
                | ((long) (sealed[bodyLength + 1] & 0xFF) << 16)
                | ((long) (sealed[bodyLength + 2] & 0xFF) << 8)
                | (long) (sealed[bodyLength + 3] & 0xFF);
        CRC32 checksum = new CRC32();
        checksum.update(mode);
        checksum.update(sealed, 0, bodyLength);
        if (checksum.getValue() != expected) {
            throw new IOException("v10 CRC-32 mismatch");
        }
        return Arrays.copyOf(sealed, bodyLength);
    }

    /** Build a B payload with the selector-bound checksum outside raw DEFLATE. */
    static byte[] deflateAndSeal(byte[] semanticBody, int strategy) throws IOException {
        byte[] sealed = seal(MODE_DEFLATE, semanticBody);
        byte[] compressed = deflate(semanticBody, strategy);
        if ((long) compressed.length + CHECKSUM_BYTES > MAX_FRAME_BYTES) {
            throw new V10ProfileLimitException(
                    "v10 compressed payload exceeds frame limit");
        }
        byte[] payload = Arrays.copyOf(compressed, compressed.length + CHECKSUM_BYTES);
        System.arraycopy(sealed, semanticBody.length, payload, compressed.length,
                CHECKSUM_BYTES);
        return payload;
    }

    /** Decode exactly one raw-DEFLATE stream and verify its external CRC. */
    static byte[] inflateAndVerify(byte[] payload) throws IOException {
        if (payload == null || payload.length < CHECKSUM_BYTES + 1
                || payload.length > MAX_FRAME_BYTES) {
            throw new IOException("v10 deflate frame length is outside limit");
        }
        int compressedLength = payload.length - CHECKSUM_BYTES;
        byte[] semantic = inflate(Arrays.copyOf(payload, compressedLength));
        if ((long) semantic.length + CHECKSUM_BYTES > MAX_FRAME_BYTES) {
            throw new IOException("v10 semantic body exceeds frame limit");
        }
        byte[] sealed = Arrays.copyOf(semantic, semantic.length + CHECKSUM_BYTES);
        System.arraycopy(payload, compressedLength, sealed, semantic.length, CHECKSUM_BYTES);
        return unseal(MODE_DEFLATE, sealed);
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

    /** Inflate a bounded semantic prefix without a dictionary or EOF requirement. */
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
     * the envelope-wide selector contract: shortest final contextual text,
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
