package com.babbur.waypointer.config;

import com.babbur.waypointer.codec.UniversalShareCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigCodeNewSettingsTest {
    @Test
    void lineOfSightAndLabelSettingsTravelInConfigCodes() {
        WaypointerConfig config = new WaypointerConfig();
        config.setLineOfSightOnly(true);
        config.setShowLabels(false);
        config.setLabelMaxDistance(64);

        UniversalShareCodec.Decoded decoded = UniversalShareCodec.decode(UniversalShareCodec.encodeConfig(config));
        WaypointerConfig imported = assertInstanceOf(UniversalShareCodec.Configuration.class, decoded).config();

        assertTrue(imported.lineOfSightOnly());
        assertFalse(imported.showLabels());
        assertEquals(64.0, imported.labelMaxDistance());
    }

    @Test
    void defaultsStayOutOfConfigCodes() {
        WaypointerConfig config = new WaypointerConfig();
        String plain = UniversalShareCodec.encodeConfig(config);
        config.setShowLabels(true);
        config.setLabelMaxDistance(0);
        config.setLineOfSightOnly(false);
        assertEquals(plain, UniversalShareCodec.encodeConfig(config));
    }
}
