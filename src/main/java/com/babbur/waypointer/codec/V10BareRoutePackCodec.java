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

/** V10 kind 6, subtype 0: ordered bare routes. Children use headerless kind-2 bodies
 * in the enclosing transport mode, without nested framing. Route count and
 * shortest-form child lengths delimit the bodies. */
final class V10BareRoutePackCodec {

    static final int CONTENT_KIND = 6;
    static final int SEMANTIC_HEADER = 0x6A;
    static final int SUBTYPE_ORDERED_BARE_REGULAR = 0;
    static final int MIN_ROUTES = 2;
    static final int MAX_ROUTES = WaypointImporter.MAX_GROUPS_PER_IMPORT;
    static final int MAX_TOTAL_WAYPOINTS = WaypointImporter.MAX_TOTAL_WAYPOINTS_PER_IMPORT;

    private static final int CHECKSUM_BYTES = V10Transport.CHECKSUM_BYTES;
    private static final int MAX_SEMANTIC_BYTES = V10Transport.MAX_FRAME_BYTES - CHECKSUM_BYTES;

    private V10BareRoutePackCodec() {}

    static boolean canEncode(List<WaypointGroup> groups, WaypointCodec.Options options) {
        if (groups == null || options == null || !options.isBareCoordinateProjection()) return false;
        if (groups.size() < MIN_ROUTES || groups.size() > MAX_ROUTES) return false;
        int totalWaypoints = 0;
        for (WaypointGroup group : groups) {
            if (!V10BareRouteCodec.canEncode(group, options)) return false;
            totalWaypoints += group.size();
            if (totalWaypoints > MAX_TOTAL_WAYPOINTS) return false;
        }
        return true;
    }

    static String encode(List<WaypointGroup> groups) throws IOException {
        V10Transport.Outbound best = V10Transport.direct(
                encodeSemantic(groups, V10Transport.MODE_DIRECT));

        byte[] deltaSemantic = encodeSemantic(groups, V10Transport.MODE_DEFLATE);
        V10Transport.Outbound defaultDeflate = V10Transport.deflated(
                deltaSemantic, Deflater.DEFAULT_STRATEGY);
        if (defaultDeflate.compareTo(best) < 0) best = defaultDeflate;

        V10Transport.Outbound filteredDeflate = V10Transport.deflated(
                deltaSemantic, Deflater.FILTERED);
        if (filteredDeflate.compareTo(best) < 0) best = filteredDeflate;
        return best.transport();
    }

    static List<WaypointGroup> decode(V10Transport.CheckedFrame frame) throws IOException {
        if (frame.contentKind() != CONTENT_KIND) {
            throw new IOException("unsupported v10 content kind " + frame.contentKind());
        }
        byte[] semantic = frame.semantic();
        Reader reader = new Reader(semantic);
        int header = reader.readUnsignedByte();
        if (header != SEMANTIC_HEADER) {
            throw new IOException("unsupported v10 bare-pack semantic header 0x"
                    + Integer.toHexString(header));
        }
        int subtype = reader.readUVarint(0x7F, 1);
        if (subtype != SUBTYPE_ORDERED_BARE_REGULAR) {
            throw new IOException("unsupported v10 bare-pack subtype " + subtype);
        }
        int routeCount = reader.readUVarint(MAX_ROUTES, 2);
        if (routeCount < MIN_ROUTES) {
            throw new IOException("v10 bare-pack route count is below " + MIN_ROUTES);
        }

        List<byte[]> children = new ArrayList<>(routeCount);
        int totalWaypoints = 0;
        int totalChildBytes = 0;
        for (int routeIndex = 0; routeIndex < routeCount; routeIndex++) {
            int childLength = reader.readUVarint(MAX_SEMANTIC_BYTES, 3);
            if (childLength == 0) {
                throw new IOException("v10 bare-pack child " + routeIndex + " is empty");
            }
            totalChildBytes = Math.addExact(totalChildBytes, childLength);
            if (totalChildBytes > MAX_SEMANTIC_BYTES) {
                throw new IOException("v10 bare-pack child bytes exceed frame limit");
            }
            byte[] child = reader.readBytes(childLength);
            byte[] kind2Semantic = restoreKind2Header(child);
            int childWaypoints = V10BareRouteCodec.waypointCount(kind2Semantic);
            totalWaypoints = Math.addExact(totalWaypoints, childWaypoints);
            if (totalWaypoints > MAX_TOTAL_WAYPOINTS) {
                throw new IOException("v10 bare-pack total waypoint count exceeds "
                        + MAX_TOTAL_WAYPOINTS);
            }
            children.add(child);
        }
        reader.requireEnd();

        // Do not allocate any waypoint arrays until every child boundary and
        // the aggregate count have passed their outer-container limits.
        List<WaypointGroup> groups = new ArrayList<>(routeCount);
        for (int routeIndex = 0; routeIndex < children.size(); routeIndex++) {
            byte[] child = children.get(routeIndex);
            WaypointGroup group = decodeCanonicalChild(child, frame.mode());
            groups.add(group);
        }
        return List.copyOf(groups);
    }

