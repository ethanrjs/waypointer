package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

/** Wire-v10 kind 2: one ordered list of integer block coordinates and nothing else. */
final class V10BareRouteCodec {

    static final int SEMANTIC_HEADER = 0x2A;
    static final int MAX_WAYPOINTS = 20_000;
    static final int MIN_COORDINATE = Waypoint.MIN_BLOCK_COORDINATE;
    static final int MAX_COORDINATE = Waypoint.MAX_BLOCK_COORDINATE;
    static final int MAX_ZIGZAG_DELTA = 536_870_910;

    private static final int CONSTANT_AXIS = -1;
    private static final int[][] COMMON_RICE_PARAMETERS = {
            {4, 0, 4},
            {4, 3, 4},
            {3, 2, 3}
    };

    private V10BareRouteCodec() {}

    static boolean canEncode(WaypointGroup group, WaypointCodec.Options options) {
        if (group == null || options == null) return false;
        if (!options.isBareCoordinateProjection()) return false;
        if (group.routeKind() != WaypointGroup.RouteKind.REGULAR) return false;
        // This preset explicitly projects every non-coordinate field away,
        // including structural flags and precision.
        return group.size() <= MAX_WAYPOINTS;
    }

    static String encode(WaypointGroup group) throws IOException {
        return encodeCandidate(group).transport();
    }

    static V10Transport.Outbound encodeCandidate(WaypointGroup group) throws IOException {
        int[][] coordinates = coordinatesOf(group);

        V10Transport.Outbound best = V10Transport.direct(encodeRiceSemantic(coordinates));

        if (coordinates.length > 1
                && coordinates.length <= V10BareEntropyCodec.MAX_QUOTIENT_WAYPOINTS) {
            V10Transport.Outbound quotient = V10Transport.direct(
                    V10BareEntropyCodec.encodeQuotient(coordinates));
            if (quotient.compareTo(best) < 0) best = quotient;
        }

        // Descriptor 0 remains reserved.

        byte[] deltaSemantic = encodeDeltaSemantic(coordinates);
        V10Transport.Outbound defaultDeflate = V10Transport.deflated(
                deltaSemantic, Deflater.DEFAULT_STRATEGY);
        if (defaultDeflate.compareTo(best) < 0) best = defaultDeflate;

        V10Transport.Outbound filteredDeflate = V10Transport.deflated(
                deltaSemantic, Deflater.FILTERED);
        if (filteredDeflate.compareTo(best) < 0) best = filteredDeflate;
        return best;
    }

    static WaypointGroup decode(String transport) throws IOException {
        return decode(V10Transport.probe(transport));
    }

    static WaypointGroup decode(V10Transport.CheckedFrame frame) throws IOException {
        if (frame.contentKind() != 2) {
            throw new IOException("unsupported v10 content kind " + frame.contentKind());
        }
        byte[] semantic = frame.semantic();
        int[][] coordinates;
        if (frame.mode() == V10Transport.MODE_DEFLATE) {
            coordinates = decodeDeltaSemantic(semantic);
        } else {
            coordinates = decodeDirectSemantic(semantic);
        }
        WaypointGroup group = WaypointGroup.create("", Zone.UNKNOWN.id());
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        List<Waypoint> waypoints = new ArrayList<>(coordinates.length);
        for (int[] point : coordinates) {
            waypoints.add(Waypoint.at(point[0], point[1], point[2]));
        }
        group.addAll(waypoints);
        return group;
    }

    /** Read the bounded point count before a pack decoder allocates the child route. */
    static int waypointCount(byte[] semantic) throws IOException {
        if (semantic == null) throw new IOException("null v10 bare-route semantic body");
        ByteReader reader = new ByteReader(semantic);
        int header = reader.readUnsignedByte();
        if (header != SEMANTIC_HEADER) {
            throw new IOException("expected v10 bare-route kind 2 child");
        }
        return (int) reader.readUVarint(MAX_WAYPOINTS, 3);
    }

    static int[][] coordinatesOf(WaypointGroup group) {
        if (group.size() > MAX_WAYPOINTS) {
            throw new IllegalArgumentException("v10 bare route exceeds waypoint limit");
        }
        int[][] coordinates = new int[group.size()][3];
        for (int index = 0; index < group.size(); index++) {
            Waypoint waypoint = group.get(index);
            coordinates[index][0] = waypoint.x();
            coordinates[index][1] = waypoint.y();
            coordinates[index][2] = waypoint.z();
        }
        return coordinates;
    }

