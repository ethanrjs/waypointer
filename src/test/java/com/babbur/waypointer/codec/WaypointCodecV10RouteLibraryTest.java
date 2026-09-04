package com.babbur.waypointer.codec;

import com.babbur.waypointer.chat.CodecScanner;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kind 6 subtype 1: folders, manual colors, and paints under the universal {@code WP:} prefix. */
class WaypointCodecV10RouteLibraryTest {

    @Test
    void libraryShareUsesUniversalPrefixAndRoundTripsEveryMetadataFamily() throws IOException {
        Fixture fixture = fixture();
        String encoded = RouteLibraryCodec.encode(
                fixture.snapshots(),
                WaypointCodec.Options.FULL_FIDELITY.toBuilder().label("Mining pack").build(),
                fixture.metadata());

        assertTrue(encoded.startsWith(WaypointCodec.MAGIC));
        assertFalse(encoded.startsWith(RouteLibraryCodec.MAGIC));
        V10Transport.CheckedFrame frame = V10Transport.probe(
                encoded.substring(WaypointCodec.MAGIC.length()));
        assertEquals(V10RouteLibraryCodec.SEMANTIC_HEADER, frame.header());
        assertEquals(V10RouteLibraryCodec.CONTENT_KIND, frame.contentKind());
        assertTrue(V10RouteLibraryCodec.isLibrarySemantic(frame.semantic()));

        // Every import surface sees the same content.
        WaypointImporter.ImportResult imported = WaypointImporter.importAny(encoded);
        assertEquals("Mining pack", imported.label());
        assertEquals(fixture.metadata(), imported.libraryMetadata());
        assertEquals(fixture.hiddenColors(), imported.groups().getFirst().manualColorSnapshot());
        assertEquals(fixture.paint(), imported.groups().get(1).paint());
        assertTrue(imported.groups().get(1).paintEnabled());

        WaypointCodec.Decoded routeOnly = WaypointCodec.decodeFull(encoded);
        assertEquals(fixture.metadata(), routeOnly.metadata());
        assertEquals(fixture.hiddenColors(), routeOnly.groups().getFirst().manualColorSnapshot());
        assertEquals(fixture.paint(), routeOnly.groups().get(1).paint());

        UniversalShareCodec.Decoded universal = UniversalShareCodec.decode(encoded);
        assertEquals(UniversalShareCodec.Type.WAYPOINTS, universal.type());

        ActiveGroupManager target = new ActiveGroupManager();
        target.addAll(imported.groups());
        imported.libraryMetadata().installFolders(target, imported.groups());
        RouteFolder installed = target.folderForGroup(imported.groups().getFirst().id());
        assertEquals("Mining", installed.name());
        assertEquals(0x2468AC, installed.color());
        assertTrue(installed.collapsed());
        assertEquals(2, target.groupIdsInFolder(installed.id()).size());

        // Chat UX: detected as a route share, hover label readable without a full decode.
        assertTrue(WaypointCodec.isValidCodec(encoded));
        CodecScanner.Match match = CodecScanner.scan("take this " + encoded + "!").getFirst();
        assertTrue(match.valid());
        assertEquals(UniversalShareCodec.Type.WAYPOINTS, match.type());
        assertEquals("Mining pack", WaypointCodec.peekLabel(encoded).orElseThrow());

        DecodeDebug debug = WaypointCodec.debugDecode(encoded);
        assertEquals(10, debug.version());
        assertEquals("Mining pack", debug.label());
        assertTrue(debug.groups().stream().allMatch(group ->
                group.coordMode().startsWith("V10_LIBRARY_")));
    }

