package dev.ethan.waypointer.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AmpersandFormattingTest {

    @Test
    void translatesMinecraftAmpersandFormattingCodes() {
        assertEquals("\u00A7e\u00A7lGold Name",
                AmpersandFormatting.translate("&e&lGold Name"));
    }

    @Test
    void leavesPlainAmpersandsAlone() {
        String plain = "Ruby & Topaz";

        assertSame(plain, AmpersandFormatting.translate(plain));
    }
}
