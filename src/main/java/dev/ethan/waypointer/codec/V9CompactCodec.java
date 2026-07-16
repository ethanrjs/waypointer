package dev.ethan.waypointer.codec;

import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class V9CompactCodec {

    private static final int NAME_MODE_EMPTY = 0;
    private static final int NAME_MODE_CONSTANT = 1;
    private static final int NAME_MODE_NUMERIC_ARITHMETIC = 2;
    private static final int NAME_MODE_NUMERIC_DELTA = 3;
    private static final int NAME_MODE_PREFIX_ARITHMETIC = 4;
    private static final int NAME_MODE_PALETTE = 5;

    private static final int COLOR_MODE_CONSTANT = 0;
    private static final int COLOR_MODE_PALETTE = 1;
    private static final int COLOR_MODE_RAW = 2;

    private static final int RANGE_PROB_BITS = 12;
    private static final int RANGE_PROB_SCALE = 1 << RANGE_PROB_BITS;
    private static final int RANGE_PROB_MOVE = 4;
    private static final int RANGE_MAX_WIDTH = 31;
    private static final long RANGE_TOP = 1L << 24;
    private static final long RANGE_BOTTOM = 1L << 16;
    private static final long RANGE_MASK = 0xFFFF_FFFFL;
    private static final int MAX_WAYPOINTS = WaypointImporter.MAX_WAYPOINTS_PER_GROUP;
    private static final int MAX_STRING_BYTES = WaypointCodec.Options.MAX_LABEL_CHARS * 4;
    private static final int MAX_COORD_PAYLOAD_BYTES = 2 << 20;
    private static final int MIN_BLOCK_COORDINATE = Math.floorDiv(Integer.MIN_VALUE, Waypoint.PRECISE_SCALE);
    private static final int MAX_BLOCK_COORDINATE = Math.floorDiv(Integer.MAX_VALUE, Waypoint.PRECISE_SCALE);

    private static final String[] TRAINED_ZONE_IDS = {
            "unknown",
            "mining_3",
            "foraging_2",
            "crystal_hollows",
            "combat_3",
            "mineshaft",
            "farming_1"
    };

    private static final short[] BASE_ZERO_PROBABILITIES = {
            // X: 12 trained bit priors followed by 19 neutral priors.
            2145, 2212, 2034, 2177, 3087, 3683, 3946, 3983, 4058, 4053, 4001, 3810,
            2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048,
            2048, 2048, 2048, 2048, 2048, 2048, 2048,
            // Y: 8 trained bit priors followed by 23 neutral priors.
            2336, 2341, 2312, 2554, 3471, 3885, 4015, 3734,
            2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048,
            2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048,
            // Z: 10 trained bit priors followed by 21 neutral priors.
            2163, 2131, 1986, 2119, 3053, 3701, 3949, 3992, 4057, 4087,
            2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048,
            2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048
    };

    static {
        if (BASE_ZERO_PROBABILITIES.length != 3 * RANGE_MAX_WIDTH) {
            throw new ExceptionInInitializerError("v9 range model must contain exactly 31 priors per axis");
        }
    }

    private static final Pattern CANONICAL_INTEGER = Pattern.compile("(?:0|-?[1-9]\\d*)");
    private static final Pattern PREFIX_INTEGER = Pattern.compile("^(.*?)(-?(?:0|[1-9]\\d*))$");

    private V9CompactCodec() {}

    static boolean canEncode(WaypointGroup group, WaypointCodec.Options options) {
        if (!options.includeNames || !options.includeColors || !options.includeGroupMeta) return false;
        if (!fitsCompactString(group.zoneId())) return false;
        if (!isCanonicalDisplayName(group.name())) return false;
        if (group.gradientMode() != WaypointGroup.GradientMode.MANUAL) return false;
        if (group.loadMode() != WaypointGroup.LoadMode.SEQUENCE) return false;
        if (Double.compare(group.defaultRadius(), Waypoint.DEFAULT_REACH_RADIUS) != 0) return false;
        if (!group.skipAheadEnabled()
                || group.staticColor() != Waypoint.DEFAULT_COLOR
                || group.gradientStartColor() != 0x00BFFF
                || group.gradientEndColor() != 0xFF3040) {
            return false;
        }
        if (group.size() > MAX_WAYPOINTS) return false;

        for (Waypoint waypoint : group.waypoints()) {
            if (!isCanonicalDisplayName(waypoint.name())
                    || waypoint.flags() != 0
                    || waypoint.customRadius() != 0.0
                    || waypoint.hasCustomPrecisePosition()
                    || waypoint.tempMode() != Waypoint.TEMP_NONE
                    || waypoint.expiresAtMillis() != 0L) {
                return false;
            }
        }
        return coordinateWidths(group.waypoints()) != null;
    }

    static boolean canEncodeCoordinates(WaypointGroup group, WaypointCodec.Options options) {
        if (options.includeNames || options.includeColors
                || options.includeRadii || options.includeWaypointFlags) {
            return false;
        }
        if (group.size() > MAX_WAYPOINTS) return false;
        if (!fitsCompactString(group.zoneId())) return false;
        if (options.includeGroupMeta
                && (group.gradientMode() != WaypointGroup.GradientMode.MANUAL
                || group.loadMode() != WaypointGroup.LoadMode.SEQUENCE
                || Double.compare(group.defaultRadius(), Waypoint.DEFAULT_REACH_RADIUS) != 0
                || !group.skipAheadEnabled())) {
            return false;
        }
        for (Waypoint waypoint : group.waypoints()) {
            if (waypoint.flags() != 0
                    || waypoint.customRadius() != 0.0
                    || waypoint.hasCustomPrecisePosition()
                    || waypoint.tempMode() != Waypoint.TEMP_NONE
                    || waypoint.expiresAtMillis() != 0L) {
                return false;
            }
        }
        return coordinateWidths(group.waypoints()) != null;
    }

    private static boolean fitsCompactString(String value) {
        int length = WaypointCodec.strictUtf8Length(value);
        return length >= 0 && length <= MAX_STRING_BYTES;
    }

    private static boolean isCanonicalDisplayName(String value) {
        return WaypointCodec.isValidRouteDisplayName(value) && fitsCompactString(value);
    }

    static byte[] encodePayload(WaypointGroup group) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        writeZone(output, group.zoneId());
        writeString(output, group.name());
        writeUnsignedVarint(output, group.size());

        List<String> names = new ArrayList<>(group.size());
        for (Waypoint waypoint : group.waypoints()) {
            names.add(waypoint.name());
        }
        writeNameStream(output, names);
        writeColorStream(output, group.waypoints());
        writeRangeCoordinates(output, group.waypoints());
        output.flush();
        return bytes.toByteArray();
    }

    static byte[] encodeCoordinatePayload(WaypointGroup group) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        writeZone(output, group.zoneId());
        writeUnsignedVarint(output, group.size());
        writeRangeCoordinates(output, group.waypoints());
        output.flush();
        return bytes.toByteArray();
    }

    static WaypointGroup decodePayload(DataInputStream input) throws IOException {
        String zoneId = readZone(input);
        String groupName = readString(input);
        if (!isCanonicalDisplayName(groupName)) {
            throw new IOException("compact group name is not canonical");
        }
        int count = readUnsignedVarint(input);
        if (count < 0 || count > MAX_WAYPOINTS) {
            throw new IOException("compact waypoint count out of range: " + count);
        }

        String[] names = readNameStream(input, count);
        int[] colors = readColorStream(input, count);
        int[][] coordinates = readRangeCoordinates(input, count);

        WaypointGroup group = WaypointGroup.create(groupName, zoneId);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        List<Waypoint> waypoints = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (!isCanonicalDisplayName(names[index])) {
                throw new IOException("compact waypoint name is not canonical");
            }
            waypoints.add(new Waypoint(
                    coordinates[index][0],
                    coordinates[index][1],
                    coordinates[index][2],
                    names[index],
                    colors[index],
                    0,
                    0.0));
        }
        group.addAll(waypoints);
        return group;
    }

    static WaypointGroup decodeCoordinatePayload(DataInputStream input) throws IOException {
        String zoneId = readZone(input);
        int count = readUnsignedVarint(input);
        if (count < 0 || count > MAX_WAYPOINTS) {
            throw new IOException("compact coordinate waypoint count out of range: " + count);
        }
        int[][] coordinates = readRangeCoordinates(input, count);
        WaypointGroup group = WaypointGroup.create("", zoneId);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        List<Waypoint> waypoints = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            waypoints.add(Waypoint.at(
                    coordinates[index][0], coordinates[index][1], coordinates[index][2]));
        }
        group.addAll(waypoints);
        return group;
    }

    private static void writeZone(DataOutputStream output, String zoneId) throws IOException {
        for (int index = 0; index < TRAINED_ZONE_IDS.length; index++) {
            if (TRAINED_ZONE_IDS[index].equals(zoneId)) {
                output.writeByte(index + 1);
                return;
            }
        }
        output.writeByte(0);
        writeString(output, zoneId);
    }

    private static String readZone(DataInputStream input) throws IOException {
        int token = input.readUnsignedByte();
        if (token == 0) return readString(input);
        if (token > TRAINED_ZONE_IDS.length) {
            throw new IOException("unknown compact zone token: " + token);
        }
        return TRAINED_ZONE_IDS[token - 1];
    }

    private static void writeNameStream(DataOutputStream output, List<String> names) throws IOException {
        boolean allEmpty = true;
        for (String name : names) {
            if (!name.isEmpty()) {
                allEmpty = false;
                break;
            }
        }
        if (allEmpty) {
            output.writeByte(NAME_MODE_EMPTY);
            return;
        }

        boolean constant = !names.isEmpty();
        for (int index = 1; index < names.size(); index++) {
            if (!names.get(0).equals(names.get(index))) {
                constant = false;
                break;
            }
        }
        if (constant) {
            output.writeByte(NAME_MODE_CONSTANT);
            writeString(output, names.get(0));
            return;
        }

        long[] numeric = parseCanonicalIntegers(names);
        if (numeric != null) {
            Long step = arithmeticStep(numeric);
            if (step != null) {
                output.writeByte(NAME_MODE_NUMERIC_ARITHMETIC);
                writeSignedVarLong(output, numeric.length == 0 ? 0 : numeric[0]);
                writeSignedVarLong(output, step);
                return;
            }
            if (numericDeltasFit(numeric)) {
                output.writeByte(NAME_MODE_NUMERIC_DELTA);
                long previous = 0;
                for (long value : numeric) {
                    long delta = Math.subtractExact(value, previous);
                    writeSignedVarLong(output, delta);
                    previous = value;
                }
                return;
            }
        }

        PrefixArithmetic prefixArithmetic = prefixArithmetic(names);
        if (prefixArithmetic != null) {
            output.writeByte(NAME_MODE_PREFIX_ARITHMETIC);
            writeString(output, prefixArithmetic.prefix());
            writeSignedVarLong(output, prefixArithmetic.start());
            writeSignedVarLong(output, prefixArithmetic.step());
            return;
        }

        output.writeByte(NAME_MODE_PALETTE);
        Map<String, Integer> indexesByName = new LinkedHashMap<>();
        int[] indexes = new int[names.size()];
        for (int index = 0; index < names.size(); index++) {
            Integer paletteIndex = indexesByName.get(names.get(index));
            if (paletteIndex == null) {
                paletteIndex = indexesByName.size();
                indexesByName.put(names.get(index), paletteIndex);
            }
            indexes[index] = paletteIndex;
        }
        writeUnsignedVarint(output, indexesByName.size());
        for (String name : indexesByName.keySet()) writeString(output, name);
        writePackedValues(output, indexes, bitsForCardinality(indexesByName.size()));
    }

    private static String[] readNameStream(DataInputStream input, int count) throws IOException {
        int mode = input.readUnsignedByte();
        String[] names = new String[count];
        if (mode == NAME_MODE_EMPTY) {
            Arrays.fill(names, "");
            return names;
        }
        if (mode == NAME_MODE_CONSTANT) {
            Arrays.fill(names, readString(input));
            return names;
        }
        if (mode == NAME_MODE_NUMERIC_ARITHMETIC || mode == NAME_MODE_PREFIX_ARITHMETIC) {
            String prefix = mode == NAME_MODE_PREFIX_ARITHMETIC ? readString(input) : "";
            long start = readSignedVarLong(input);
            long step = readSignedVarLong(input);
            for (int index = 0; index < count; index++) {
                try {
                    names[index] = prefix + Math.addExact(start, Math.multiplyExact(step, index));
                } catch (ArithmeticException e) {
                    throw new IOException("compact arithmetic name overflow", e);
                }
            }
            return names;
        }
        if (mode == NAME_MODE_NUMERIC_DELTA) {
            long previous = 0;
            for (int index = 0; index < count; index++) {
                try {
                    previous = Math.addExact(previous, readSignedVarLong(input));
                } catch (ArithmeticException e) {
                    throw new IOException("compact numeric name overflow", e);
                }
                names[index] = Long.toString(previous);
            }
            return names;
        }
        if (mode == NAME_MODE_PALETTE) {
            int paletteCount = readUnsignedVarint(input);
            if (paletteCount < 1 || paletteCount > Math.max(1, count)) {
                throw new IOException("compact name palette count out of range: " + paletteCount);
            }
            String[] palette = new String[paletteCount];
            for (int index = 0; index < paletteCount; index++) palette[index] = readString(input);
            int[] indexes = readPackedValues(input, count, bitsForCardinality(paletteCount));
            for (int index = 0; index < count; index++) {
                if (indexes[index] >= paletteCount) {
                    throw new IOException("compact name palette index out of range: " + indexes[index]);
                }
                names[index] = palette[indexes[index]];
            }
            return names;
        }
        throw new IOException("unsupported compact name mode: " + mode);
    }

    private static void writeColorStream(DataOutputStream output, List<Waypoint> waypoints) throws IOException {
        if (waypoints.isEmpty()) {
            output.writeByte(COLOR_MODE_RAW);
            return;
        }

        Map<Integer, Integer> indexesByColor = new LinkedHashMap<>();
        int[] indexes = new int[waypoints.size()];
        for (int index = 0; index < waypoints.size(); index++) {
            int color = waypoints.get(index).color() & 0xFFFFFF;
            Integer paletteIndex = indexesByColor.get(color);
            if (paletteIndex == null) {
                paletteIndex = indexesByColor.size();
                indexesByColor.put(color, paletteIndex);
            }
            indexes[index] = paletteIndex;
        }

        if (indexesByColor.size() == 1) {
            output.writeByte(COLOR_MODE_CONSTANT);
            writeRgb(output, indexesByColor.keySet().iterator().next());
            return;
        }

        int bits = bitsForCardinality(indexesByColor.size());
        long paletteBytes = 1L + unsignedVarintLength(indexesByColor.size())
                + indexesByColor.size() * 3L
                + ((long) waypoints.size() * bits + 7L) / 8L;
        long rawBytes = 1L + waypoints.size() * 3L;
        if (paletteBytes < rawBytes) {
            output.writeByte(COLOR_MODE_PALETTE);
            writeUnsignedVarint(output, indexesByColor.size());
            for (int color : indexesByColor.keySet()) writeRgb(output, color);
            writePackedValues(output, indexes, bits);
            return;
        }

        output.writeByte(COLOR_MODE_RAW);
        for (Waypoint waypoint : waypoints) writeRgb(output, waypoint.color());
    }

    private static int[] readColorStream(DataInputStream input, int count) throws IOException {
        int mode = input.readUnsignedByte();
        int[] colors = new int[count];
        if (mode == COLOR_MODE_CONSTANT) {
            Arrays.fill(colors, readRgb(input));
            return colors;
        }
        if (mode == COLOR_MODE_PALETTE) {
            int paletteCount = readUnsignedVarint(input);
            if (paletteCount < 1 || paletteCount > Math.max(1, count)) {
                throw new IOException("compact color palette count out of range: " + paletteCount);
            }
            int[] palette = new int[paletteCount];
            for (int index = 0; index < paletteCount; index++) palette[index] = readRgb(input);
            int[] indexes = readPackedValues(input, count, bitsForCardinality(paletteCount));
            for (int index = 0; index < count; index++) {
                if (indexes[index] >= paletteCount) {
                    throw new IOException("compact color palette index out of range: " + indexes[index]);
                }
                colors[index] = palette[indexes[index]];
            }
            return colors;
        }
        if (mode == COLOR_MODE_RAW) {
            for (int index = 0; index < count; index++) colors[index] = readRgb(input);
            return colors;
        }
        throw new IOException("unsupported compact color mode: " + mode);
    }

    private static void writeRangeCoordinates(DataOutputStream output, List<Waypoint> waypoints)
            throws IOException {
        int[] widths = coordinateWidths(waypoints);
        if (widths == null) throw new IOException("coordinate delta exceeds compact range width");

        if (!waypoints.isEmpty()) {
            Waypoint first = waypoints.get(0);
            writeSignedVarLong(output, first.x());
            writeSignedVarLong(output, first.y());
            writeSignedVarLong(output, first.z());
        }
        int packedWidths = (widths[0] << 10) | (widths[1] << 5) | widths[2];
        output.writeShort(packedWidths);

        RangeEncoder encoder = new RangeEncoder();
        short[] probabilities = BASE_ZERO_PROBABILITIES.clone();
        for (int axis = 0; axis < 3; axis++) {
            for (int index = 1; index < waypoints.size(); index++) {
                long delta = coordinate(waypoints.get(index), axis)
                        - coordinate(waypoints.get(index - 1), axis);
                long encoded = zigzag(delta);
                for (int bit = widths[axis] - 1; bit >= 0; bit--) {
                    encoder.writeBit(probabilities, axis * RANGE_MAX_WIDTH + bit,
                            (int) ((encoded >>> bit) & 1L));
                }
            }
        }
        byte[] payload = encoder.finish();
        writeUnsignedVarint(output, payload.length);
        output.write(payload);
    }

    private static int[][] readRangeCoordinates(DataInputStream input, int count) throws IOException {
        int[][] coordinates = new int[count][3];
        if (count > 0) {
            for (int axis = 0; axis < 3; axis++) {
                long value = readSignedVarLong(input);
                coordinates[0][axis] = validateBlockCoordinate(value);
            }
        }

        int packedWidths = input.readUnsignedShort();
        if ((packedWidths & 0x8000) != 0) {
            throw new IOException("compact coordinate width reserved bit is set");
        }
        int[] widths = {
                (packedWidths >>> 10) & RANGE_MAX_WIDTH,
                (packedWidths >>> 5) & RANGE_MAX_WIDTH,
                packedWidths & RANGE_MAX_WIDTH
        };
        int payloadLength = readUnsignedVarint(input);
        long bitCount = (long) Math.max(0, count - 1)
                * (widths[0] + widths[1] + widths[2]);
        long maximumPayloadLength = Math.max(16L, bitCount + 16L);
        if (payloadLength < 0 || payloadLength > MAX_COORD_PAYLOAD_BYTES
                || payloadLength > maximumPayloadLength) {
            throw new IOException("compact coordinate payload length out of range: " + payloadLength);
        }
        byte[] payload = input.readNBytes(payloadLength);
        if (payload.length != payloadLength) throw new EOFException("truncated compact coordinate payload");

        RangeDecoder decoder = new RangeDecoder(payload);
        short[] probabilities = BASE_ZERO_PROBABILITIES.clone();
        for (int axis = 0; axis < 3; axis++) {
            long previous = count == 0 ? 0 : coordinates[0][axis];
            for (int index = 1; index < count; index++) {
                long encoded = 0;
                for (int bit = widths[axis] - 1; bit >= 0; bit--) {
                    encoded |= (long) decoder.readBit(probabilities, axis * RANGE_MAX_WIDTH + bit) << bit;
                }
                long delta = unzigzag(encoded);
                previous += delta;
                coordinates[index][axis] = validateBlockCoordinate(previous);
            }
        }
        int[] canonicalWidths = coordinateWidths(coordinates);
        if (!Arrays.equals(widths, canonicalWidths)) {
            throw new IOException("compact coordinate widths are non-canonical");
        }
        byte[] canonicalPayload = encodeRangePayload(coordinates, widths);
        if (!Arrays.equals(payload, canonicalPayload)) {
            throw new IOException("compact coordinate range payload is truncated or non-canonical");
        }
        return coordinates;
    }

    private static int validateBlockCoordinate(long value) throws IOException {
        if (value < MIN_BLOCK_COORDINATE || value > MAX_BLOCK_COORDINATE) {
            throw new IOException("compact coordinate outside representable block range: " + value);
        }
        return (int) value;
    }

    private static byte[] encodeRangePayload(int[][] coordinates, int[] widths) {
        RangeEncoder encoder = new RangeEncoder();
        short[] probabilities = BASE_ZERO_PROBABILITIES.clone();
        for (int axis = 0; axis < 3; axis++) {
            for (int index = 1; index < coordinates.length; index++) {
                long delta = (long) coordinates[index][axis] - coordinates[index - 1][axis];
                long encoded = zigzag(delta);
                for (int bit = widths[axis] - 1; bit >= 0; bit--) {
                    encoder.writeBit(probabilities, axis * RANGE_MAX_WIDTH + bit,
                            (int) ((encoded >>> bit) & 1L));
                }
            }
        }
        return encoder.finish();
    }

    private static int[] coordinateWidths(List<Waypoint> waypoints) {
        int[] widths = new int[3];
        for (int index = 1; index < waypoints.size(); index++) {
            for (int axis = 0; axis < 3; axis++) {
                long delta = coordinate(waypoints.get(index), axis)
                        - coordinate(waypoints.get(index - 1), axis);
                long encoded = zigzag(delta);
                int width = encoded == 0 ? 0 : Long.SIZE - Long.numberOfLeadingZeros(encoded);
                if (width > RANGE_MAX_WIDTH) return null;
                widths[axis] = Math.max(widths[axis], width);
            }
        }
        return widths;
    }

    private static int[] coordinateWidths(int[][] coordinates) {
        int[] widths = new int[3];
        for (int index = 1; index < coordinates.length; index++) {
            for (int axis = 0; axis < 3; axis++) {
                long delta = (long) coordinates[index][axis] - coordinates[index - 1][axis];
                long encoded = zigzag(delta);
                int width = encoded == 0 ? 0 : Long.SIZE - Long.numberOfLeadingZeros(encoded);
                widths[axis] = Math.max(widths[axis], width);
            }
        }
        return widths;
    }

    private static long coordinate(Waypoint waypoint, int axis) {
        return axis == 0 ? waypoint.x() : axis == 1 ? waypoint.y() : waypoint.z();
    }

    private static long[] parseCanonicalIntegers(List<String> names) {
        long[] values = new long[names.size()];
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            if (!CANONICAL_INTEGER.matcher(name).matches()) return null;
            try {
                long value = Long.parseLong(name);
                if (!Long.toString(value).equals(name)) return null;
                values[index] = value;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return values;
    }

    private static Long arithmeticStep(long[] values) {
        if (values.length < 2) return 0L;
        final long step;
        try {
            step = Math.subtractExact(values[1], values[0]);
            for (int index = 2; index < values.length; index++) {
                long expected = Math.addExact(values[0], Math.multiplyExact(step, index));
                if (values[index] != expected) return null;
            }
        } catch (ArithmeticException ignored) {
            return null;
        }
        return step;
    }

    private static boolean numericDeltasFit(long[] values) {
        long previous = 0;
        try {
            for (long value : values) {
                Math.subtractExact(value, previous);
                previous = value;
            }
            return true;
        } catch (ArithmeticException ignored) {
            return false;
        }
    }

    private static PrefixArithmetic prefixArithmetic(List<String> names) {
        if (names.isEmpty()) return null;
        String prefix = null;
        long[] values = new long[names.size()];
        for (int index = 0; index < names.size(); index++) {
            Matcher matcher = PREFIX_INTEGER.matcher(names.get(index));
            if (!matcher.matches() || matcher.group(1).isEmpty()) return null;
            if (prefix == null) prefix = matcher.group(1);
            else if (!prefix.equals(matcher.group(1))) return null;
            String suffix = matcher.group(2);
            if (!CANONICAL_INTEGER.matcher(suffix).matches()) return null;
            try {
                values[index] = Long.parseLong(suffix);
                if (!Long.toString(values[index]).equals(suffix)) return null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        Long step = arithmeticStep(values);
        return step == null ? null : new PrefixArithmetic(prefix, values[0], step);
    }

    private static void writeRgb(DataOutputStream output, int color) throws IOException {
        output.writeByte(color >>> 16);
        output.writeByte(color >>> 8);
        output.writeByte(color);
    }

    private static int readRgb(DataInputStream input) throws IOException {
        return (input.readUnsignedByte() << 16)
                | (input.readUnsignedByte() << 8)
                | input.readUnsignedByte();
    }

    private static int bitsForCardinality(int cardinality) throws IOException {
        if (cardinality < 1) throw new IOException("invalid compact palette cardinality: " + cardinality);
        return cardinality <= 1 ? 0 : Integer.SIZE - Integer.numberOfLeadingZeros(cardinality - 1);
    }

    private static void writePackedValues(DataOutputStream output, int[] values, int bits) throws IOException {
        if (bits == 0) return;
        long limit = 1L << bits;
        long buffer = 0;
        int bufferedBits = 0;
        for (int value : values) {
            if (value < 0 || value >= limit) {
                throw new IOException("compact packed value does not fit " + bits + " bits: " + value);
            }
            buffer |= (long) value << bufferedBits;
            bufferedBits += bits;
            while (bufferedBits >= 8) {
                output.writeByte((int) buffer);
                buffer >>>= 8;
                bufferedBits -= 8;
            }
        }
        if (bufferedBits > 0) output.writeByte((int) buffer);
    }

    private static int[] readPackedValues(DataInputStream input, int count, int bits) throws IOException {
        int[] values = new int[count];
        if (bits == 0) return values;
        long buffer = 0;
        int bufferedBits = 0;
        long mask = (1L << bits) - 1L;
        for (int index = 0; index < count; index++) {
            while (bufferedBits < bits) {
                buffer |= (long) input.readUnsignedByte() << bufferedBits;
                bufferedBits += 8;
            }
            values[index] = (int) (buffer & mask);
            buffer >>>= bits;
            bufferedBits -= bits;
        }
        if (buffer != 0) throw new IOException("compact palette padding bits are nonzero");
        return values;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = WaypointCodec.encodeUtf8Strict(value);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("compact string is too long");
        writeUnsignedVarint(output, bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = readUnsignedVarint(input);
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("compact string length out of range: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("truncated compact string");
        return WaypointCodec.decodeUtf8Strict(bytes);
    }

    private static int unsignedVarintLength(int value) {
        int length = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            length++;
        }
        return length;
    }

    private static void writeUnsignedVarint(DataOutputStream output, int value) throws IOException {
        if (value < 0) throw new IOException("negative compact unsigned varint");
        while ((value & ~0x7F) != 0) {
            output.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.writeByte(value);
    }

    private static int readUnsignedVarint(DataInputStream input) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.readUnsignedByte();
            if (shift == 28 && (next & 0xF0) != 0) {
                throw new IOException("compact varint exceeds signed 32-bit range");
            }
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                if (shift > 0 && (next & 0x7F) == 0) {
                    throw new IOException("compact varint is non-canonical");
                }
                return value;
            }
        }
        throw new IOException("compact varint is too long");
    }

    private static void writeSignedVarLong(DataOutputStream output, long value) throws IOException {
        writeUnsignedVarLong(output, zigzag(value));
    }

    private static long readSignedVarLong(DataInputStream input) throws IOException {
        return unzigzag(readUnsignedVarLong(input));
    }

    private static void writeUnsignedVarLong(DataOutputStream output, long value) throws IOException {
        while ((value & ~0x7FL) != 0) {
            output.writeByte(((int) value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.writeByte((int) value);
    }

    private static long readUnsignedVarLong(DataInputStream input) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int next = input.readUnsignedByte();
            if (shift == 63 && (next & 0xFE) != 0) throw new IOException("compact varlong is too long");
            value |= (long) (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                if (shift > 0 && (next & 0x7F) == 0) {
                    throw new IOException("compact varlong is non-canonical");
                }
                return value;
            }
        }
        throw new IOException("compact varlong is too long");
    }

    private static long zigzag(long value) {
        return (value << 1) ^ (value >> 63);
    }

    private static long unzigzag(long value) {
        return (value >>> 1) ^ -(value & 1L);
    }

    private record PrefixArithmetic(String prefix, long start, long step) {}

    private static final class RangeEncoder {
        private long low;
        private long range = RANGE_MASK;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        void writeBit(short[] probabilities, int context, int bit) {
            int probability = probabilities[context] & 0xFFFF;
            long bound = (range >>> RANGE_PROB_BITS) * probability;
            if (bit == 0) {
                range = bound;
                probability += (RANGE_PROB_SCALE - probability) >>> RANGE_PROB_MOVE;
            } else {
                low = (low + bound) & RANGE_MASK;
                range = (range - bound) & RANGE_MASK;
                probability -= probability >>> RANGE_PROB_MOVE;
            }
            probabilities[context] = (short) probability;
            renormalize();
        }

        private void renormalize() {
            while (true) {
                if ((low ^ (low + range)) < RANGE_TOP) {
                    // Stable top byte.
                } else if (range < RANGE_BOTTOM) {
                    range = (-low) & (RANGE_BOTTOM - 1L);
                } else {
                    break;
                }
                output.write((int) (low >>> 24));
                low = (low << 8) & RANGE_MASK;
                range = (range << 8) & RANGE_MASK;
            }
        }

        byte[] finish() {
            for (int byteCount = 0; byteCount <= 4; byteCount++) {
                int shift = 8 * (4 - byteCount);
                long candidate = shift == 0
                        ? low
                        : ((low + ((1L << shift) - 1L)) >>> shift) << shift;
                if ((((candidate - low) & RANGE_MASK) < range) && candidate <= RANGE_MASK) {
                    for (int index = 0; index < byteCount; index++) {
                        output.write((int) (candidate >>> (24 - 8 * index)));
                    }
                    return output.toByteArray();
                }
            }
            for (int index = 0; index < 4; index++) {
                output.write((int) (low >>> (24 - 8 * index)));
            }
            return output.toByteArray();
        }
    }

    private static final class RangeDecoder {
        private final byte[] data;
        private int position;
        private long low;
        private long range = RANGE_MASK;
        private long code;

        RangeDecoder(byte[] data) {
            this.data = data;
            for (int index = 0; index < 4; index++) code = ((code << 8) | nextByte()) & RANGE_MASK;
        }

        int readBit(short[] probabilities, int context) {
            int probability = probabilities[context] & 0xFFFF;
            long bound = (range >>> RANGE_PROB_BITS) * probability;
            int bit;
            if (((code - low) & RANGE_MASK) < bound) {
                bit = 0;
                range = bound;
                probability += (RANGE_PROB_SCALE - probability) >>> RANGE_PROB_MOVE;
            } else {
                bit = 1;
                low = (low + bound) & RANGE_MASK;
                range = (range - bound) & RANGE_MASK;
                probability -= probability >>> RANGE_PROB_MOVE;
            }
            probabilities[context] = (short) probability;
            renormalize();
            return bit;
        }

        private int nextByte() {
            int value = position < data.length ? data[position] & 0xFF : 0;
            position++;
            return value;
        }

        private void renormalize() {
            while (true) {
                if ((low ^ (low + range)) < RANGE_TOP) {
                    // Stable top byte.
                } else if (range < RANGE_BOTTOM) {
                    range = (-low) & (RANGE_BOTTOM - 1L);
                } else {
                    break;
                }
                code = ((code << 8) | nextByte()) & RANGE_MASK;
                low = (low << 8) & RANGE_MASK;
                range = (range << 8) & RANGE_MASK;
            }
        }
    }
}
