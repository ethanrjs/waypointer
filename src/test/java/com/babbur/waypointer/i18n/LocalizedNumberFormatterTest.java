package com.babbur.waypointer.i18n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalizedNumberFormatterTest {

    @Test
    void formatsWesternDigitsAndDecimalSeparator() {
        LocalizedNumberFormatter numbers = LocalizedNumberFormatter.forMinecraftLocale("en_us");

        assertEquals("1234567890", numbers.integer(1_234_567_890));
        assertEquals("12.5", numbers.oneDecimal(12.5));
        assertEquals("#12.3", numbers.waypointOrdinal("#12.3"));
    }

    @Test
    void formatsArabicIndicDigits() {
        LocalizedNumberFormatter numbers = LocalizedNumberFormatter.forMinecraftLocale("ar_sa");

        assertEquals("\u0661\u0662\u0663", numbers.integer(123));
        assertEquals("\u0661\u0662\u066b\u0665", numbers.oneDecimal(12.5));
        assertEquals("#\u0661\u0662\u066b\u0663", numbers.waypointOrdinal("#12.3"));
    }

    @Test
    void formatsPersianDigits() {
        LocalizedNumberFormatter numbers = LocalizedNumberFormatter.forMinecraftLocale("fa_ir");

        assertEquals("\u06f1\u06f2\u06f3", numbers.integer(123));
        assertEquals("\u06f1\u06f2\u066b\u06f5", numbers.oneDecimal(12.5));
        assertEquals("#\u06f1\u06f2\u066b\u06f3", numbers.waypointOrdinal("#12.3"));
    }

    @Test
    void keepsClDrDefaultWesternDigitsForHindiAndThai() {
        assertEquals("#12.3", LocalizedNumberFormatter.forMinecraftLocale("hi_in")
                .waypointOrdinal("#12.3"));
        assertEquals("#12.3", LocalizedNumberFormatter.forMinecraftLocale("th_th")
                .waypointOrdinal("#12.3"));
    }
}
