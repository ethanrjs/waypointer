package dev.ethan.waypointer.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the JSON codec. Avoids FabricLoader calls by using the
 * package-private static helpers directly.
 */
class StorageJsonTest {

    @TempDir
    Path tempDir;

    @Test
    void waypoint_roundTripPreservesAllFields() {
        Waypoint original = new Waypoint(-123, 67, 512, "Terminal 3", 0xABCDEF,
                Waypoint.FLAG_THROUGH_WALL | Waypoint.FLAG_LOCKED_COLOR, 4.5);
        JsonObject json = Storage.waypointToJson(original);
        Waypoint copy = Storage.waypointFromJson(json);
        assertEquals(original, copy);
    }

    /*[[AI-FN-DOC
Function:
waypoint_omitsOptionalFieldsWhenDefault.
Purpose:
Verify default waypoint serialization stays compact by omitting fields that are implied by defaults.
Why this exists:
The storage schema has optional fields for labels, flags, radius, and precise centers; default waypoints should not bloat every saved route.
When to use:
Run with the storage JSON tests whenever optional waypoint fields are added or changed.
Inputs:
No runtime inputs; constructs a plain default waypoint.
Outputs:
Assertions pass when optional JSON properties are absent.
Side effects:
None beyond test allocations.
Failure modes:
Fails if serialization starts writing default name, flags, radius, or precise fields unnecessarily.
Important invariants:
Default block-centered precision must remain implicit so old-looking waypoints save cleanly.
Internal logic:
Serialize Waypoint.at(1,2,3), then assert each optional field is missing.
Pseudocode:
plain = Waypoint.at(1, 2, 3)
json = waypointToJson(plain)
assert no name
assert no flags
assert no radius
assert no preciseX
Implementation notes:
Checking only preciseX is enough for the precision group because storage writes all three precise axes together.
AI self-check:
Verify the test covers the new precision omission in addition to the older optional fields.
]]*/
    @Test
    void waypoint_omitsOptionalFieldsWhenDefault() {
        Waypoint plain = Waypoint.at(1, 2, 3);
        JsonObject json = Storage.waypointToJson(plain);
        assertFalse(json.has("name"),   "empty name should not serialize");
        assertFalse(json.has("flags"),  "zero flags should not serialize");
        assertFalse(json.has("radius"), "default radius should not serialize");
        assertFalse(json.has("preciseX"), "default block-center precision should not serialize");
    }

    /*[[AI-FN-DOC
Function:
waypoint_precisePositionRoundTripsWhenCustom.
Purpose:
Verify local storage preserves optional sixteenth-block waypoint centers.
Why this exists:
Small waypoint repositioning stores sub-block precision in waypoints.json, and losing those fields on save/load would make the UI appear to work only until restart.
When to use:
Run with the storage JSON test suite whenever waypoint serialization changes.
Inputs:
No runtime inputs; constructs a waypoint with precise sixteenths that differ from the default block center.
Outputs:
Assertions pass when precise fields serialize and load back into an equal precise center.
Side effects:
None beyond temporary test allocations.
Failure modes:
Fails if Storage omits custom precise fields or waypointFromJson falls back to block center despite the fields being present.
Important invariants:
The required legacy x/y/z block fields remain present alongside optional precise fields.
Internal logic:
Create a waypoint, apply precise sixteenths, serialize it, assert precise fields exist, deserialize it, and compare precise centers and derived block coordinates.
Pseudocode:
original = waypoint at 1,2,-2 with precise 20,39,-25
json = waypointToJson(original)
assert json precise fields equal supplied values
copy = waypointFromJson(json)
assert copy precise fields equal original
assert copy x/y/z match containing blocks
Implementation notes:
The negative Z value covers floorDiv behavior for coordinates west/north of zero.
AI self-check:
Verify this test covers both persistence and the derived block-coordinate invariant.
]]*/
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
                "null",
                "[]",
                "{\"groups\": null}",
                "{\"groups\": {\"id\": \"not-array\"}}",
                "{\"groups\": [null]}"
        );

        for (int i = 0; i < malformedFiles.size(); i++) {
            ActiveGroupManager manager = managerWithExistingGroup();
            Path file = tempDir.resolve("malformed-" + i + ".json");
            Files.writeString(file, malformedFiles.get(i));

            new Storage(file).load(manager);

            assertExistingGroupSurvived(manager, "case " + i);
        }
    }

    @Test
    void load_keepsExistingGroupsWhenLaterGroupFailsToParse() throws Exception {
        ActiveGroupManager manager = managerWithExistingGroup();
        Path file = tempDir.resolve("mid-array-failure.json");
        Files.writeString(file, """
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
                """);

        new Storage(file).load(manager);

        assertExistingGroupSurvived(manager, "partially parsed file must not replace live state");
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
}
