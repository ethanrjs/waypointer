package com.babbur.waypointer.config;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Canonical wire-v10 kind-3 semantic body, independent of text/compression transport. */
public final class V10ConfigBodyCodec {

    public static final int CONTENT_KIND = 3;
    public static final int SEMANTIC_HEADER = (CONTENT_KIND << 4) | 10;
    public static final int MAX_BODY_BYTES = 32 * 1024;
    public static final int MAX_FIELDS = 256;
    public static final int MAX_FIELD_BYTES = 4 * 1024;
    public static final int MAX_TAG = 65_535;
    public static final int MAX_STRING_LIST_ENTRIES = 256;
    public static final int MAX_STRING_BYTES = 64;

    private static final int LENGTH_ONE = 0;
    private static final int LENGTH_THREE = 1;
    private static final int LENGTH_EIGHT = 2;
    private static final int LENGTH_EXPLICIT = 3;

    private V10ConfigBodyCodec() {}

    public static byte[] encode(WaypointerConfig config) throws IOException {
        List<WireField> fields = encodeWireFields(config);
        if (fields.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("v10 config exceeds field-count limit");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(SEMANTIC_HEADER);
        int previousTag = 0;
        for (WireField field : fields) {
            int tag = field.tag();
            byte[] value = field.value();
            if (tag <= previousTag || tag > MAX_TAG) {
                throw new IllegalArgumentException("v10 config fields are not strictly ascending");
            }
            if (value.length > MAX_FIELD_BYTES) {
                throw new IllegalArgumentException("v10 config field " + tag + " exceeds length limit");
            }
            int lengthClass = lengthClass(value.length);
            long token = ((long) (tag - previousTag) << 2) | lengthClass;
            writeUVarint(output, token);
            if (lengthClass == LENGTH_EXPLICIT) writeUVarint(output, value.length);
            output.writeBytes(value);
            previousTag = tag;
        }
        byte[] semantic = output.toByteArray();
        if (semantic.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("v10 config semantic body exceeds limit");
        }
        return semantic;
    }

    public static WaypointerConfig decode(byte[] semantic) throws IOException {
        if (semantic == null || semantic.length < 1 || semantic.length > MAX_BODY_BYTES) {
            throw new IOException("v10 config semantic body length is outside limit");
        }
        ByteReader input = new ByteReader(semantic);
        int header = input.readUnsignedByte();
        if (header != SEMANTIC_HEADER) {
            int kind = (header >>> 4) & 0b111;
            throw new IOException("expected v10 config kind 3, got kind " + kind);
        }
        List<WireField> knownFields = new ArrayList<>();
        int fieldCount = 0;
        int tag = 0;
        while (input.hasRemaining()) {
            long token = input.readUVarint(((long) MAX_TAG << 2) | 3, 3);
            if (token == 0) throw new IOException("zero v10 config field token");
            if (++fieldCount > MAX_FIELDS) throw new IOException("v10 config exceeds field-count limit");
            long delta = token >>> 2;
            if (delta == 0 || delta > MAX_TAG - tag) {
                throw new IOException("v10 config tags are not strictly ascending");
            }
            tag += (int) delta;
            int encodedLengthClass = (int) token & 3;
            int length = switch (encodedLengthClass) {
                case LENGTH_ONE -> 1;
                case LENGTH_THREE -> 3;
                case LENGTH_EIGHT -> 8;
                case LENGTH_EXPLICIT -> (int) input.readUVarint(MAX_FIELD_BYTES, 2);
                default -> throw new AssertionError();
            };
            if (encodedLengthClass == LENGTH_EXPLICIT && lengthClass(length) != LENGTH_EXPLICIT) {
                throw new IOException("non-canonical v10 config scalar length class");
            }
            byte[] value = input.readBytes(length);
            if (WaypointerConfigCodec.isActiveFieldTag(tag)) {
                validateWireField(tag, value);
                knownFields.add(new WireField(tag, value));
            }
        }

        WaypointerConfig decoded = WaypointerConfigCodec.decodeTaggedFields(toLegacyFields(knownFields));
        List<WireField> normalized = encodeWireFields(decoded);
        if (!fieldListsEqual(knownFields, normalized)) {
            throw new IOException("v10 config contains an explicit default or normalized field value");
        }
        return decoded;
    }

    private static int lengthClass(int length) {
        return switch (length) {
            case 1 -> LENGTH_ONE;
            case 3 -> LENGTH_THREE;
            case 8 -> LENGTH_EIGHT;
            default -> LENGTH_EXPLICIT;
        };
    }

    private static boolean fieldListsEqual(List<WireField> left, List<WireField> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            WireField a = left.get(index);
            WireField b = right.get(index);
            if (a.tag() != b.tag() || !Arrays.equals(a.value(), b.value())) return false;
        }
        return true;
    }

    private static List<WireField> encodeWireFields(WaypointerConfig config) throws IOException {
        List<WireField> wire = new ArrayList<>();
        for (WaypointerConfigCodec.TaggedField field
                : WaypointerConfigCodec.encodeTaggedFields(config)) {
            int tag = field.tag();
            byte[] legacy = field.value();
            byte[] value;
            if (WaypointerConfigCodec.isRgbFieldTag(tag)) {
                value = Arrays.copyOfRange(legacy, 1, Integer.BYTES);
            } else if (WaypointerConfigCodec.isUnsignedIntegerFieldTag(tag)) {
                int integer = ByteBuffer.wrap(legacy).getInt();
                if (integer < 0) throw new IOException("negative bounded config integer " + tag);
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                writeUVarint(encoded, integer);
                value = encoded.toByteArray();
            } else if (WaypointerConfigCodec.isStringListFieldTag(tag)) {
                value = encodeStringList(config.chatCoordSenderBlacklist());
            } else {
                value = legacy;
            }
            wire.add(new WireField(tag, value));
        }
        return List.copyOf(wire);
    }

