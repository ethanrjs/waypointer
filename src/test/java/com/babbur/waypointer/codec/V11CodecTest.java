package com.babbur.waypointer.codec;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.core.WaypointStyle;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V11CodecTest {

    @Test
    void everyRouteDetailSurvivesTheCompactForm() {
        List<WaypointGroup> routes = richRoutes();
        RouteLibraryMetadata folders = new RouteLibraryMetadata(List.of(), List.of(
                new RouteLibraryMetadata.FolderDefinition("Gems ✨", 0x2468AC, true, List.of(0))));
        WaypointCodec.Options options = WaypointCodec.Options.FULL_FIDELITY.toBuilder().label("Pack").build();

        String code = UniversalShareCodec.encodeWaypoints(routes, options, folders);

        assertEquals("V11_COMPACT_DIRECT", WaypointCodec.debugDecode(code).groups().getFirst().coordMode());
        V11Codec.Decoded decoded = V11Codec.decode(code);
        List<WaypointGroup> expected = V11Codec.project(routes, options);
        assertEquals(V11Codec.fingerprint(expected, "Pack", folders.folders()), V11Codec.fingerprint(decoded));
        assertEquals("tag:diamond_pickaxe", decoded.groups().getFirst().icon());
        assertEquals(List.of(0x123456, 0x654321, 0xABCDEF, 0x111111, 0x222222),
                decoded.groups().getFirst().manualColorSnapshot());
    }

    @Test
    void exportOptionsStripWhatTheyExclude() {
        WaypointCodec.Options noNames = WaypointCodec.Options.FULL_FIDELITY.toBuilder().includeNames(false).build();

        List<WaypointGroup> decoded = WaypointCodec.decode(UniversalShareCodec.encodeWaypoints(richRoutes(), noNames));

        assertTrue(decoded.stream().flatMap(g -> g.waypoints().stream()).noneMatch(Waypoint::hasName));
        assertEquals("Ruby", decoded.getFirst().name(), "route names stay");
    }

    @Test
    void tamperedSharesAreRejectedAsInvalid() {
        String code = UniversalShareCodec.encodeWaypoints(richRoutes(), WaypointCodec.Options.FULL_FIDELITY);
        assertTrue(V11Codec.isPayload(code));

        assertFalse(V11Codec.isPayload(code + "A"), "an extra character breaks the frame");
        assertFalse(V11Codec.isPayload(code.substring(0, code.length() - 1)));
        char[] flipped = code.toCharArray();
        flipped[10] = flipped[10] == 'A' ? 'B' : 'A';
        assertFalse(V11Codec.isPayload(new String(flipped)), "the checksum catches a changed character");
    }

    @Test
    void randomBodiesInAValidFrameFailCleanly() {
        Random random = new Random(11);
        int accepted = 0;
        for (int round = 0; round < 3000; round++) {
            byte[] body = new byte[1 + random.nextInt(40)];
            random.nextBytes(body);
            body[0] = (byte) (V11Codec.WIRE_VERSION | V11Codec.KIND_COMPACT << 4);
            String code = WaypointCodec.MAGIC + V10Transport.direct(body).transport();
            try {
                V11Codec.Decoded decoded = V11Codec.decode(code);
                assertNotNull(decoded);
                accepted++;
            } catch (IllegalArgumentException rejected) {
            }
        }
        assertTrue(accepted < 3000);
    }

    @Test
    void dungeonRoomsTravelAsCompactV11() {
        WaypointGroup room = WaypointGroup.create("FlameOfWar", "atlas");
        room.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        room.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        room.add(new Waypoint(13, 71, 55, "Bat", 0x9C5A2E,
                Waypoint.FLAG_SKIP_ON_STAND | Waypoint.FLAG_DUNGEON_BAT | Waypoint.FLAG_DUNGEON_SECRET, 0.0));
        room.add(new Waypoint(20, 68, 49, "", 0x9C2EFF,
                Waypoint.FLAG_SKIP_ON_STAND | Waypoint.FLAG_DUNGEON_ETHERWARP, 0.0));
        room.add(new Waypoint(23, 70, 49, "", 0x6EE7B7, Waypoint.FLAG_SUBWAYPOINT | Waypoint.FLAG_SKIP_ON_MINE
                | Waypoint.FLAG_DUNGEON_DUNGEONBREAKER | Waypoint.FLAG_STEP_ANY_ORDER, 0.0));

        String code = UniversalShareCodec.encodeDungeon(List.of(room));

        assertTrue(code.length() < 40, code);
        UniversalShareCodec.DungeonRoutes rooms = (UniversalShareCodec.DungeonRoutes) UniversalShareCodec.decode(code);
        assertEquals(V11Codec.fingerprint(V11Codec.project(List.of(room), WaypointCodec.Options.FULL_FIDELITY), "", List.of()),
                V11Codec.fingerprint(rooms.result().groups(), "", List.of()));
    }

    @Test
    void deeplyNestedJsonSharesAreRejectedNotFollowed() {
        String nested = "{\"routes\":" + "[".repeat(200_000) + "]".repeat(200_000) + "}";
        byte[] json = nested.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] semantic = new byte[json.length + 1];
        semantic[0] = (byte) (V11Codec.WIRE_VERSION | V11Codec.KIND_JSON << 4);
        System.arraycopy(json, 0, semantic, 1, json.length);
        String share = WaypointCodec.MAGIC + V11Codec.frame(semantic);

        assertThrows(IllegalArgumentException.class, () -> V11Codec.decode(share));
    }

    @Test
    void theShippedStartingStatisticsArePinned() throws Exception {
        assertNotNull(V11CompactCodec.model);
        try (InputStream in = V11CodecTest.class.getResourceAsStream(V11Model.RESOURCE)) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
            assertEquals("02468d7962b6aabc26feb60c929866cfa762a19e6662bee87803d75d663e08d2", hash);
        }
    }

    @Test
    void numberedNamesCostAlmostNothing() {
        WaypointGroup numbered = WaypointGroup.create("Mithril", "dwarven_mines");
        WaypointGroup unnamed = WaypointGroup.create("Mithril", "dwarven_mines");
        for (int i = 0; i < 60; i++) {
            numbered.add(new Waypoint(i * 5, 100 + (i % 7), -i * 3, Integer.toString(i + 1), Waypoint.DEFAULT_COLOR, 0, 0.0));
            unnamed.add(new Waypoint(i * 5, 100 + (i % 7), -i * 3, "", Waypoint.DEFAULT_COLOR, 0, 0.0));
        }

        int withNames = UniversalShareCodec.encodeWaypoints(List.of(numbered), WaypointCodec.Options.FULL_FIDELITY).length();
        int without = UniversalShareCodec.encodeWaypoints(List.of(unnamed), WaypointCodec.Options.FULL_FIDELITY).length();

        assertTrue(withNames - without <= 4, withNames + " vs " + without);
    }

    @Test
    void foldersMixingZonesAreRefusedBeforeEncoding() {
        RouteLibraryMetadata mixed = new RouteLibraryMetadata(List.of(), List.of(
                new RouteLibraryMetadata.FolderDefinition("Mixed", 0x2468AC, false, List.of(0, 1))));
        assertThrows(IllegalArgumentException.class, () -> UniversalShareCodec.encodeWaypoints(
                richRoutes(), WaypointCodec.Options.FULL_FIDELITY, mixed));
    }

    @Test
    void rejectsNothingToShare() {
        assertThrows(IllegalArgumentException.class, () -> V11Codec.encodeV11(List.of(), "", List.of()));
    }

    private static List<WaypointGroup> richRoutes() {
        WaypointGroup ruby = WaypointGroup.create("Ruby", "crystal_hollows");
        ruby.setIcon("tag:diamond_pickaxe");
        ruby.setStylePresetId("bright");
        ruby.setStyleOverride(new WaypointStyle(Map.of("showTracer", "false")));
        ruby.setDefaultRadius(4.5);
        ruby.setSkipAheadEnabled(false);
        ruby.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        ruby.add(new Waypoint(10, 70, 20, "1", 0x123456, 0, 0.0));
        ruby.add(new Waypoint(14, 72, 26, "2", 0x654321, Waypoint.FLAG_LOCKED_COLOR, 2.25));
        ruby.add(new Waypoint(18, 73, 29, "Ruby 7", 0xABCDEF, Waypoint.FLAG_SUBWAYPOINT, 0.0)
                .withPreciseSixteenths(18 * 16 + 3, 73 * 16 + 12, 29 * 16));
        ruby.add(new Waypoint(-30, 60, 40, "Ruby 8", 0x111111, 0xFFFF_FFFF, 0.0)
                .withStyleOverride(new WaypointStyle(Map.of("labelScale", "1.5"))));
        ruby.add(new Waypoint(-31, 61, 41, "42", 0x222222, 0, 0.0));
        ruby.setGradientMode(WaypointGroup.GradientMode.AUTO);

        WaypointGroup topaz = WaypointGroup.create("Topaz ✨", "custom_island_name");
        topaz.setLoadMode(WaypointGroup.LoadMode.STATIC);
        topaz.setGradientMode(WaypointGroup.GradientMode.STATIC);
        topaz.setStaticColor(0xFFAA00);
        int[] palette = new int[WaypointPaint.PALETTE_SIZE];
        for (int i = 0; i < palette.length; i++) palette[i] = i * 0x111111;
        byte[] pixels = new byte[6 * WaypointPaint.SIZE * WaypointPaint.SIZE];
        for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) (i % WaypointPaint.PALETTE_SIZE);
        topaz.setPaint(new WaypointPaint(palette, pixels));
        topaz.add(new Waypoint(1_000_000, -64, -1_000_000, "ナビ", 0xFF123456, 0, 100.0));
        topaz.add(new Waypoint(1_000_003, 300, -999_990, "", Waypoint.DEFAULT_COLOR, 0, 0.0,
                Waypoint.TEMP_TIME, 5L));

        WaypointGroup room = WaypointGroup.create("FlameOfWar", "atlas");
        room.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        room.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        room.add(new Waypoint(31, 78, 52, "Chest", 0x2EE0FF,
                Waypoint.FLAG_SKIP_ON_INTERACT | Waypoint.FLAG_DUNGEON_SECRET, 0.0));
        room.add(new Waypoint(32, 78, 52, "Odd colour", 0xFF123456, 0, 0.0));

        List<WaypointGroup> out = new ArrayList<>(List.of(ruby, topaz, room));
        return out;
    }
}
