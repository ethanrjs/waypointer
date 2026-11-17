package com.babbur.waypointer.codec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportFailuresTest {

    /** The code from issue #197: a WP: code that Discord mangled. */
    private static final String MANGLED =
            "WP:~6~x:1&6MID0Yp6#p_D1m&6ac>A2eo[=VtYra+{kyYL)ZLv_M/Re;UZ1woD7_H^s-$QC_a#deBRU&SGMr9lS^>199#K4%G=P!";

    @AfterEach
    void clear() {
        ImportFailures.clearRecent();
    }

    @Test
    void aDamagedCodeIsReportedAsDamagedNotAsEveryDecodersError() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode(MANGLED));
        assertEquals(ImportFailures.Kind.DAMAGED, ImportFailures.record("test", MANGLED, failure));
        var recent = ImportFailures.recent().getFirst();
        assertEquals("WP:", recent.prefix());
        assertEquals(MANGLED.length(), recent.length());
        assertFalse(recent.detail().contains(MANGLED.substring(10)), "the code itself is not kept");
    }

    @Test
    void textThatIsNoCodeIsNotACode() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> UniversalShareCodec.decode("hello there"));
        assertEquals(ImportFailures.Kind.NOT_A_CODE, ImportFailures.classify("hello there", failure));
        assertEquals(ImportFailures.Kind.OTHER, ImportFailures.classify("{}",
                new IllegalArgumentException("group \"a\" has too many waypoints")));
        assertTrue(ImportFailures.Kind.OTHER.translationKey() == null);
    }

    @Test
    void aDamagedWpCodeGetsTheDiscordHint() {
        assertEquals("waypointer.import.error.damaged_discord",
                ImportFailures.Kind.DAMAGED.translationKey(MANGLED));
        assertEquals("waypointer.import.error.damaged",
                ImportFailures.Kind.DAMAGED.translationKey("WPL:abc"));
        assertEquals("waypointer.import.error.unrecognized",
                ImportFailures.Kind.NOT_A_CODE.translationKey("WP:abc"));
    }
}
