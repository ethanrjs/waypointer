package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Wire-v10 kind 1: one compact route with either full or no-names semantics. */
final class V10CompactRouteCodec {

    static final int CONTENT_KIND = 1;
    static final int SEMANTIC_HEADER = (CONTENT_KIND << 4) | WaypointCodec.V10_WIRE_VERSION;
    static final int SUBTYPE_FULL = 0;
    static final int SUBTYPE_NO_NAMES = 1;

    private static final int CONTROL_SUBTYPE_MASK = 1;
    private static final int CONTROL_SKIP_AHEAD_DISABLED = 1 << 1;
    private static final int CONTROL_FLAGS_PRESENT = 1 << 2;
    private static final int CONTROL_STATIC_LOAD = 1 << 3;
    private static final int CONTROL_RADIUS_PRESENT = 1 << 4;
    private static final int CONTROL_GRADIENT_SHIFT = 5;
    private static final int CONTROL_GROUP_COLORS_PRESENT = 1 << 7;
    private static final int MAX_INLINE_ZONE_BYTES = 1 << 20;
    private static final long MAX_UINT32 = 0xFFFF_FFFFL;

    private V10CompactRouteCodec() {}

    static boolean canEncode(WaypointGroup group, WaypointCodec.Options options) {
        return canEncodeFull(group, options) || canEncodeNoNames(group, options);
    }

    private static boolean canEncodeFull(WaypointGroup group, WaypointCodec.Options options) {
        if (group == null || options == null || !options.includeNames || !options.includeColors
                || !options.includeZone || !options.label.isEmpty()) {
            return false;
        }
        boolean full = options.includeRadii && options.includeWaypointFlags && options.includeGroupMeta;
        if (!full && !hasUnchangedCommonProjection(group, options)) return false;
        return V9CompactCodec.canEncode(normalizedFull(group), WaypointCodec.Options.FULL_FIDELITY);
    }

    /** The full body also represents this partial export when every omitted field is unchanged. */
    private static boolean hasUnchangedCommonProjection(
            WaypointGroup group, WaypointCodec.Options options) {
        if (options.includeRadii || options.includeWaypointFlags || options.includeGroupMeta
                || group.routeKind() != WaypointGroup.RouteKind.REGULAR
                || group.gradientMode() != WaypointGroup.GradientMode.MANUAL
                || group.loadMode() != WaypointGroup.LoadMode.SEQUENCE
                || Double.compare(group.defaultRadius(), Waypoint.DEFAULT_REACH_RADIUS) != 0
                || !group.skipAheadEnabled()
                || group.staticColor() != Waypoint.DEFAULT_COLOR
                || group.gradientStartColor() != 0x00BFFF
                || group.gradientEndColor() != 0xFF3040) {
            return false;
        }
        for (Waypoint waypoint : group.waypoints()) {
            if (waypoint.customRadius() != 0.0
                    || (waypoint.color() & 0xFF000000) != 0
                    || waypoint.flags() != WaypointCodec.exportedWaypointFlags(waypoint, options)
                    || waypoint.hasCustomPrecisePosition()
                            != WaypointCodec.shouldExportPrecisePosition(waypoint, options)) {
                return false;
            }
        }
        return true;
    }

    private static boolean canEncodeNoNames(WaypointGroup group, WaypointCodec.Options options) {
        if (group == null || options == null || options.includeNames || options.includeColors
                || options.includeRadii || options.includeWaypointFlags || !options.includeGroupMeta
                || !options.includeZone || !options.label.isEmpty()
                || group.size() > V10BareRouteCodec.MAX_WAYPOINTS
                || !hasEncodableZone(group.zoneId())) {
            return false;
        }
        return true;
    }

