package com.babbur.waypointer.dungeon.data;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.codec.WaypointCodec;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Canonical wire-v10 kind-4 body for flattened WPD schema-2 dungeon collections. */
public final class V10DungeonBodyCodec {

    /** Bounded collection-level coordinate portfolios used by final-wire scoring. */
    enum CoordinatePolicy {
        LOCAL_RAW,
        FORCE_DELTA,
        PACK_WHEN_ELIGIBLE
    }

    public static final int CONTENT_KIND = 4;
    public static final int SEMANTIC_HEADER = (CONTENT_KIND << 4) | 10;
    public static final int SUBTYPE_FLATTENED_WPD_SCHEMA_2 = 0;

    public static final int MAX_ROUTES = 512;
    public static final int MAX_WAYPOINTS_PER_ROUTE = 512;
    public static final int MAX_TOTAL_WAYPOINTS = 50_000;
    public static final int MAX_STRING_BYTES = 256;
    public static final int MAX_TOTAL_STRING_BYTES = 1024 * 1024;
    public static final int MAX_ROUTE_BODY_BYTES = 1024 * 1024;
    /** Frame limit minus the two-byte V10 checksum. */
    public static final int MAX_SEMANTIC_BYTES = 2 * 1024 * 1024 - 2;

    private static final long MAX_WORK_UNITS = (long) MAX_SEMANTIC_BYTES
            + (long) MAX_TOTAL_WAYPOINTS * 16
            + (long) MAX_ROUTES * 64;
    private static final long MAX_COORDINATE_ZIGZAG = 536_870_910L;
    private static final int COORDINATE_MODE_DELTA_VARINT = 0;
    private static final int COORDINATE_MODE_PACKED_LOCAL = 1;

    private static final int GROUP_FLAG_SEQUENCE = 1;
    private static final int GROUP_FLAG_SKIP_AHEAD = 1 << 1;
    private static final int GROUP_FLAG_CUSTOM_RADIUS = 1 << 2;
    private static final int GROUP_FLAGS_MASK = 0b111;

    private static final int WAYPOINT_FLAG_NAME = 1;
    private static final int WAYPOINT_FLAG_RGB = 1 << 1;
    private static final int WAYPOINT_FLAG_USER_FLAGS = 1 << 2;
    private static final int WAYPOINT_FLAG_RADIUS = 1 << 3;
    private static final int WAYPOINT_FLAG_PRECISE = 1 << 4;
    /** Preserves unusual legacy WPD integer colors outside the ordinary RGB range. */
    private static final int WAYPOINT_FLAG_EXTENDED_COLOR = 1 << 5;
    private static final int WAYPOINT_FLAGS_MASK = 0b11_1111;

    private V10DungeonBodyCodec() {}

    public static byte[] encode(Collection<WaypointGroup> routes) throws IOException {
        return encode(routes, CoordinatePolicy.LOCAL_RAW);
    }

    static byte[] encode(Collection<WaypointGroup> routes,
                         CoordinatePolicy coordinatePolicy) throws IOException {
        List<WaypointGroup> safe = routes == null ? List.of() : new ArrayList<>(routes);
        validateCollection(safe);
        if (coordinatePolicy == null) throw new IllegalArgumentException("null coordinate policy");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(SEMANTIC_HEADER);
        writeUVarint(output, SUBTYPE_FLATTENED_WPD_SCHEMA_2);
        writeUVarint(output, safe.size());
        long totalStrings = 0;
        for (WaypointGroup route : safe) {
            EncodedRoute encoded = encodeRoute(route, coordinatePolicy);
            totalStrings = Math.addExact(totalStrings, encoded.stringBytes());
            if (totalStrings > MAX_TOTAL_STRING_BYTES) {
                throw new IllegalArgumentException("v10 dungeon strings exceed aggregate UTF-8 limit");
            }
            writeUVarint(output, encoded.bytes().length);
            output.writeBytes(encoded.bytes());
            if (output.size() > MAX_SEMANTIC_BYTES) {
                throw new IllegalArgumentException("v10 dungeon semantic body exceeds limit");
            }
        }
        return output.toByteArray();
    }

