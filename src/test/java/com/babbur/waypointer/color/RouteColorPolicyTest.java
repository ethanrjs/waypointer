package com.babbur.waypointer.color;

import com.babbur.waypointer.codec.WaypointImporter;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RouteColorPolicyTest {

        @Test
    void defaultStaticImportPolicyOverridesColoredJsonRoute() {
        String json = "{\"name\":\"Imported\",\"island\":\"hub\",\"waypoints\":["
                + "{\"x\":1,\"y\":70,\"z\":2,\"color\":\"#112233\"},"
                + "{\"x\":3,\"y\":71,\"z\":4,\"color\":\"#445566\"}"
                + "]}";
        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        WaypointGroup group = result.groups().get(0);

        RouteColorPolicy.applyImportedRouteDefaults(result.groups(), new WaypointerConfig());

        assertEquals(WaypointGroup.GradientMode.STATIC, group.gradientMode());
        assertEquals(0x00FF00, group.staticColor());
        assertEquals(0x00FF00, group.get(0).color());
        assertEquals(0x00FF00, group.get(1).color());
    }

        @Test
    void gradientImportPolicyAppliesRouteGradient() {
        WaypointerConfig config = new WaypointerConfig();
        config.setImportedRouteColorMode(WaypointGroup.GradientMode.AUTO);
        WaypointGroup group = WaypointGroup.create("gradient", "hub");
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.add(Waypoint.at(0, 70, 0).withColor(0x111111));
        group.add(Waypoint.at(1, 70, 0).withColor(0x111111));
        group.add(Waypoint.at(2, 70, 0).withColor(0x111111));

        RouteColorPolicy.applyImportedRouteDefaults(List.of(group), config);

        assertEquals(WaypointGroup.GradientMode.AUTO, group.gradientMode());
        assertNotEquals(group.get(0).color(), group.get(group.size() - 1).color());
    }

        @Test
    void manualImportPolicyPreservesPayloadColors() {
        String json = "{\"name\":\"Imported\",\"island\":\"hub\",\"waypoints\":["
                + "{\"x\":1,\"y\":70,\"z\":2,\"color\":\"#112233\"},"
                + "{\"x\":3,\"y\":71,\"z\":4,\"color\":\"#445566\"}"
                + "]}";
        WaypointImporter.ImportResult result = WaypointImporter.importAny(json);
        WaypointGroup group = result.groups().get(0);
        WaypointerConfig config = new WaypointerConfig();
        config.setImportedRouteColorMode(WaypointGroup.GradientMode.MANUAL);

        RouteColorPolicy.applyImportedRouteDefaults(result.groups(), config);

        assertEquals(WaypointGroup.GradientMode.MANUAL, group.gradientMode());
        assertEquals(0x112233, group.get(0).color());
        assertEquals(0x445566, group.get(1).color());
    }
}
