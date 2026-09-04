package com.babbur.waypointer.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.babbur.waypointer.codec.WaypointImporter;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.CatalogRouteProvenance;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the JSON codec. Avoids FabricLoader calls by using the
 * package-private static helpers directly.
 */
class StorageJsonTest {

    @TempDir
    Path tempDir;

    @Test
    void loadBoundsUnsafePersistedReachRadii() {
        JsonObject groupJson = new JsonObject();
        groupJson.addProperty("zone", "hub");
        groupJson.addProperty("defaultRadius", Double.POSITIVE_INFINITY);
        WaypointGroup group = Storage.groupFromJson(groupJson);

        JsonObject waypointJson = new JsonObject();
        waypointJson.addProperty("x", 0);
        waypointJson.addProperty("y", 64);
        waypointJson.addProperty("z", 0);
        waypointJson.addProperty("radius", 1_000_000.0);
        Waypoint waypoint = Storage.waypointFromJson(waypointJson);

        assertEquals(Waypoint.DEFAULT_REACH_RADIUS, group.defaultRadius());
        assertEquals(Waypoint.MAX_REACH_RADIUS, waypoint.customRadius());
    }

    @Test
    void waypoint_roundTripPreservesAllFields() {
        Waypoint original = new Waypoint(-123, 67, 512, "Terminal 3", 0xABCDEF,
                Waypoint.FLAG_THROUGH_WALL | Waypoint.FLAG_LOCKED_COLOR
                        | Waypoint.FLAG_DISABLED, 4.5);
        JsonObject json = Storage.waypointToJson(original);
        Waypoint copy = Storage.waypointFromJson(json);
        assertEquals(original, copy);
    }

    @Test
    void waypoint_omitsOptionalFieldsWhenDefault() {
        Waypoint plain = Waypoint.at(1, 2, 3);
        JsonObject json = Storage.waypointToJson(plain);
        assertFalse(json.has("name"),   "empty name should not serialize");
        assertFalse(json.has("flags"),  "zero flags should not serialize");
        assertFalse(json.has("radius"), "default radius should not serialize");
        assertFalse(json.has("preciseX"), "default block-center precision should not serialize");
    }

    @Test
    void waypoint_precisePositionRoundTripsWhenCustom() {
        Waypoint original = Waypoint.at(1, 2, -2)
                .withPreciseSixteenths(20, 39, -25);

        JsonObject json = Storage.waypointToJson(original);
        Waypoint copy = Storage.waypointFromJson(json);

        assertEquals(20, json.get("preciseX").getAsInt());
        assertEquals(39, json.get("preciseY").getAsInt());
        assertEquals(-25, json.get("preciseZ").getAsInt());
        assertEquals(original.preciseX(), copy.preciseX());
        assertEquals(original.preciseY(), copy.preciseY());
        assertEquals(original.preciseZ(), copy.preciseZ());
        assertEquals(1, copy.x());
        assertEquals(2, copy.y());
        assertEquals(-2, copy.z());
    }

    @Test
    void group_dropsTempWaypointsOnSave() {
        // Temp waypoints intentionally don't survive a save -- they're session
        // scratch. We stuff three of them into a group plus one real waypoint
        // and expect only the real one to survive the round-trip.
        WaypointGroup g = WaypointGroup.create("temp-route", "dungeon_f7");
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.add(Waypoint.at(1, 10, 1).withName("keeper"));
        g.add(Waypoint.at(2, 10, 2).withName("timed")
                .withTemp(Waypoint.TEMP_TIME, System.currentTimeMillis() + 60_000));
        g.add(Waypoint.at(3, 10, 3).withName("reach")
                .withTemp(Waypoint.TEMP_UNTIL_REACHED, 0L));
        g.add(Waypoint.at(4, 10, 4).withName("leave")
                .withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0L));

        JsonObject json = Storage.groupToJson(g);
        WaypointGroup copy = Storage.groupFromJson(json);

