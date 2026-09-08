package com.babbur.waypointer.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SkyblockerConsentTest {
    @Test void consentIsOffByDefaultAndPersistsOnlyLocally() {
        assertFalse(new WaypointerConfig().crystalHollowsSkyblockerSharing());
        assertFalse(WaypointerConfig.fromJson("{}").crystalHollowsSkyblockerSharing());
        var local = WaypointerConfig.fromJson("{\"crystalHollowsSkyblockerSharing\":true}");
        assertTrue(local.crystalHollowsSkyblockerSharing());
        assertFalse(WaypointerConfigCodec.decode(WaypointerConfigCodec.encode(local))
                .crystalHollowsSkyblockerSharing());
        var recipient = new WaypointerConfig();
        recipient.replaceShareableSettingsWith(local);
        assertFalse(recipient.crystalHollowsSkyblockerSharing());
        local.replaceShareableSettingsWith(new WaypointerConfig());
        assertTrue(local.crystalHollowsSkyblockerSharing());
        local.resetToDefaults();
        assertFalse(local.crystalHollowsSkyblockerSharing());
        local.setCrystalHollowsSkyblockerSharing(true);
        local.disableAllSettings();
        assertFalse(local.crystalHollowsSkyblockerSharing());
    }
}
