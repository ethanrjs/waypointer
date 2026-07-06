package dev.ethan.waypointer.chat;

import dev.ethan.waypointer.codec.WaypointCodec;
import dev.ethan.waypointer.config.WaypointerConfig;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChatImportDetectorTest {

    @Test
    void importPillPreservesSenderRankAndNameStyles() throws Exception {
        String export = sampleExport();
        MutableComponent message = Component.empty()
                .append(Component.literal("[MVP++] ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("Babbur").withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(export).withStyle(ChatFormatting.WHITE));

        ChatImportCache cache = new ChatImportCache();
        Component out = invokeDetector(message, cache);

        assertEquals("[MVP++] Babbur: [◆ Click to import Waypoints]", out.getString());
        assertEquals(1, cache.size(), "valid codec should be cached behind a short click handle");

        List<StyledRun> runs = runs(out);
        assertRun(runs, "[MVP++] ", ChatFormatting.GOLD);
        assertRun(runs, "Babbur", ChatFormatting.LIGHT_PURPLE);
        assertRun(runs, ": ", ChatFormatting.GRAY);
        assertTrue(runs.stream().anyMatch(run ->
                        run.text().contains("Click to import Waypoints")
                                && legacyColor(ChatFormatting.AQUA).equals(run.style().getColor())),
                "clickable pill text should keep Waypointer's aqua accent");
    }

    @Test
    void overlayMessagesAreIgnored() throws Exception {
        Component message = Component.literal(sampleExport());
        ChatImportCache cache = new ChatImportCache();

        Component out = invokeDetector(message, cache, true);

        assertSame(message, out);
        assertEquals(0, cache.size());
    }

    @Test
    void malformedChatExportRendersInvalidPillAndDoesNotCacheImport() throws Exception {
        String invalid = truncatedRealChatExport();
        assertFalse(WaypointCodec.isValidCodec(invalid));

        MutableComponent message = Component.empty()
                .append(Component.literal("[MVP++] ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("Babbur").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(invalid).withStyle(ChatFormatting.WHITE));

        ChatImportCache cache = new ChatImportCache();
        Component out = invokeDetector(message, cache);

        assertEquals("[MVP++] Babbur: [Invalid Waypoints]", out.getString());
        assertEquals(0, cache.size(), "invalid payloads should not get clickable import handles");
    }

    @Test
    void multipleExportsBecomeSeparateClickableImportHandles() throws Exception {
        String first = sampleExport();
        String second = secondSampleExport();
        MutableComponent message = Component.empty()
                .append(Component.literal("[VIP] ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("Guide").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(": first ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(first).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" and second ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(second).withStyle(ChatFormatting.WHITE));

        ChatImportCache cache = new ChatImportCache();
        Component out = invokeDetector(message, cache);

        assertEquals(2, countOccurrences(out.getString(), "Click to import Waypoints"));
        assertEquals(2, cache.size(), "each valid payload should get its own cache handle");
        Set<String> importCommands = new LinkedHashSet<>();
        for (StyledRun run : runs(out)) {
            ClickEvent clickEvent = run.style().getClickEvent();
            if (clickEvent instanceof ClickEvent.RunCommand runCommand
                    && runCommand.command().startsWith("/waypointer importchat ")) {
                importCommands.add(runCommand.command());
            }
        }
        assertEquals(2, importCommands.size(), "each visible import pill should target a distinct command");
        for (String command : importCommands) {
            String handle = command.substring("/waypointer importchat ".length());
            assertNotNull(cache.get(handle), "import command handle should resolve to cached payload");
        }
    }

    private static Component invokeDetector(Component message, ChatImportCache cache) throws Exception {
        return invokeDetector(message, cache, false);
    }

    private static Component invokeDetector(Component message, ChatImportCache cache, boolean overlay)
            throws Exception {
        ChatImportDetector detector = new ChatImportDetector(new WaypointerConfig(), cache);
        Method onMessage = ChatImportDetector.class.getDeclaredMethod("onMessage", Component.class, boolean.class);
        onMessage.setAccessible(true);
        return (Component) onMessage.invoke(detector, message, overlay);
    }

    private static List<StyledRun> runs(Component component) {
        List<StyledRun> runs = new ArrayList<>();
        component.visit((FormattedText.StyledContentConsumer<Void>) (style, text) -> {
            if (!text.isEmpty()) runs.add(new StyledRun(text, style));
            return Optional.empty();
        }, Style.EMPTY);
        return runs;
    }

    private static void assertRun(List<StyledRun> runs, String text, ChatFormatting color) {
        assertTrue(runs.stream().anyMatch(run ->
                        run.text().equals(text) && legacyColor(color).equals(run.style().getColor())),
                "missing styled run " + text + " with " + color + ": " + runs);
    }

    private static TextColor legacyColor(ChatFormatting color) {
        TextColor textColor = TextColor.fromLegacyFormat(color);
        assertNotNull(textColor, "test color must have a legacy text color");
        return textColor;
    }

    private static String sampleExport() {
        WaypointGroup group = WaypointGroup.create("Shared", "hub");
        group.add(new Waypoint(10, 70, 20, "start", Waypoint.DEFAULT_COLOR, 0, 0));
        return WaypointCodec.encode(List.of(group), WaypointCodec.Options.WITH_NAMES);
    }

    private static String secondSampleExport() {
        WaypointGroup group = WaypointGroup.create("Shared Two", "hub");
        group.add(new Waypoint(30, 75, -12, "finish", Waypoint.DEFAULT_COLOR, 0, 0));
        return WaypointCodec.encode(List.of(group), WaypointCodec.Options.WITH_NAMES);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int cursor = 0;
        while (true) {
            int next = text.indexOf(needle, cursor);
            if (next < 0) return count;
            count++;
            cursor = next + needle.length();
        }
    }

    private static String truncatedRealChatExport() {
        return "WP:12^)a&p|zWy@Ie3A~~MMKlKe'Zj]MZxf4}+H4U'P]yT%bJR:o{g_?i&4_&U>zNl6q%6$Ar=4=Juwb_=kgD!%'";
    }

    private record StyledRun(String text, Style style) {
    }
}
