package com.babbur.waypointer.codec;

import com.babbur.waypointer.api.DefaultWaypointerApi;
import com.babbur.waypointer.api.RouteOverlaySpec;
import com.babbur.waypointer.api.RouteSpec;
import com.babbur.waypointer.api.WaypointerApi;
import com.babbur.waypointer.config.Storage;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZoneCanonicalizationIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void zoneCanonicalizationIsCaseInsensitiveAndPreservesMineshaftAliases() {
        assertEquals("dungeon_f7", Zone.canonicalId("  DUNGEON_F7  "));
        assertEquals("mineshaft_crystal", Zone.canonicalId("MINESHAFT_JASPER_CRYSTAL"));
        assertEquals("mineshaft_crystal", Zone.canonicalId("mineshaft_crystal"));
        assertEquals("dwarven_mines", Zone.canonicalId("GLACITE_TUNNELS"));
    }

    @Test
    void apiRoutesAndOverlaysWithMixedCaseZonesActivateInCanonicalZone() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("dungeon_f7", "Catacombs F7"));
        WaypointerApi api = new DefaultWaypointerApi(manager);

        api.createRoute(RouteSpec.builder().name("Route").zoneId("DUNGEON_F7").build());
        api.showRouteOverlay(RouteOverlaySpec.builder().name("Overlay").zoneId("Dungeon_F7").build());

        assertEquals(List.of("Route", "Overlay"),
                api.activeGroups().stream().map(group -> group.name()).toList());
        assertEquals(2, api.groupsForZone("DUNGEON_F7").size());
    }

    @Test
    void nativeRoundTripKeepsCanonicalZoneId() {
        WaypointGroup route = new WaypointGroup("route", "Route", "DUNGEON_F7");
        route.add(Waypoint.at(1, 2, 3));

        WaypointGroup decoded = WaypointCodec.decode(WaypointCodec.encode(List.of(route))).get(0);

        assertEquals("dungeon_f7", decoded.zoneId());
    }

    @Test
    void legacyNativeDecodeMigratesMixedCaseZoneId() throws Exception {
        WaypointGroup decoded = WaypointCodec.decode(legacyV7Payload("DUNGEON_F7")).get(0);

        assertEquals("dungeon_f7", decoded.zoneId());
    }

    @Test
    void legacyNativeDecodeMigratesRetiredDwarvenSurfaceZone() throws Exception {
        for (String legacyId : List.of(
                "great_glacite_lake", "glacite_tunnels", "dwarven_base_camp")) {
            WaypointGroup decoded = WaypointCodec.decode(legacyV7Payload(legacyId)).get(0);
            assertEquals("dwarven_mines", decoded.zoneId());
        }
    }

    @Test
    void v9DictionaryKeepsRetiredEntriesDecodableAtTheirOriginalIndexes() {
        assertEquals("great_glacite_lake", CodecZoneDictionary.idAt(46));
        assertEquals("glacite_tunnels", CodecZoneDictionary.idAt(47));
        assertEquals("dwarven_base_camp", CodecZoneDictionary.idAt(48));
        assertEquals("dwarven_mines", Zone.canonicalId(CodecZoneDictionary.idAt(46)));
        assertEquals("dwarven_mines", Zone.canonicalId(CodecZoneDictionary.idAt(47)));
        assertEquals("dwarven_mines", Zone.canonicalId(CodecZoneDictionary.idAt(48)));
    }

    @Test
    void fixedLegacyV9DictionaryAndInlinePayloadsMigrateAfterWireDecode() {
        List<LegacyV9Fixture> fixtures = List.of(
                new LegacyV9Fixture("great_glacite_lake", 1,
                        "WP:s/LQ:DjnlWq8{UYdyZ/ir]wqzX>1#H#@@!WB~~O91C52Vr*At)XG}(v{YjhOYXdSn_dX'N|\"D&4!"),
                new LegacyV9Fixture("great_glacite_lake", 0,
                        "WP:h0^){w}k1WRcR^>F+IV)}}O?)p\"~~EJM#^4YP2I4F0!f9E|$aw)!!"),
                new LegacyV9Fixture("glacite_tunnels", 1,
                        "WP:s/9{/&N=sxN15D2@G@\\LjF6\\'](==\"jIs}z:m[9TvrO/cd&yT=]s'+qoYf0[8geW2N_a\\L!!"),
                new LegacyV9Fixture("glacite_tunnels", 0,
                        "WP:h0^){w}k1WRcR^>FgNV)}}O?)p\"~~EJM#^4YP2I4F0!f9Nqa0V'!!"),
                new LegacyV9Fixture("dwarven_base_camp", 1,
                        "WP:s/K\"w![~~LBVy>&6i1tWZ)gt4Z?%n%]]!A_}}O?dG?/f6]j23k|0nv6SR{51J%^?J304s*^U#!!"),
                new LegacyV9Fixture("dwarven_base_camp", 0,
                        "WP:h0^){w}k1WRcR^>FYEV)}}O?)p\"~~EJM#^4YP2I4F0!f9BDny$\"!!")
        );

        for (LegacyV9Fixture fixture : fixtures) {
            DecodeDebug debug = WaypointCodec.debugDecode(fixture.payload());

            assertEquals(9, debug.version());
            assertEquals(fixture.contentKind(), WaypointCodec.v9ContentKind(debug.headerByte()));
            String expectedDebugZone = fixture.contentKind() == 0
                    ? fixture.legacyZoneId()
                    : "dwarven_mines";
            assertEquals(expectedDebugZone, debug.groups().get(0).zoneId());
            assertEquals("dwarven_mines", debug.decodedGroups().get(0).zoneId());
            assertEquals("dwarven_mines", WaypointCodec.decode(fixture.payload()).get(0).zoneId());
        }
    }

    @Test
    void fixedLegacyV8DictionaryPayloadsMigrateAfterWireDecode() {
        List<LegacyPayloadFixture> fixtures = List.of(
                new LegacyPayloadFixture("great_glacite_lake",
                        "WP:[\"^){w}k1WRcR^>FZ2V)on[;H$p5:-jqQGCU'!"),
                new LegacyPayloadFixture("glacite_tunnels",
                        "WP:[\"^){w}k1WRcR^>F88V)on[;H$p5iDGwPu7\"!"),
                new LegacyPayloadFixture("dwarven_base_camp",
                        "WP:[\"^){w}k1WRcR^>F)/V)on[;H$p5:-c5jmN7%!")
        );

        for (LegacyPayloadFixture fixture : fixtures) {
            DecodeDebug debug = WaypointCodec.debugDecode(fixture.payload());

            assertEquals(8, debug.version());
            assertEquals(fixture.legacyZoneId(), debug.groups().get(0).zoneId());
            assertEquals("dwarven_mines", debug.decodedGroups().get(0).zoneId());
            assertEquals("dwarven_mines", WaypointCodec.decode(fixture.payload()).get(0).zoneId());
        }
    }

    @Test
    void storageLoadMigratesMixedCaseZoneIdAndActivatesRoute() throws Exception {
        Path file = tempDir.resolve("waypoints.json");
        Files.writeString(file, """
                {
                  "schema": 1,
                  "groups": [{
                    "id": "mixed-case",
                    "name": "Mixed Case",
                    "zone": "DUNGEON_F7",
                    "waypoints": [{"x": 1, "y": 2, "z": 3}]
                  }]
                }
                """);
        ActiveGroupManager manager = new ActiveGroupManager();

        new Storage(file).load(manager);
        manager.onZoneChanged(new Zone("dungeon_f7", "Catacombs F7"));

        assertEquals("dungeon_f7", manager.get("mixed-case").zoneId());
        assertEquals(List.of("mixed-case"),
                manager.activeGroups().stream().map(WaypointGroup::id).toList());
    }

    private record LegacyV9Fixture(String legacyZoneId, int contentKind, String payload) {
    }

    private record LegacyPayloadFixture(String legacyZoneId, String payload) {
    }

    private static String legacyV7Payload(String zoneId) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(7 | 0x40);
        WaypointCodec.writeVarint(out, 0);
        writeUtf8(out, zoneId);
        out.writeByte(0x01 | 0x04);
        WaypointCodec.writeVarint(out, 1);
        WaypointCodec.writeZigzag(out, 0);
        WaypointCodec.writeZigzag(out, 64);
        WaypointCodec.writeZigzag(out, 0);
        out.flush();
        return WaypointCodec.MAGIC
                + WaypointCodec.escapeHypixelEmotes(AsciiStreamCodec.encode(deflate(raw.toByteArray())));
    }

    private static void writeUtf8(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        WaypointCodec.writeVarint(out, bytes.length);
        out.write(bytes);
    }

    private static byte[] deflate(byte[] raw) throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(compressed, deflater)) {
            stream.write(raw);
        } finally {
            deflater.end();
        }
        return compressed.toByteArray();
    }
}
