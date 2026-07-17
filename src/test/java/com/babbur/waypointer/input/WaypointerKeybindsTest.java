package com.babbur.waypointer.input;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.DungeonRoomRouteFeedback;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.lwjgl.glfw.GLFW;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointerKeybindsTest {

    @Test
    void completedRoomRouteFeedbackExplainsHiddenRecovery() {
        Component feedback = DungeonRoomRouteFeedback.completionHiddenUntilNextRun();

        assertEquals("Room route complete -- hidden until next run. Press Previous to reopen.",
                feedback.getString());
        assertEquals(legacyColor(ChatFormatting.AQUA), feedback.getStyle().getColor());
    }

    @Test
    void everyRegisteredKeybindHasEnglishLanguageEntry() throws IOException {
        JsonObject english = loadEnglishLang();

        assertLanguageEntry(english, WaypointerKeybinds.CATEGORY_TRANSLATION_KEY);
        assertEquals(List.of(
                WaypointerKeybinds.OPEN_EDITOR_TRANSLATION_KEY,
                WaypointerKeybinds.ADD_WAYPOINT_HERE_TRANSLATION_KEY,
                WaypointerKeybinds.ADD_NAMED_WAYPOINT_HERE_TRANSLATION_KEY,
                WaypointerKeybinds.ADD_TEMP_WAYPOINT_HERE_TRANSLATION_KEY,
                WaypointerKeybinds.ADD_SUBWAYPOINT_WHERE_LOOKING_TRANSLATION_KEY,
                WaypointerKeybinds.ADD_SMALL_SUBWAYPOINT_WHERE_LOOKING_TRANSLATION_KEY,
                WaypointerKeybinds.SKIP_WAYPOINT_TRANSLATION_KEY,
                WaypointerKeybinds.PREVIOUS_WAYPOINT_TRANSLATION_KEY,
                WaypointerKeybinds.ENTER_EDIT_MODE_TRANSLATION_KEY,
                WaypointerKeybinds.EXIT_EDIT_MODE_TRANSLATION_KEY,
                WaypointerKeybinds.TOGGLE_EDIT_MODE_TRANSLATION_KEY,
                WaypointerKeybinds.REPOSITION_ADD_WAYPOINT_TRANSLATION_KEY,
                WaypointerKeybinds.REPOSITION_ADD_NAMED_WAYPOINT_TRANSLATION_KEY),
                WaypointerKeybinds.KEYBIND_TRANSLATION_KEYS);
        assertEquals(13, WaypointerKeybinds.KEYBIND_TRANSLATION_KEYS.size());
        for (String translationKey : WaypointerKeybinds.KEYBIND_TRANSLATION_KEYS) {
            assertLanguageEntry(english, translationKey);
        }
    }

    @Test
    void keybindDefaultsKeepOnlyEditorBoundByDefault() {
        assertEquals(WaypointerKeybinds.KEYBIND_TRANSLATION_KEYS.size(),
                WaypointerKeybinds.KEYBIND_DEFAULT_KEYS.size());
        assertEquals(GLFW.GLFW_KEY_U, WaypointerKeybinds.OPEN_EDITOR_DEFAULT_KEY);
        assertEquals(InputConstants.UNKNOWN.getValue(), WaypointerKeybinds.UNBOUND_DEFAULT_KEY);
        assertEquals(GLFW.GLFW_KEY_U, WaypointerKeybinds.KEYBIND_DEFAULT_KEYS.get(0));

        for (int index = 1; index < WaypointerKeybinds.KEYBIND_DEFAULT_KEYS.size(); index++) {
            int keyIndex = index;
            String message = WaypointerKeybinds.KEYBIND_TRANSLATION_KEYS.get(keyIndex)
                    + " should be unbound by default";
            assertEquals(InputConstants.UNKNOWN.getValue(),
                    WaypointerKeybinds.KEYBIND_DEFAULT_KEYS.get(keyIndex),
                    message);
        }
    }

    @Test
    void screenKeyFallbackMatchesDefaultOpenEditorKey() {
        assertTrue(WaypointerKeybinds.isOpenEditorKey(
                new KeyEvent(GLFW.GLFW_KEY_U, 0, 0)));
        assertFalse(WaypointerKeybinds.isOpenEditorKey(
                new KeyEvent(GLFW.GLFW_KEY_Y, 0, 0)));
    }

    @Test
    void nullScreenDoesNotBlockScreenOpenCloseKey() {
        assertFalse(WaypointerKeybinds.focusedEditBox(null));
    }

    @Test
    void previousCanRecoverHiddenCompletedDungeonRoomRoute() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("spider", "Spider"));
        assertNotNull(DungeonRoomData.definition("spider"));

        WaypointGroup group = WaypointGroup.create("Room Route", "spider");
        group.add(Waypoint.at(0, 0, 0));
        group.add(Waypoint.at(1, 0, 0));
        manager.add(group);
        group.advancePast(1);
        manager.fireDataChanged();

        assertEquals(0, manager.activeGroups().size());

        // The completed stored route is still reachable through the retreat
        // fallback and moves its persisted progress...
        assertEquals(1, WaypointerKeybinds.retreatPreviousWaypointTargets(manager));
        manager.fireDataChanged();
        assertEquals(1, group.currentIndex());

        // ...but stored dungeon-room routes hold room-local coordinates, so
        // they never render directly: DungeonRoomRouteSync projects them into
        // a runtime mirror while the player is in the room (covered by
        // DungeonRoomRouteSyncTest).
        assertEquals(0, manager.activeGroups().size());
    }

    private static TextColor legacyColor(ChatFormatting color) {
        TextColor textColor = TextColor.fromLegacyFormat(color);
        assertNotNull(textColor, "test color must have a legacy text color");
        return textColor;
    }

    private static JsonObject loadEnglishLang() throws IOException {
        try (InputStream stream = WaypointerKeybindsTest.class.getClassLoader()
                .getResourceAsStream("assets/waypointer/lang/en_us.json")) {
            assertNotNull(stream, "en_us.json must be available as a test resource");
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    private static void assertLanguageEntry(JsonObject english, String translationKey) {
        assertTrue(english.has(translationKey), "Missing language key " + translationKey);
        assertFalse(english.get(translationKey).getAsString().isBlank(),
                "Blank language value for " + translationKey);
    }
}