    @Test
    void semanticBodyIsCanonicalAndDecoderRejectsTamperedMetadata() throws IOException {
        Fixture fixture = fixture();
        byte[] semantic = V10RouteLibraryCodec.encodeSemantic(
                fixture.snapshots(), WaypointCodec.Options.FULL_FIDELITY, fixture.metadata());

        assertEquals(0x6A, semantic[0] & 0xFF);
        assertEquals(1, semantic[1] & 0xFF);
        assertEquals(fixture.metadata(),
                V10RouteLibraryCodec.decode(direct(semantic)).metadata());

        // Subtype 2 is reserved.
        assertRejected(semantic, body -> { body[1] = 2; return body; });
        // Trailing bytes.
        assertRejected(semantic, body -> Arrays.copyOf(body, body.length + 1));
        // Truncation.
        assertRejected(semantic, body -> Arrays.copyOf(body, body.length - 1));
        // Manual-color ordinal pushed out of bounds.
        int manualOrdinalOffset = manualColorOrdinalOffset(semantic);
        assertRejected(semantic, body -> { body[manualOrdinalOffset] = 9; return body; });
        // Folder reserved flag bit.
        int folderFlagOffset = folderFlagOffset(semantic);
        assertEquals(V10RouteLibraryCodec.FOLDER_FLAG_COLLAPSED, semantic[folderFlagOffset]);
        assertRejected(semantic, body -> { body[folderFlagOffset] = 0x03; return body; });
        // A committed V10 frame with a bad body must not fall back to legacy decoding.
        byte[] reserved = semantic.clone();
        reserved[1] = 2;
        String tampered = WaypointCodec.MAGIC + V10Transport.direct(reserved).transport();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> WaypointCodec.decode(tampered));
        assertTrue(failure.getMessage().contains("v10"), failure.getMessage());
    }

    @Test
    void libraryWithoutMetadataIsAPlainRouteShareAndPixelPackingIsLossless() {
        WaypointGroup route = WaypointGroup.create("Plain", "hub");
        route.add(Waypoint.at(1, 2, 3));
        String plain = RouteLibraryCodec.encode(
                List.of(route), WaypointCodec.Options.FULL_FIDELITY,
                RouteLibraryMetadata.empty());
        assertEquals(WaypointCodec.encode(List.of(route)), plain);
        assertThrows(IllegalArgumentException.class, () ->
                V10RouteLibraryCodec.encodeSemantic(List.of(route),
                        WaypointCodec.Options.FULL_FIDELITY, RouteLibraryMetadata.empty()));

        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) ((i * 7) % 16);
        byte[] packed = V10RouteLibraryCodec.packPixels(pixels);
        assertEquals(V10RouteLibraryCodec.PACKED_PIXEL_BYTES, packed.length);
        assertArrayEquals(pixels, V10RouteLibraryCodec.unpackPixels(packed));
    }

    @Test
    void coordinateOnlyOptionsStillSkipTheLibraryEntirely() {
        Fixture fixture = fixture();
        String bare = RouteLibraryCodec.encode(
                fixture.snapshots(), WaypointCodec.Options.BARE_COORDINATES, fixture.metadata());
        assertTrue(bare.startsWith(WaypointCodec.MAGIC));
        assertTrue(WaypointImporter.importAny(bare).libraryMetadata().isEmpty());
    }

    private static V10Transport.CheckedFrame direct(byte[] semantic) throws IOException {
        return V10Transport.probe(V10Transport.direct(semantic).transport());
    }

    private static void assertRejected(byte[] semantic, UnaryOperator<byte[]> mutation) {
        byte[] mutated = mutation.apply(semantic.clone());
        assertThrows(IOException.class, () -> V10RouteLibraryCodec.decode(direct(mutated)));
    }

    /** Offset of the first manual-color group ordinal: header, subtype, length varint, route, count. */
    private static int manualColorOrdinalOffset(byte[] semantic) {
        int offset = 2;
        int routeLength = 0;
        int shift = 0;
        while (true) {
            int b = semantic[offset++] & 0xFF;
            routeLength |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        offset += routeLength;
        return offset + 1;
    }

    /** The fixture has one manual-color entry with two colors, then one folder. */
    private static int folderFlagOffset(byte[] semantic) {
        int offset = manualColorOrdinalOffset(semantic);
        offset += 1; // ordinal
        offset += 1; // color count (2)
        offset += 2 * 3; // colors
        offset += 1; // folder count
        int nameLength = semantic[offset] & 0xFF;
        offset += 1 + nameLength;
        offset += 3; // folder color
        return offset;
    }

    private record Fixture(List<WaypointGroup> snapshots, RouteLibraryMetadata metadata,
                           List<Integer> hiddenColors, WaypointPaint paint) {}

    private static Fixture fixture() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup first = new WaypointGroup("first", "First", "hub");
        first.add(Waypoint.at(1, 70, 1).withColor(0x102030));
        first.add(Waypoint.at(4, 71, 6).withColor(0x445566));
        first.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        first.set(0, first.get(0).withColor(0xABCDEF));
        first.set(1, first.get(1).withColor(0x123456));
        List<Integer> hiddenColors = first.manualColorSnapshot();
        first.setStaticColor(0x0A0B0C);
        first.setGradientMode(WaypointGroup.GradientMode.STATIC);

        WaypointGroup second = new WaypointGroup("second", "Second", "hub");
        second.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        second.add(Waypoint.at(9, 72, 9).withColor(0x102030));
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) (i % WaypointPaint.PALETTE_SIZE);
        WaypointPaint paint = new WaypointPaint(WaypointPaint.defaultPalette(0x654321), pixels);
        second.setPaint(paint);
        second.setPaintEnabled(true);

        manager.addAll(List.of(first, second));
        manager.addFolder(new RouteFolder(
                "source-folder", "Mining", "hub", true, 0x2468AC),
                List.of(first.id(), second.id()));
        List<WaypointGroup> live = List.of(first, second);
        RouteLibraryMetadata metadata = RouteLibraryMetadata.capture(manager, live);
        assertEquals(1, metadata.manualColors().size());
        assertEquals(1, metadata.folders().size());
        assertEquals(1, metadata.paints().size());
        return new Fixture(
                live.stream().map(WaypointGroup::exportSnapshot).toList(),
                metadata, hiddenColors, paint);
    }
}
