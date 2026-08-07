package com.babbur.waypointer.input;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.Direction;
import com.babbur.waypointer.dungeon.DungeonRoom;
import com.babbur.waypointer.dungeon.DungeonRoomShape;
import com.babbur.waypointer.dungeon.DungeonRoomType;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointRepositionModeTest {

    private static void bootstrapMinecraftRegistriesForBlocks() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void resetBeforeTest() {
        WaypointRepositionMode.setEditModeEnabled(null, null, false);
    }

    @AfterEach
    void resetAfterTest() {
        WaypointRepositionMode.setEditModeEnabled(null, null, false);
    }

    @Test
    void toggleEditModeRequiresLoadedRuntimeCollaborators() {
        assertFalse(WaypointRepositionMode.toggleEditMode(null, new WaypointerConfig()));
        assertFalse(WaypointRepositionMode.isEditModeEnabled());

        assertFalse(WaypointRepositionMode.toggleEditMode(new ActiveGroupManager(), null));
        assertFalse(WaypointRepositionMode.isEditModeEnabled());
    }

    @Test
    void toggleEditModeTurnsOnAndOffWithLoadedRuntimeCollaborators() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerConfig config = new WaypointerConfig();

        assertTrue(WaypointRepositionMode.toggleEditMode(manager, config));
        assertTrue(WaypointRepositionMode.isEditModeEnabled());

        assertFalse(WaypointRepositionMode.toggleEditMode(manager, config));
        assertFalse(WaypointRepositionMode.isEditModeEnabled());
    }

    @Test
    void dungeonbreakerRightClickRemovesFromTheDurableRoute() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup stored = WaypointGroup.create("Dungeon", "remove-room");
        stored.add(Waypoint.at(1, 70, 1));
        stored.add(Waypoint.at(2, 70, 2));
        manager.add(stored);

        WaypointGroup mirror = new WaypointGroup(
                "dungeon:auto:remove-room", "Dungeon", "remove-room");
        mirror.setRuntimeOnly(true);
        mirror.addAll(stored.waypoints());
        manager.add(mirror);

        assertTrue(WaypointRepositionMode.removeWaypoint(manager, mirror, 0));
        assertEquals(1, stored.size());
        assertEquals(2, stored.get(0).x());
        assertEquals(70, stored.get(0).y());
        assertEquals(2, stored.get(0).z());
        assertEquals(2, mirror.size());

        WaypointGroup downloadedOnly = new WaypointGroup(
                "dungeon:auto:downloaded-room", "Downloaded", "downloaded-room");
        downloadedOnly.setRuntimeOnly(true);
        downloadedOnly.add(Waypoint.at(3, 70, 3));
        manager.add(downloadedOnly);
        assertFalse(WaypointRepositionMode.removeWaypoint(manager, downloadedOnly, 0));
        assertEquals(1, downloadedOnly.size());
    }

    @Test
    void explicitEditModeSetterKeepsTheRequestedState() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerConfig config = new WaypointerConfig();

        WaypointRepositionMode.setEditModeEnabled(manager, config, true);
        assertTrue(WaypointRepositionMode.isEditModeEnabled());

        WaypointRepositionMode.setEditModeEnabled(manager, config, true);
        assertTrue(WaypointRepositionMode.isEditModeEnabled());

        WaypointRepositionMode.setEditModeEnabled(manager, config, false);
        assertFalse(WaypointRepositionMode.isEditModeEnabled());

        WaypointRepositionMode.setEditModeEnabled(manager, config, false);
        assertFalse(WaypointRepositionMode.isEditModeEnabled());
    }

    @Test
    void editorEditModeKeepsTheRouteThatOpenedIt() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerConfig config = new WaypointerConfig();
        WaypointGroup firstRoute = WaypointGroup.create("First", "hub");
        WaypointGroup editedRoute = WaypointGroup.create("Citrine 2", "hub");
        manager.add(firstRoute);
        manager.add(editedRoute);
        manager.onZoneChanged(com.babbur.waypointer.core.Zone.fromId("hub"));

        assertEquals(firstRoute, manager.getOrCreateActiveGroup());

        WaypointRepositionMode.setEditModeEnabled(manager, config, editedRoute, true);

        assertEquals(editedRoute, WaypointRepositionMode.editModeAddTarget(manager));
    }

    @Test
    void defaultDungeonEditFlagsChooseOneTriggerOnlyForDungeonRooms() {
        DungeonRoomData.clearAllCustom();
        try {
            DungeonRoomData.defineRoom("edit-default-test-room", "Edit Default Test",
                    new DungeonRoom(
                            DungeonRoomType.ROOM,
                            DungeonRoomShape.ONE_BY_ONE,
                            Direction.NW,
                            0,
                            0,
                            java.util.List.of(DungeonRoom.packSegment(0, 0))));

            WaypointGroup normalGroup = WaypointGroup.create("Normal", "hub");
            WaypointGroup tempGroup = WaypointGroup.create("Temp", "edit-default-test-room");
            tempGroup.setTemp(true);
            WaypointGroup dungeonGroup = WaypointGroup.create(
                    "Dungeon", "edit-default-test-room");

            assertEquals(0, WaypointRepositionMode.defaultDungeonEditFlags(normalGroup));
            assertEquals(0, WaypointRepositionMode.defaultDungeonEditFlags(tempGroup));
            assertEquals(0, WaypointRepositionMode.defaultDungeonEditFlags(normalGroup, true));
            assertEquals(Waypoint.FLAG_SKIP_ON_STAND,
                    WaypointRepositionMode.defaultDungeonEditFlags(dungeonGroup));
            assertEquals(Waypoint.FLAG_SKIP_ON_STAND,
                    WaypointRepositionMode.defaultDungeonEditFlags(dungeonGroup, false));
            assertEquals(Waypoint.FLAG_SKIP_ON_INTERACT,
                    WaypointRepositionMode.defaultDungeonEditFlags(dungeonGroup, true));
        } finally {
            DungeonRoomData.clearAllCustom();
        }
    }

    @Test
    void interactDefaultBlockClassifierAcceptsLeverButtonAndChests() {
        bootstrapMinecraftRegistriesForBlocks();

        assertTrue(WaypointRepositionMode.isDungeonInteractDefaultBlock(Blocks.LEVER.defaultBlockState()));
        assertTrue(WaypointRepositionMode.isDungeonInteractDefaultBlock(Blocks.OAK_BUTTON.defaultBlockState()));
        assertTrue(WaypointRepositionMode.isDungeonInteractDefaultBlock(Blocks.CHEST.defaultBlockState()));
        assertTrue(WaypointRepositionMode.isDungeonInteractDefaultBlock(Blocks.ENDER_CHEST.defaultBlockState()));
        assertFalse(WaypointRepositionMode.isDungeonInteractDefaultBlock(Blocks.STONE.defaultBlockState()));
        assertFalse(WaypointRepositionMode.isDungeonInteractDefaultBlock(null));
    }

}
