package com.babbur.waypointer.core;

import com.babbur.waypointer.codec.WaypointCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SafeTextTest {

    @Test
    void formattingCodesControlsAndDirectionOverridesNeverReachTheScreen() {
        assertEquals("cFree coins", SafeText.line("§cFree coins"));
        assertEquals("Route evil", SafeText.line("Route‮ evil"));
        assertEquals("ab", SafeText.line("a​b⁦"));
        assertEquals("a b", SafeText.line(" a\u0007 b\u0085 "));
        assertEquals("line one\nline two", SafeText.paragraph("line one\nline two\r"));
        assertEquals("line oneline two", SafeText.line("line one\nline two"));
    }

    @Test
    void ordinaryTextAndEmojiSurvive() {
        assertEquals("Mines of Divan → Jungle ⛏", SafeText.line("Mines of Divan → Jungle ⛏"));
        assertEquals("👨‍👩", SafeText.line("👨‍👩"), "joined emoji keep their joiner");
        assertEquals("日本語のルート", SafeText.line("日本語のルート"));
    }

    @Test
    void waypointNamesFromSharesDropDirectionOverridesToo() {
        assertEquals("Chest", WaypointCodec.Options.sanitizeLabel("‮Chest"));
    }
}
