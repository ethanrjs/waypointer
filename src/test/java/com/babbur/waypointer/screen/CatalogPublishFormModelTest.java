package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.CatalogPublishRequest;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogPublishFormModelTest {

    @Test
    void aDraftRetainsItsFieldsWhileRoutesAreAddedAndRemovedAcrossZones() {
        WaypointGroup hub = WaypointGroup.create("Hub route", "hub");
        hub.add(Waypoint.at(1, 64, 2));
        WaypointGroup garden = WaypointGroup.create("Garden route", "garden");
        garden.add(Waypoint.at(3, 64, 4));
        CatalogPublishFormModel form = new CatalogPublishFormModel(hub, null);
        form.setTitle("My route collection");
        form.setDescription("Routes for multiple islands.");
        form.setVisibility(CatalogPublishRequest.Visibility.PUBLIC);

        assertTrue(form.addGroups(List.of(garden, hub)));
        assertFalse(form.addGroups(List.of(garden)));
        assertEquals(List.of(hub, garden), form.groups());
        assertEquals("multiple", form.zoneId());
        assertEquals("My route collection", form.title());
        assertEquals("Routes for multiple islands.", form.description());
        assertEquals(CatalogPublishRequest.Visibility.PUBLIC, form.visibility());
        assertTrue(form.valid());
        assertFalse(form.removeGroups(List.of(hub, garden)), "keep at least one route");
        assertTrue(form.removeGroups(List.of(hub)));
        assertEquals("garden", form.zoneId());
        assertEquals(garden, form.group());
    }

    @Test
    void addingRoutesRejectsUnsupportedSourcesAndValidatesEverySelectedRoute() {
        WaypointGroup hub = WaypointGroup.create("Hub", "hub");
        hub.add(Waypoint.at(1, 64, 2));
        WaypointGroup garden = WaypointGroup.create("Garden", "garden");
        garden.add(Waypoint.at(3, 64, 4));
        CatalogPublishFormModel form = new CatalogPublishFormModel(hub, null);
        assertFalse(form.addGroups(List.of(WaypointGroup.create("Empty", "garden"))));
        assertTrue(form.addGroups(List.of(garden)));
        garden.setTemp(true);
        assertEquals(CatalogPublishFormModel.Validation.TEMPORARY_ROUTE, form.validation());
    }

    @Test
    void validationIsAnExplicitFormStateMachine() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        CatalogPublishFormModel form = new CatalogPublishFormModel(group, null);
        assertEquals(CatalogPublishFormModel.Validation.EMPTY_ROUTE, form.validation());

        group.add(Waypoint.at(1, 64, 2));
        group.setTemp(true);
        assertEquals(CatalogPublishFormModel.Validation.TEMPORARY_ROUTE, form.validation());
        group.setTemp(false);

        form.setTitle("  ");
        assertEquals(CatalogPublishFormModel.Validation.TITLE_REQUIRED, form.validation());
        form.setTitle("Published route");
        assertEquals(CatalogPublishFormModel.Validation.DESCRIPTION_TOO_SHORT,
                form.validation());

        form.setDescription("A useful route description.");
        assertNull(form.validation());
        assertTrue(form.valid());
    }

    @Test
    void editsNormalizeOnceAndNotifyOnlyForRealChanges() {
        AtomicInteger edits = new AtomicInteger();
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        CatalogPublishFormModel form = new CatalogPublishFormModel(group, edits::incrementAndGet);

        form.setTitle("Route");
        form.setDescription("line one\r\nline two\u0000");
        form.setDescription("line one\nline two");
        form.setVisibility(CatalogPublishRequest.Visibility.PUBLIC);

        assertEquals(2, edits.get());
        assertEquals("line one\nline two", form.description());
        assertEquals(CatalogPublishRequest.Visibility.PUBLIC, form.visibility());
    }

    @Test
    void previewNamePrefersTheEditedTitleAndFallsBackToTheRouteName() {
        WaypointGroup group = WaypointGroup.create("Saved name", "hub");
        CatalogPublishFormModel form = new CatalogPublishFormModel(group, null);

        assertEquals("Saved name", form.previewName());
        form.setTitle("   ");
        assertEquals("Saved name", form.previewName());
        form.setTitle("  Edited  ");
        assertEquals("Edited", form.previewName());
        assertEquals("Edited", form.normalizedTitle());
        assertEquals(group, form.group());
    }

    @Test
    void routesWithoutARealSkyBlockZoneCannotBePublished() {
        WaypointGroup group = WaypointGroup.create("Route", "unknown");
        group.add(Waypoint.at(1, 64, 2));
        CatalogPublishFormModel form = new CatalogPublishFormModel(group, null);
        form.setTitle("Published route");
        form.setDescription("A useful route description.");

        assertEquals(CatalogPublishFormModel.Validation.UNPUBLISHABLE_ZONE,
                form.validation());
        assertFalse(form.valid());

        assertTrue(CatalogPublishFormModel.unpublishableZone(null));
        assertTrue(CatalogPublishFormModel.unpublishableZone("unknown"));
        assertTrue(CatalogPublishFormModel.unpublishableZone("private_world"));
        assertTrue(CatalogPublishFormModel.unpublishableZone("Private_World"));
        assertFalse(CatalogPublishFormModel.unpublishableZone("hub"));
        assertFalse(CatalogPublishFormModel.unpublishableZone("mineshaft_topaz_1"));
    }

    @Test
    void durableDungeonRoomRoutesPublishAsOneDungeonListing() {
        WaypointGroup room = WaypointGroup.create("Room route", "shape_L_rot_0");
        room.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        room.add(Waypoint.at(1, 64, 2));
        CatalogPublishFormModel form = new CatalogPublishFormModel(room, null);
        form.setTitle("Published route");
        form.setDescription("A useful route description.");

        WaypointGroup otherRoom = WaypointGroup.create("Other room", "shape_I_rot_0");
        otherRoom.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        otherRoom.add(Waypoint.at(3, 65, 4));
        assertTrue(form.addGroups(List.of(otherRoom)));
        assertEquals("dungeon", form.zoneId());
        assertNull(form.validation());
        assertTrue(form.valid());
        assertTrue(CatalogPublishFormModel.eligibleBundle(form.groups()));
        assertFalse(CatalogPublishFormModel.unsafeDungeonRoute(room));

        assertTrue(CatalogPublishFormModel.dungeonRoute(room));
        assertTrue(CatalogPublishFormModel.dungeonRoute(
                WaypointGroup.create("Floor", "dungeon_f7")));
        assertTrue(CatalogPublishFormModel.dungeonRoute(
                WaypointGroup.create("Master floor", "dungeon_m5")));
        assertTrue(CatalogPublishFormModel.dungeonRoute(
                WaypointGroup.create("Entrance", "dungeon")));
        assertFalse(CatalogPublishFormModel.dungeonRoute(
                WaypointGroup.create("Lobby", "dungeon_hub")));
        assertFalse(CatalogPublishFormModel.dungeonRoute(
                WaypointGroup.create("Hub", "hub")));
    }

    @Test
    void worldCoordinateDungeonRoutesAndTemporaryRoomRoutesStayBlocked() {
        for (String zone : List.of("dungeon", "dungeon_f1", "dungeon_f7", "dungeon_m1", "dungeon_m7")) {
            WaypointGroup floor = WaypointGroup.create("Unsafe floor route", zone);
            floor.add(Waypoint.at(1, 64, 2));
            CatalogPublishFormModel form = new CatalogPublishFormModel(floor, null);
            assertFalse(CatalogPublishFormModel.eligible(floor));
            assertEquals(CatalogPublishFormModel.Validation.DUNGEON_ROUTE, form.validation());
        }
        WaypointGroup room = WaypointGroup.create("Room", "shape_L_rot_0");
        room.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        room.add(Waypoint.at(1, 64, 2));
        room.setTemp(true);
        assertFalse(CatalogPublishFormModel.eligible(room));
        room.setTemp(false);
        room.setRuntimeOnly(true);
        assertFalse(CatalogPublishFormModel.eligible(room));
    }

    @Test
    void dungeonAndRegularDraftsRejectMixedAdditionsWithoutPartiallyChangingTheDraft() {
        WaypointGroup room = WaypointGroup.create("Room", "shape_L_rot_0");
        room.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        room.add(Waypoint.at(1, 64, 2));
        WaypointGroup hub = WaypointGroup.create("Hub", "hub");
        hub.add(Waypoint.at(3, 64, 4));
        WaypointGroup garden = WaypointGroup.create("Garden", "garden");
        garden.add(Waypoint.at(5, 64, 6));
        CatalogPublishFormModel dungeon = new CatalogPublishFormModel(room, null);
        CatalogPublishFormModel regular = new CatalogPublishFormModel(hub, null);
        assertFalse(dungeon.accepts(hub));
        assertFalse(dungeon.addGroups(List.of(hub)));
        assertEquals(List.of(room), dungeon.groups());
        assertFalse(regular.accepts(room));
        assertFalse(regular.addGroups(List.of(garden, room)));
        assertEquals(List.of(hub), regular.groups());
        assertFalse(CatalogPublishFormModel.eligibleBundle(List.of(hub, room)));
        assertFalse(CatalogPublishFormModel.eligibleBundle(List.of()));
        assertTrue(regular.addGroups(List.of(garden)));
        garden.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        assertEquals(CatalogPublishFormModel.Validation.MIXED_ROUTES, regular.validation());
    }

    @Test
    void descriptionLengthCountsCodePointsAndCapsInput() {
        assertFalse(CatalogPublishFormModel.descriptionLengthValid("123456789"));
        assertTrue(CatalogPublishFormModel.descriptionLengthValid("😀".repeat(500)));
        assertFalse(CatalogPublishFormModel.descriptionLengthValid("😀".repeat(501)));
        assertEquals(500, CatalogPublishFormModel.normalizeDescriptionInput(
                "😀".repeat(501)).codePointCount(0, 1000));
        assertEquals(0, CatalogPublishFormModel.descriptionCharactersRemaining(
                "😀".repeat(500)));
    }
}
