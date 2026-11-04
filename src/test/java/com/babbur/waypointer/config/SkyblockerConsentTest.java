package com.babbur.waypointer.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SkyblockerConsentTest {
    @Test void sharingDefaultsOnAndPreservesLocalOptOuts() {
        assertTrue(new WaypointerConfig().crystalHollowsSkyblockerSharing());
        assertTrue(WaypointerConfig.fromJson("{}").crystalHollowsSkyblockerSharing());
        assertTrue(WaypointerConfig.fromJson("{\"crystalHollowsSkyblockerSharing\":true}")
                .crystalHollowsSkyblockerSharing());
        var local = WaypointerConfig.fromJson("{\"crystalHollowsSkyblockerSharing\":false}");
        assertFalse(local.crystalHollowsSkyblockerSharing());
        assertTrue(WaypointerConfigCodec.decode(WaypointerConfigCodec.encode(local))
                .crystalHollowsSkyblockerSharing());
        var recipient = new WaypointerConfig();
        recipient.replaceShareableSettingsWith(local);
        assertTrue(recipient.crystalHollowsSkyblockerSharing());
        local.replaceShareableSettingsWith(new WaypointerConfig());
        assertFalse(local.crystalHollowsSkyblockerSharing());
        local.resetToDefaults();
        assertTrue(local.crystalHollowsSkyblockerSharing());
        local.disableAllSettings();
        assertFalse(local.crystalHollowsSkyblockerSharing());
    }
}
