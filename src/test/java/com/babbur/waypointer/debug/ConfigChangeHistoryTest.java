package com.babbur.waypointer.debug;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigChangeHistoryTest {

    @AfterEach
    void clearHistory() {
        ConfigChangeHistory.clear();
    }

    @Test
    void keepsNewestChangesFirstAndSkipsNoOps() {
        ConfigChangeHistory.recordSetting("showTracer", "On", "Off");
        ConfigChangeHistory.recordSetting("labelScale", "1", "1");
        ConfigChangeHistory.recordBulk("Applied Minimal preset");

        List<ConfigChangeHistory.Entry> entries = ConfigChangeHistory.snapshot();

        assertEquals(2, entries.size());
        assertEquals("bulk", entries.get(0).kind());
        assertEquals("showTracer", entries.get(1).subject());
        assertEquals("On", entries.get(1).before());
        assertEquals("Off", entries.get(1).after());
    }

    @Test
    void ringBufferStaysBounded() {
        for (int i = 0; i < 40; i++) {
            ConfigChangeHistory.recordSetting("setting" + i, "old", "new");
        }

        List<ConfigChangeHistory.Entry> entries = ConfigChangeHistory.snapshot();

        assertEquals(24, entries.size());
        assertEquals("setting39", entries.get(0).subject());
        assertTrue(entries.stream().noneMatch(entry -> entry.subject().equals("setting0")));
    }
}