        assertEquals(1, copy.size(), "temp waypoints must not round-trip");
        assertEquals("keeper", copy.get(0).name());
        assertFalse(copy.get(0).isTemp(), "surviving waypoint is not temporary");
    }

    @Test
    void group_catalogProvenanceRoundTripsButMalformedOptionalDataIsDropped() {
        WaypointGroup group = WaypointGroup.create("Catalog route", "hub");
        CatalogRouteProvenance provenance = new CatalogRouteProvenance(
                "https://catalog.example/api/", "Abcdefghijklmnopqrstuv",
                3, 9, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 1, 2);
        group.setCatalogProvenance(provenance);

        JsonObject json = Storage.groupToJson(group);
        assertEquals(provenance, Storage.groupFromJson(json).catalogProvenance());

        json.getAsJsonObject("catalogSource").addProperty("groupIndex", 5);
        WaypointGroup recovered = assertDoesNotThrow(() -> Storage.groupFromJson(json));
        assertNull(recovered.catalogProvenance());
        assertEquals(group.id(), recovered.id());
    }

    @Test
    void save_skipsTempGroups() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup real = WaypointGroup.create("real", "hub");
        real.add(Waypoint.at(1, 2, 3).withName("keeper"));
        WaypointGroup temp = new WaypointGroup("temp::hub", "Temporary", "hub");
        temp.setTemp(true);
        temp.add(Waypoint.at(4, 5, 6).withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0L));
        manager.add(real);
        manager.add(temp);

        Path file = tempDir.resolve("waypoints.json");
        new Storage(file).save(manager);
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();

        assertEquals(1, root.getAsJsonArray("groups").size());
        assertEquals("real", root.getAsJsonArray("groups").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void save_skipsRuntimeOnlyGroups() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup real = WaypointGroup.create("real", "hub");
        real.add(Waypoint.at(1, 2, 3).withName("keeper"));
        WaypointGroup runtime = new WaypointGroup("runtime::hub", "Runtime", "hub");
        runtime.setRuntimeOnly(true);
        runtime.add(Waypoint.at(4, 5, 6).withName("generated"));
        manager.add(real);
        manager.add(runtime);

        Path file = tempDir.resolve("waypoints.json");
        new Storage(file).save(manager);
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();

        assertEquals(1, root.getAsJsonArray("groups").size());
        assertEquals("real", root.getAsJsonArray("groups").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void attached_save_snapshots_live_state_at_pump_time() throws Exception {
        // Snapshots are deferred to the pump, so a mutation made directly on a
        // group after the manager event -- which fires no event of its own --
        // still reaches disk instead of being silently dropped until the next
        // unrelated change. The snapshot is taken on the pumping thread, so it
        // is still one consistent point in time, just a later one.
        ActiveGroupManager manager = new ActiveGroupManager();
        Path file = tempDir.resolve("waypoints.json");
        Storage storage = new Storage(file);
        storage.attach(manager);

        WaypointGroup group = WaypointGroup.create("snap", "hub");
        group.add(Waypoint.at(1, 2, 3).withName("before"));
        manager.add(group);
        group.add(Waypoint.at(4, 5, 6).withName("after"));

        storage.flush();

        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject savedGroup = root.getAsJsonArray("groups").get(0).getAsJsonObject();

        assertEquals(2, savedGroup.getAsJsonArray("waypoints").size());
        assertEquals("before", savedGroup.getAsJsonArray("waypoints")
                .get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("after", savedGroup.getAsJsonArray("waypoints")
                .get(1).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void failedFlushRemainsDirtyAndRetriesTheSameSnapshot() throws Exception {
        Path blockedParent = tempDir.resolve("not-a-directory");
        Files.writeString(blockedParent, "occupied");
        Path file = blockedParent.resolve("waypoints.json");
        ActiveGroupManager manager = new ActiveGroupManager();
        Storage storage = new Storage(file);
        storage.attach(manager);
        manager.add(WaypointGroup.create("Retry me", "hub"));

        assertThrows(UncheckedIOException.class, storage::flush);
        assertEquals(0, storage.writeCount());

        Files.delete(blockedParent);
        Files.createDirectory(blockedParent);
        storage.flush();

        assertEquals(1, storage.writeCount());
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals("Retry me", root.getAsJsonArray("groups").get(0)
                .getAsJsonObject().get("name").getAsString());
    }

    @Test
    void attachedStorageIgnoresTransientGroupsAndSnapshotsPersistentChangesOnce() {
        ActiveGroupManager manager = new ActiveGroupManager();
        Storage storage = new Storage(tempDir.resolve("waypoints.json"));
        storage.attach(manager);
        int snapshotsAfterAttach = storage.snapshotCount();

        WaypointGroup temp = new WaypointGroup("temp::hub", "Temporary", "hub");
        temp.setTemp(true);
        temp.add(Waypoint.at(1, 2, 3).withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0L));
        manager.add(temp);
        manager.clearTemporaryWaypoints();

        WaypointGroup overlay = new WaypointGroup("api-overlay::test", "Overlay", "hub");
        overlay.setTemp(true);
        overlay.add(Waypoint.at(4, 5, 6).withTemp(Waypoint.TEMP_UNTIL_LEAVE, 0L));
        manager.add(overlay);
        manager.remove(overlay.id());

        WaypointGroup runtime = new WaypointGroup("dungeon:auto:test", "Runtime", "hub");
        runtime.setRuntimeOnly(true);
        manager.add(runtime);
        WaypointGroup rebuiltRuntime = new WaypointGroup(runtime.id(), "Rebuilt", "hub");
        rebuiltRuntime.setRuntimeOnly(true);
        manager.add(rebuiltRuntime);
        manager.removeAll(List.of(rebuiltRuntime.id()));
        storage.flush();

        assertEquals(snapshotsAfterAttach, storage.snapshotCount());
        assertEquals(0, storage.writeCount());

        boolean[] nestedRuntimeAdded = {false};
        manager.addDataListener(() -> {
            if (nestedRuntimeAdded[0]) return;
            nestedRuntimeAdded[0] = true;
            WaypointGroup nestedRuntime = new WaypointGroup(
                    "dungeon:auto:nested", "Nested runtime", "hub");
            nestedRuntime.setRuntimeOnly(true);
            manager.add(nestedRuntime);
        });
        manager.add(WaypointGroup.create("Persistent", "hub"));
        storage.pumpPendingSnapshot();
        assertEquals(snapshotsAfterAttach + 1, storage.snapshotCount());
        storage.flush();
        assertEquals(1, storage.writeCount());
    }

    @Test
    void aBurstOfPersistentChangesCostsOneSnapshot() {
        // Bulk operations (hide-all, import, closing the route list) fire the
        // persistent listener once per route. Serializing per event is what made
        // those stall with a large library; one pump must cover the whole burst.
        ActiveGroupManager manager = new ActiveGroupManager();
        Storage storage = new Storage(tempDir.resolve("waypoints.json"));
        storage.attach(manager);
        int before = storage.snapshotCount();

        for (int i = 0; i < 50; i++) {
            manager.add(WaypointGroup.create("Route " + i, "hub"));
        }

        assertEquals(before, storage.snapshotCount(), "no snapshot until the pump runs");

        storage.pumpPendingSnapshot();
        assertEquals(before + 1, storage.snapshotCount());

        storage.pumpPendingSnapshot();
        assertEquals(before + 1, storage.snapshotCount(), "a clean pump is free");
    }

    @Test
    void flushSnapshotsPendingChangesSoShutdownCannotLoseThem() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        Path file = tempDir.resolve("waypoints.json");
        Storage storage = new Storage(file);
        storage.attach(manager);

        manager.add(WaypointGroup.create("Persistent", "hub"));
        storage.flush();

        assertEquals(1, storage.writeCount());
        assertTrue(Files.readString(file).contains("Persistent"));
    }

    @Test
    void load_replacesGroupsOnlyAfterWholeFileParses() throws Exception {
        ActiveGroupManager manager = managerWithExistingGroup();
        Path file = tempDir.resolve("waypoints.json");
        Files.writeString(file, """
                {
                  "schema": 1,
                  "groups": [
                    {
                      "id": "loaded",
                      "name": "Loaded",
                      "zone": "hub",
                      "waypoints": [{"x": 4, "y": 5, "z": 6}]
                    }
                  ]
                }
                """);

        new Storage(file).load(manager);

        assertNull(manager.get("existing"));
        assertNotNull(manager.get("loaded"));
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, manager.get("loaded").loadMode());
        assertEquals(1, manager.allGroups().size());
    }

    @Test
    void load_keepsExistingGroupsWhenRootOrGroupsAreMalformed() throws Exception {
        List<String> malformedFiles = List.of(
                " ",
                "null",
                "[]",
                "{\"groups\": []}",
                "{\"schema\": 1.5, \"groups\": []}",
                "{\"schema\": 1, \"groups\": null}",
                "{\"schema\": 1, \"groups\": {\"id\": \"not-array\"}}",
                "{\"schema\": 1, \"groups\": [null]}"
        );

        for (int i = 0; i < malformedFiles.size(); i++) {
            ActiveGroupManager manager = managerWithExistingGroup();
            Path file = tempDir.resolve("malformed-" + i + ".json");
            Files.writeString(file, malformedFiles.get(i));

            new Storage(file).load(manager);

            assertExistingGroupSurvived(manager, "case " + i);
            assertQuarantined(file, malformedFiles.get(i));
        }
    }

    @Test
    void load_quarantinesUnsupportedSchemaBeforeItCanBeOverwritten() throws Exception {
        ActiveGroupManager manager = managerWithExistingGroup();
        Path file = tempDir.resolve("future-schema.json");
        String raw = """
                {
                  "schema": 3,
                  "groups": [{"id": "future", "name": "Future", "zone": "hub"}]
                }
                """;
        Files.writeString(file, raw);

        new Storage(file).load(manager);

        assertExistingGroupSurvived(manager, "future schema must not replace live data");
        assertQuarantined(file, raw);
    }

    @Test
    void load_quarantinesDuplicateGroupIdsInsteadOfSilentlyDroppingOne() throws Exception {
        ActiveGroupManager manager = managerWithExistingGroup();
        Path file = tempDir.resolve("duplicate-ids.json");
        String raw = """
                {
                  "schema": 1,
                  "groups": [
                    {"id": "same", "name": "First", "zone": "hub"},
                    {"id": "same", "name": "Second", "zone": "hub"}
                  ]
                }
                """;
        Files.writeString(file, raw);

        new Storage(file).load(manager);

        assertExistingGroupSurvived(manager, "duplicate ids must reject the whole file");
        assertQuarantined(file, raw);
    }

    @Test
    void laterSavesCannotDestroyQuarantinedStartupData() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        Path file = tempDir.resolve("startup-corruption.json");
        String invalidData = "{not-json";
        Files.writeString(file, invalidData);
        Storage storage = new Storage(file);

        storage.load(manager);
        storage.attach(manager);
        manager.add(WaypointGroup.create("Replacement", "hub"));
        storage.flush();

        assertTrue(Files.exists(file), "new valid state may use the canonical path");
        assertEquals(invalidData, Files.readString(findQuarantine(file)),
                "the rejected startup data must survive later saves unchanged");
        assertEquals(1, JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                .getAsJsonArray("groups").size());
    }

    @Test
    void load_keepsExistingGroupsWhenLaterGroupFailsToParse() throws Exception {
        ActiveGroupManager manager = managerWithExistingGroup();
        Path file = tempDir.resolve("mid-array-failure.json");
        String raw = """
                {
                  "schema": 1,
                  "groups": [
                    {
                      "id": "loaded",
                      "name": "Loaded",
                      "zone": "hub",
                      "waypoints": [{"x": 4, "y": 5, "z": 6}]
                    },
                    {
                      "id": "broken",
                      "name": "Broken",
                      "zone": "hub",
                      "waypoints": [{"x": "not-a-number", "y": 5, "z": 6}]
                    }
                  ]
                }
                """;
        Files.writeString(file, raw);

        new Storage(file).load(manager);

        assertExistingGroupSurvived(manager, "partially parsed file must not replace live state");
        assertQuarantined(file, raw);
    }

    @Test
    void group_perGroupGradientEndpointsRoundTrip() {
        // Gradient endpoints are per-group (not global). If the codec drops
        // them the gradient silently resets on the next load -- users then
        // report "my colours keep reverting" and we chase the wrong bug.
        WaypointGroup g = WaypointGroup.create("palette", "galatea");
        // MANUAL prevents the setter's re-apply from overwriting waypoint colors
        // we haven't added yet, which isn't the point of this test.
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.setGradientStartColor(0x112233);
        g.setGradientEndColor(0xFEDCBA);

        JsonObject json = Storage.groupToJson(g);
        WaypointGroup copy = Storage.groupFromJson(json);

        assertEquals(0x112233, copy.gradientStartColor());
        assertEquals(0xFEDCBA, copy.gradientEndColor());
    }

    @Test
    void group_hiddenManualColorsSurviveNonManualModeRoundTrip() {
        WaypointGroup group = WaypointGroup.create("subway colors", "hub");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(Waypoint.at(0, 70, 0).withColor(0xAA1100));
        group.add(Waypoint.at(1, 70, 0).withColor(0xFF9900));
        group.add(Waypoint.at(2, 70, 0).withColor(0x0033CC));
        assertTrue(group.toggleSubwaypoint(1));
        group.setGradientStartColor(0x123456);
        group.setGradientEndColor(0xABCDEF);
        group.setStaticColor(0x556677);
        group.setGradientMode(WaypointGroup.GradientMode.STATIC);

        JsonObject json = Storage.groupToJson(group);
        WaypointGroup copy = Storage.groupFromJson(json);

        assertTrue(json.has("manualColors"));
        assertEquals(WaypointGroup.GradientMode.STATIC, copy.gradientMode());
        copy.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        assertEquals(List.of(0xAA1100, 0xFF9900, 0x0033CC),
                copy.waypoints().stream().map(Waypoint::color).toList());
        assertEquals(0x123456, copy.gradientStartColor());
        assertEquals(0xABCDEF, copy.gradientEndColor());
    }

    @Test
    void group_routeKindRoundTripsIncludingExplicitRegularForKnownRoom() {
        WaypointGroup dungeon = WaypointGroup.create("Dungeon route", "admin");
        dungeon.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        WaypointGroup regular = WaypointGroup.create("Regular route", "admin");

        JsonObject dungeonJson = Storage.groupToJson(dungeon);
        JsonObject regularJson = Storage.groupToJson(regular);

        assertEquals("DUNGEON", dungeonJson.get("routeKind").getAsString());
        assertEquals(WaypointGroup.RouteKind.DUNGEON,
                Storage.groupFromJson(dungeonJson).routeKind());
        assertEquals("REGULAR", regularJson.get("routeKind").getAsString());
        assertEquals(WaypointGroup.RouteKind.REGULAR,
                Storage.groupFromJson(regularJson).routeKind());
    }

    @Test
    void group_missingRouteKindUsesTemporaryDungeonCatalogInference() {
        JsonObject knownRoom = Storage.groupToJson(WaypointGroup.create("Legacy", "admin"));
        knownRoom.remove("routeKind");
        JsonObject ordinaryZone = Storage.groupToJson(WaypointGroup.create("Legacy", "hub"));
        ordinaryZone.remove("routeKind");

        assertEquals(WaypointGroup.RouteKind.DUNGEON,
                Storage.groupFromJson(knownRoom).routeKind());
        assertEquals(WaypointGroup.RouteKind.REGULAR,
                Storage.groupFromJson(ordinaryZone).routeKind());
    }

    @Test
    void group_waypointPaintRoundTripsAndInvalidOptionalPaintIsIgnored() {
        WaypointGroup group = WaypointGroup.create("painted", "hub");
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        pixels[WaypointPaint.pixelOffset(WaypointPaint.Face.UP, 4, 5)] = 7;
        WaypointPaint paint = new WaypointPaint(
                WaypointPaint.defaultPalette(0x123456), pixels);
        group.setPaint(paint);
        group.setPaintEnabled(false);

        JsonObject json = Storage.groupToJson(group);
        assertEquals(paint, Storage.groupFromJson(json).paint());
        assertFalse(Storage.groupFromJson(json).paintEnabled());

        json.remove("paintEnabled");
        assertTrue(Storage.groupFromJson(json).paintEnabled(),
                "old routes inherit future Apply to All paint by default");

        json.getAsJsonObject("paint").addProperty("pixels", "AA==");
        assertNull(Storage.groupFromJson(json).paint(),
                "bad optional paint must not discard the otherwise valid group");
    }

        @Test
    void group_staticColorAndModeRoundTrip() {
        WaypointGroup g = WaypointGroup.create("one-color", "hub");
        g.add(Waypoint.at(1, 2, 3).withColor(0x111111));
        g.add(Waypoint.at(4, 5, 6).withColor(0x222222));
        g.setStaticColor(0x135724);
        g.setGradientMode(WaypointGroup.GradientMode.STATIC);

        JsonObject json = Storage.groupToJson(g);
        WaypointGroup copy = Storage.groupFromJson(json);

        assertEquals(0x135724, json.get("staticColor").getAsInt());
        assertEquals(WaypointGroup.GradientMode.STATIC, copy.gradientMode());
        assertEquals(0x135724, copy.staticColor());
        assertEquals(0x135724, copy.get(0).color());
        assertEquals(0x135724, copy.get(1).color());
    }

    @Test
    void movedSkyHanniRouteCanChangeColorModeAndReload() {
        String skyHanniRoute = "{\"waypoints\":["
                + "{\"x\":100,\"y\":64,\"z\":200,\"r\":0,\"g\":1,\"b\":0,"
                + "\"options\":{\"name\":\"1\"}},"
                + "{\"x\":110,\"y\":65,\"z\":210,\"r\":0,\"g\":1,\"b\":0,"
                + "\"options\":{\"name\":\"2\"}}]}";
        WaypointGroup imported = WaypointImporter.importAny(skyHanniRoute).groups().get(0);

        imported.setZoneId("crystal_hollows");
        imported.setStaticColor(0x135724);
        imported.setGradientMode(WaypointGroup.GradientMode.STATIC);
        imported.setGradientMode(WaypointGroup.GradientMode.AUTO);

        WaypointGroup reloaded = Storage.groupFromJson(Storage.groupToJson(imported));

        assertEquals("crystal_hollows", reloaded.zoneId());
        assertEquals(WaypointGroup.GradientMode.AUTO, reloaded.gradientMode());
        assertEquals(2, reloaded.size());
        assertEquals("1", reloaded.get(0).name());
        assertEquals("2", reloaded.get(1).name());
    }

        @Test
    void group_legacyGradientModeJsonStillLoads() {
        JsonObject json = JsonParser.parseString("""
                {
                  "id": "legacy-gradient",
                  "name": "Legacy Gradient",
                  "zone": "hub",
                  "gradientMode": "AUTO",
                  "waypoints": [
                    {"x": 1, "y": 2, "z": 3}
                  ]
                }
                """).getAsJsonObject();

        WaypointGroup copy = Storage.groupFromJson(json);

        assertEquals(WaypointGroup.GradientMode.AUTO, copy.gradientMode());
    }

    @Test
    void group_skipAheadSettingRoundTrips() {
        WaypointGroup g = WaypointGroup.create("strict-route", "dungeon_f7");
        g.setSkipAheadEnabled(false);

        JsonObject json = Storage.groupToJson(g);
        WaypointGroup copy = Storage.groupFromJson(json);

        assertFalse(copy.skipAheadEnabled(),
                "per-route skip-ahead preference must survive reloads");
    }

    @Test
    void groupIgnoresLegacyBestRouteTime() {
        JsonObject legacy = Storage.groupToJson(WaypointGroup.create("route", "hub"));
        legacy.addProperty("bestTimeMillis", 573_000L);

        WaypointGroup copy = Storage.groupFromJson(legacy);

        assertFalse(Storage.groupToJson(copy).has("bestTimeMillis"));
    }

    @Test
    void group_subwaypointStructureRoundTrips() {
        WaypointGroup g = WaypointGroup.create("subway-route", "dungeon_f7");
        g.add(Waypoint.at(1, 10, 1).withName("main"));
        g.add(Waypoint.at(2, 10, 2).withName("helper"));
        g.toggleSubwaypoint(1);

        JsonObject json = Storage.groupToJson(g);
        WaypointGroup copy = Storage.groupFromJson(json);

        assertFalse(copy.isSubwaypoint(0));
        assertTrue(copy.isSubwaypoint(1));
        assertEquals(0, copy.parentMainIndex(1));
    }

    @Test
    void group_roundTripPreservesProgressAndOrder() {
        WaypointGroup g = WaypointGroup.create("my-route", "dungeon_f7");
        g.setName("Terminals route");
        g.setDefaultRadius(2.5);
        g.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        g.add(Waypoint.at(1, 10, 1).withName("a"));
        g.add(Waypoint.at(2, 10, 2).withName("b"));
        g.add(Waypoint.at(3, 10, 3).withName("c"));
        g.advancePast(1); // currentIndex = 2

        JsonObject json = Storage.groupToJson(g);
        WaypointGroup copy = Storage.groupFromJson(json);

        assertEquals(g.id(), copy.id());
        assertEquals(g.name(), copy.name());
        assertEquals(g.zoneId(), copy.zoneId());
        assertEquals(g.defaultRadius(), copy.defaultRadius());
        assertEquals(g.gradientMode(), copy.gradientMode());
        assertEquals(g.size(), copy.size());
        assertEquals(g.currentIndex(), copy.currentIndex());
        for (int i = 0; i < g.size(); i++) {
            assertEquals(g.get(i).name(), copy.get(i).name(), "waypoint order preserved at " + i);
        }
    }

    @Test
    void loadAndAttachRewritesRetiredDwarvenZonesWithoutMergingOrLosingRouteData() throws Exception {
        Path file = tempDir.resolve("waypoints.json");
        Files.writeString(file, """
                {
                  "schema": 1,
                  "groups": [
                    {
                      "id": "tunnels-route",
                      "name": "Tunnels Route",
                      "zone": "glacite_tunnels",
                      "enabled": false,
                      "currentIndex": 1,
                      "gradientMode": "MANUAL",
                      "loadMode": "STATIC",
                      "defaultRadius": 3.5,
                      "skipAheadEnabled": false,
                      "bestTimeMillis": 12345,
                      "staticColor": 1193046,
                      "gradientStartColor": 1122867,
                      "gradientEndColor": 16702650,
                      "paintEnabled": false,
                      "waypoints": [
                        {"x": 1, "y": 151, "z": 387, "name": "First", "color": 4478310},
                        {"x": 2, "y": 152, "z": 388, "name": "Second", "color": 7833753}
                      ]
                    },
                    {
                      "id": "camp-route",
                      "name": "Camp Route",
                      "zone": "dwarven_base_camp",
                      "waypoints": [{"x": 3, "y": 153, "z": 389, "name": "Camp"}]
                    },
                    {
                      "id": "lake-route",
                      "name": "Lake Route",
                      "zone": "great_glacite_lake",
                      "waypoints": [{"x": 4, "y": 154, "z": 390, "name": "Lake"}]
                    }
                  ]
                }
                """);
        ActiveGroupManager manager = new ActiveGroupManager();
        Storage storage = new Storage(file);

        storage.load(manager);

        assertEquals(List.of("tunnels-route", "camp-route", "lake-route"),
                manager.allGroups().stream().map(WaypointGroup::id).toList());
        assertTrue(manager.allGroups().stream()
                .allMatch(group -> "dwarven_mines".equals(group.zoneId())));
        WaypointGroup tunnels = manager.get("tunnels-route");
        assertEquals("Tunnels Route", tunnels.name());
        assertFalse(tunnels.enabled());
        assertEquals(1, tunnels.currentIndex());
        assertEquals(WaypointGroup.GradientMode.MANUAL, tunnels.gradientMode());
        assertEquals(WaypointGroup.LoadMode.STATIC, tunnels.loadMode());
        assertEquals(3.5, tunnels.defaultRadius());
        assertFalse(tunnels.skipAheadEnabled());
        assertEquals(0x123456, tunnels.staticColor());
        assertEquals(0x112233, tunnels.gradientStartColor());
        assertEquals(0xFEDCBA, tunnels.gradientEndColor());
        assertFalse(tunnels.paintEnabled());
        assertEquals(List.of("First", "Second"),
                tunnels.waypoints().stream().map(Waypoint::name).toList());

        storage.attach(manager);
        storage.flush();

        assertEquals(1, storage.writeCount());
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(2, saved.get("schema").getAsInt());
        assertTrue(saved.getAsJsonArray("folders").isEmpty());
        assertEquals(3, saved.getAsJsonArray("groups").size());
        for (int index = 0; index < 3; index++) {
            assertEquals("dwarven_mines", saved.getAsJsonArray("groups").get(index)
                    .getAsJsonObject().get("zone").getAsString());
        }
        JsonObject savedTunnels = saved.getAsJsonArray("groups").get(0).getAsJsonObject();
        assertEquals("tunnels-route", savedTunnels.get("id").getAsString());
        assertEquals(1, savedTunnels.get("currentIndex").getAsInt());
        assertEquals("First", savedTunnels.getAsJsonArray("waypoints").get(0)
                .getAsJsonObject().get("name").getAsString());
        assertEquals("Second", savedTunnels.getAsJsonArray("waypoints").get(1)
                .getAsJsonObject().get("name").getAsString());

        ActiveGroupManager reloadedManager = new ActiveGroupManager();
        Storage reloadedStorage = new Storage(file);
        reloadedStorage.load(reloadedManager);
        reloadedStorage.attach(reloadedManager);
        reloadedStorage.flush();

        assertEquals(0, reloadedStorage.writeCount(),
                "the canonical file must not be rewritten on every startup");
        assertEquals(List.of("tunnels-route", "camp-route", "lake-route"),
                reloadedManager.allGroups().stream().map(WaypointGroup::id).toList());
        assertEquals(List.of("First", "Second"),
                reloadedManager.get("tunnels-route").waypoints().stream()
                        .map(Waypoint::name).toList());
    }

    @Test
    void loadAndAttachRewritesLegacyMineshaftRouteAndFolderOnce() throws Exception {
        Path file = tempDir.resolve("legacy-mineshaft.json");
        Files.writeString(file, """
                {
                  "schema": 2,
                  "groups": [{
                    "id": "shaft-route",
                    "name": "Shaft Route",
                    "zone": "mineshaft",
                    "waypoints": [{"x": 1, "y": 2, "z": 3}]
                  }],
                  "folders": [{
                    "id": "shaft-folder",
                    "name": "Shafts",
                    "zone": "mineshaft",
                    "groupIds": ["shaft-route"]
                  }]
                }
                """);
        ActiveGroupManager manager = new ActiveGroupManager();
        Storage storage = new Storage(file);

        storage.load(manager);

        assertEquals("mineshaft_unknown", manager.get("shaft-route").zoneId());
        assertEquals("mineshaft_unknown", manager.folder("shaft-folder").zoneId());
        assertEquals(List.of("shaft-route"), manager.groupIdsInFolder("shaft-folder"));

        storage.attach(manager);
        storage.flush();

        assertEquals(1, storage.writeCount());
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals("mineshaft_unknown", saved.getAsJsonArray("groups").get(0)
                .getAsJsonObject().get("zone").getAsString());
        assertEquals("mineshaft_unknown", saved.getAsJsonArray("folders").get(0)
                .getAsJsonObject().get("zone").getAsString());

        ActiveGroupManager reloadedManager = new ActiveGroupManager();
        Storage reloadedStorage = new Storage(file);
        reloadedStorage.load(reloadedManager);
        reloadedStorage.attach(reloadedManager);
        reloadedStorage.flush();
        assertEquals(0, reloadedStorage.writeCount());
    }

    @Test
    void foldersRoundTripWithOrderCollapseAndMembership() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup first = new WaypointGroup("first", "First", "hub");
        WaypointGroup second = new WaypointGroup("second", "Second", "hub");
        manager.addAll(List.of(first, second));
        manager.addFolder(new RouteFolder("folder", "Mining", "hub", true, 0xC46DFF),
                List.of(second.id(), first.id()));
        Path file = tempDir.resolve("folders.json");

        new Storage(file).save(manager);
        ActiveGroupManager loaded = new ActiveGroupManager();
        new Storage(file).load(loaded);

        assertEquals(2, JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                .get("schema").getAsInt());
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(0xC46DFF, saved.getAsJsonArray("folders").get(0)
                .getAsJsonObject().get("color").getAsInt());
        assertEquals(List.of(new RouteFolder(
                        "folder", "Mining", "hub", true, 0xC46DFF)),
                loaded.folders());
        assertEquals(List.of("first", "second"), loaded.groupIdsInFolder("folder"),
                "folder membership follows the manager's global route order");
    }

    @Test
    void runtimeFoldersAndTheirRuntimeRoutesAreNeverPersisted() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup runtime = new WaypointGroup("runtime", "Odawa", "crystal_hollows");
        runtime.setRuntimeOnly(true);
        manager.add(runtime);
        manager.addFolder(new RouteFolder("runtime-folder", "Structures", "crystal_hollows",
                false, 0x55FFFF, true), List.of(runtime.id()));
        Path file = tempDir.resolve("runtime-folder.json");

        new Storage(file).save(manager);
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertTrue(saved.getAsJsonArray("groups").isEmpty());
        assertTrue(saved.getAsJsonArray("folders").isEmpty());

        ActiveGroupManager loaded = new ActiveGroupManager();
        new Storage(file).load(loaded);
        assertTrue(loaded.allGroups().isEmpty());
        assertTrue(loaded.folders().isEmpty());
    }

    @Test
    void schemaTwoFolderWithoutColorUsesTheCyanDefault() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup route = new WaypointGroup("route", "Route", "hub");
        manager.add(route);
        manager.addFolder(new RouteFolder("folder", "Mining", "hub", false),
                List.of(route.id()));
        Path file = tempDir.resolve("legacy-folder-color.json");
        new Storage(file).save(manager);
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        saved.getAsJsonArray("folders").get(0).getAsJsonObject().remove("color");
        Files.writeString(file, saved.toString());

        ActiveGroupManager loaded = new ActiveGroupManager();
        new Storage(file).load(loaded);

        assertEquals(RouteFolder.DEFAULT_COLOR, loaded.folder("folder").color());
        assertEquals(List.of("route"), loaded.groupIdsInFolder("folder"));
    }

    @Test
    void malformedOptionalFolderColorKeepsTheFolderWithTheCyanDefault() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup route = new WaypointGroup("route", "Route", "hub");
        manager.add(route);
        manager.addFolder(new RouteFolder("folder", "Mining", "hub", false),
                List.of(route.id()));
        Path file = tempDir.resolve("malformed-folder-color.json");
        new Storage(file).save(manager);
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        saved.getAsJsonArray("folders").get(0).getAsJsonObject()
                .addProperty("color", "not-a-color");
        Files.writeString(file, saved.toString());

        ActiveGroupManager loaded = new ActiveGroupManager();
        new Storage(file).load(loaded);

        assertEquals(RouteFolder.DEFAULT_COLOR, loaded.folder("folder").color());
        assertEquals(List.of("route"), loaded.groupIdsInFolder("folder"));
    }

    @Test
    void catalogSourceRoundTripsAndMalformedOptionalSourceKeepsGroup() {
        CatalogRouteProvenance source = new CatalogRouteProvenance(
                "https://catalog.example/api", "abcdefghijklmnopqrstuv", 2, 9,
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ", 0, 1);
        WaypointGroup group = new WaypointGroup("route", "Route", "hub");
        group.setCatalogProvenance(source);

        WaypointGroup copy = Storage.groupFromJson(Storage.groupToJson(group));

        assertEquals(source, copy.catalogProvenance());

        JsonObject malformed = Storage.groupToJson(group);
        malformed.add("catalogSource", JsonParser.parseString("{\"routeId\":7}"));
        WaypointGroup retained = assertDoesNotThrow(() -> Storage.groupFromJson(malformed));
        assertEquals("route", retained.id());
        assertNull(retained.catalogProvenance());
    }

    private static ActiveGroupManager managerWithExistingGroup() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup existing = new WaypointGroup("existing", "Existing", "hub");
        existing.add(Waypoint.at(1, 2, 3).withName("keeper"));
        manager.add(existing);
        return manager;
    }

    private static void assertExistingGroupSurvived(ActiveGroupManager manager, String message) {
        assertNotNull(manager.get("existing"), message);
        assertEquals(1, manager.allGroups().size(), message);
        assertEquals("keeper", manager.get("existing").get(0).name(), message);
    }

    private static void assertQuarantined(Path original, String expectedContents) throws Exception {
        assertFalse(Files.exists(original), "invalid source should be moved out of the live storage path");
        assertEquals(expectedContents, Files.readString(findQuarantine(original)));
    }

    private static Path findQuarantine(Path original) throws Exception {
        String prefix = original.getFileName() + ".invalid";
        try (Stream<Path> files = Files.list(original.getParent())) {
            List<Path> matches = files
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .toList();
            assertEquals(1, matches.size(), "exactly one quarantine copy should be kept");
            return matches.get(0);
        }
    }
}
