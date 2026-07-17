package com.babbur.waypointer.chat;

import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChatImportDetectorBehaviorTest {

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
