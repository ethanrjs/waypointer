package com.babbur.waypointer.screen.preview;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointAppearancePreviewPartTest {
    @Test void everyCategoryMapsBySettingIdentityRatherThanItsTranslatedTitle() {
        String[] settings = {"maxStaticWaypointRenderDistance", "boxStyle", "currentWaypointOpacity", "labelScale",
                "showTracer", "beaconBeamMode", "showRouteLines", "routeMinRenderDistance"};
        var parts = WaypointAppearancePreviewPart.values();
        assertEquals(settings.length, parts.length);
        for (int i = 0; i < settings.length; i++) assertEquals(parts[i], WaypointAppearancePreviewPart.forSettings(List.of(settings[i])));
        assertNull(WaypointAppearancePreviewPart.forSettings(List.of("unrelatedSetting")));
    }
}
