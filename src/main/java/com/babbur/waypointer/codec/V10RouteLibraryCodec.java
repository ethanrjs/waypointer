package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire-v10 kind 6, subtype 1: one general route body plus route-library
 * metadata (manual color snapshots, folders, and waypoint paints).
 *
 * <p>The route body is a complete kind-0 semantic body carried by length so a
 * decoder can hand it to the general-route reader unchanged. The metadata that
 * follows refers to routes by their ordinal inside that body. This subtype
 * replaces the legacy {@code WPL:1:} JSON wrapper for outbound shares; the
 * wrapper remains importable and is still written when a library exceeds the
 * bounded V10 frame profile.
 *
 * <pre>{@code
 * 0x6A                          header
 * subtype      : uvarint        MUST be 1
 * routeLength  : uvarint >= 1
 * route        : kind-0 semantic body
 * manualCount  : uvarint        0..256, strictly ascending group ordinals
 *   ordinal    : uvarint
 *   colorCount : uvarint        MUST equal that route's point count
 *   colors     : u24be x colorCount
 * folderCount  : uvarint        0..256
 *   name       : uvarint length + strict UTF-8 (1..256 bytes, trimmed, nonblank)
 *   color      : u24be
 *   flags      : u8             bit 0 collapsed; other bits MUST be zero
 *   memberCount: uvarint        1..256
 *   members    : uvarint x memberCount (route ordinals, no duplicates)
 * paintCount   : uvarint        0..256, strictly ascending group ordinals
 *   ordinal    : uvarint
 *   enabled    : u8             0 or 1
 *   palette    : u24be x 16
 *   pixels     : 768 bytes      two 4-bit palette slots per byte, low nibble first
 * }</pre>
 *
 * <p>At least one metadata entry MUST be present; a library without metadata is
 * a plain kind-0 route and MUST be written as one.
 */
final class V10RouteLibraryCodec {

    static final int CONTENT_KIND = V10BareRoutePackCodec.CONTENT_KIND;
    static final int SEMANTIC_HEADER = V10BareRoutePackCodec.SEMANTIC_HEADER;
    static final int SUBTYPE_ROUTE_LIBRARY = 1;
    static final int FOLDER_FLAG_COLLAPSED = 1;
    static final int PACKED_PIXEL_BYTES = WaypointPaint.PIXEL_COUNT / 2;

    private static final int CHECKSUM_BYTES = V10Transport.CHECKSUM_BYTES;
    private static final int MAX_SEMANTIC_BYTES = V10Transport.MAX_FRAME_BYTES - CHECKSUM_BYTES;
    private static final int MAX_RGB = 0xFFFFFF;

    private V10RouteLibraryCodec() {}

    /** True when a committed kind-6 semantic body declares the route-library subtype. */
    static boolean isLibrarySemantic(byte[] semantic) {
        return semantic != null && semantic.length >= 2
                && (semantic[0] & 0xFF) == SEMANTIC_HEADER
                && (semantic[1] & 0xFF) == SUBTYPE_ROUTE_LIBRARY;
    }

    static V10Transport.Outbound encodeCandidate(
            List<WaypointGroup> groups, WaypointCodec.Options options,
            RouteLibraryMetadata metadata) throws IOException {
        return V10GeneralRouteCodec.selectCandidate(encodeSemantic(groups, options, metadata));
    }

    static byte[] encodeSemantic(
            List<WaypointGroup> groups, WaypointCodec.Options options,
            RouteLibraryMetadata metadata) throws IOException {
        if (metadata == null || metadata.isEmpty()) {
            throw new IllegalArgumentException("v10 route library requires metadata");
        }
        WaypointCodec.validateEncodeInputForV10(groups, options);
        metadata.validateForGroups(groups);
        byte[] route = WaypointCodec.encodeV10GeneralSemantic(
                groups, options, WaypointCodec.PackingMode.AUTO);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(route.length + 64);
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeByte(SEMANTIC_HEADER);
        WaypointCodec.writeVarint(out, SUBTYPE_ROUTE_LIBRARY);
        WaypointCodec.writeVarint(out, route.length);
        out.write(route);

        List<RouteLibraryMetadata.ManualColorsEntry> manualColors =
                sortedByOrdinal(metadata.manualColors(),
                        RouteLibraryMetadata.ManualColorsEntry::groupOrdinal);
        WaypointCodec.writeVarint(out, manualColors.size());
        for (RouteLibraryMetadata.ManualColorsEntry entry : manualColors) {
            WaypointCodec.writeVarint(out, entry.groupOrdinal());
            WaypointCodec.writeVarint(out, entry.colors().size());
            for (int color : entry.colors()) writeRgb(out, color);
        }

        WaypointCodec.writeVarint(out, metadata.folders().size());
        for (RouteLibraryMetadata.FolderDefinition folder : metadata.folders()) {
            writeString(out, folder.name());
            writeRgb(out, folder.color());
            out.writeByte(folder.collapsed() ? FOLDER_FLAG_COLLAPSED : 0);
            WaypointCodec.writeVarint(out, folder.memberOrdinals().size());
            for (int ordinal : folder.memberOrdinals()) WaypointCodec.writeVarint(out, ordinal);
        }

        List<RouteLibraryMetadata.PaintEntry> paints = sortedByOrdinal(
                metadata.paints(), RouteLibraryMetadata.PaintEntry::groupOrdinal);
        WaypointCodec.writeVarint(out, paints.size());
        for (RouteLibraryMetadata.PaintEntry entry : paints) {
            WaypointCodec.writeVarint(out, entry.groupOrdinal());
            out.writeByte(entry.enabled() ? 1 : 0);
            for (int color : entry.paint().paletteCopy()) writeRgb(out, color);
            out.write(packPixels(entry.paint().pixelsCopy()));
        }
        out.flush();

        byte[] semantic = buffer.toByteArray();
        if (semantic.length > MAX_SEMANTIC_BYTES) {
            throw new V10ProfileLimitException(
                    "v10 route library semantic body exceeds the 2 MiB frame profile");
        }
        return semantic;
    }

