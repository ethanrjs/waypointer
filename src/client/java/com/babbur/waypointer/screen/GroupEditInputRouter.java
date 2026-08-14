package com.babbur.waypointer.screen;

import static com.babbur.waypointer.screen.GroupEditPolicy.SUBWAY_STYLE_ACTION_NONE;
import static com.babbur.waypointer.screen.GroupEditPolicy.WAYPOINT_CONTROL_ACTION_NONE;

final class GroupEditInputRouter {
    static final int MOUSE_BUTTON_LEFT = 0;
    static final int MOUSE_BUTTON_RIGHT = 1;
    static final int MOUSE_BUTTON_MIDDLE = 2;

    private GroupEditInputRouter() {
    }

    enum ActionType {
        NONE,
        CONSUME,
        SELECT_DUNGEON_TYPE,
        CLOSE_DUNGEON_PICKER_WITH_SOUND,
        CLOSE_DUNGEON_PICKER,
        BEGIN_ROUTE_SCROLLBAR_DRAG,
        TOGGLE_DISABLED,
        TOGGLE_SUBWAYPOINT,
        GOTO,
        OPEN_DUNGEON_TYPE_PICKER,
        TOGGLE_WAYPOINT_CONTROL,
        TOGGLE_SUBWAYPOINT_STYLE,
        REPOSITION,
        UNLOCK_COLOR,
        EDIT_COLOR,
        SELECT,
        RENAME
    }

    record Action(ActionType type, int rowIndex, int value) {
        private static final Action NONE = new Action(ActionType.NONE, -1, -1);

        Action {
            if (type == null) throw new IllegalArgumentException("action type cannot be null");
        }

        static Action of(ActionType type) {
            return new Action(type, -1, -1);
        }

        static Action row(ActionType type, int rowIndex) {
            return new Action(type, rowIndex, -1);
        }

        static Action rowValue(ActionType type, int rowIndex, int value) {
            return new Action(type, rowIndex, value);
        }

        boolean handledBeforeWidgets() {
            return type != ActionType.NONE;
        }
    }

    record Pointer(double x, double y, int button, boolean shiftDown, boolean doubleClick) {
    }

    record State(boolean dungeonPickerOpen, int selectedIndex) {
    }

    // Hit tests must not mutate editor state.
    interface Geometry {
        int dungeonPickerTypeAt(double x, double y);

        boolean dungeonPickerAnchorAt(double x, double y);

        boolean insideDungeonPicker(double x, double y);

        boolean overRouteScrollbar(double x, double y);

        int rowIndexAt(double x, double y);

        int dungeonTypeButtonIndexAt(double x, double y);

        int waypointControlActionAt(double x, double y);

        int subwaypointStyleActionAt(double x, double y);

        int swatchIndexAt(double x, double y);

        boolean lockedColorAt(int rowIndex);
    }

    // Earlier checks have click precedence; NONE defers to child widgets.
    static Action beforeWidgets(Pointer pointer, State state, Geometry geometry) {
        if (pointer == null || state == null || geometry == null) return Action.NONE;

        if (state.dungeonPickerOpen()) {
            return dungeonPickerAction(pointer, geometry);
        }
        if (pointer.button() == MOUSE_BUTTON_LEFT
                && geometry.overRouteScrollbar(pointer.x(), pointer.y())) {
            return Action.of(ActionType.BEGIN_ROUTE_SCROLLBAR_DRAG);
        }

        int rowIndex = geometry.rowIndexAt(pointer.x(), pointer.y());
        if (rowIndex >= 0 && pointer.button() == MOUSE_BUTTON_MIDDLE) {
            return Action.row(ActionType.TOGGLE_DISABLED, rowIndex);
        }
        if (rowIndex >= 0 && pointer.button() == MOUSE_BUTTON_RIGHT) {
            return Action.row(pointer.shiftDown()
                    ? ActionType.TOGGLE_SUBWAYPOINT : ActionType.GOTO, rowIndex);
        }
        if (pointer.button() != MOUSE_BUTTON_LEFT) return Action.NONE;

        int dungeonTypeIndex = geometry.dungeonTypeButtonIndexAt(pointer.x(), pointer.y());
        if (dungeonTypeIndex >= 0) {
            return Action.row(ActionType.OPEN_DUNGEON_TYPE_PICKER, dungeonTypeIndex);
        }

        int controlAction = geometry.waypointControlActionAt(pointer.x(), pointer.y());
        if (controlAction != WAYPOINT_CONTROL_ACTION_NONE) {
            return Action.rowValue(ActionType.TOGGLE_WAYPOINT_CONTROL,
                    rowIndex, controlAction);
        }

        int styleAction = geometry.subwaypointStyleActionAt(pointer.x(), pointer.y());
        if (styleAction != SUBWAY_STYLE_ACTION_NONE) {
            return Action.rowValue(ActionType.TOGGLE_SUBWAYPOINT_STYLE,
                    rowIndex, styleAction);
        }

        int swatchIndex = geometry.swatchIndexAt(pointer.x(), pointer.y());
        if (pointer.shiftDown() && rowIndex >= 0 && swatchIndex < 0) {
            return Action.row(ActionType.REPOSITION, rowIndex);
        }
        if (swatchIndex >= 0) {
            return Action.row(pointer.shiftDown() && geometry.lockedColorAt(swatchIndex)
                    ? ActionType.UNLOCK_COLOR : ActionType.EDIT_COLOR, swatchIndex);
        }
        return Action.NONE;
    }

    // Run only after child widgets decline the click.
    static Action afterWidgets(Pointer pointer, State state, Geometry geometry) {
        if (pointer == null || state == null || geometry == null
                || pointer.button() != MOUSE_BUTTON_LEFT) {
            return Action.NONE;
        }
        int rowIndex = geometry.rowIndexAt(pointer.x(), pointer.y());
        if (rowIndex < 0) return Action.NONE;
        boolean rename = pointer.doubleClick() && rowIndex == state.selectedIndex();
        return Action.row(rename ? ActionType.RENAME : ActionType.SELECT, rowIndex);
    }

    private static Action dungeonPickerAction(Pointer pointer, Geometry geometry) {
        if (pointer.button() == MOUSE_BUTTON_LEFT) {
            int typeIndex = geometry.dungeonPickerTypeAt(pointer.x(), pointer.y());
            if (typeIndex >= 0) {
                return Action.rowValue(ActionType.SELECT_DUNGEON_TYPE, -1, typeIndex);
            }
            if (geometry.dungeonPickerAnchorAt(pointer.x(), pointer.y())) {
                return Action.of(ActionType.CLOSE_DUNGEON_PICKER_WITH_SOUND);
            }
        }
        return geometry.insideDungeonPicker(pointer.x(), pointer.y())
                ? Action.of(ActionType.CONSUME)
                : Action.of(ActionType.CLOSE_DUNGEON_PICKER);
    }
}
