package dev.ethan.waypointer.config;

import com.google.gson.JsonObject;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the JSON codec. Avoids FabricLoader calls by using the
 * package-private static helpers directly.
 */
class StorageJsonTest {

    @Test
    void waypoint_roundTripPreservesAllFields() {
        Waypoint original = new Waypoint(-123, 67, 512, "Terminal 3", 0xABCDEF,
                Waypoint.FLAG_THROUGH_WALL | Waypoint.FLAG_LOCKED_COLOR, 4.5);
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
}