    static RouteLibraryCodec.Decoded decode(V10Transport.CheckedFrame frame) throws IOException {
        if (frame.contentKind() != CONTENT_KIND) {
            throw new IOException("unsupported v10 content kind " + frame.contentKind());
        }
        try {
            return decodeBody(frame.semantic());
        } catch (EOFException truncated) {
            throw new IOException("truncated v10 route library body", truncated);
        }
    }

    private static RouteLibraryCodec.Decoded decodeBody(byte[] semantic) throws IOException {
        if (semantic.length > MAX_SEMANTIC_BYTES) {
            throw new IOException("v10 route library semantic length is outside limit");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(semantic));
        int header = in.readUnsignedByte();
        if (header != SEMANTIC_HEADER) {
            throw new IOException("unsupported v10 route library semantic header 0x"
                    + Integer.toHexString(header));
        }
        int subtype = WaypointCodec.readVarint(in);
        if (subtype != SUBTYPE_ROUTE_LIBRARY) {
            throw new IOException("unsupported v10 kind-6 subtype " + subtype);
        }

        int routeLength = WaypointCodec.readVarint(in);
        if (routeLength < 1 || routeLength > in.available()) {
            throw new IOException("v10 route library route length is outside limit");
        }
        byte[] route = in.readNBytes(routeLength);
        WaypointCodec.Decoded inner = WaypointCodec.decodeV10GeneralSemantic(route);
        List<WaypointGroup> groups = inner.groups();
        int groupCount = groups.size();

        List<RouteLibraryMetadata.ManualColorsEntry> manualColors = new ArrayList<>();
        int manualCount = readBoundedCount(in, RouteLibraryMetadata.MAX_GROUPS,
                "v10 route library manual color count");
        int previousOrdinal = -1;
        for (int index = 0; index < manualCount; index++) {
            int ordinal = readAscendingOrdinal(in, previousOrdinal, groupCount,
                    "v10 route library manual color");
            previousOrdinal = ordinal;
            int colorCount = WaypointCodec.readVarint(in);
            if (colorCount != groups.get(ordinal).size()) {
                throw new IOException("v10 route library manual color count does not match"
                        + " route " + ordinal);
            }
            List<Integer> colors = new ArrayList<>(colorCount);
            for (int colorIndex = 0; colorIndex < colorCount; colorIndex++) {
                colors.add(readRgb(in));
            }
            manualColors.add(wrap(() -> new RouteLibraryMetadata.ManualColorsEntry(
                    ordinal, colors)));
        }

        List<RouteLibraryMetadata.FolderDefinition> folders = new ArrayList<>();
        int folderCount = readBoundedCount(in, RouteLibraryMetadata.MAX_FOLDERS,
                "v10 route library folder count");
        for (int index = 0; index < folderCount; index++) {
            String name = readString(in, RouteLibraryMetadata.MAX_FOLDER_NAME_BYTES);
            if (name.isEmpty() || !name.equals(name.trim())) {
                throw new IOException("v10 route library folder name is not canonical");
            }
            int color = readRgb(in);
            int flags = in.readUnsignedByte();
            if ((flags & ~FOLDER_FLAG_COLLAPSED) != 0) {
                throw new IOException("v10 route library folder uses reserved flag bits");
            }
            int memberCount = WaypointCodec.readVarint(in);
            if (memberCount < 1 || memberCount > RouteLibraryMetadata.MAX_GROUPS) {
                throw new IOException("v10 route library folder member count is outside limit");
            }
            List<Integer> members = new ArrayList<>(memberCount);
            for (int memberIndex = 0; memberIndex < memberCount; memberIndex++) {
                int ordinal = WaypointCodec.readVarint(in);
                if (ordinal >= groupCount) {
                    throw new IOException("v10 route library folder member is out of bounds");
                }
                members.add(ordinal);
            }
            boolean collapsed = (flags & FOLDER_FLAG_COLLAPSED) != 0;
            folders.add(wrap(() -> new RouteLibraryMetadata.FolderDefinition(
                    name, color, collapsed, members)));
        }

        List<RouteLibraryMetadata.PaintEntry> paints = new ArrayList<>();
        int paintCount = readBoundedCount(in, RouteLibraryMetadata.MAX_GROUPS,
                "v10 route library paint count");
        previousOrdinal = -1;
        for (int index = 0; index < paintCount; index++) {
            int ordinal = readAscendingOrdinal(in, previousOrdinal, groupCount,
                    "v10 route library paint");
            previousOrdinal = ordinal;
            int enabledByte = in.readUnsignedByte();
            if (enabledByte > 1) {
                throw new IOException("v10 route library paint has non-canonical enabled flag");
            }
            int[] palette = new int[WaypointPaint.PALETTE_SIZE];
            for (int slot = 0; slot < palette.length; slot++) palette[slot] = readRgb(in);
            byte[] packed = in.readNBytes(PACKED_PIXEL_BYTES);
            if (packed.length != PACKED_PIXEL_BYTES) {
                throw new IOException("truncated v10 route library paint pixels");
            }
            WaypointPaint paint = wrap(() -> new WaypointPaint(palette, unpackPixels(packed)));
            paints.add(new RouteLibraryMetadata.PaintEntry(ordinal, paint, enabledByte == 1));
        }

        if (in.available() != 0) {
            throw new IOException("trailing v10 route library bytes");
        }
        RouteLibraryMetadata metadata = wrap(
                () -> new RouteLibraryMetadata(manualColors, folders, paints));
        if (metadata.isEmpty()) {
            throw new IOException("v10 route library carries no metadata");
        }
        wrap(() -> {
            metadata.validateForGroups(groups);
            return null;
        });
        return new RouteLibraryCodec.Decoded(groups, inner.label(), metadata);
    }

