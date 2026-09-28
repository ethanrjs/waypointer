package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import static com.babbur.waypointer.screen.GroupEditGeometry.WAYPOINT_ORDINAL_GAP;
import static com.babbur.waypointer.screen.GroupEditGeometry.WAYPOINT_SWATCH_SIZE;
import static com.babbur.waypointer.screen.GroupEditGeometry.WAYPOINT_SWATCH_TOP;
import static com.babbur.waypointer.screen.GroupEditGeometry.WAYPOINT_SUBWAYPOINT_INDENT;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #160: route editor row numbers sit in a pixel-aligned column. */
class GroupEditRowAlignmentTest {

    private static final int ROW_LEFT = 100;

    /** A proportional font: '1' is narrower than the other digits. */
    private static final ToIntFunction<String> FONT = text -> {
        int width = 0;
        for (char c : text.toCharArray()) {
            width += switch (c) {
                case '1' -> 4;
                case '.' -> 2;
                case ',' -> 3;
                default -> 6;
            };
        }
        return width;
    };

    @Test
    void ordinalColumnsUseTheWidestMeasuredLabelOnEachLevel() {
        WaypointGroup group = mixedRoute();
        GroupEditGeometry.WaypointOrdinalColumn ordinals =
                GroupEditGeometry.waypointOrdinalColumn(group, UnaryOperator.identity(), FONT);

        assertEquals(List.of("#1", "#1.1", "#1.2", "#2", "#3", "#4", "#5", "#6", "#7",
                "#8", "#9", "#10", "#11", "#11.1"), ordinals.labels());
        // "#10" (16px) is wider than "#11" (14px) even though both have three
        // characters, so the column is measured rather than counted.
        assertEquals(16, ordinals.mainWidth());
        assertEquals(16, ordinals.width(false));
        // "#11.1" is the widest child label.
        assertEquals(20, ordinals.subwaypointWidth());
        assertEquals(20, ordinals.width(true));
    }

    @Test
    void ordinalColumnsMeasureTheLocalizedLabels() {
        WaypointGroup group = mixedRoute();
        GroupEditGeometry.WaypointOrdinalColumn ordinals = GroupEditGeometry.waypointOrdinalColumn(
                group, label -> label.replace('.', ','), FONT);

        assertEquals("#11,1", ordinals.label(13));
        assertEquals(21, ordinals.subwaypointWidth());
        assertEquals("", ordinals.label(-1));
        assertEquals("", ordinals.label(group.size()));
    }

    @Test
    void everyRowOnALevelStartsItsCoordinatesAtTheSamePixel() {
        WaypointGroup group = mixedRoute();
        GroupEditGeometry.WaypointOrdinalColumn ordinals =
                GroupEditGeometry.waypointOrdinalColumn(group, UnaryOperator.identity(), FONT);

        Set<Integer> mainCoordinateStarts = new HashSet<>();
        Set<Integer> childCoordinateStarts = new HashSet<>();
        Set<Integer> mainOrdinalStarts = new HashSet<>();
        Set<Integer> childOrdinalStarts = new HashSet<>();
        for (int i = 0; i < group.size(); i++) {
            boolean subwaypoint = group.isSubwaypoint(i);
            GroupEditGeometry.WaypointRowColumns columns =
                    GroupEditGeometry.waypointRowColumns(ROW_LEFT, subwaypoint, ordinals);
            (subwaypoint ? childCoordinateStarts : mainCoordinateStarts).add(columns.coordinatesX());
            (subwaypoint ? childOrdinalStarts : mainOrdinalStarts).add(columns.ordinalX());

            int ordinalRight = columns.ordinalX() + FONT.applyAsInt(ordinals.label(i));
            assertTrue(ordinalRight + WAYPOINT_ORDINAL_GAP <= columns.coordinatesX(),
                    "label " + ordinals.label(i) + " must not run into its coordinates");
        }

        assertEquals(Set.of(ROW_LEFT + GAP + 2 + WAYPOINT_SWATCH_SIZE + 6), mainOrdinalStarts);
        assertEquals(Set.of(ROW_LEFT + GAP + 2 + WAYPOINT_SUBWAYPOINT_INDENT
                + WAYPOINT_SWATCH_SIZE + 6), childOrdinalStarts);
        // Left-aligned column: the widest label ends exactly one gap before the coordinates.
        assertEquals(Set.of(130 + 16 + WAYPOINT_ORDINAL_GAP), mainCoordinateStarts);
        assertEquals(Set.of(146 + 20 + WAYPOINT_ORDINAL_GAP), childCoordinateStarts);
    }

