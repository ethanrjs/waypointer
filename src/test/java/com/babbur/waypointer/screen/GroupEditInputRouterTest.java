package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.BEGIN_ROUTE_SCROLLBAR_DRAG;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.CLOSE_DUNGEON_PICKER;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.CLOSE_DUNGEON_PICKER_WITH_SOUND;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.CONSUME;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.EDIT_COLOR;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.OPEN_CONTEXT_MENU;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.NONE;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.OPEN_DUNGEON_TYPE_PICKER;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.RENAME;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.REPOSITION;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.SELECT;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.SELECT_DUNGEON_TYPE;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.TOGGLE_DISABLED;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.TOGGLE_SUBWAYPOINT;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.TOGGLE_SUBWAYPOINT_STYLE;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.TOGGLE_WAYPOINT_CONTROL;
import static com.babbur.waypointer.screen.GroupEditInputRouter.ActionType.UNLOCK_COLOR;
import static com.babbur.waypointer.screen.GroupEditInputRouter.MOUSE_BUTTON_LEFT;
import static com.babbur.waypointer.screen.GroupEditInputRouter.MOUSE_BUTTON_MIDDLE;
import static com.babbur.waypointer.screen.GroupEditInputRouter.MOUSE_BUTTON_RIGHT;
import static com.babbur.waypointer.screen.GroupEditPolicy.SUBWAY_STYLE_ACTION_NONE;
import static com.babbur.waypointer.screen.GroupEditPolicy.SUBWAY_STYLE_ACTION_SMALL;
import static com.babbur.waypointer.screen.GroupEditPolicy.WAYPOINT_CONTROL_ACTION_DEPTH_CHECK;
import static com.babbur.waypointer.screen.GroupEditPolicy.WAYPOINT_CONTROL_ACTION_NONE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GroupEditInputRouterTest {
    @Test
    void dungeonPickerCapturesEveryButtonBeforeUnderlyingRows() {
        GeometryStub geometry = fullyOverlappingGeometry();
        geometry.pickerType = 3;

        assertAction(SELECT_DUNGEON_TYPE, -1, 3,
                before(pointer(MOUSE_BUTTON_LEFT, false, false), true, geometry));
        assertEquals(0, geometry.rowQueries,
                "an open overlay must not query or activate its underlying row");

        geometry.pickerType = -1;
        geometry.pickerAnchor = true;
        geometry.insidePicker = true;
        assertAction(CLOSE_DUNGEON_PICKER_WITH_SOUND, -1, -1,
                before(pointer(MOUSE_BUTTON_LEFT, false, false), true, geometry));

        geometry.pickerAnchor = false;
        geometry.insidePicker = true;
        assertAction(CONSUME, -1, -1,
                before(pointer(MOUSE_BUTTON_LEFT, false, false), true, geometry));
        assertAction(CONSUME, -1, -1,
                before(pointer(MOUSE_BUTTON_MIDDLE, false, false), true, geometry));
        assertAction(CONSUME, -1, -1,
                before(pointer(MOUSE_BUTTON_RIGHT, false, false), true, geometry));

        geometry.insidePicker = false;
        assertAction(CLOSE_DUNGEON_PICKER, -1, -1,
                before(pointer(MOUSE_BUTTON_MIDDLE, false, false), true, geometry));
        assertAction(CLOSE_DUNGEON_PICKER, -1, -1,
                before(pointer(MOUSE_BUTTON_RIGHT, false, false), true, geometry));
        assertEquals(0, geometry.rowQueries,
                "closing the overlay must consume the click instead of clicking through");
    }

    @Test
    void middleAndRightButtonsWinBeforeAllLeftClickControls() {
        GeometryStub geometry = fullyOverlappingGeometry();
        geometry.overScrollbar = true;

        assertAction(TOGGLE_DISABLED, 3, -1,
                before(pointer(MOUSE_BUTTON_MIDDLE, true, true), false, geometry));
        assertAction(OPEN_CONTEXT_MENU, 3, -1,
                before(pointer(MOUSE_BUTTON_RIGHT, false, true), false, geometry));
        assertAction(TOGGLE_SUBWAYPOINT, 3, -1,
                before(pointer(MOUSE_BUTTON_RIGHT, true, true), false, geometry));
    }

    @Test
    void leftClickControlsUseOneExplicitPrecedenceOrder() {
        GeometryStub geometry = fullyOverlappingGeometry();

        assertAction(OPEN_DUNGEON_TYPE_PICKER, 3, -1,
                before(pointer(MOUSE_BUTTON_LEFT, true, true), false, geometry));

        geometry.dungeonTypeButton = -1;
        assertAction(TOGGLE_WAYPOINT_CONTROL, 3, WAYPOINT_CONTROL_ACTION_DEPTH_CHECK,
                before(pointer(MOUSE_BUTTON_LEFT, true, true), false, geometry));

        geometry.controlAction = WAYPOINT_CONTROL_ACTION_NONE;
        assertAction(TOGGLE_SUBWAYPOINT_STYLE, 3, SUBWAY_STYLE_ACTION_SMALL,
                before(pointer(MOUSE_BUTTON_LEFT, true, true), false, geometry));

        geometry.styleAction = SUBWAY_STYLE_ACTION_NONE;
        geometry.lockedColor = true;
        assertAction(UNLOCK_COLOR, 3, -1,
                before(pointer(MOUSE_BUTTON_LEFT, true, true), false, geometry));

        geometry.lockedColor = false;
        assertAction(EDIT_COLOR, 3, -1,
                before(pointer(MOUSE_BUTTON_LEFT, true, true), false, geometry));

        geometry.swatch = -1;
        assertAction(REPOSITION, 3, -1,
                before(pointer(MOUSE_BUTTON_LEFT, true, true), false, geometry));
    }

    @Test
    void scrollbarWinsBeforeRowsAndNormalRowsWaitForWidgets() {
        GeometryStub geometry = new GeometryStub();
        geometry.row = 3;
        geometry.overScrollbar = true;

        assertAction(BEGIN_ROUTE_SCROLLBAR_DRAG, -1, -1,
                before(pointer(MOUSE_BUTTON_LEFT, false, false), false, geometry));

        geometry.overScrollbar = false;
        GroupEditInputRouter.Pointer click = pointer(MOUSE_BUTTON_LEFT, false, false);
        assertAction(NONE, -1, -1, before(click, false, geometry));
        assertAction(SELECT, 3, -1,
                GroupEditInputRouter.afterWidgets(click, state(false, -1), geometry));
        assertAction(RENAME, 3, -1,
                GroupEditInputRouter.afterWidgets(
                        pointer(MOUSE_BUTTON_LEFT, false, true), state(false, 3), geometry));
    }

    @Test
    void unsupportedButtonsAndEmptySpaceProduceNoAction() {
        GeometryStub geometry = new GeometryStub();
        geometry.row = -1;

        assertAction(NONE, -1, -1,
                before(pointer(MOUSE_BUTTON_LEFT, false, false), false, geometry));
        assertAction(NONE, -1, -1,
                before(pointer(4, false, false), false, geometry));
        assertAction(NONE, -1, -1,
                GroupEditInputRouter.afterWidgets(
                        pointer(MOUSE_BUTTON_LEFT, false, true), state(false, 3), geometry));
    }

    private static GroupEditInputRouter.Action before(
            GroupEditInputRouter.Pointer pointer,
            boolean pickerOpen,
            GeometryStub geometry) {
        return GroupEditInputRouter.beforeWidgets(pointer, state(pickerOpen, 3), geometry);
    }

    private static GroupEditInputRouter.Pointer pointer(
            int button, boolean shiftDown, boolean doubleClick) {
        return new GroupEditInputRouter.Pointer(10.0D, 10.0D,
                button, shiftDown, doubleClick);
    }

    private static GroupEditInputRouter.State state(boolean pickerOpen, int selectedIndex) {
        return new GroupEditInputRouter.State(pickerOpen, selectedIndex);
    }

    private static GeometryStub fullyOverlappingGeometry() {
        GeometryStub geometry = new GeometryStub();
        geometry.row = 3;
        geometry.dungeonTypeButton = 3;
        geometry.controlAction = WAYPOINT_CONTROL_ACTION_DEPTH_CHECK;
        geometry.styleAction = SUBWAY_STYLE_ACTION_SMALL;
        geometry.swatch = 3;
        return geometry;
    }

    private static void assertAction(
            GroupEditInputRouter.ActionType type,
            int rowIndex,
            int value,
            GroupEditInputRouter.Action actual) {
        assertEquals(type, actual.type());
        assertEquals(rowIndex, actual.rowIndex());
        assertEquals(value, actual.value());
    }

    private static final class GeometryStub implements GroupEditInputRouter.Geometry {
        private int pickerType = -1;
        private boolean pickerAnchor;
        private boolean insidePicker;
        private boolean overScrollbar;
        private int row = -1;
        private int dungeonTypeButton = -1;
        private int controlAction = WAYPOINT_CONTROL_ACTION_NONE;
        private int styleAction = SUBWAY_STYLE_ACTION_NONE;
        private int swatch = -1;
        private boolean lockedColor;
        private int rowQueries;

        @Override
        public int dungeonPickerTypeAt(double x, double y) {
            return pickerType;
        }

        @Override
        public boolean dungeonPickerAnchorAt(double x, double y) {
            return pickerAnchor;
        }

        @Override
        public boolean insideDungeonPicker(double x, double y) {
            return insidePicker;
        }

        @Override
        public boolean overRouteScrollbar(double x, double y) {
            return overScrollbar;
        }

        @Override
        public int rowIndexAt(double x, double y) {
            rowQueries++;
            return row;
        }

        @Override
        public int dungeonTypeButtonIndexAt(double x, double y) {
            return dungeonTypeButton;
        }

        @Override
        public int waypointControlActionAt(double x, double y) {
            return controlAction;
        }

        @Override
        public int subwaypointStyleActionAt(double x, double y) {
            return styleAction;
        }

        @Override
        public int swatchIndexAt(double x, double y) {
            return swatch;
        }

        @Override
        public boolean lockedColorAt(int rowIndex) {
            return lockedColor;
        }
    }
}