    /** Return the selected kind-2 coordinate primitive without its 0x2A header. */
    static byte[] encodeCoordinateBody(int[][] coordinates, int mode) {
        byte[] semantic = switch (mode) {
            case V10Transport.MODE_DIRECT -> encodeRiceSemantic(coordinates);
            case V10Transport.MODE_DEFLATE -> encodeDeltaSemantic(coordinates);
            default -> throw new IllegalArgumentException("unknown v10 coordinate mode " + mode);
        };
        return Arrays.copyOfRange(semantic, 1, semantic.length);
    }

    /** Decode the shared kind-2 coordinate primitive from a containing body. */
    static int[][] decodeCoordinateBody(byte[] body, int mode) throws IOException {
        if (body == null || body.length == 0) {
            throw new IOException("empty v10 coordinate body");
        }
        byte[] semantic = new byte[body.length + 1];
        semantic[0] = (byte) SEMANTIC_HEADER;
        System.arraycopy(body, 0, semantic, 1, body.length);
        return switch (mode) {
            case V10Transport.MODE_DIRECT -> decodeDirectSemantic(semantic);
            case V10Transport.MODE_DEFLATE -> decodeDeltaSemantic(semantic);
            default -> throw new IOException("unknown v10 coordinate mode " + mode);
        };
    }

    private static int[][] decodeDirectSemantic(byte[] semantic) throws IOException {
        return switch (V10BareEntropyCodec.descriptor(semantic)) {
            case RICE -> decodeRiceSemantic(semantic);
            case RESERVED_GOLOMB -> throw new IOException(
                    "reserved v10 Golomb descriptor is not supported");
            case QUOTIENT -> V10BareEntropyCodec.decodeQuotient(semantic);
        };
    }

    static byte[] encodeRiceSemantic(int[][] coordinates) {
        ByteArrayOutputStream output = semanticPrefix(coordinates);
        if (coordinates.length <= 1) return output.toByteArray();

        int[][] axes = deltaAxes(coordinates);
        int[] parameters = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            parameters[axis] = isAllZero(axes[axis])
                    ? CONSTANT_AXIS
                    : chooseK(axes[axis]);
        }

