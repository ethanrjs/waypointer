package com.babbur.waypointer.render.gpu;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The per-route point-style hash is cached, but every change that restyles a point still changes the key. */
class SceneKeyPointStylesTest {

    @Test
    void cachedPointStylesStillFollowEveryStyleChange() {
        SceneKeyFactory factory = new SceneKeyFactory(OverlayRendererOptions.defaults());
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup group = WaypointGroup.create("Styled", "hub");
        for (int i = 0; i < 50; i++) group.add(Waypoint.at(i, 70, i));
        group.setWaypointStyle(3, WaypointStyle.EMPTY.with("beaconOpacity", 0.5));

        SceneKey first = key(factory, config, group);
        assertEquals(first, key(factory, config, group), "nothing changed");

        group.setWaypointStyle(4, WaypointStyle.EMPTY.with("beaconOpacity", 0.25));
        SceneKey pointStyled = key(factory, config, group);
        assertNotEquals(first, pointStyled, "a point style changed");

        group.setStyleOverride(WaypointStyle.EMPTY.with("waypointOutlineOpacity", 0.3));
        SceneKey routeStyled = key(factory, config, group);
        assertNotEquals(pointStyled, routeStyled, "the route style changed");

        config.setWaypointOutlineThickness(config.waypointOutlineThickness() + 1);
        assertNotEquals(routeStyled, key(factory, config, group), "a base setting changed");
    }

    private static SceneKey key(SceneKeyFactory factory, WaypointerConfig config, WaypointGroup group) {
        return factory.build(List.of(group), config, new Vec3(0.0, 64.0, 0.0), null, -64, 320,
                0L, 0L, 0L, null);
    }
}
