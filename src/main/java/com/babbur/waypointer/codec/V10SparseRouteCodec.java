package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * Wire-v10 kind 5: the kind-2 coordinate primitive plus sparse waypoint flags
 * and sixteenth-block precision. Eligible body variants compete on final text.
 */
final class V10SparseRouteCodec {

    static final int CONTENT_KIND = 5;
    static final int SEMANTIC_HEADER = 0x5A;

    private static final int MAX_WAYPOINTS = V10BareRouteCodec.MAX_WAYPOINTS;
    private static final long MAX_UINT32 = 0xFFFF_FFFFL;
    private static final int STYLE_FLAGS = Waypoint.SUBWAYPOINT_STYLE_FLAGS;

    private static final int SIDE_NONE = 0;
    private static final int SIDE_PRESENCE = 1;
    private static final int SIDE_ORDINAL = 2;

    private static final int GRAMMAR_UNIFIED = 0;
    private static final int GRAMMAR_CONTROLLED = 1;

    private V10SparseRouteCodec() {}

    static boolean canEncode(WaypointGroup group, WaypointCodec.Options options) {
        if (group == null || options == null) return false;
        if (options.isBareCoordinateProjection()) return false;
        if (options.includeNames || options.includeColors || options.includeRadii
                || options.includeGroupMeta || options.includeZone || !options.label.isEmpty()) {
            return false;
        }
        if (group.routeKind() != WaypointGroup.RouteKind.REGULAR
                || group.size() > MAX_WAYPOINTS) {
            return false;
        }
        // Kind 5 has no group-name field, so the source must already be
        // anonymous. BARE_COORDINATES is handled separately by kind 2.
        if (!group.name().isEmpty()) return false;
        if (!group.isEmpty()
                && (projectedFlags(group.get(0), options) & Waypoint.FLAG_SUBWAYPOINT) != 0) {
            return false;
        }
        return hasProjectedExceptions(group, options);
    }

    static boolean hasProjectedExceptions(WaypointGroup group, WaypointCodec.Options options) {
        if (group == null || options == null) return false;
        for (Waypoint waypoint : group.waypoints()) {
            if (projectedFlags(waypoint, options) != 0
                    || WaypointCodec.shouldExportPrecisePosition(waypoint, options)) {
                return true;
            }
        }
        return false;
    }

    static String encode(WaypointGroup group, WaypointCodec.Options options) throws IOException {
        return encodeCandidate(group, options).transport();
    }

    static V10Transport.Outbound encodeCandidate(
            WaypointGroup group, WaypointCodec.Options options) throws IOException {
        if (!canEncode(group, options)) {
            throw new IllegalArgumentException("route is not an exact v10 kind-5 projection");
        }
        ProjectedRoute route = project(group, options);
        V10Transport.Outbound best = null;
        for (int coordinateMode : new int[]{V10Transport.MODE_DIRECT, V10Transport.MODE_DEFLATE}) {
            byte[] coordinateBody = V10BareRouteCodec.encodeCoordinateBody(
                    V10BareRouteCodec.coordinatesOf(group), coordinateMode);

            boolean subway = route.points.stream().anyMatch(ProjectedPoint::isSubwaypoint);
            boolean precision = route.points.stream().anyMatch(ProjectedPoint::hasCustomPrecision);
            boolean other = route.points.stream().anyMatch(point -> otherFlags(point.flags) != 0);
            for (int subwayMode : sideChoices(subway)) {
                for (int precisionMode : sideChoices(precision)) {
                    for (int otherMode : sideChoices(other)) {
                        best = choose(best, frame(
                                encodeSplit(route, coordinateBody,
                                        subwayMode, precisionMode, otherMode),
                                coordinateMode, Deflater.DEFAULT_STRATEGY));
                        if (coordinateMode == V10Transport.MODE_DEFLATE) {
                            best = choose(best, frame(
                                    encodeSplit(route, coordinateBody,
                                            subwayMode, precisionMode, otherMode),
                                    coordinateMode, Deflater.FILTERED));
                        }
                    }
                }
            }

            byte[] unified = encodeUnified(route, coordinateBody, false);
            best = choose(best, frame(unified, coordinateMode, Deflater.DEFAULT_STRATEGY));
            if (coordinateMode == V10Transport.MODE_DEFLATE) {
                best = choose(best, frame(unified, coordinateMode, Deflater.FILTERED));
            }

            byte[] controlled = encodeUnified(route, coordinateBody, true);
            best = choose(best, frame(controlled, coordinateMode, Deflater.DEFAULT_STRATEGY));
            if (coordinateMode == V10Transport.MODE_DEFLATE) {
                best = choose(best, frame(controlled, coordinateMode, Deflater.FILTERED));
            }
        }
        if (best == null) throw new IllegalStateException("v10 kind-5 produced no candidates");
        return best;
    }

