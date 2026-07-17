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
