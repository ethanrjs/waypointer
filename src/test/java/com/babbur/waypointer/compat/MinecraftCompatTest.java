package com.babbur.waypointer.compat;

import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MinecraftCompatTest {

    @Test
    void legacyColorUsesTheStableTextColorBridge() {
        assertEquals(0xFF5555, MinecraftCompat.legacyColor(ChatFormatting.RED));
        assertNull(MinecraftCompat.legacyColor(ChatFormatting.BOLD));
    }
}