    @Test
    void swatchKeepsItsPlaceAndHitAreaWhileTheColumnGrows() {
        WaypointGroup group = mixedRoute();
        GroupEditGeometry.WaypointOrdinalColumn ordinals =
                GroupEditGeometry.waypointOrdinalColumn(group, UnaryOperator.identity(), FONT);
        int rowTop = 40;

        for (boolean subwaypoint : new boolean[] {false, true}) {
            int swatchX = GroupEditGeometry.waypointRowColumns(
                    ROW_LEFT, subwaypoint, ordinals).swatchX();
            assertEquals(GroupEditGeometry.waypointSwatchX(ROW_LEFT, subwaypoint), swatchX);
            assertEquals(ROW_LEFT + GAP + 2 + (subwaypoint ? WAYPOINT_SUBWAYPOINT_INDENT : 0),
                    swatchX);
            int swatchY = rowTop + WAYPOINT_SWATCH_TOP;

            assertTrue(GroupEditGeometry.isOverWaypointSwatch(
                    swatchX, swatchY, ROW_LEFT, rowTop, subwaypoint));
            assertTrue(GroupEditGeometry.isOverWaypointSwatch(
                    swatchX + WAYPOINT_SWATCH_SIZE - 0.5, swatchY + WAYPOINT_SWATCH_SIZE - 0.5,
                    ROW_LEFT, rowTop, subwaypoint));
            assertFalse(GroupEditGeometry.isOverWaypointSwatch(
                    swatchX - 1, swatchY, ROW_LEFT, rowTop, subwaypoint));
            assertFalse(GroupEditGeometry.isOverWaypointSwatch(
                    swatchX + WAYPOINT_SWATCH_SIZE, swatchY, ROW_LEFT, rowTop, subwaypoint));
            assertFalse(GroupEditGeometry.isOverWaypointSwatch(
                    swatchX, swatchY + WAYPOINT_SWATCH_SIZE, ROW_LEFT, rowTop, subwaypoint));
        }
    }

    @Test
    void nameAndRenameBoxStartOneGapAfterTheCoordinates() {
        GroupEditGeometry.WaypointOrdinalColumn ordinals =
                GroupEditGeometry.waypointOrdinalColumn(mixedRoute(), UnaryOperator.identity(), FONT);
        int coordinatesX = GroupEditGeometry.waypointRowColumns(ROW_LEFT, false, ordinals)
                .coordinatesX();

        assertEquals(coordinatesX + 60 + GAP, GroupEditGeometry.waypointNameX(coordinatesX, 60));
        assertEquals(coordinatesX + GAP, GroupEditGeometry.waypointNameX(coordinatesX, -5));
    }

    @Test
    void emptyOrMissingRouteHasNoColumnWidth() {
        GroupEditGeometry.WaypointOrdinalColumn empty =
                GroupEditGeometry.waypointOrdinalColumn(null, UnaryOperator.identity(), FONT);
        assertSame(GroupEditGeometry.WaypointOrdinalColumn.EMPTY, empty);
        assertFalse(empty.matchesStructureOf(null));

        GroupEditGeometry.WaypointRowColumns columns =
                GroupEditGeometry.waypointRowColumns(ROW_LEFT, false, null);
        assertEquals(columns.ordinalX() + WAYPOINT_ORDINAL_GAP, columns.coordinatesX());
    }

    @Test
    void cachedColumnsGoStaleWhenRowsChangeLevelOrCount() {
        WaypointGroup group = mixedRoute();
        GroupEditGeometry.WaypointOrdinalColumn ordinals =
                GroupEditGeometry.waypointOrdinalColumn(group, UnaryOperator.identity(), FONT);
        assertTrue(ordinals.matchesStructureOf(group));

        group.set(4, group.get(4).withName("renamed"));
        assertTrue(ordinals.matchesStructureOf(group), "renaming does not move the columns");

        assertTrue(group.toggleSubwaypoint(4));
        assertFalse(ordinals.matchesStructureOf(group));

        GroupEditGeometry.WaypointOrdinalColumn rebuilt =
                GroupEditGeometry.waypointOrdinalColumn(group, UnaryOperator.identity(), FONT);
        assertTrue(rebuilt.matchesStructureOf(group));
        group.add(Waypoint.at(50, 70, 50));
        assertFalse(rebuilt.matchesStructureOf(group));
    }

    /** #1 with two children, #2 to #11, and one child under #11. */
    private static WaypointGroup mixedRoute() {
        WaypointGroup group = WaypointGroup.create("Aligned Route", "dungeon_f7");
        for (int i = 0; i < 14; i++) {
            group.add(Waypoint.at(i, 70, 0));
        }
        assertTrue(group.toggleSubwaypoint(1));
        assertTrue(group.toggleSubwaypoint(2));
        assertTrue(group.toggleSubwaypoint(13));
        return group;
    }
}
