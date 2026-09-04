package com.babbur.waypointer.chat;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.codec.UniversalShareCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatImportDetectorBehaviorTest {

    private static final String USER_PASTED_ROUTE =
            "WP:[\"!*Pu]q5tA^N^X?7?d=MV[z(um7f{qlB/ABChxUxD3_x?ZjT0b<]t<fU-h;[4M9LQqGx3wEq<7P:Y@s/*EhUh9\"!";

    @Test
    void configDisabledLeavesCodecChatUntouched() throws Exception {
        WaypointerConfig config = new WaypointerConfig();
        config.setChatCodecDetection(false);
        Component message = Component.literal(sampleExport());
        ChatImportCache cache = new ChatImportCache();

        Component out = invokeDetector(message, config, cache, false);

        assertSame(message, out);
        assertEquals(0, cache.size());
    }

    @Test
    void validImportPillCarriesRunnableImportChatCommand() throws Exception {
        ChatImportCache cache = new ChatImportCache();

        Component out = invokeDetector(Component.literal(sampleExport()), new WaypointerConfig(), cache, false);

        String clickedCommand = null;
        for (StyledRun run : runs(out)) {
            ClickEvent clickEvent = run.style.getClickEvent();
            if (clickEvent instanceof ClickEvent.RunCommand runCommand
                    && runCommand.command().startsWith("/waypointer importchat ")) {
                clickedCommand = runCommand.command();
                break;
            }
        }
        assertNotNull(clickedCommand, "valid import pill should carry a run-command click event");
        String handle = clickedCommand.substring("/waypointer importchat ".length());
        assertNotNull(cache.get(handle), "click command handle should resolve to cached payload");
    }

    @Test
    void exactPlayerChatPayloadGetsClickableHoverableImportPill() {
        ChatImportCache cache = new ChatImportCache();
        ChatImportDetector detector = new ChatImportDetector(new WaypointerConfig(), cache);

        Component out = detector.decorate(Component.literal("Babbur: " + USER_PASTED_ROUTE));

        String command = null;
        Component hover = null;
        for (StyledRun run : runs(out)) {
            if (run.style.getClickEvent() instanceof ClickEvent.RunCommand click) {
                command = click.command();
            }
            if (run.style.getHoverEvent() instanceof HoverEvent.ShowText showText) {
                hover = showText.value();
            }
        }
        assertNotNull(command, "player-chat route code should have a click action");
        assertTrue(command.startsWith("/waypointer importchat "));
        String handle = command.substring("/waypointer importchat ".length());
        assertEquals(USER_PASTED_ROUTE, cache.get(handle));
        assertNotNull(hover, "player-chat route code should have hover details");
        assertEquals(1, countTranslationKey(out, "waypointer.chat.import.click"));
    }

    @Test
    void v10ConfigPillIsTypedAndPromisesReviewRatherThanRouteMutation() throws Exception {
        WaypointerConfig shared = new WaypointerConfig();
        shared.setShowTracer(false);
        String payload = UniversalShareCodec.encodeConfig(shared);
        ChatImportCache cache = new ChatImportCache();

        Component out = invokeDetector(Component.literal(payload),
                new WaypointerConfig(), cache, false);

        String command = null;
        Component hover = null;
        for (StyledRun run : runs(out)) {
            if (run.style.getClickEvent() instanceof ClickEvent.RunCommand click) {
                command = click.command();
            }
            if (run.style.getHoverEvent() instanceof HoverEvent.ShowText showText) {
                hover = showText.value();
            }
        }
        assertNotNull(command);
        assertTrue(command.startsWith("/waypointer importchat config "));
        String handle = command.substring("/waypointer importchat config ".length());
        assertEquals(payload, cache.get(handle));
        assertEquals(1, countTranslationKey(out, "waypointer.chat.import.click.config"));
        assertEquals(0, countTranslationKey(out, "waypointer.chat.import.click"));
        assertNotNull(hover);
        assertEquals(1, countTranslationKey(hover, "waypointer.chat.import.config"));
        assertEquals(1, countTranslationKey(hover, "waypointer.chat.import.hover.config"));
        assertEquals(0, countTranslationKey(hover, "waypointer.chat.import.hover"));
    }

    @Test
    void v10DungeonPillUsesTheTypedDungeonImportCommandAndCopy() throws Exception {
        WaypointGroup dungeon = WaypointGroup.create("Crypt", "crypt-a");
        dungeon.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
        dungeon.add(new Waypoint(1, 70, 2, "Chest", 0xAA5500,
                Waypoint.FLAG_DUNGEON_SECRET, 0.0));
        String payload = UniversalShareCodec.encodeDungeon(List.of(dungeon));
        ChatImportCache cache = new ChatImportCache();

        Component out = invokeDetector(Component.literal(payload),
                new WaypointerConfig(), cache, false);

        String command = null;
        Component hover = null;
        for (StyledRun run : runs(out)) {
            if (run.style.getClickEvent() instanceof ClickEvent.RunCommand click) {
                command = click.command();
            }
            if (run.style.getHoverEvent() instanceof HoverEvent.ShowText showText) {
                hover = showText.value();
            }
        }
        assertNotNull(command);
        assertTrue(command.startsWith("/waypointer importchat dungeon "));
        String handle = command.substring("/waypointer importchat dungeon ".length());
        assertEquals(payload, cache.get(handle));
        assertEquals(1, countTranslationKey(out, "waypointer.chat.import.click.dungeon"));
        assertEquals(0, countTranslationKey(out, "waypointer.chat.import.click"));
        assertNotNull(hover);
        assertEquals(1, countTranslationKey(hover, "waypointer.chat.import.dungeon"));
        assertEquals(1, countTranslationKey(hover, "waypointer.chat.import.hover.dungeon"));
        assertEquals(0, countTranslationKey(hover, "waypointer.chat.import.hover"));
    }

    private static Component invokeDetector(Component message, WaypointerConfig config,
                                            ChatImportCache cache, boolean overlay)
            throws Exception {
        ChatImportDetector detector = new ChatImportDetector(config, cache);
        Method onMessage = ChatImportDetector.class.getDeclaredMethod("onMessage", Component.class, boolean.class);
        onMessage.setAccessible(true);
        return (Component) onMessage.invoke(detector, message, overlay);
    }

    private static List<StyledRun> runs(Component component) {
        List<StyledRun> runs = new ArrayList<>();
        component.visit(
                (FormattedText.StyledContentConsumer<Void>) (style, text) -> {
                    if (!text.isEmpty()) runs.add(new StyledRun(text, style));
                    return Optional.empty();
                }, Style.EMPTY);
        return runs;
    }

    private static int countTranslationKey(Component component, String key) {
        int count = component.getContents()
                instanceof net.minecraft.network.chat.contents.TranslatableContents translated
                && translated.getKey().equals(key) ? 1 : 0;
        for (Component sibling : component.getSiblings()) {
            count += countTranslationKey(sibling, key);
        }
        return count;
    }

    private static String sampleExport() {
        WaypointGroup group = WaypointGroup.create("Shared", "hub");
        group.add(new Waypoint(10, 70, 20, "start", Waypoint.DEFAULT_COLOR, 0, 0));
        return WaypointCodec.encode(List.of(group), WaypointCodec.Options.WITH_NAMES);
    }

    private static final class StyledRun {
        private final String text;
        private final Style style;

        private StyledRun(String text, Style style) {
            this.text = text;
            this.style = style;
        }
    }
}
