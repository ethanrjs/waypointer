package com.babbur.waypointer.render;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Routes whose points share one beam mode skip the whole-route walk. */
class BeamFastPathTest {

    @Test
    void currentModeVisitsOnlyTheCurrentPointAndOffVisitsNothing() throws Exception {
        WaypointGroup group = WaypointGroup.create("Static", "hub");
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        for (int i = 0; i < 500; i++) group.add(Waypoint.at(i, 70, i));
        group.setCurrentIndex(42);

        WaypointerConfig config = new WaypointerConfig();
        config.setUseBeaconBeamTextures(false);
        config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.CURRENT);
        assertEquals(List.of(42), visits(group, config));

        config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.OFF);
        assertTrue(visits(group, config).isEmpty());

        config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE);
        assertEquals(500, visits(group, config).size());
    }

    @Test
    void aPointStyleOverrideKeepsTheFullWalk() throws Exception {
        WaypointGroup group = WaypointGroup.create("Static", "hub");
        group.setLoadMode(WaypointGroup.LoadMode.STATIC);
        for (int i = 0; i < 20; i++) group.add(Waypoint.at(i, 70, i));
        group.setCurrentIndex(3);
        group.setWaypointStyle(7, WaypointStyle.EMPTY.with("beaconBeamMode", "ALL_VISIBLE"));

        WaypointerConfig config = new WaypointerConfig();
        config.setUseBeaconBeamTextures(false);
        config.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.CURRENT);
        assertEquals(20, visits(group, config).size());
    }

    private static List<Integer> visits(WaypointGroup group, WaypointerConfig config) throws Exception {
        List<Integer> visited = new ArrayList<>();
        WaypointWorldRenderer renderer = new WaypointWorldRenderer(new ActiveGroupManager(), config) {
            @Override
            boolean shouldRenderWaypointWorld(WaypointGroup g, int index, int current, boolean completed,
                                              Vec3 camera, Vec3 player, double distance, double near,
                                              boolean depth, Minecraft client, ClientLevel level) {
                visited.add(index);
                return false;
            }
        };
        var emit = WaypointWorldRenderer.class.getDeclaredMethod("emitBeaconBeams",
                PoseStack.class, VertexConsumer.class, WaypointGroup.class, Vec3.class, Vec3.class,
                double.class, double.class, int.class, int.class, boolean.class,
                Minecraft.class, ClientLevel.class, boolean.class);
        emit.setAccessible(true);
        emit.invoke(renderer, null, null, group, null, null, 0.0, 0.0, -64, 320, false, null, null, false);
        return visited;
    }
}