    static WaypointGroup decode(V10Transport.CheckedFrame frame) throws IOException {
        if (frame.contentKind() != CONTENT_KIND) {
            throw new IOException("unsupported v10 content kind " + frame.contentKind());
        }
        byte[] semantic = frame.semantic();
        Reader reader = new Reader(semantic);
        if (reader.readUnsignedByte() != SEMANTIC_HEADER) {
            throw new IOException("v10 kind-5 semantic header mismatch");
        }

        int selector = (int) reader.readUVarint(V10Transport.MAX_FRAME_BYTES, 4);
        Metadata metadata;
        int[][] coordinates;
        if (selector == GRAMMAR_UNIFIED || selector == GRAMMAR_CONTROLLED) {
            metadata = selector == GRAMMAR_CONTROLLED
                    ? decodeControlled(reader, MAX_WAYPOINTS)
                    : decodeUnified(reader, MAX_WAYPOINTS);
            byte[] coordinateBody = reader.remainingBytes();
            if (coordinateBody.length == 0) throw new IOException("empty v10 coordinate body");
            coordinates = V10BareRouteCodec.decodeCoordinateBody(coordinateBody, frame.mode());
            metadata.requireIndicesWithin(coordinates.length);
        } else {
            byte[] coordinateBody = reader.read(selector);
            coordinates = V10BareRouteCodec.decodeCoordinateBody(coordinateBody, frame.mode());
            int sideHeader = reader.readUnsignedByte();
            if ((sideHeader & 0xC0) != 0) {
                throw new IOException("reserved v10 kind-5 split selector bit is set");
            }
            int subwayMode = sideHeader & 3;
            int precisionMode = (sideHeader >>> 2) & 3;
            int otherMode = (sideHeader >>> 4) & 3;
            if (subwayMode == 3 || precisionMode == 3 || otherMode == 3) {
                throw new IOException("reserved v10 kind-5 side-stream mode");
            }
            metadata = decodeSplit(reader, coordinates.length,
                    subwayMode, precisionMode, otherMode);
        }
        reader.requireEnd();
        return buildGroup(coordinates, metadata);
    }

    private static V10Transport.Outbound frame(
            byte[] semantic, int coordinateMode, int strategy) throws IOException {
        return coordinateMode == V10Transport.MODE_DIRECT
                ? V10Transport.direct(semantic)
                : V10Transport.deflated(semantic, strategy);
    }

    private static V10Transport.Outbound choose(
            V10Transport.Outbound current, V10Transport.Outbound candidate) {
        return current == null || candidate.compareTo(current) < 0 ? candidate : current;
    }

    private static ProjectedRoute project(
            WaypointGroup group, WaypointCodec.Options options) {
        List<ProjectedPoint> points = new ArrayList<>(group.size());
        for (Waypoint waypoint : group.waypoints()) {
            int flags = projectedFlags(waypoint, options);
            boolean precise = WaypointCodec.shouldExportPrecisePosition(waypoint, options);
            points.add(new ProjectedPoint(
                    waypoint.x(), waypoint.y(), waypoint.z(), flags,
                    precise ? Math.floorMod(waypoint.preciseX(), Waypoint.PRECISE_SCALE) : 8,
                    precise ? Math.floorMod(waypoint.preciseY(), Waypoint.PRECISE_SCALE) : 8,
                    precise ? Math.floorMod(waypoint.preciseZ(), Waypoint.PRECISE_SCALE) : 8));
        }
        return new ProjectedRoute(List.copyOf(points));
    }

