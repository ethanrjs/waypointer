package dev.ethan.waypointer.compat;

import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftCompatTest {

    @Test
    void currentMinecraftExposesEveryRequiredCompatibilityBinding() {
        assertTrue(MinecraftCompat.requiredBindingsAvailable());
    }

    @Test
    void legacyColorUsesTheStableTextColorBridge() {
        assertEquals(0xFF5555, MinecraftCompat.legacyColor(ChatFormatting.RED));
        assertNull(MinecraftCompat.legacyColor(ChatFormatting.BOLD));
    }
}
