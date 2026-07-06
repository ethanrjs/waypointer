package dev.ethan.waypointer.screen;

import dev.ethan.waypointer.core.Waypoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddTempScreenTest {
    @Test
    void usesDurationFieldOnlyForTimeMode() {
        assertTrue(AddTempScreen.usesDurationField(Waypoint.TEMP_TIME));
        assertFalse(AddTempScreen.usesDurationField(Waypoint.TEMP_UNTIL_REACHED));
        assertFalse(AddTempScreen.usesDurationField(Waypoint.TEMP_UNTIL_LEAVE));
        assertFalse(AddTempScreen.usesDurationField(Waypoint.TEMP_NONE));
        assertFalse(AddTempScreen.usesDurationField(999));
    }
    @Test
    void modeCycleSkipsNonTemporaryNoneMode() {
        assertEquals(Waypoint.TEMP_UNTIL_REACHED,
                AddTempScreen.nextTempMode(Waypoint.TEMP_TIME));
        assertEquals(Waypoint.TEMP_UNTIL_LEAVE,
                AddTempScreen.nextTempMode(Waypoint.TEMP_UNTIL_REACHED));
        assertEquals(Waypoint.TEMP_TIME,
                AddTempScreen.nextTempMode(Waypoint.TEMP_UNTIL_LEAVE));
        assertEquals(Waypoint.TEMP_TIME,
                AddTempScreen.nextTempMode(Waypoint.TEMP_NONE));
    }
    @Test
    void durationEditsClampZeroAndKeepLastValidValueForInvalidInput() {
        assertEquals(7, AddTempScreen.durationSecondsAfterEdit(3, " 7 "));
        assertEquals(1, AddTempScreen.durationSecondsAfterEdit(3, "0"));
        assertEquals(1, AddTempScreen.durationSecondsAfterEdit(3, "-4"));
        assertEquals(9, AddTempScreen.durationSecondsAfterEdit(9, ""));
        assertEquals(9, AddTempScreen.durationSecondsAfterEdit(9, "abc"));
        assertEquals(1, AddTempScreen.durationSecondsAfterEdit(0, null));
        assertEquals(24 * 60 * 60, AddTempScreen.durationSecondsAfterEdit(9, "99999"));
    }
}
