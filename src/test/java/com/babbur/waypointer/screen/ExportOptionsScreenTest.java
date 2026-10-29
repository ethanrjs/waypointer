package com.babbur.waypointer.screen;

import com.babbur.waypointer.codec.WaypointExportCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import org.junit.jupiter.api.Test;

import static com.babbur.waypointer.screen.ExportOptionsScreen.Option;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportOptionsScreenTest {

    @Test
    void optionsAreEditableOnlyWhereTheFormatCarriesThem() {
        for (Option option : Option.values()) {
            assertTrue(ExportOptionsScreen.editable(option, WaypointExportCodec.Target.WAYPOINTER, false), option.name());
        }
        assertTrue(ExportOptionsScreen.editable(Option.NAMES, WaypointExportCodec.Target.SKYBLOCKER, false));
        assertFalse(ExportOptionsScreen.editable(Option.RADII, WaypointExportCodec.Target.SKYBLOCKER, false));
    }

    @Test
    void subwaypointsKeepWaypointDisplayIncluded() {
        WaypointerConfig config = new WaypointerConfig();
        config.setExportIncludeWaypointFlags(false);

        assertFalse(ExportOptionsScreen.shownIncluded(Option.WAYPOINT_FLAGS, config, false));
        assertTrue(ExportOptionsScreen.shownIncluded(Option.WAYPOINT_FLAGS, config, true));
        assertFalse(ExportOptionsScreen.editable(Option.WAYPOINT_FLAGS, WaypointExportCodec.Target.WAYPOINTER, true));
    }
}