        RiceBitWriter bits = new RiceBitWriter();
        writeKDescriptor(bits, parameters);
        for (int axis = 0; axis < 3; axis++) {
            int parameter = parameters[axis];
            if (parameter == CONSTANT_AXIS) continue;
            for (int value : axes[axis]) bits.writeRice(value, parameter);
        }
        output.writeBytes(bits.finish());
        return requireSemanticLimit(output.toByteArray(), "Rice");
    }

    static int[][] decodeRiceSemantic(byte[] semantic) throws IOException {
        ByteReader reader = new ByteReader(semantic);
        Prefix prefix = decodePrefix(reader);
        int count = prefix.count;
        if (count == 0) {
            reader.requireEnd();
            return new int[0][3];
        }
        int[][] coordinates = new int[count][3];
        System.arraycopy(prefix.first, 0, coordinates[0], 0, 3);
        if (count == 1) {
            reader.requireEnd();
            return coordinates;
        }

        RiceBitReader bits = new RiceBitReader(
                reader.remainingBytes(), 90L * (count - 1));
        int[] parameters = readKDescriptor(bits);
        int[][] axes = new int[3][count - 1];
        for (int axis = 0; axis < 3; axis++) {
            int parameter = parameters[axis];
            if (parameter == CONSTANT_AXIS) continue;
            for (int index = 0; index < count - 1; index++) {
                axes[axis][index] = bits.readRice(parameter);
            }
        }
        bits.requireZeroPadding();

        for (int index = 1; index < count; index++) {
            for (int axis = 0; axis < 3; axis++) {
                long value = (long) coordinates[index - 1][axis]
                        + unzigzag(axes[axis][index - 1]);
                coordinates[index][axis] = checkedCoordinate(value, "Rice reconstruction");
            }
        }
        if (!Arrays.equals(encodeRiceSemantic(coordinates), semantic)) {
            throw new IOException("non-canonical v10 Rice semantic body");
        }
        return coordinates;
    }

    static byte[] encodeDeltaSemantic(int[][] coordinates) {
        ByteArrayOutputStream output = semanticPrefix(coordinates);
        for (int index = 1; index < coordinates.length; index++) {
            for (int axis = 0; axis < 3; axis++) {
                long delta = (long) coordinates[index][axis] - coordinates[index - 1][axis];
                writeUVarint(output, zigzag(delta));
            }
        }
        return requireSemanticLimit(output.toByteArray(), "delta");
    }

    static int[][] decodeDeltaSemantic(byte[] semantic) throws IOException {
        ByteReader reader = new ByteReader(semantic);
        Prefix prefix = decodePrefix(reader);
        int count = prefix.count;
        if (count == 0) {
            reader.requireEnd();
            return new int[0][3];
        }
        int[][] coordinates = new int[count][3];
        System.arraycopy(prefix.first, 0, coordinates[0], 0, 3);
        for (int index = 1; index < count; index++) {
            for (int axis = 0; axis < 3; axis++) {
                long encoded = reader.readUVarint(MAX_ZIGZAG_DELTA, 5);
                long value = (long) coordinates[index - 1][axis] + unzigzag(encoded);
                coordinates[index][axis] = checkedCoordinate(value, "delta reconstruction");
            }
        }
        reader.requireEnd();
        if (!Arrays.equals(encodeDeltaSemantic(coordinates), semantic)) {
            throw new IOException("non-canonical v10 delta semantic body");
        }
        return coordinates;
    }

    static ByteArrayOutputStream semanticPrefix(int[][] coordinates) {
        if (coordinates.length > MAX_WAYPOINTS) {
            throw new IllegalArgumentException("v10 bare route exceeds waypoint limit");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(SEMANTIC_HEADER);
        writeUVarint(output, coordinates.length);
        if (coordinates.length > 0) {
            for (int axis = 0; axis < 3; axis++) {
                int coordinate = checkedCoordinate(coordinates[0][axis], "first coordinate");
                writeUVarint(output, zigzag(coordinate));
            }
        }
        return output;
    }

    static Prefix decodePrefix(ByteReader reader) throws IOException {
        int header = reader.readUnsignedByte();
        if (header != SEMANTIC_HEADER) {
            throw new IOException("unsupported v10 semantic header 0x"
                    + Integer.toHexString(header));
        }
        int count = (int) reader.readUVarint(MAX_WAYPOINTS, 3);
        if (count == 0) return new Prefix(0, null);
        int[] first = new int[3];
        long maximumFirstZigzag = ((long) MAX_COORDINATE << 1) + 1;
        for (int axis = 0; axis < 3; axis++) {
            first[axis] = checkedCoordinate(
                    unzigzag(reader.readUVarint(maximumFirstZigzag, 5)),
                    "first coordinate");
        }
        return new Prefix(count, first);
    }

    static int[][] deltaAxes(int[][] coordinates) {
        int[][] axes = new int[3][coordinates.length - 1];
        for (int index = 1; index < coordinates.length; index++) {
            for (int axis = 0; axis < 3; axis++) {
                long delta = (long) coordinates[index][axis] - coordinates[index - 1][axis];
                long encoded = zigzag(delta);
                if (encoded > MAX_ZIGZAG_DELTA) {
                    throw new IllegalArgumentException("coordinate delta exceeds v10 model");
                }
                axes[axis][index - 1] = (int) encoded;
            }
        }
        return axes;
    }

    private static int chooseK(int[] values) {
        int bestK = 0;
        long bestCost = Long.MAX_VALUE;
        for (int k = 0; k <= 30; k++) {
            long cost = (long) values.length * (k + 1);
            for (int value : values) cost += value >>> k;
            if (cost < bestCost) {
                bestCost = cost;
                bestK = k;
            }
        }
        return bestK;
    }

    static boolean isAllZero(int[] values) {
        for (int value : values) if (value != 0) return false;
        return true;
    }

    private static void writeKDescriptor(RiceBitWriter bits, int[] parameters) {
        for (int token = 0; token < COMMON_RICE_PARAMETERS.length; token++) {
            if (Arrays.equals(parameters, COMMON_RICE_PARAMETERS[token])) {
                bits.writeBits(token, 2);
                return;
            }
        }
        bits.writeBits(3, 2);
        for (int parameter : parameters) {
            if (parameter == CONSTANT_AXIS) {
                bits.writeBits(7, 3);
                bits.writeBits(31, 5);
            } else if (parameter <= 6) {
                bits.writeBits(parameter, 3);
            } else {
                bits.writeBits(7, 3);
                bits.writeBits(parameter, 5);
            }
        }
    }

    private static int[] readKDescriptor(RiceBitReader bits) throws IOException {
        int token = bits.readBits(2);
        if (token < COMMON_RICE_PARAMETERS.length) return COMMON_RICE_PARAMETERS[token].clone();
        int[] parameters = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            int small = bits.readBits(3);
            if (small < 7) {
                parameters[axis] = small;
                continue;
            }
            int extended = bits.readBits(5);
            if (extended == 31) parameters[axis] = CONSTANT_AXIS;
            else if (extended >= 7) parameters[axis] = extended;
            else throw new IOException("non-canonical extended v10 Rice parameter");
        }
        return parameters;
    }

    static byte[] requireSemanticLimit(byte[] semantic, String mode) {
        if ((long) semantic.length + V10Transport.CHECKSUM_BYTES > V10Transport.MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("v10 " + mode + " semantic body exceeds limit");
        }
        return semantic;
    }

    static int checkedCoordinate(long value, String field) {
        if (value < MIN_COORDINATE || value > MAX_COORDINATE) {
            throw new IllegalArgumentException(field + " is outside v10 coordinate bounds");
        }
        return (int) value;
    }

    private static long zigzag(long value) {
        return value >= 0 ? value * 2 : (-value * 2) - 1;
    }

    static long unzigzag(long value) {
        return (value & 1) == 0 ? value >>> 1 : -(value >>> 1) - 1;
    }

    private static void writeUVarint(ByteArrayOutputStream output, long value) {
        if (value < 0) throw new IllegalArgumentException("negative v10 uvarint");
        do {
            int next = (int) (value & 0x7F);
            value >>>= 7;
            output.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    record Prefix(int count, int[] first) {}

    static final class ByteReader {
        private final byte[] data;
        private int position;

        ByteReader(byte[] data) {
            this.data = data;
        }

        int readUnsignedByte() throws IOException {
            if (position >= data.length) throw new IOException("truncated v10 semantic body");
            return data[position++] & 0xFF;
        }

        long readUVarint(long maximum, int maximumBytes) throws IOException {
            long result = 0;
            for (int byteIndex = 0; byteIndex < maximumBytes; byteIndex++) {
                int next = readUnsignedByte();
                result |= (long) (next & 0x7F) << (7 * byteIndex);
                if ((next & 0x80) == 0) {
                    if (byteIndex > 0 && (next & 0x7F) == 0) {
                        throw new IOException("non-canonical v10 uvarint");
                    }
                    if (result > maximum) throw new IOException("v10 uvarint exceeds field limit");
                    return result;
                }
            }
            throw new IOException("v10 uvarint is too long");
        }

        byte[] remainingBytes() {
            byte[] remaining = Arrays.copyOfRange(data, position, data.length);
            position = data.length;
            return remaining;
        }

        void requireEnd() throws IOException {
            if (position != data.length) throw new IOException("trailing v10 semantic bytes");
        }
    }

    static final class RiceBitWriter {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int currentByte;
        private int bitCount;

        void writeBit(int value) {
            if (value != 0) currentByte |= 1 << bitCount;
            bitCount++;
            if (bitCount == 8) {
                output.write(currentByte);
                currentByte = 0;
                bitCount = 0;
            }
        }

        void writeBits(int value, int count) {
            for (int bit = 0; bit < count; bit++) writeBit((value >>> bit) & 1);
        }

        void writeRice(int value, int k) {
            int quotient = value >>> k;
            for (int index = 0; index < quotient; index++) writeBit(0);
            writeBit(1);
            if (k > 0) writeBits(value & (int) ((1L << k) - 1), k);
        }

        byte[] finish() {
            if (bitCount != 0) output.write(currentByte);
            return output.toByteArray();
        }
    }

    static final class RiceBitReader {
        private final byte[] data;
        private final long unaryZeroBudget;
        private int position;
        private long unaryZeros;

        RiceBitReader(byte[] data, long unaryZeroBudget) {
            this.data = data;
            this.unaryZeroBudget = unaryZeroBudget;
        }

        int readBit() throws IOException {
            if (position >= data.length * 8L) throw new IOException("truncated v10 Rice stream");
            int value = (data[position >>> 3] >>> (position & 7)) & 1;
            position++;
            return value;
        }

        int readBits(int count) throws IOException {
            int value = 0;
            for (int bit = 0; bit < count; bit++) value |= readBit() << bit;
            return value;
        }

        int readRice(int k) throws IOException {
            int quotient = 0;
            int maximumQuotient = MAX_ZIGZAG_DELTA >>> k;
            while (readBit() == 0) {
                quotient++;
                unaryZeros++;
                if (quotient > maximumQuotient) {
                    throw new IOException("v10 Rice quotient exceeds coordinate model");
                }
                if (unaryZeros > unaryZeroBudget) {
                    throw new IOException("v10 Rice unary work exceeds count-derived limit");
                }
            }
            long value = ((long) quotient << k) | readBits(k);
            if (value > MAX_ZIGZAG_DELTA) {
                throw new IOException("v10 Rice value exceeds coordinate model");
            }
            return (int) value;
        }

        void requireZeroPadding() throws IOException {
            int remaining = data.length * 8 - position;
            if (remaining >= 8) throw new IOException("redundant v10 Rice padding byte");
            while (position < data.length * 8) {
                if (readBit() != 0) throw new IOException("non-zero v10 Rice terminal padding");
            }
        }
    }
}