    private static List<WaypointerConfigCodec.TaggedField> toLegacyFields(List<WireField> wire)
            throws IOException {
        List<WaypointerConfigCodec.TaggedField> legacy = new ArrayList<>(wire.size());
        for (WireField field : wire) {
            int tag = field.tag();
            byte[] value = field.value();
            byte[] converted;
            if (WaypointerConfigCodec.isRgbFieldTag(tag)) {
                converted = new byte[] {0, value[0], value[1], value[2]};
            } else if (WaypointerConfigCodec.isUnsignedIntegerFieldTag(tag)) {
                long integer = readOneUVarint(value, Integer.MAX_VALUE);
                converted = ByteBuffer.allocate(Integer.BYTES).putInt((int) integer).array();
            } else if (WaypointerConfigCodec.isStringListFieldTag(tag)) {
                converted = encodeLegacyStringList(decodeStringList(value));
            } else {
                converted = value;
            }
            WaypointerConfigCodec.validateTaggedFieldPayload(tag, converted);
            legacy.add(new WaypointerConfigCodec.TaggedField(tag, converted));
        }
        return List.copyOf(legacy);
    }

    private static void validateWireField(int tag, byte[] value) throws IOException {
        if (WaypointerConfigCodec.isRgbFieldTag(tag)) {
            if (value.length != 3) throw new IOException("v10 RGB config field has wrong length");
            return;
        }
        if (WaypointerConfigCodec.isUnsignedIntegerFieldTag(tag)) {
            readOneUVarint(value, Integer.MAX_VALUE);
            return;
        }
        if (WaypointerConfigCodec.isStringListFieldTag(tag)) {
            decodeStringList(value);
            return;
        }
        WaypointerConfigCodec.validateTaggedFieldPayload(tag, value);
    }

    private static long readOneUVarint(byte[] bytes, long maximum) throws IOException {
        ByteReader reader = new ByteReader(bytes);
        long value = reader.readUVarint(maximum, 5);
        reader.requireEnd();
        return value;
    }

    private static byte[] encodeStringList(List<String> values) throws IOException {
        if (values.size() > MAX_STRING_LIST_ENTRIES) {
            throw new IllegalArgumentException("v10 config string-list count exceeds limit");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeUVarint(output, values.size());
        for (String value : values) {
            byte[] utf8 = strictUtf8(value == null ? "" : value);
            if (utf8.length > MAX_STRING_BYTES) {
                throw new IllegalArgumentException("v10 config string exceeds UTF-8 limit");
            }
            writeUVarint(output, utf8.length);
            output.writeBytes(utf8);
        }
        return output.toByteArray();
    }

    private static List<String> decodeStringList(byte[] bytes) throws IOException {
        ByteReader reader = new ByteReader(bytes);
        int count = (int) reader.readUVarint(MAX_STRING_LIST_ENTRIES, 2);
        List<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int length = (int) reader.readUVarint(MAX_STRING_BYTES, 1);
            values.add(decodeStrictUtf8(reader.readBytes(length)));
        }
        reader.requireEnd();
        return List.copyOf(values);
    }

    private static byte[] encodeLegacyStringList(List<String> values) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(output);
        data.writeShort(values.size());
        for (String value : values) data.writeUTF(value);
        data.flush();
        return output.toByteArray();
    }

    private static byte[] strictUtf8(String value) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static String decodeStrictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void writeUVarint(ByteArrayOutputStream output, long value) {
        if (value < 0) throw new IllegalArgumentException("negative v10 config uvarint");
        do {
            int next = (int) (value & 0x7F);
            value >>>= 7;
            output.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    private static final class ByteReader {
        private final byte[] data;
        private int position;

        ByteReader(byte[] data) {
            this.data = data;
        }

        int readUnsignedByte() throws IOException {
            if (position >= data.length) throw new IOException("truncated v10 config semantic body");
            return data[position++] & 0xFF;
        }

        long readUVarint(long maximum, int maximumBytes) throws IOException {
            long result = 0;
            for (int byteIndex = 0; byteIndex < maximumBytes; byteIndex++) {
                int next = readUnsignedByte();
                result |= (long) (next & 0x7F) << (7 * byteIndex);
                if ((next & 0x80) == 0) {
                    if (byteIndex > 0 && (next & 0x7F) == 0) {
                        throw new IOException("non-canonical v10 config uvarint");
                    }
                    if (result > maximum) throw new IOException("v10 config uvarint exceeds limit");
                    return result;
                }
            }
            throw new IOException("v10 config uvarint is too long");
        }

        byte[] readBytes(int length) throws IOException {
            if (length < 0 || position > data.length - length) {
                throw new IOException("truncated v10 config field");
            }
            byte[] bytes = Arrays.copyOfRange(data, position, position + length);
            position += length;
            return bytes;
        }

        void requireEnd() throws IOException {
            if (position != data.length) throw new IOException("trailing v10 config semantic bytes");
        }

        boolean hasRemaining() {
            return position < data.length;
        }
    }

    private record WireField(int tag, byte[] value) {
        WireField {
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }
    }
}