    static byte[] encodeSemantic(List<WaypointGroup> groups, int mode) throws IOException {
        if (groups == null || groups.size() < MIN_ROUTES || groups.size() > MAX_ROUTES) {
            throw new IllegalArgumentException("v10 bare-pack route count is outside limit");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(SEMANTIC_HEADER);
        writeUVarint(output, SUBTYPE_ORDERED_BARE_REGULAR);
        writeUVarint(output, groups.size());
        int totalWaypoints = 0;
        for (WaypointGroup group : groups) {
            if (group == null || group.routeKind() != WaypointGroup.RouteKind.REGULAR
                    || group.size() > V10BareRouteCodec.MAX_WAYPOINTS) {
                throw new IllegalArgumentException("v10 bare-pack contains an unsupported route");
            }
            totalWaypoints += group.size();
            if (totalWaypoints > MAX_TOTAL_WAYPOINTS) {
                throw new IllegalArgumentException("v10 bare-pack total waypoint count exceeds limit");
            }
            byte[] child = encodeCanonicalChild(group, mode);
            writeUVarint(output, child.length);
            output.writeBytes(child);
            if (output.size() > MAX_SEMANTIC_BYTES) {
                throw new IllegalArgumentException("v10 bare-pack semantic body exceeds frame limit");
            }
        }
        return output.toByteArray();
    }

    private static byte[] stripKind2Header(byte[] semantic) {
        if (semantic.length == 0 || semantic[0] != (byte) V10BareRouteCodec.SEMANTIC_HEADER) {
            throw new IllegalArgumentException("invalid v10 kind-2 semantic child");
        }
        return Arrays.copyOfRange(semantic, 1, semantic.length);
    }

    private static byte[] restoreKind2Header(byte[] body) {
        byte[] semantic = new byte[body.length + 1];
        semantic[0] = (byte) V10BareRouteCodec.SEMANTIC_HEADER;
        System.arraycopy(body, 0, semantic, 1, body.length);
        return semantic;
    }

    /** Headerless kind-2 child: direct uses shorter Rice/quotient (Rice wins ties); B uses delta. */
    private static byte[] encodeCanonicalChild(WaypointGroup group, int mode)
            throws IOException {
        int[][] coordinates = V10BareRouteCodec.coordinatesOf(group);
        byte[] semantic;
        if (mode == V10Transport.MODE_DIRECT) {
            semantic = V10BareRouteCodec.encodeRiceSemantic(coordinates);
            if (coordinates.length > 1
                    && coordinates.length <= V10BareEntropyCodec.MAX_QUOTIENT_WAYPOINTS) {
                byte[] quotient = V10BareEntropyCodec.encodeQuotient(coordinates);
                if (quotient.length < semantic.length) semantic = quotient;
            }
        } else if (mode == V10Transport.MODE_DEFLATE) {
            semantic = V10BareRouteCodec.encodeDeltaSemantic(coordinates);
        } else {
            throw new IllegalArgumentException("unknown v10 compression mode: " + mode);
        }
        return stripKind2Header(semantic);
    }

    private static WaypointGroup decodeCanonicalChild(byte[] body, int mode)
            throws IOException {
        int[][] coordinates = V10BareRouteCodec.decodeCoordinateBody(body, mode);
        WaypointGroup group = WaypointGroup.create("", Zone.UNKNOWN.id());
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        List<Waypoint> waypoints = new ArrayList<>(coordinates.length);
        for (int[] point : coordinates) {
            waypoints.add(Waypoint.at(point[0], point[1], point[2]));
        }
        group.addAll(waypoints);
        if (!Arrays.equals(body, encodeCanonicalChild(group, mode))) {
            throw new IOException("non-canonical v10 bare-pack coordinate child");
        }
        return group;
    }

    private static void writeUVarint(ByteArrayOutputStream output, int value) {
        if (value < 0) throw new IllegalArgumentException("negative v10 bare-pack uvarint");
        do {
            int next = value & 0x7F;
            value >>>= 7;
            output.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    private static final class Reader {
        private final byte[] data;
        private int position;

        Reader(byte[] data) {
            if (data == null || data.length > MAX_SEMANTIC_BYTES) {
                throw new IllegalArgumentException("v10 bare-pack semantic length is outside limit");
            }
            this.data = data;
        }

        int readUnsignedByte() throws IOException {
            if (position >= data.length) throw new IOException("truncated v10 bare-pack body");
            return data[position++] & 0xFF;
        }

        int readUVarint(int maximum, int maximumBytes) throws IOException {
            int result = 0;
            for (int byteIndex = 0; byteIndex < maximumBytes; byteIndex++) {
                int next = readUnsignedByte();
                result |= (next & 0x7F) << (7 * byteIndex);
                if ((next & 0x80) == 0) {
                    if (byteIndex > 0 && (next & 0x7F) == 0) {
                        throw new IOException("non-canonical v10 bare-pack uvarint");
                    }
                    if (result > maximum) {
                        throw new IOException("v10 bare-pack uvarint exceeds field limit");
                    }
                    return result;
                }
            }
            throw new IOException("v10 bare-pack uvarint is too long");
        }

        byte[] readBytes(int length) throws IOException {
            if (length < 0 || length > data.length - position) {
                throw new IOException("truncated v10 bare-pack child");
            }
            byte[] value = Arrays.copyOfRange(data, position, position + length);
            position += length;
            return value;
        }

        void requireEnd() throws IOException {
            if (position != data.length) throw new IOException("trailing v10 bare-pack bytes");
        }
    }
}