    public static Decoded decode(byte[] semantic) throws IOException {
        if (semantic == null || semantic.length < 3 || semantic.length > MAX_SEMANTIC_BYTES) {
            throw new IOException("v10 dungeon semantic body length is outside limit");
        }
        Budget budget = new Budget();
        ByteReader input = new ByteReader(semantic, budget, "v10 dungeon body");
        int header = input.readUnsignedByte();
        if (header != SEMANTIC_HEADER) {
            int kind = (header >>> 4) & 0b111;
            throw new IOException("expected v10 dungeon kind 4, got kind " + kind);
        }
        int subtype = (int) input.readUVarint(255, 2);
        if (subtype != SUBTYPE_FLATTENED_WPD_SCHEMA_2) {
            throw new IOException("unsupported v10 dungeon subtype " + subtype);
        }
        int routeCount = (int) input.readUVarint(MAX_ROUTES, 2);
        if (routeCount == 0) throw new IOException("v10 dungeon collection contains no routes");

        List<WaypointGroup> routes = new ArrayList<>(routeCount);
        int totalWaypoints = 0;
        long aggregateChildBytes = 0;
        for (int routeIndex = 0; routeIndex < routeCount; routeIndex++) {
            budget.chargeWork(64);
            int routeLength = (int) input.readUVarint(MAX_ROUTE_BODY_BYTES, 3);
            if (routeLength == 0) throw new IOException("empty v10 dungeon route body");
            aggregateChildBytes = Math.addExact(aggregateChildBytes, routeLength);
            if (aggregateChildBytes > MAX_SEMANTIC_BYTES) {
                throw new IOException("v10 dungeon child bodies exceed aggregate limit");
            }
            ByteReader routeInput = input.readSlice(routeLength, "v10 dungeon route " + routeIndex);
            DecodedRoute decoded = decodeRoute(routeInput, budget,
                    MAX_TOTAL_WAYPOINTS - totalWaypoints);
            routeInput.requireEnd();
            totalWaypoints = Math.addExact(totalWaypoints, decoded.route().size());
            if (totalWaypoints > MAX_TOTAL_WAYPOINTS) {
                throw new IOException("v10 dungeon collection contains too many waypoints");
            }
            routes.add(decoded.route());
        }
        input.requireEnd();
        if (totalWaypoints == 0) {
            throw new IOException("v10 dungeon collection contains no waypoints");
        }
        return new Decoded(routes, totalWaypoints, subtype);
    }

    private static EncodedRoute encodeRoute(WaypointGroup route,
                                            CoordinatePolicy coordinatePolicy) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(raw);
        String room = DungeonRoomCatalogEntry.normalizeId(route.zoneId());
        long stringBytes = writeString(output, room, false);
        stringBytes += writeString(output, route.name(), true);