    private static int projectedFlags(Waypoint waypoint, WaypointCodec.Options options) {
        return WaypointCodec.exportedWaypointFlags(waypoint, options);
    }

    private static long unsigned(int flags) {
        return Integer.toUnsignedLong(flags);
    }

    private static long otherFlags(int flags) {
        long value = unsigned(flags);
        long specialized = (value & Waypoint.FLAG_SUBWAYPOINT) != 0
                ? Waypoint.FLAG_SUBWAYPOINT | (long) STYLE_FLAGS
                : Waypoint.FLAG_SUBWAYPOINT;
        return value & ~specialized & MAX_UINT32;
    }

    private static int[] sideChoices(boolean present) {
        return present
                ? new int[]{SIDE_PRESENCE, SIDE_ORDINAL}
                : new int[]{SIDE_NONE};
    }

    private static byte[] encodeSplit(
            ProjectedRoute route, byte[] coordinateBody,
            int subwayMode, int precisionMode, int otherMode) {
        if (coordinateBody.length <= 1) {
            throw new IllegalArgumentException("split coordinate body collides with unified selector");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(SEMANTIC_HEADER);
        writeUVarint(output, coordinateBody.length);
        output.writeBytes(coordinateBody);
        output.write(subwayMode | (precisionMode << 2) | (otherMode << 4));
        output.writeBytes(encodeSubway(route, subwayMode));
        output.writeBytes(encodePrecision(route, precisionMode));
        output.writeBytes(encodeOther(route, otherMode));
        return output.toByteArray();
    }

    private static byte[] encodeUnified(
            ProjectedRoute route, byte[] coordinateBody, boolean controlled) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(SEMANTIC_HEADER);
        writeUVarint(output, controlled ? GRAMMAR_CONTROLLED : GRAMMAR_UNIFIED);
        output.writeBytes(controlled ? encodeControlled(route) : encodeUnifiedMetadata(route));
        output.writeBytes(coordinateBody);
        return output.toByteArray();
    }

    private static byte[] encodeSubway(ProjectedRoute route, int mode) {
        List<Integer> indices = matchingIndices(route, ProjectedPoint::isSubwaypoint);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(encodeIndices(indices, route.points.size(), mode));
        BitWriter styles = new BitWriter();
        for (int index : indices) {
            styles.writeBits((route.points.get(index).flags & STYLE_FLAGS) >>> 5, 3);
        }
        output.writeBytes(styles.finish());
        return output.toByteArray();
    }

    private static byte[] encodePrecision(ProjectedRoute route, int mode) {
        List<Integer> indices = matchingIndices(route, ProjectedPoint::hasCustomPrecision);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(encodeIndices(indices, route.points.size(), mode));
        BitWriter offsets = new BitWriter();
        for (int index : indices) {
            ProjectedPoint point = route.points.get(index);
            offsets.writeBits((point.offsetX << 8) | (point.offsetY << 4) | point.offsetZ, 12);
        }
        output.writeBytes(offsets.finish());
        return output.toByteArray();
    }