    private static WaypointGroup normalizedFull(WaypointGroup group) {
        WaypointGroup normalized = group.exportSnapshot();
        normalized.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        normalized.setStaticColor(Waypoint.DEFAULT_COLOR);
        normalized.setGradientStartColor(0x00BFFF);
        normalized.setGradientEndColor(0xFF3040);
        normalized.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        normalized.setDefaultRadius(Waypoint.DEFAULT_REACH_RADIUS);
        normalized.setSkipAheadEnabled(true);
        List<Waypoint> points = group.waypoints().stream()
                .map(waypoint -> waypoint.withFlags(0).withPreciseSixteenths(
                        Waypoint.preciseBlockCenter(waypoint.x()),
                        Waypoint.preciseBlockCenter(waypoint.y()),
                        Waypoint.preciseBlockCenter(waypoint.z())))
                .toList();
        normalized.replaceWaypoints(points);
        return normalized;
    }

    private static boolean hasEncodableZone(String zoneId) {
        int length = WaypointCodec.strictUtf8Length(zoneId);
        return length >= 0 && length <= MAX_INLINE_ZONE_BYTES;
    }

    static String encode(WaypointGroup group, WaypointCodec.Options options) throws IOException {
        return encodeCandidate(group, options).transport();
    }

    static V10Transport.Outbound encodeCandidate(
            WaypointGroup group, WaypointCodec.Options options) throws IOException {
        if (canEncodeFull(group, options)) {
            return V10GeneralRouteCodec.selectCandidate(
                    encodeFullSemantic(group, WaypointCodec.Options.FULL_FIDELITY));
        }
        if (canEncodeNoNames(group, options)) return selectNoNamesCandidate(group);
        throw new IllegalArgumentException("route is not an exact v10 kind-1 projection");
    }

    static WaypointGroup decode(V10Transport.CheckedFrame frame) throws IOException {
        if (frame.contentKind() != CONTENT_KIND) {
            throw new IOException("unsupported v10 content kind " + frame.contentKind());
        }
        byte[] semantic = frame.semantic();
        if (semantic.length < 2
                || semantic.length + V10Transport.CHECKSUM_BYTES > V10Transport.MAX_FRAME_BYTES
                || (semantic[0] & 0xFF) != SEMANTIC_HEADER) {
            throw new IOException("v10 kind-1 semantic header mismatch");
        }
        int control = semantic[1] & 0xFF;
        int gradientId = (control >>> CONTROL_GRADIENT_SHIFT) & 3;
        if (gradientId == 3) {
            throw new IOException("reserved v10 kind-1 gradient id");
        }
        int subtype = control & CONTROL_SUBTYPE_MASK;
        WaypointGroup group = switch (subtype) {
            case SUBTYPE_FULL -> decodeFull(semantic, control);
            case SUBTYPE_NO_NAMES -> decodeNoNames(semantic, control);
            default -> throw new IOException("unsupported v10 kind-1 subtype " + subtype);
        };
        byte[] canonical = subtype == SUBTYPE_FULL
                ? encodeFullSemantic(group, WaypointCodec.Options.FULL_FIDELITY)
                : canonicalNoNamesSemantic(group);
        if (!Arrays.equals(semantic, canonical)) {
            throw new IOException("non-canonical v10 kind-1 semantic body");
        }
        return group;
    }