        int groupFlags = (route.loadMode() == WaypointGroup.LoadMode.SEQUENCE
                ? GROUP_FLAG_SEQUENCE : 0)
                | (route.skipAheadEnabled() ? GROUP_FLAG_SKIP_AHEAD : 0)
                | (Double.doubleToLongBits(route.defaultRadius())
                == Double.doubleToLongBits(Waypoint.DEFAULT_REACH_RADIUS)
                ? 0 : GROUP_FLAG_CUSTOM_RADIUS);
        output.writeByte(groupFlags);
        if ((groupFlags & GROUP_FLAG_CUSTOM_RADIUS) != 0) {
            writeCanonicalDefaultRadius(output, route.defaultRadius());
        }
        writeUVarint(output, route.size());
        int coordinateMode = coordinateMode(route, coordinatePolicy);
        output.writeByte(coordinateMode);
        writeCoordinates(output, route, coordinateMode);
        for (Waypoint waypoint : route.waypoints()) {
            stringBytes += writeWaypoint(output, waypoint);
        }
        output.flush();
        byte[] bytes = raw.toByteArray();
        if (bytes.length > MAX_ROUTE_BODY_BYTES) {
            throw new IllegalArgumentException("v10 dungeon route body exceeds limit");
        }
        return new EncodedRoute(bytes, stringBytes);
    }

    private static DecodedRoute decodeRoute(ByteReader input, Budget budget,
                                            int remainingWaypoints) throws IOException {
        String room = input.readString(false);
        if (!room.equals(DungeonRoomCatalogEntry.normalizeId(room))) {
            throw new IOException("non-canonical v10 dungeon room id");
        }
        String name = input.readString(true);
        if (!WaypointCodec.isValidRouteDisplayName(name)) {
            throw new IOException("unsafe v10 dungeon route display name");
        }
        int groupFlags = input.readUnsignedByte();
        if ((groupFlags & ~GROUP_FLAGS_MASK) != 0) {
            throw new IOException("reserved v10 dungeon group flags");
        }
        double defaultRadius = (groupFlags & GROUP_FLAG_CUSTOM_RADIUS) == 0
                ? Waypoint.DEFAULT_REACH_RADIUS : input.readDouble();
        validateDefaultRadius(defaultRadius,
                (groupFlags & GROUP_FLAG_CUSTOM_RADIUS) != 0);
        int count = (int) input.readUVarint(
                Math.min(MAX_WAYPOINTS_PER_ROUTE, remainingWaypoints), 2);
        budget.chargeWork((long) count * 16);
        int coordinateMode = input.readUnsignedByte();
        int[][] coordinates = readCoordinates(input, count, coordinateMode);

        WaypointGroup route = WaypointGroup.create(name, room);
        route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        route.setLoadMode((groupFlags & GROUP_FLAG_SEQUENCE) != 0
                ? WaypointGroup.LoadMode.SEQUENCE : WaypointGroup.LoadMode.STATIC);
        route.setSkipAheadEnabled((groupFlags & GROUP_FLAG_SKIP_AHEAD) != 0);
        route.setDefaultRadius(defaultRadius);
        for (int index = 0; index < count; index++) {
            route.add(readWaypoint(input,
                    coordinates[0][index], coordinates[1][index], coordinates[2][index]));
        }
        return new DecodedRoute(route);
    }

    private static long writeWaypoint(DataOutputStream output, Waypoint waypoint) throws IOException {
        requireRepresentableWaypoint(waypoint);
        boolean precise = waypoint.hasCustomPrecisePosition();
        boolean ordinaryRgb = waypoint.color() >= 0 && waypoint.color() <= 0xFF_FFFF;
        boolean hasColor = waypoint.color() != Waypoint.DEFAULT_COLOR;
        int flags = (waypoint.hasName() ? WAYPOINT_FLAG_NAME : 0)
                | (hasColor && ordinaryRgb ? WAYPOINT_FLAG_RGB : 0)
                | (waypoint.flags() != 0 ? WAYPOINT_FLAG_USER_FLAGS : 0)
                | (waypoint.customRadius() != 0.0 ? WAYPOINT_FLAG_RADIUS : 0)
                | (precise ? WAYPOINT_FLAG_PRECISE : 0)
                | (hasColor && !ordinaryRgb ? WAYPOINT_FLAG_EXTENDED_COLOR : 0);
        output.writeByte(flags);
        long stringBytes = 0;
        if ((flags & WAYPOINT_FLAG_NAME) != 0) {
            stringBytes = writeString(output, waypoint.name(), false);
        }
        if ((flags & WAYPOINT_FLAG_RGB) != 0) writeRgb(output, waypoint.color());
        if ((flags & WAYPOINT_FLAG_EXTENDED_COLOR) != 0) {
            writeUVarint(output, Integer.toUnsignedLong(waypoint.color()));
        }
        if ((flags & WAYPOINT_FLAG_USER_FLAGS) != 0) {
            writeUVarint(output, Integer.toUnsignedLong(waypoint.flags()));
        }
        if ((flags & WAYPOINT_FLAG_RADIUS) != 0) {
            writeCanonicalCustomRadius(output, waypoint.customRadius());
        }
        if ((flags & WAYPOINT_FLAG_PRECISE) != 0) {
            writeSVarint(output, preciseResidual(waypoint.preciseX(), waypoint.x()));
            writeSVarint(output, preciseResidual(waypoint.preciseY(), waypoint.y()));
            writeSVarint(output, preciseResidual(waypoint.preciseZ(), waypoint.z()));
        }
        return stringBytes;
    }

    private static Waypoint readWaypoint(ByteReader input, int x, int y, int z) throws IOException {
        int bodyFlags = input.readUnsignedByte();
        if ((bodyFlags & ~WAYPOINT_FLAGS_MASK) != 0) {
            throw new IOException("reserved v10 dungeon waypoint flags");
        }
        if ((bodyFlags & WAYPOINT_FLAG_RGB) != 0
                && (bodyFlags & WAYPOINT_FLAG_EXTENDED_COLOR) != 0) {
            throw new IOException("conflicting v10 dungeon color encodings");
        }
        String name = (bodyFlags & WAYPOINT_FLAG_NAME) != 0 ? input.readString(false) : "";
        if (!WaypointCodec.isValidRouteDisplayName(name)) {
            throw new IOException("unsafe v10 dungeon waypoint display name");
        }
        int color;
        if ((bodyFlags & WAYPOINT_FLAG_RGB) != 0) {
            color = input.readRgb();
            if (color == Waypoint.DEFAULT_COLOR) {
                throw new IOException("explicit default v10 dungeon waypoint color");
            }
        } else if ((bodyFlags & WAYPOINT_FLAG_EXTENDED_COLOR) != 0) {
            color = (int) input.readUVarint(0xFFFF_FFFFL, 5);
            if (color >= 0 && color <= 0xFF_FFFF) {
                throw new IOException("non-canonical extended v10 dungeon color");
            }
        } else {
            color = Waypoint.DEFAULT_COLOR;
        }
        int flags = (bodyFlags & WAYPOINT_FLAG_USER_FLAGS) != 0
                ? (int) input.readUVarint(0xFFFF_FFFFL, 5) : 0;
        if ((bodyFlags & WAYPOINT_FLAG_USER_FLAGS) != 0 && flags == 0) {
            throw new IOException("explicit zero v10 dungeon waypoint flags");
        }
        double radius = (bodyFlags & WAYPOINT_FLAG_RADIUS) != 0 ? input.readDouble() : 0.0;
        validateCustomRadius(radius, (bodyFlags & WAYPOINT_FLAG_RADIUS) != 0);

        int preciseX = Waypoint.preciseBlockCenter(x);
        int preciseY = Waypoint.preciseBlockCenter(y);
        int preciseZ = Waypoint.preciseBlockCenter(z);
        if ((bodyFlags & WAYPOINT_FLAG_PRECISE) != 0) {
            int residualX = (int) input.readSVarint(15, 1);
            int residualY = (int) input.readSVarint(15, 1);
            int residualZ = (int) input.readSVarint(15, 1);
            if (residualX == 0 && residualY == 0 && residualZ == 0) {
                throw new IOException("explicit centered v10 dungeon precise position");
            }
            preciseX = checkedPrecise(x, residualX);
            preciseY = checkedPrecise(y, residualY);
            preciseZ = checkedPrecise(z, residualZ);
        }
        return new Waypoint(x, y, z, name, color, flags, radius,
                Waypoint.TEMP_NONE, 0L, preciseX, preciseY, preciseZ);
    }

    private static void writeCoordinates(DataOutputStream output, WaypointGroup route,
                                         int coordinateMode) throws IOException {
        if (coordinateMode == COORDINATE_MODE_DELTA_VARINT) {
            int previousX = 0, previousY = 0, previousZ = 0;
            for (int index = 0; index < route.size(); index++) {
                Waypoint waypoint = route.get(index);
                writeSVarint(output, index == 0 ? waypoint.x() : (long) waypoint.x() - previousX);
                writeSVarint(output, index == 0 ? waypoint.y() : (long) waypoint.y() - previousY);
                writeSVarint(output, index == 0 ? waypoint.z() : (long) waypoint.z() - previousZ);
                previousX = waypoint.x();
                previousY = waypoint.y();
                previousZ = waypoint.z();
            }
            return;
        }
        if (coordinateMode != COORDINATE_MODE_PACKED_LOCAL) {
            throw new IllegalArgumentException("unsupported v10 dungeon coordinate mode");
        }
        if (route.isEmpty()) return;
        Waypoint first = route.get(0);
        writeSVarint(output, first.x());
        writeSVarint(output, first.y());
        writeSVarint(output, first.z());
        int bits = 0;
        int bitCount = 0;
        for (int index = 1; index < route.size(); index++) {
            Waypoint previous = route.get(index - 1);
            Waypoint waypoint = route.get(index);
            int value = (zigzagInt(waypoint.x() - previous.x()) & 0x0F)
                    | ((zigzagInt(waypoint.y() - previous.y()) & 0x03) << 4)
                    | ((zigzagInt(waypoint.z() - previous.z()) & 0x0F) << 6);
            bits |= value << bitCount;
            bitCount += 10;
            while (bitCount >= 8) {
                output.writeByte(bits & 0xFF);
                bits >>>= 8;
                bitCount -= 8;
            }
        }
        if (bitCount != 0) output.writeByte(bits & 0xFF);
    }

    private static int[][] readCoordinates(ByteReader input, int count, int coordinateMode)
            throws IOException {
        int[] xs = new int[count];
        int[] ys = new int[count];
        int[] zs = new int[count];
        if (coordinateMode == COORDINATE_MODE_DELTA_VARINT) {
            for (int index = 0; index < count; index++) {
                long dx = input.readSVarint(MAX_COORDINATE_ZIGZAG, 5);
                long dy = input.readSVarint(MAX_COORDINATE_ZIGZAG, 5);
                long dz = input.readSVarint(MAX_COORDINATE_ZIGZAG, 5);
                xs[index] = checkedCoordinate(index == 0 ? dx : (long) xs[index - 1] + dx);
                ys[index] = checkedCoordinate(index == 0 ? dy : (long) ys[index - 1] + dy);
                zs[index] = checkedCoordinate(index == 0 ? dz : (long) zs[index - 1] + dz);
            }
        } else if (coordinateMode == COORDINATE_MODE_PACKED_LOCAL) {
            if (count > 0) {
                xs[0] = checkedCoordinate(input.readSVarint(MAX_COORDINATE_ZIGZAG, 5));
                ys[0] = checkedCoordinate(input.readSVarint(MAX_COORDINATE_ZIGZAG, 5));
                zs[0] = checkedCoordinate(input.readSVarint(MAX_COORDINATE_ZIGZAG, 5));
            }
            int packedLength = Math.toIntExact(((long) Math.max(0, count - 1) * 10 + 7) / 8);
            byte[] packed = input.readBytes(packedLength);
            for (int index = 1, bitOffset = 0; index < count; index++, bitOffset += 10) {
                int byteOffset = bitOffset >>> 3;
                int shift = bitOffset & 7;
                int window = packed[byteOffset] & 0xFF;
                if (byteOffset + 1 < packed.length) window |= (packed[byteOffset + 1] & 0xFF) << 8;
                if (byteOffset + 2 < packed.length) window |= (packed[byteOffset + 2] & 0xFF) << 16;
                int value = (window >>> shift) & 0x3FF;
                xs[index] = checkedCoordinate((long) xs[index - 1] + unzigzagInt(value & 0x0F));
                ys[index] = checkedCoordinate((long) ys[index - 1] + unzigzagInt((value >>> 4) & 0x03));
                zs[index] = checkedCoordinate((long) zs[index - 1] + unzigzagInt((value >>> 6) & 0x0F));
            }
            int usedBits = Math.max(0, count - 1) * 10;
            if (packedLength > 0 && (usedBits & 7) != 0
                    && ((packed[packedLength - 1] & 0xFF) >>> (usedBits & 7)) != 0) {
                throw new IOException("non-zero v10 dungeon coordinate padding");
            }
        } else {
            throw new IOException("unsupported v10 dungeon coordinate mode " + coordinateMode);
        }
        if (coordinateMode(xs, ys, zs) != coordinateMode) {
            throw new IOException("non-canonical v10 dungeon coordinate mode");
        }
        return new int[][] {xs, ys, zs};
    }

    private static int coordinateMode(WaypointGroup route, CoordinatePolicy policy) {
        int[] xs = new int[route.size()];
        int[] ys = new int[route.size()];
        int[] zs = new int[route.size()];
        for (int index = 0; index < route.size(); index++) {
            xs[index] = route.get(index).x();
            ys[index] = route.get(index).y();
            zs[index] = route.get(index).z();
        }
        return switch (policy) {
            case LOCAL_RAW -> coordinateMode(xs, ys, zs);
            case FORCE_DELTA -> COORDINATE_MODE_DELTA_VARINT;
            case PACK_WHEN_ELIGIBLE -> packedEligible(xs, ys, zs)
                    ? COORDINATE_MODE_PACKED_LOCAL : COORDINATE_MODE_DELTA_VARINT;
        };
    }

    private static int coordinateMode(int[] xs, int[] ys, int[] zs) {
        if (xs.length <= 1) return COORDINATE_MODE_DELTA_VARINT;
        int varintBytes = signedVarintSize(xs[0]) + signedVarintSize(ys[0]) + signedVarintSize(zs[0]);
        for (int index = 1; index < xs.length; index++) {
            int dx = xs[index] - xs[index - 1];
            int dy = ys[index] - ys[index - 1];
            int dz = zs[index] - zs[index - 1];
            if (dx < -7 || dx > 7 || dy < -1 || dy > 1 || dz < -7 || dz > 7) {
                return COORDINATE_MODE_DELTA_VARINT;
            }
            varintBytes += signedVarintSize(dx) + signedVarintSize(dy) + signedVarintSize(dz);
        }
        int packedBytes = signedVarintSize(xs[0]) + signedVarintSize(ys[0])
                + signedVarintSize(zs[0]) + ((xs.length - 1) * 10 + 7) / 8;
        return packedBytes < varintBytes
                ? COORDINATE_MODE_PACKED_LOCAL : COORDINATE_MODE_DELTA_VARINT;
    }

    private static boolean packedEligible(int[] xs, int[] ys, int[] zs) {
        if (xs.length <= 1) return false;
        for (int index = 1; index < xs.length; index++) {
            int dx = xs[index] - xs[index - 1];
            int dy = ys[index] - ys[index - 1];
            int dz = zs[index] - zs[index - 1];
            if (dx < -7 || dx > 7 || dy < -1 || dy > 1 || dz < -7 || dz > 7) {
                return false;
            }
        }
        return true;
    }

    private static void validateCollection(List<WaypointGroup> routes) {
        if (routes.isEmpty() || routes.size() > MAX_ROUTES) {
            throw new IllegalArgumentException("invalid v10 dungeon route count");
        }
        int total = 0;
        for (WaypointGroup route : routes) {
            if (route == null) throw new IllegalArgumentException("null v10 dungeon route");
            if (route.routeKind() != WaypointGroup.RouteKind.DUNGEON) {
                throw new IllegalArgumentException("v10 dungeon collection contains a regular route");
            }
            WaypointCodec.validateRouteDisplayName(route.name(),
                    "v10 dungeon route name");
            String room = DungeonRoomCatalogEntry.normalizeId(route.zoneId());
            if (room.isEmpty()) throw new IllegalArgumentException("v10 dungeon route has no room id");
            if (route.size() > MAX_WAYPOINTS_PER_ROUTE) {
                throw new IllegalArgumentException("v10 dungeon route contains too many waypoints");
            }
            for (int waypointIndex = 0; waypointIndex < route.size(); waypointIndex++) {
                WaypointCodec.validateRouteDisplayName(route.get(waypointIndex).name(),
                        "v10 dungeon waypoint " + waypointIndex + " name");
            }
            total = Math.addExact(total, route.size());
            if (total > MAX_TOTAL_WAYPOINTS) {
                throw new IllegalArgumentException("v10 dungeon collection contains too many waypoints");
            }
        }
        if (total == 0) throw new IllegalArgumentException("v10 dungeon collection contains no waypoints");
    }

    private static void requireRepresentableWaypoint(Waypoint waypoint) {
        if (!Waypoint.isRepresentableBlockCoordinate(waypoint.x())
                || !Waypoint.isRepresentableBlockCoordinate(waypoint.y())
                || !Waypoint.isRepresentableBlockCoordinate(waypoint.z())) {
            throw new IllegalArgumentException("v10 dungeon coordinate is outside model range");
        }
        if (Waypoint.blockCoordinateFromPrecise(waypoint.preciseX()) != waypoint.x()
                || Waypoint.blockCoordinateFromPrecise(waypoint.preciseY()) != waypoint.y()
                || Waypoint.blockCoordinateFromPrecise(waypoint.preciseZ()) != waypoint.z()) {
            throw new IllegalArgumentException("v10 dungeon precise coordinate disagrees with block");
        }
    }

    private static int checkedCoordinate(long value) throws IOException {
        if (value < Waypoint.MIN_BLOCK_COORDINATE || value > Waypoint.MAX_BLOCK_COORDINATE) {
            throw new IOException("v10 dungeon coordinate is outside model range");
        }
        return (int) value;
    }

    private static int preciseResidual(int precise, int block) {
        int residual = precise - Waypoint.preciseBlockCenter(block);
        if (residual < -8 || residual > 7) {
            throw new IllegalArgumentException("v10 dungeon precise residual is outside block");
        }
        return residual;
    }

    private static int checkedPrecise(int block, int residual) throws IOException {
        if (residual < -8 || residual > 7) {
            throw new IOException("v10 dungeon precise residual is outside block");
        }
        long precise = (long) Waypoint.preciseBlockCenter(block) + residual;
        if (precise < Integer.MIN_VALUE || precise > Integer.MAX_VALUE) {
            throw new IOException("v10 dungeon precise coordinate overflow");
        }
        return (int) precise;
    }

    private static void writeCanonicalDefaultRadius(DataOutputStream output, double radius)
            throws IOException {
        validateDefaultRadius(radius, true);
        output.writeLong(Double.doubleToLongBits(radius));
    }

    private static void validateDefaultRadius(double radius, boolean explicit) throws IOException {
        if (!Double.isFinite(radius) || radius < Waypoint.MIN_REACH_RADIUS
                || radius > Waypoint.MAX_REACH_RADIUS
                || Double.doubleToLongBits(Waypoint.normalizeDefaultRadius(radius))
                != Double.doubleToLongBits(radius)) {
            throw new IOException("invalid v10 dungeon default radius");
        }
        if (explicit && Double.doubleToLongBits(radius)
                == Double.doubleToLongBits(Waypoint.DEFAULT_REACH_RADIUS)) {
            throw new IOException("explicit default v10 dungeon group radius");
        }
    }

    private static void writeCanonicalCustomRadius(DataOutputStream output, double radius)
            throws IOException {
        validateCustomRadius(radius, true);
        output.writeLong(Double.doubleToLongBits(radius));
    }

    private static void validateCustomRadius(double radius, boolean explicit) throws IOException {
        if (!explicit) {
            if (radius != 0.0) throw new IOException("unexpected v10 dungeon waypoint radius");
            return;
        }
        if (!Double.isFinite(radius) || radius <= 0.0 || radius > Waypoint.MAX_REACH_RADIUS
                || Double.doubleToLongBits(Waypoint.normalizeCustomRadius(radius))
                != Double.doubleToLongBits(radius)) {
            throw new IOException("invalid v10 dungeon waypoint radius");
        }
    }

    private static long writeString(DataOutputStream output, String value, boolean allowEmpty)
            throws IOException {
        byte[] bytes = strictUtf8(value == null ? "" : value);
        if ((!allowEmpty && bytes.length == 0) || bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("v10 dungeon string length is outside limit");
        }
        writeUVarint(output, bytes.length);
        output.write(bytes);
        return bytes.length;
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

    private static void writeRgb(DataOutputStream output, int rgb) throws IOException {
        output.writeByte(rgb >>> 16);
        output.writeByte(rgb >>> 8);
        output.writeByte(rgb);
    }

    private static int signedVarintSize(long value) {
        long unsigned = (value << 1) ^ (value >> 63);
        int size = 1;
        while ((unsigned & ~0x7FL) != 0) {
            unsigned >>>= 7;
            size++;
        }
        return size;
    }

    private static int zigzagInt(int value) {
        return (value << 1) ^ (value >> 31);
    }

    private static int unzigzagInt(int value) {
        return (value >>> 1) ^ -(value & 1);
    }

    private static void writeSVarint(DataOutputStream output, long value) throws IOException {
        writeUVarint(output, (value << 1) ^ (value >> 63));
    }

    private static void writeUVarint(DataOutputStream output, long value) throws IOException {
        if (value < 0) throw new IllegalArgumentException("negative v10 dungeon uvarint");
        do {
            int next = (int) (value & 0x7F);
            value >>>= 7;
            output.writeByte(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    private static void writeUVarint(ByteArrayOutputStream output, long value) {
        if (value < 0) throw new IllegalArgumentException("negative v10 dungeon uvarint");
        do {
            int next = (int) (value & 0x7F);
            value >>>= 7;
            output.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    private static final class Budget {
        private long stringBytes;
        private long workUnits;

        void chargeString(int bytes) throws IOException {
            stringBytes = Math.addExact(stringBytes, bytes);
            if (stringBytes > MAX_TOTAL_STRING_BYTES) {
                throw new IOException("v10 dungeon strings exceed aggregate UTF-8 limit");
            }
        }

        void chargeWork(long units) throws IOException {
            workUnits = Math.addExact(workUnits, units);
            if (workUnits > MAX_WORK_UNITS) {
                throw new IOException("v10 dungeon parse work exceeds limit");
            }
        }
    }

    private static final class ByteReader {
        private final byte[] data;
        private final int end;
        private final Budget budget;
        private final String context;
        private int position;

        ByteReader(byte[] data, Budget budget, String context) {
            this(data, 0, data.length, budget, context);
        }

        ByteReader(byte[] data, int start, int end, Budget budget, String context) {
            this.data = data;
            this.position = start;
            this.end = end;
            this.budget = budget;
            this.context = context;
        }

        int readUnsignedByte() throws IOException {
            if (position >= end) throw new IOException("truncated " + context);
            budget.chargeWork(1);
            return data[position++] & 0xFF;
        }

        double readDouble() throws IOException {
            return Double.longBitsToDouble(readLong());
        }

        long readLong() throws IOException {
            long value = 0;
            for (int index = 0; index < Long.BYTES; index++) {
                value = (value << 8) | readUnsignedByte();
            }
            return value;
        }

        long readSVarint(long maximumZigzag, int maximumBytes) throws IOException {
            long encoded = readUVarint(maximumZigzag, maximumBytes);
            return (encoded >>> 1) ^ -(encoded & 1);
        }

        long readUVarint(long maximum, int maximumBytes) throws IOException {
            long result = 0;
            for (int byteIndex = 0; byteIndex < maximumBytes; byteIndex++) {
                int next = readUnsignedByte();
                result |= (long) (next & 0x7F) << (7 * byteIndex);
                if ((next & 0x80) == 0) {
                    if (byteIndex > 0 && (next & 0x7F) == 0) {
                        throw new IOException("non-canonical v10 dungeon uvarint");
                    }
                    if (Long.compareUnsigned(result, maximum) > 0) {
                        throw new IOException("v10 dungeon uvarint exceeds limit");
                    }
                    return result;
                }
            }
            throw new IOException("v10 dungeon uvarint is too long");
        }

        int readRgb() throws IOException {
            return (readUnsignedByte() << 16) | (readUnsignedByte() << 8) | readUnsignedByte();
        }

        String readString(boolean allowEmpty) throws IOException {
            int length = (int) readUVarint(MAX_STRING_BYTES, 2);
            if (!allowEmpty && length == 0) throw new IOException("empty required v10 dungeon string");
            budget.chargeString(length);
            return decodeStrictUtf8(readBytes(length));
        }

        byte[] readBytes(int length) throws IOException {
            if (length < 0 || position > end - length) throw new IOException("truncated " + context);
            budget.chargeWork(length);
            byte[] bytes = Arrays.copyOfRange(data, position, position + length);
            position += length;
            return bytes;
        }

        ByteReader readSlice(int length, String childContext) throws IOException {
            if (length < 0 || position > end - length) throw new IOException("truncated " + context);
            int start = position;
            position += length;
            return new ByteReader(data, start, position, budget, childContext);
        }

        void requireEnd() throws IOException {
            if (position != end) throw new IOException("trailing " + context + " bytes");
        }
    }

    private record EncodedRoute(byte[] bytes, long stringBytes) {
        EncodedRoute {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record DecodedRoute(WaypointGroup route) {}

    public record Decoded(List<WaypointGroup> routes, int waypointCount, int subtype) {
        public Decoded {
            routes = List.copyOf(routes);
        }
    }
}