    private static byte[] encodeOther(ProjectedRoute route, int mode) {
        List<Integer> indices = matchingIndices(route, point -> otherFlags(point.flags) != 0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(encodeIndices(indices, route.points.size(), mode));
        for (int index : indices) writeUVarint(output, otherFlags(route.points.get(index).flags));
        return output.toByteArray();
    }

    private static byte[] encodeUnifiedMetadata(ProjectedRoute route) {
        List<Integer> indices = matchingIndices(route, ProjectedPoint::isException);
        if (indices.isEmpty()) throw new IllegalArgumentException("kind-5 requires an exception");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(encodeOrdinals(indices));
        BitWriter bits = new BitWriter();
        List<Long> others = new ArrayList<>();
        for (int index : indices) {
            ProjectedPoint point = route.points.get(index);
            bits.writeBit(point.isSubwaypoint() ? 1 : 0);
            if (point.isSubwaypoint()) bits.writeBits((point.flags & STYLE_FLAGS) >>> 5, 3);
            long other = otherFlags(point.flags);
            bits.writeBit(other != 0 ? 1 : 0);
            if (other != 0) others.add(other);
            writeResiduals(bits, point);
        }
        output.writeBytes(bits.finish());
        for (long other : others) writeUVarint(output, other);
        return output.toByteArray();
    }

    private static byte[] encodeControlled(ProjectedRoute route) {
        List<Integer> indices = matchingIndices(route, ProjectedPoint::isException);
        if (indices.isEmpty()) throw new IllegalArgumentException("kind-5 requires an exception");
        boolean anySubway = false;
        boolean allSubway = true;
        boolean anyOther = false;
        boolean allOther = true;
        boolean anyPrecision = false;
        for (int index : indices) {
            ProjectedPoint point = route.points.get(index);
            anySubway |= point.isSubwaypoint();
            allSubway &= point.isSubwaypoint();
            boolean hasOther = otherFlags(point.flags) != 0;
            anyOther |= hasOther;
            allOther &= hasOther;
            anyPrecision |= point.hasCustomPrecision();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(encodeOrdinals(indices));
        BitWriter bits = new BitWriter();
        bits.writeBit(anySubway ? 1 : 0);
        if (anySubway) bits.writeBit(allSubway ? 1 : 0);
        bits.writeBit(anyOther ? 1 : 0);
        if (anyOther) bits.writeBit(allOther ? 1 : 0);
        bits.writeBit(anyPrecision ? 1 : 0);
        for (int index : indices) {
            ProjectedPoint point = route.points.get(index);
            if (anySubway && !allSubway) bits.writeBit(point.isSubwaypoint() ? 1 : 0);
            if (point.isSubwaypoint()) bits.writeBits((point.flags & STYLE_FLAGS) >>> 5, 3);
            boolean hasOther = otherFlags(point.flags) != 0;
            if (anyOther && !allOther) bits.writeBit(hasOther ? 1 : 0);
            if (anyPrecision) writeResiduals(bits, point);
        }
        output.writeBytes(bits.finish());
        for (int index : indices) {
            long other = otherFlags(route.points.get(index).flags);
            if (other != 0) writeUVarint(output, other);
        }
        return output.toByteArray();
    }

    private static void writeResiduals(BitWriter bits, ProjectedPoint point) {
        int[] residuals = {point.offsetX - 8, point.offsetY - 8, point.offsetZ - 8};
        int mask = 0;
        for (int axis = 0; axis < 3; axis++) if (residuals[axis] != 0) mask |= 1 << axis;
        bits.writeBits(mask, 3);
        for (int axis = 0; axis < 3; axis++) {
            if ((mask & (1 << axis)) != 0) bits.writeBits(residuals[axis] & 15, 4);
        }
    }

    private static Metadata decodeSplit(
            Reader reader, int count, int subwayMode, int precisionMode, int otherMode)
            throws IOException {
        List<Integer> subwayIndices = decodeIndices(reader, count, subwayMode, "subwaypoint");
        if (!subwayIndices.isEmpty() && subwayIndices.get(0) == 0) {
            throw new IOException("the first waypoint cannot be a subwaypoint");
        }
        List<Integer> styles = readPacked(reader, subwayIndices.size(), 3, "subwaypoint style");
        Map<Integer, Integer> flags = new HashMap<>();
        for (int index = 0; index < subwayIndices.size(); index++) {
            flags.put(subwayIndices.get(index), Waypoint.FLAG_SUBWAYPOINT | (styles.get(index) << 5));
        }

        List<Integer> precisionIndices = decodeIndices(reader, count, precisionMode, "precision");
        List<Integer> packedPrecision = readPacked(reader, precisionIndices.size(), 12, "precision");
        Map<Integer, int[]> precision = new HashMap<>();
        for (int index = 0; index < precisionIndices.size(); index++) {
            int value = packedPrecision.get(index);
            int[] offsets = {(value >>> 8) & 15, (value >>> 4) & 15, value & 15};
            if (Arrays.equals(offsets, new int[]{8, 8, 8})) {
                throw new IOException("default precision side record is non-canonical");
            }
            precision.put(precisionIndices.get(index), offsets);
        }

        List<Integer> otherIndices = decodeIndices(reader, count, otherMode, "other flags");
        for (int index : otherIndices) {
            long other = reader.readUVarint(MAX_UINT32, 5);
            if (other == 0) throw new IOException("zero other-flags value is non-canonical");
            long forbidden = Waypoint.FLAG_SUBWAYPOINT
                    | (flags.containsKey(index) ? (long) STYLE_FLAGS : 0L);
            if ((other & forbidden) != 0) {
                throw new IOException("split other flags overlap specialized flags");
            }
            flags.put(index, flags.getOrDefault(index, 0) | (int) other);
        }

        if (subwayMode != SIDE_NONE && subwayIndices.isEmpty()) {
            throw new IOException("non-canonical empty subwaypoint stream");
        }
        if (precisionMode != SIDE_NONE && precisionIndices.isEmpty()) {
            throw new IOException("non-canonical empty precision stream");
        }
        if (otherMode != SIDE_NONE && otherIndices.isEmpty()) {
            throw new IOException("non-canonical empty other-flags stream");
        }
        if (flags.isEmpty() && precision.isEmpty()) {
            throw new IOException("kind-5 split grammar requires an exception");
        }
        return new Metadata(flags, precision);
    }

    private static Metadata decodeUnified(Reader reader, int countLimit) throws IOException {
        List<Integer> indices = readOrdinals(reader, countLimit, "unified exception");
        if (indices.isEmpty()) throw new IOException("kind-5 unified grammar requires an exception");
        MetadataBits bits = new MetadataBits(reader);
        List<DecodedRecord> records = new ArrayList<>(indices.size());
        for (int index : indices) {
            boolean subway = bits.readBits(1) != 0;
            int styles = subway ? bits.readBits(3) << 5 : 0;
            boolean hasOther = bits.readBits(1) != 0;
            int[] offsets = readResiduals(bits);
            records.add(new DecodedRecord(index, subway, styles, hasOther, offsets));
        }
        bits.finish();
        return finishUnified(reader, records, false, false, false);
    }

    private static Metadata decodeControlled(Reader reader, int countLimit) throws IOException {
        List<Integer> indices = readOrdinals(reader, countLimit, "controlled unified exception");
        if (indices.isEmpty()) throw new IOException("kind-5 controlled grammar requires an exception");
        MetadataBits bits = new MetadataBits(reader);
        boolean anySubway = bits.readBits(1) != 0;
        boolean allSubway = anySubway && bits.readBits(1) != 0;
        boolean anyOther = bits.readBits(1) != 0;
        boolean allOther = anyOther && bits.readBits(1) != 0;
        boolean anyPrecision = bits.readBits(1) != 0;
        boolean sawSubway = false;
        boolean sawNonSubway = false;
        boolean sawOther = false;
        boolean sawNoOther = false;
        boolean sawPrecision = false;
        List<DecodedRecord> records = new ArrayList<>(indices.size());
        for (int index : indices) {
            boolean subway = anySubway && (allSubway || bits.readBits(1) != 0);
            int styles = subway ? bits.readBits(3) << 5 : 0;
            boolean hasOther = anyOther && (allOther || bits.readBits(1) != 0);
            int[] offsets = anyPrecision ? readResiduals(bits) : null;
            sawSubway |= subway;
            sawNonSubway |= !subway;
            sawOther |= hasOther;
            sawNoOther |= !hasOther;
            sawPrecision |= offsets != null;
            records.add(new DecodedRecord(index, subway, styles, hasOther, offsets));
        }
        bits.finish();
        if (anySubway && !allSubway && !(sawSubway && sawNonSubway)) {
            throw new IOException("non-canonical mixed subway control");
        }
        if (anyOther && !allOther && !(sawOther && sawNoOther)) {
            throw new IOException("non-canonical mixed other-flags control");
        }
        if (anyPrecision && !sawPrecision) {
            throw new IOException("non-canonical empty precision control");
        }
        return finishUnified(reader, records, anySubway, anyOther, anyPrecision);
    }

    private static Metadata finishUnified(
            Reader reader, List<DecodedRecord> records,
            boolean ignoredSubwayControl, boolean ignoredOtherControl,
            boolean ignoredPrecisionControl) throws IOException {
        Map<Integer, Integer> flags = new HashMap<>();
        Map<Integer, int[]> precision = new HashMap<>();
        for (DecodedRecord record : records) {
            int value = (record.subway ? Waypoint.FLAG_SUBWAYPOINT : 0) | record.styles;
            if (record.hasOther) {
                long other = reader.readUVarint(MAX_UINT32, 5);
                if (other == 0) throw new IOException("zero unified other-flags value is non-canonical");
                long forbidden = Waypoint.FLAG_SUBWAYPOINT
                        | (record.subway ? (long) STYLE_FLAGS : 0L);
                if ((other & forbidden) != 0) {
                    throw new IOException("unified other flags overlap specialized flags");
                }
                value |= (int) other;
            }
            if (value == 0 && record.offsets == null) {
                throw new IOException("no-op unified exception is non-canonical");
            }
            if (value != 0) flags.put(record.index, value);
            if (record.offsets != null) precision.put(record.index, record.offsets);
        }
        if ((flags.getOrDefault(0, 0) & Waypoint.FLAG_SUBWAYPOINT) != 0) {
            throw new IOException("the first waypoint cannot be a subwaypoint");
        }
        return new Metadata(flags, precision);
    }

    private static int[] readResiduals(MetadataBits bits) throws IOException {
        int mask = bits.readBits(3);
        if (mask == 0) return null;
        int[] offsets = {8, 8, 8};
        for (int axis = 0; axis < 3; axis++) {
            if ((mask & (1 << axis)) == 0) continue;
            int nibble = bits.readBits(4);
            int residual = nibble < 8 ? nibble : nibble - 16;
            if (residual == 0) {
                throw new IOException("zero precision residual is non-canonical");
            }
            offsets[axis] += residual;
            if (offsets[axis] < 0 || offsets[axis] > 15) {
                throw new IOException("precision residual reconstructs outside block");
            }
        }
        return offsets;
    }

    private static WaypointGroup buildGroup(int[][] coordinates, Metadata metadata) throws IOException {
        WaypointGroup group = WaypointGroup.create("", Zone.UNKNOWN.id());
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        List<Waypoint> points = new ArrayList<>(coordinates.length);
        for (int index = 0; index < coordinates.length; index++) {
            int[] coordinate = coordinates[index];
            int flags = metadata.flags.getOrDefault(index, 0);
            if (index == 0 && (flags & Waypoint.FLAG_SUBWAYPOINT) != 0) {
                throw new IOException("the first waypoint cannot be a subwaypoint");
            }
            Waypoint point = Waypoint.at(coordinate[0], coordinate[1], coordinate[2])
                    .withFlags(flags);
            int[] offsets = metadata.precision.get(index);
            if (offsets != null) {
                point = point.withPreciseSixteenths(
                        coordinate[0] * Waypoint.PRECISE_SCALE + offsets[0],
                        coordinate[1] * Waypoint.PRECISE_SCALE + offsets[1],
                        coordinate[2] * Waypoint.PRECISE_SCALE + offsets[2]);
            }
            points.add(point);
        }
        group.addAll(points);
        return group;
    }

    private static byte[] encodeIndices(List<Integer> indices, int count, int mode) {
        if (mode == SIDE_NONE) {
            if (!indices.isEmpty()) throw new IllegalArgumentException("nonempty side stream uses none mode");
            return new byte[0];
        }
        if (mode == SIDE_PRESENCE) {
            byte[] bitmap = new byte[(count + 7) / 8];
            for (int index : indices) bitmap[index >>> 3] |= (byte) (1 << (index & 7));
            return bitmap;
        }
        if (mode == SIDE_ORDINAL) return encodeOrdinals(indices);
        throw new IllegalArgumentException("unknown v10 kind-5 side mode");
    }

    private static List<Integer> decodeIndices(
            Reader reader, int count, int mode, String field) throws IOException {
        if (mode == SIDE_NONE) return List.of();
        if (mode == SIDE_ORDINAL) return readOrdinals(reader, count, field);
        if (mode != SIDE_PRESENCE) throw new IOException("unknown " + field + " side mode");
        byte[] bitmap = reader.read((count + 7) / 8);
        if ((count & 7) != 0 && bitmap.length != 0
                && ((bitmap[bitmap.length - 1] & 0xFF) >>> (count & 7)) != 0) {
            throw new IOException("non-zero " + field + " presence padding");
        }
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            if ((bitmap[index >>> 3] & (1 << (index & 7))) != 0) indices.add(index);
        }
        return indices;
    }

    private static byte[] encodeOrdinals(List<Integer> indices) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeUVarint(output, indices.size());
        int previous = -1;
        for (int index : indices) {
            writeUVarint(output, index - previous - 1L);
            previous = index;
        }
        return output.toByteArray();
    }