    private static byte[] encodeFullSemantic(
            WaypointGroup group, WaypointCodec.Options options) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(SEMANTIC_HEADER);
        int control = control(group, options, SUBTYPE_FULL);
        output.write(control);
        writeZone(output, group.zoneId(), false);
        writeGroupName(output, group.name(), group.zoneId());
        output.writeBytes(V9CompactCodec.encodeWaypointPayload(normalizedFull(group)));
        writeFlagStream(output, group, options, control);
        writeRadius(output, group, control);
        writeGroupColors(output, group, control);
        writePrecisePositions(output, group, options);
        return output.toByteArray();
    }

    private static byte[] encodeNoNamesSemantic(
            WaypointGroup group, byte[] coordinates) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(SEMANTIC_HEADER);
        int control = control(group, WaypointCodec.Options.NO_NAMES, SUBTYPE_NO_NAMES);
        output.write(control);
        writeZone(output, group.zoneId(), true);
        writeUVarint(output, coordinates.length);
        output.writeBytes(coordinates);
        writeFlagStream(output, group, WaypointCodec.Options.NO_NAMES, control);
        writeRadius(output, group, control);
        writeGroupColors(output, group, control);
        writePrecisePositions(output, group, WaypointCodec.Options.NO_NAMES);
        return output.toByteArray();
    }

    private static int control(
            WaypointGroup group, WaypointCodec.Options options, int subtype) {
        int control = subtype;
        if (!group.skipAheadEnabled()) control |= CONTROL_SKIP_AHEAD_DISABLED;
        if (group.loadMode() == WaypointGroup.LoadMode.STATIC) control |= CONTROL_STATIC_LOAD;
        if (Double.compare(group.defaultRadius(), Waypoint.DEFAULT_REACH_RADIUS) != 0) {
            control |= CONTROL_RADIUS_PRESENT;
        }
        if (options.includeColors) {
            control |= switch (group.gradientMode()) {
                case STATIC -> 0;
                case AUTO -> 1 << CONTROL_GRADIENT_SHIFT;
                case MANUAL -> 2 << CONTROL_GRADIENT_SHIFT;
            };
            if (group.staticColor() != Waypoint.DEFAULT_COLOR
                    || group.gradientStartColor() != 0x00BFFF
                    || group.gradientEndColor() != 0xFF3040) {
                control |= CONTROL_GROUP_COLORS_PRESENT;
            }
        } else {
            control |= 2 << CONTROL_GRADIENT_SHIFT;
        }
        for (Waypoint waypoint : group.waypoints()) {
            if (WaypointCodec.exportedWaypointFlags(waypoint, options) != 0) {
                return control | CONTROL_FLAGS_PRESENT;
            }
        }
        return control;
    }

    private static void writeFlagStream(
            ByteArrayOutputStream output, WaypointGroup group,
            WaypointCodec.Options options, int control) {
        if ((control & CONTROL_FLAGS_PRESENT) == 0) return;
        List<Integer> palette = new ArrayList<>();
        int[] indexes = new int[group.size()];
        for (int index = 0; index < group.size(); index++) {
            int flags = WaypointCodec.exportedWaypointFlags(group.get(index), options);
            int paletteIndex = palette.indexOf(flags);
            if (paletteIndex < 0) {
                paletteIndex = palette.size();
                palette.add(flags);
            }
            indexes[index] = paletteIndex;
        }
        writeUVarint(output, palette.size());
        for (int flags : palette) writeUVarint(output, Integer.toUnsignedLong(flags));
        int bitsPerIndex = 32 - Integer.numberOfLeadingZeros(palette.size() - 1);
        writePackedIndexes(output, indexes, bitsPerIndex);
    }

    private static void writeRadius(
            ByteArrayOutputStream output, WaypointGroup group, int control) throws IOException {
        if ((control & CONTROL_RADIUS_PRESENT) == 0) return;
        new DataOutputStream(output).writeDouble(group.defaultRadius());
    }

    private static void writePrecisePositions(
            ByteArrayOutputStream output, WaypointGroup group, WaypointCodec.Options options) {
        int count = 0;
        for (Waypoint waypoint : group.waypoints()) {
            if (WaypointCodec.shouldExportPrecisePosition(waypoint, options)) count++;
        }
        if (count == 0) return;
        writeUVarint(output, count);
        int previousIndex = -1;
        for (int index = 0; index < group.size(); index++) {
            Waypoint waypoint = group.get(index);
            if (!WaypointCodec.shouldExportPrecisePosition(waypoint, options)) continue;
            writeUVarint(output, index - previousIndex - 1);
            writeSVarint(output, (long) waypoint.preciseX()
                    - Waypoint.preciseBlockCenter(waypoint.x()));
            writeSVarint(output, (long) waypoint.preciseY()
                    - Waypoint.preciseBlockCenter(waypoint.y()));
            writeSVarint(output, (long) waypoint.preciseZ()
                    - Waypoint.preciseBlockCenter(waypoint.z()));
            previousIndex = index;
        }
    }

    private static void writeGroupColors(
            ByteArrayOutputStream output, WaypointGroup group, int control) throws IOException {
        if ((control & CONTROL_GROUP_COLORS_PRESENT) == 0) return;
        DataOutputStream data = new DataOutputStream(output);
        writeRgb(data, group.staticColor());
        writeRgb(data, group.gradientStartColor());
        writeRgb(data, group.gradientEndColor());
    }

    private static void writeRgb(DataOutputStream output, int color) throws IOException {
        output.writeByte(color >>> 16);
        output.writeByte(color >>> 8);
        output.writeByte(color);
    }

    private static void writePackedIndexes(
            ByteArrayOutputStream output, int[] indexes, int bitsPerIndex) {
        int accumulator = 0;
        int storedBits = 0;
        for (int index : indexes) {
            accumulator = (accumulator << bitsPerIndex) | index;
            storedBits += bitsPerIndex;
            while (storedBits >= 8) {
                storedBits -= 8;
                output.write(accumulator >>> storedBits);
                accumulator &= (1 << storedBits) - 1;
            }
        }
        if (storedBits != 0) output.write(accumulator << (8 - storedBits));
    }

    private static V10Transport.Outbound selectNoNamesCandidate(WaypointGroup group)
            throws IOException {
        int[][] points = V10BareRouteCodec.coordinatesOf(group);
        V10Transport.Outbound best = V10GeneralRouteCodec.selectCandidate(
                encodeNoNamesSemantic(group,
                        stripKind2Header(V10BareRouteCodec.encodeRiceSemantic(points))));
        if (points.length > 1 && points.length <= V10BareEntropyCodec.MAX_QUOTIENT_WAYPOINTS) {
            V10Transport.Outbound quotient = V10GeneralRouteCodec.selectCandidate(
                    encodeNoNamesSemantic(group,
                            stripKind2Header(V10BareEntropyCodec.encodeQuotient(points))));
            if (quotient.compareTo(best) < 0) best = quotient;
        }
        return best;
    }

    private static byte[] canonicalNoNamesSemantic(WaypointGroup group) throws IOException {
        V10Transport.Outbound candidate = selectNoNamesCandidate(group);
        return candidate.mode() == V10Transport.MODE_DIRECT
                ? V10Transport.unseal(candidate.mode(), candidate.payload())
                : V10Transport.inflateAndVerify(candidate.payload());
    }

    private static byte[] stripKind2Header(byte[] semantic) {
        if (semantic.length == 0 || (semantic[0] & 0xFF) != V10BareRouteCodec.SEMANTIC_HEADER) {
            throw new IllegalArgumentException("invalid v10 kind-2 coordinate semantic");
        }
        return Arrays.copyOfRange(semantic, 1, semantic.length);
    }

    private static WaypointGroup decodeFull(byte[] semantic, int control) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(
                semantic, 2, semantic.length - 2));
        String zoneId = readZone(input);
        String groupName = readGroupName(input, zoneId);
        WaypointGroup group = V9CompactCodec.decodeWaypointPayload(input, zoneId, groupName);
        restoreMetadata(input, group, control);
        if (input.available() > 0) restorePrecisePositions(input, group);
        if (input.read() != -1) throw new IOException("trailing v10 kind-1 full bytes");
        return group;
    }

    private static WaypointGroup decodeNoNames(byte[] semantic, int control) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(
                semantic, 2, semantic.length - 2));
        String zoneId = readZone(input);
        int coordinateLength = (int) readUVarint(
                input, V10Transport.MAX_FRAME_BYTES, 4);
        if (coordinateLength < 1 || coordinateLength > input.available()) {
            throw new IOException("v10 kind-1 coordinate body length is outside body bounds");
        }
        byte[] coordinateBody = input.readNBytes(coordinateLength);
        int[][] points = V10BareRouteCodec.decodeCoordinateBody(
                coordinateBody, V10Transport.MODE_DIRECT);
        WaypointGroup group = WaypointGroup.create("", zoneId);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        List<Waypoint> waypoints = new ArrayList<>(points.length);
        for (int[] point : points) waypoints.add(Waypoint.at(point[0], point[1], point[2]));
        group.addAll(waypoints);
        restoreMetadata(input, group, control);
        if (input.available() > 0) restorePrecisePositions(input, group);
        if (input.read() != -1) throw new IOException("trailing v10 kind-1 no-names bytes");
        return group;
    }

    private static void restoreMetadata(
            DataInputStream input, WaypointGroup group, int control) throws IOException {
        if ((control & CONTROL_FLAGS_PRESENT) != 0) {
            int paletteSize = (int) readUVarint(
                    input, Math.max(1, group.size()), 3);
            if (paletteSize < 1 || group.isEmpty()) {
                throw new IOException("invalid v10 kind-1 flag palette size");
            }
            int[] palette = new int[paletteSize];
            for (int index = 0; index < paletteSize; index++) {
                palette[index] = (int) readUVarint(input, MAX_UINT32, 5);
            }
            int bitsPerIndex = 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
            int[] indexes = readPackedIndexes(input, group.size(), bitsPerIndex, paletteSize);
            List<Waypoint> points = new ArrayList<>(group.size());
            for (int index = 0; index < group.size(); index++) {
                points.add(group.get(index).withFlags(palette[indexes[index]]));
            }
            group.replaceWaypoints(points);
        }
        group.setLoadMode((control & CONTROL_STATIC_LOAD) != 0
                ? WaypointGroup.LoadMode.STATIC : WaypointGroup.LoadMode.SEQUENCE);
        group.setSkipAheadEnabled((control & CONTROL_SKIP_AHEAD_DISABLED) == 0);
        if ((control & CONTROL_RADIUS_PRESENT) != 0) {
            double radius = input.readDouble();
            if (!Double.isFinite(radius) || radius <= 0.0) {
                throw new IOException("invalid v10 kind-1 default radius");
            }
            group.setDefaultRadius(radius);
        }
        group.setGradientMode(switch ((control >>> CONTROL_GRADIENT_SHIFT) & 3) {
            case 0 -> WaypointGroup.GradientMode.STATIC;
            case 1 -> WaypointGroup.GradientMode.AUTO;
            case 2 -> WaypointGroup.GradientMode.MANUAL;
            default -> throw new IOException("reserved v10 kind-1 gradient id");
        });
        if ((control & CONTROL_GROUP_COLORS_PRESENT) != 0) {
            group.setStaticColor(readRgb(input));
            group.setGradientStartColor(readRgb(input));
            group.setGradientEndColor(readRgb(input));
        }
    }

    private static int readRgb(DataInputStream input) throws IOException {
        return input.readUnsignedByte() << 16
                | input.readUnsignedByte() << 8
                | input.readUnsignedByte();
    }

    private static void restorePrecisePositions(
            DataInputStream input, WaypointGroup group) throws IOException {
        int count = (int) readUVarint(input, group.size(), 3);
        if (count == 0) return;
        List<Waypoint> points = new ArrayList<>(group.waypoints());
        int previousIndex = -1;
        for (int entry = 0; entry < count; entry++) {
            int index = previousIndex + 1
                    + (int) readUVarint(input, group.size(), 3);
            if (index >= group.size()) {
                throw new IOException("v10 kind-1 precise waypoint index is outside the route");
            }
            Waypoint waypoint = points.get(index);
            int preciseX = addPreciseOffset(waypoint.x(), readSVarint(input));
            int preciseY = addPreciseOffset(waypoint.y(), readSVarint(input));
            int preciseZ = addPreciseOffset(waypoint.z(), readSVarint(input));
            points.set(index, waypoint.withPreciseSixteenths(preciseX, preciseY, preciseZ));
            previousIndex = index;
        }
        group.replaceWaypoints(points);
    }

    private static int addPreciseOffset(int blockCoordinate, long offset) throws IOException {
        long value = (long) Waypoint.preciseBlockCenter(blockCoordinate) + offset;
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("v10 kind-1 precise coordinate is outside the integer range");
        }
        return (int) value;
    }

    private static int[] readPackedIndexes(
            DataInputStream input, int count, int bitsPerIndex, int paletteSize)
            throws IOException {
        int[] indexes = new int[count];
        int accumulator = 0;
        int storedBits = 0;
        for (int index = 0; index < count; index++) {
            while (storedBits < bitsPerIndex) {
                accumulator = (accumulator << 8) | input.readUnsignedByte();
                storedBits += 8;
            }
            storedBits -= bitsPerIndex;
            indexes[index] = accumulator >>> storedBits;
            accumulator &= (1 << storedBits) - 1;
            if (indexes[index] >= paletteSize) {
                throw new IOException("v10 kind-1 flag palette index is outside the palette");
            }
        }
        if (storedBits != 0 && accumulator != 0) {
            throw new IOException("non-zero v10 kind-1 flag padding");
        }
        return indexes;
    }

    private static final String SECRET_ROUTE_PREFIX = "Secret Route — ";
    private static final String SECRETS_SUFFIX = " secrets";

    private static void writeGroupName(
            ByteArrayOutputStream output, String name, String zoneId) throws IOException {
        String zoneName = zoneDisplayName(zoneId);
        if (name.isEmpty()) {
            output.write(1);
        } else if (name.equals("Secret Route")) {
            output.write(2);
        } else if (name.equals(zoneName + SECRETS_SUFFIX)) {
            output.write(3);
        } else if (name.equals(SECRET_ROUTE_PREFIX + zoneName)) {
            output.write(4);
        } else if (name.equals("Route 1")) {
            output.write(5);
        } else if (name.equals("New group")) {
            output.write(6);
        } else if (name.equals("Imported Route")) {
            output.write(7);
        } else if (name.equals("Route -- " + zoneId.replace('-', ' ').replace('_', ' '))) {
            output.write(8);
        } else {
            output.write(0);
            writeInlineString(output, name);
        }
    }

    private static String readGroupName(DataInputStream input, String zoneId) throws IOException {
        String zoneName = zoneDisplayName(zoneId);
        return switch (input.readUnsignedByte()) {
            case 0 -> readInlineString(input);
            case 1 -> "";
            case 2 -> "Secret Route";
            case 3 -> zoneName + SECRETS_SUFFIX;
            case 4 -> SECRET_ROUTE_PREFIX + zoneName;
            case 5 -> "Route 1";
            case 6 -> "New group";
            case 7 -> "Imported Route";
            case 8 -> "Route -- " + zoneId.replace('-', ' ').replace('_', ' ');
            default -> throw new IOException("unknown v10 kind-1 group-name token");
        };
    }

    private static String zoneDisplayName(String zoneId) {
        StringBuilder value = new StringBuilder(zoneId.length());
        boolean first = true;
        for (int index = 0; index < zoneId.length(); index++) {
            char character = zoneId.charAt(index);
            if (character == '-' || character == '_') {
                value.append(' ');
                first = true;
            } else {
                value.append(first ? Character.toUpperCase(character) : character);
                first = false;
            }
        }
        return value.toString();
    }

    private static void writeInlineString(ByteArrayOutputStream output, String value) throws IOException {
        byte[] bytes = WaypointCodec.encodeUtf8Strict(value);
        if (bytes.length > MAX_INLINE_ZONE_BYTES) {
            throw new IllegalArgumentException("v10 kind-1 inline string exceeds limit");
        }
        writeUVarint(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static String readInlineString(DataInputStream input) throws IOException {
        int length = (int) readUVarint(input, MAX_INLINE_ZONE_BYTES, 3);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("truncated v10 kind-1 inline string");
        return WaypointCodec.decodeUtf8Strict(bytes);
    }

    private static void writeZone(
            ByteArrayOutputStream output, String zoneId, boolean allowPacked) throws IOException {
        int dictionaryIndex = CodecZoneDictionary.indexOf(zoneId);
        if (dictionaryIndex >= 0 && dictionaryIndex < 0xFE) {
            output.write(dictionaryIndex + 1);
        } else if (allowPacked && isCompactIdentifier(zoneId)) {
            output.write(0);
            writeUVarint(output, zoneId.length());
            int[] symbols = zoneId.chars().map(V10CompactRouteCodec::identifierSymbol).toArray();
            writePackedIndexes(output, symbols, 5);
        } else {
            output.write(0xFF);
            writeInlineString(output, zoneId);
        }
    }

    private static String readZone(DataInputStream input) throws IOException {
        int token = input.readUnsignedByte();
        if (token == 0) {
            int length = (int) readUVarint(input, MAX_INLINE_ZONE_BYTES, 3);
            int[] symbols = readPackedSymbols(input, length, 5, 28);
            StringBuilder value = new StringBuilder(length);
            for (int symbol : symbols) {
                value.append(symbol < 26 ? (char) ('a' + symbol) : symbol == 26 ? '-' : '_');
            }
            return value.toString();
        }
        if (token == 0xFF) return readInlineString(input);
        int dictionaryIndex = token - 1;
        if (!CodecZoneDictionary.isKnownIndex(dictionaryIndex)) {
            throw new IOException("unknown v10 kind-1 zone token " + token);
        }
        return CodecZoneDictionary.idAt(dictionaryIndex);
    }

    private static boolean isCompactIdentifier(String value) {
        return !value.isEmpty() && value.chars().allMatch(character ->
                character >= 'a' && character <= 'z' || character == '-' || character == '_');
    }

    private static int identifierSymbol(int character) {
        if (character >= 'a' && character <= 'z') return character - 'a';
        return character == '-' ? 26 : 27;
    }

    private static int[] readPackedSymbols(
            DataInputStream input, int count, int bitsPerSymbol, int alphabetSize)
            throws IOException {
        int[] symbols = new int[count];
        int accumulator = 0;
        int storedBits = 0;
        for (int index = 0; index < count; index++) {
            while (storedBits < bitsPerSymbol) {
                accumulator = (accumulator << 8) | input.readUnsignedByte();
                storedBits += 8;
            }
            storedBits -= bitsPerSymbol;
            symbols[index] = accumulator >>> storedBits;
            accumulator &= (1 << storedBits) - 1;
            if (symbols[index] >= alphabetSize) {
                throw new IOException("v10 kind-1 packed symbol is outside its alphabet");
            }
        }
        if (storedBits != 0 && accumulator != 0) {
            throw new IOException("non-zero v10 kind-1 packed-string padding");
        }
        return symbols;
    }

    private static void writeSVarint(ByteArrayOutputStream output, long value) {
        writeUVarint(output, value << 1 ^ value >> 63);
    }

    private static long readSVarint(DataInputStream input) throws IOException {
        long value = readUVarint(input, 0x1_FFFF_FFFEL, 5);
        return value >>> 1 ^ -(value & 1);
    }

    private static void writeUVarint(ByteArrayOutputStream output, long value) {
        do {
            int next = (int) (value & 0x7F);
            value >>>= 7;
            output.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    private static long readUVarint(
            DataInputStream input, long maximum, int maximumBytes) throws IOException {
        long result = 0;
        for (int byteIndex = 0; byteIndex < maximumBytes; byteIndex++) {
            int next = input.readUnsignedByte();
            result |= (long) (next & 0x7F) << (7 * byteIndex);
            if ((next & 0x80) == 0) {
                if (byteIndex > 0 && (next & 0x7F) == 0) {
                    throw new IOException("non-canonical v10 kind-1 uvarint");
                }
                if (result > maximum) {
                    throw new IOException("v10 kind-1 uvarint exceeds field limit");
                }
                return result;
            }
        }
        throw new IOException("v10 kind-1 uvarint is too long");
    }
}
