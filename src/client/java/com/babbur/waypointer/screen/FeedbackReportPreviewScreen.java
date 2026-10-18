package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.FormattedCharSequence;
import java.util.List;
import static com.babbur.waypointer.screen.GuiTokens.*;

final class FeedbackReportPreviewScreen extends Screen {
    private final Screen parent;
    private final String report;
    private List<FormattedCharSequence> lines = List.of();
    private int scroll;

    FeedbackReportPreviewScreen(Screen parent, String report) {
        super(FeedbackScreen.tr("debug.preview.title", "Debug report preview"));
        this.parent = parent;
        this.report = report;
    }

    @Override protected void init() {
        lines = font.split(Component.literal(report), Math.max(100, width - 32));
        scroll = Math.max(0, Math.min(Math.max(0, lines.size() * 11 - (height - 64)), scroll));
        addRenderableWidget(styledButton(width / 2 - 65, height - 28, 130, BTN_H,
                FeedbackScreen.tr("debug.preview.back", "Back to feedback"), b -> onClose(), null));
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        scroll = Math.max(0, Math.min(Math.max(0, lines.size() * 11 - (height - 64)),
                scroll - (int) (vertical * 33)));
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN
                || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP) {
            mouseScrolled(0, 0, 0, event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN ? -5 : 5);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float partial) {
        g.fill(0, 0, width, height, SURFACE);
        g.text(font, getTitle(), 16, 10, TEXT, false);
        g.enableScissor(16, 30, width - 16, height - 34);
        int first = scroll / 11;
        int last = Math.min(lines.size(), first + Math.max(0, height - 64) / 11 + 2);
        for (int i = first; i < last; i++)
            g.text(font, lines.get(i), 16, 30 + i * 11 - scroll, TEXT_DIM, false);
        g.disableScissor();
        super.extractRenderState(g, x, y, partial);
    }

    @Override public void onClose() { MinecraftCompat.setScreen(minecraft, parent); }
    @Override public boolean isPauseScreen() { return false; }
}