    private static List<Integer> readOrdinals(
            Reader reader, int pointCount, String field) throws IOException {
        int itemCount = (int) reader.readUVarint(pointCount, 3);
        List<Integer> indices = new ArrayList<>(itemCount);
        int previous = -1;
        for (int item = 0; item < itemCount; item++) {
            int gap = (int) reader.readUVarint(pointCount, 3);
            long index = (long) previous + gap + 1L;
            if (index >= pointCount) throw new IOException(field + " ordinal is outside the route");
            indices.add((int) index);
            previous = (int) index;
        }
        return indices;
    }

    private static List<Integer> readPacked(
            Reader reader, int count, int width, String field) throws IOException {
        byte[] data = reader.read((int) (((long) count * width + 7) / 8));
        PackedBits bits = new PackedBits(data);
        List<Integer> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) values.add(bits.readBits(width));
        bits.requireZeroPadding(field);
        return values;
    }

    private static List<Integer> matchingIndices(
            ProjectedRoute route, java.util.function.Predicate<ProjectedPoint> predicate) {
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < route.points.size(); index++) {
            if (predicate.test(route.points.get(index))) indices.add(index);
        }
        return indices;
    }

    private static void writeUVarint(ByteArrayOutputStream output, long value) {
        if (value < 0) throw new IllegalArgumentException("negative v10 kind-5 uvarint");
        do {
            int next = (int) (value & 0x7F);
            value >>>= 7;
            output.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    private record ProjectedRoute(List<ProjectedPoint> points) {}

    private record ProjectedPoint(
            int x, int y, int z, int flags, int offsetX, int offsetY, int offsetZ) {
        boolean isSubwaypoint() {
            return (flags & Waypoint.FLAG_SUBWAYPOINT) != 0;
        }

        boolean hasCustomPrecision() {
            return offsetX != 8 || offsetY != 8 || offsetZ != 8;
        }

        boolean isException() {
            return flags != 0 || hasCustomPrecision();
        }
    }

    private record DecodedRecord(
            int index, boolean subway, int styles, boolean hasOther, int[] offsets) {}

    private record Metadata(Map<Integer, Integer> flags, Map<Integer, int[]> precision) {
        void requireIndicesWithin(int count) throws IOException {
            for (int index : flags.keySet()) {
                if (index >= count) throw new IOException("v10 kind-5 flag ordinal is outside route");
            }
            for (int index : precision.keySet()) {
                if (index >= count) throw new IOException("v10 kind-5 precision ordinal is outside route");
            }
        }
    }

    private static final class Reader {
        private final byte[] data;
        private int position;

        Reader(byte[] data) {
            if (data == null) throw new IllegalArgumentException("null v10 kind-5 body");
            this.data = data;
        }

        int readUnsignedByte() throws IOException {
            if (position >= data.length) throw new IOException("truncated v10 kind-5 body");
            return data[position++] & 0xFF;
        }

        byte[] read(int count) throws IOException {
            if (count < 0 || count > data.length - position) {
                throw new IOException("truncated v10 kind-5 body");
            }
            byte[] value = Arrays.copyOfRange(data, position, position + count);
            position += count;
            return value;
        }

        long readUVarint(long maximum, int maximumBytes) throws IOException {
            long result = 0;
            for (int byteIndex = 0; byteIndex < maximumBytes; byteIndex++) {
                int next = readUnsignedByte();
                result |= (long) (next & 0x7F) << (byteIndex * 7);
                if ((next & 0x80) == 0) {
                    if (byteIndex > 0 && (next & 0x7F) == 0) {
                        throw new IOException("non-canonical v10 kind-5 uvarint");
                    }
                    if (result > maximum) throw new IOException("v10 kind-5 uvarint exceeds limit");
                    return result;
                }
            }
            throw new IOException("v10 kind-5 uvarint is too long");
        }

        byte[] remainingBytes() {
            byte[] remaining = Arrays.copyOfRange(data, position, data.length);
            position = data.length;
            return remaining;
        }

        void requireEnd() throws IOException {
            if (position != data.length) throw new IOException("trailing v10 kind-5 bytes");
        }
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int current;
        private int used;

        void writeBit(int value) {
            if (value != 0) current |= 1 << used;
            used++;
            if (used == 8) flush();
        }

        void writeBits(int value, int count) {
            for (int bit = 0; bit < count; bit++) writeBit((value >>> bit) & 1);
        }

        byte[] finish() {
            if (used != 0) flush();
            return output.toByteArray();
        }

        private void flush() {
            output.write(current);
            current = 0;
            used = 0;
        }
    }

    private static final class PackedBits {
        private final byte[] data;
        private int position;

        PackedBits(byte[] data) {
            this.data = data;
        }

        int readBits(int count) throws IOException {
            int value = 0;
            for (int bit = 0; bit < count; bit++) {
                if (position >= data.length * 8L) throw new IOException("truncated packed v10 metadata");
                value |= ((data[position >>> 3] >>> (position & 7)) & 1) << bit;
                position++;
            }
            return value;
        }

        void requireZeroPadding(String field) throws IOException {
            while (position < data.length * 8) {
                if (readBits(1) != 0) throw new IOException("non-zero " + field + " padding");
            }
        }
    }

    private static final class MetadataBits {
        private final Reader reader;
        private final int start;
        private int position;

        MetadataBits(Reader reader) {
            this.reader = reader;
            this.start = reader.position;
        }

        int readBits(int count) throws IOException {
            int value = 0;
            for (int bit = 0; bit < count; bit++) {
                int absolute = position + bit;
                int byteIndex = start + (absolute >>> 3);
                if (byteIndex >= reader.data.length) {
                    throw new IOException("truncated unified v10 metadata bits");
                }
                value |= ((reader.data[byteIndex] >>> (absolute & 7)) & 1) << bit;
            }
            position += count;
            return value;
        }

        void finish() throws IOException {
            int byteCount = (position + 7) / 8;
            if (start + byteCount > reader.data.length) {
                throw new IOException("truncated unified v10 metadata bits");
            }
            int used = position & 7;
            if (used != 0 && ((reader.data[start + byteCount - 1] & 0xFF) >>> used) != 0) {
                throw new IOException("non-zero unified v10 metadata padding");
            }
            reader.position = start + byteCount;
        }
    }
}