    private static <T> List<T> sortedByOrdinal(
            List<T> entries, java.util.function.ToIntFunction<T> ordinal) {
        List<T> sorted = new ArrayList<>(entries);
        sorted.sort(java.util.Comparator.comparingInt(ordinal));
        return sorted;
    }

    private static int readBoundedCount(DataInputStream in, int maximum, String field)
            throws IOException {
        int count = WaypointCodec.readVarint(in);
        if (count < 0 || count > maximum) {
            throw new IOException(field + " is outside limit: " + count);
        }
        return count;
    }

    private static int readAscendingOrdinal(DataInputStream in, int previous, int groupCount,
                                            String field) throws IOException {
        int ordinal = WaypointCodec.readVarint(in);
        if (ordinal <= previous) {
            throw new IOException(field + " ordinals must be strictly ascending");
        }
        if (ordinal >= groupCount) {
            throw new IOException(field + " group ordinal is out of bounds");
        }
        return ordinal;
    }

    private static void writeRgb(DataOutputStream out, int color) throws IOException {
        if (color < 0 || color > MAX_RGB) {
            throw new IllegalArgumentException("route library color is outside the RGB range");
        }
        out.writeByte(color >>> 16);
        out.writeByte(color >>> 8);
        out.writeByte(color);
    }

    private static int readRgb(DataInputStream in) throws IOException {
        int red = in.readUnsignedByte();
        int green = in.readUnsignedByte();
        int blue = in.readUnsignedByte();
        return (red << 16) | (green << 8) | blue;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = WaypointCodec.encodeUtf8Strict(value);
        if (bytes.length == 0 || bytes.length > RouteLibraryMetadata.MAX_FOLDER_NAME_BYTES) {
            throw new IllegalArgumentException("route library folder name length is outside limit");
        }
        WaypointCodec.writeVarint(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int maximumBytes) throws IOException {
        int length = WaypointCodec.readVarint(in);
        if (length < 1 || length > maximumBytes) {
            throw new IOException("v10 route library string length is outside limit");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("truncated v10 route library string");
        return WaypointCodec.decodeUtf8Strict(bytes);
    }

    static byte[] packPixels(byte[] pixels) {
        if (pixels.length != WaypointPaint.PIXEL_COUNT) {
            throw new IllegalArgumentException("waypoint paint must contain 1536 pixels");
        }
        byte[] packed = new byte[PACKED_PIXEL_BYTES];
        for (int index = 0; index < packed.length; index++) {
            int low = pixels[index * 2] & 0x0F;
            int high = pixels[index * 2 + 1] & 0x0F;
            packed[index] = (byte) (low | (high << 4));
        }
        return packed;
    }

    static byte[] unpackPixels(byte[] packed) {
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        for (int index = 0; index < packed.length; index++) {
            pixels[index * 2] = (byte) (packed[index] & 0x0F);
            pixels[index * 2 + 1] = (byte) ((packed[index] >>> 4) & 0x0F);
        }
        return pixels;
    }

    private interface Construction<T> {
        T build();
    }

    /** Model validation failures become decode failures rather than escaping as runtime errors. */
    private static <T> T wrap(Construction<T> construction) throws IOException {
        try {
            return construction.build();
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new IOException("invalid v10 route library metadata: "
                    + failure.getMessage(), failure);
        }
    }
}
