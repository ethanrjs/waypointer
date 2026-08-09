package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.data.DungeonRoomShareCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.FOOTER_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_SECTION;
import static com.babbur.waypointer.screen.GuiTokens.PAD_OUTER;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE_SUBTLE;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;

final class DungeonRoomExportScreen extends Screen {
    private static final int PREVIEW_INSET = 6;
    private static final int LINE_H = 12;
    private static final long COPIED_FEEDBACK_MS = 1500;

    private final Screen parent;

    private String payload = "";
    private String encodingError = "";
    private boolean encoding = true;
    private final String subtitle;

    private Button copyButton;
    private Button copyCodeBlockButton;
    private long copyFeedbackUntil;
    private long copyCodeBlockFeedbackUntil;

    private DungeonRoomExportScreen(Screen parent, int roomCount, int waypointCount) {
        super(Component.translatable("waypointer.screen.dungeon_export.title"));
        this.parent = parent;
        this.subtitle = Component.translatable("waypointer.screen.dungeon_export.subtitle",
                roomCount, waypointCount).getString();
    }

    static void open(Screen parent, List<WaypointGroup> routes,
                     int roomCount, int waypointCount) {
        DungeonRoomExportScreen screen =
                new DungeonRoomExportScreen(parent, roomCount, waypointCount);
        MinecraftCompat.setScreen(Minecraft.getInstance(), screen);
        List<WaypointGroup> encodeInput = List.copyOf(routes);
        if (!CodecWorker.run(() -> DungeonRoomShareCodec.encode(encodeInput), screen::applyPayload)) {
            screen.failEncoding(Component.translatable("waypointer.codec.busy").getString());
        }
    }

    private void applyPayload(String encoded) {
        if (encoded == null) {
            failEncoding(Component.translatable(
                    "waypointer.command.export.failed", "unexpected encoding failure").getString());
            return;
        }
        this.payload = encoded;
        this.encodingError = "";
        this.encoding = false;
        updateCopyButtonsActive();
    }

    private void failEncoding(String message) {
        this.payload = "";
        this.encodingError = message;
        this.encoding = false;
        updateCopyButtonsActive();
    }

    private void updateCopyButtonsActive() {
        boolean ready = !encoding && !payload.isEmpty();
        if (copyButton != null) copyButton.active = ready;
        if (copyCodeBlockButton != null) copyCodeBlockButton.active = ready;
    }

    @Override
    protected void init() {
        int footerY = height - FOOTER_H;
        int copyW = 140;
        int codeBlockCopyW = 136;
        int rightClusterW = codeBlockCopyW + GAP + copyW;
        int codeBlockCopyX = width - PAD_OUTER - rightClusterW;
        int copyX = width - PAD_OUTER - copyW;

        GuiTokens.layoutFooter(width, footerY,
                List.of(new GuiTokens.ButtonSpec("Back", this::goBackToParent)),
                null,
                this::addRenderableWidget,
                font,
                PAD_OUTER,
                PAD_OUTER + rightClusterW + GAP_SECTION);

        copyCodeBlockButton = GuiTokens.styledButton(
                codeBlockCopyX, footerY, codeBlockCopyW, BTN_H,
                Component.translatable("waypointer.export.copy_code_block"), this::copyAsCodeBlock, null);
        copyButton = GuiTokens.styledButton(copyX, footerY, copyW, BTN_H,
                Component.translatable("waypointer.export.copy_clipboard"), this::copyToClipboard, null);
        addRenderableWidget(copyCodeBlockButton);
        addRenderableWidget(copyButton);
        updateCopyButtonsActive();
    }

    private void goBackToParent() {
        MinecraftCompat.setScreen(minecraft, parent);
    }

    private void copyToClipboard(Button button) {
        minecraft.keyboardHandler.setClipboard(payload);
        copyFeedbackUntil = System.currentTimeMillis() + COPIED_FEEDBACK_MS;
        copyButton.setMessage(Component.translatable("waypointer.common.copied")
                .withStyle(ChatFormatting.GREEN));
    }

    private void copyAsCodeBlock(Button button) {
        minecraft.keyboardHandler.setClipboard(ExportPolicy.codeBlockPayload(payload));
        copyCodeBlockFeedbackUntil = System.currentTimeMillis() + COPIED_FEEDBACK_MS;
        copyCodeBlockButton.setMessage(Component.translatable("waypointer.common.copied")
                .withStyle(ChatFormatting.GREEN));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);
        updateCopyFeedback();

        g.text(font, getTitle(), PAD_OUTER, PAD_OUTER, TEXT, false);
        g.text(font, subtitle, PAD_OUTER, PAD_OUTER + LINE_H, TEXT_DIM, false);

        int y = PAD_OUTER + LINE_H * 3;
        if (encoding) {
            g.text(font, Component.translatable("waypointer.screen.export.encoding"),
                    PAD_OUTER, y, TEXT_MUTED, false);
        } else if (!encodingError.isEmpty()) {
            g.text(font, encodingError, PAD_OUTER, y, 0xFFFF5555, false);
        } else {
            ExportScreen.drawSizeLine(g, font, PAD_OUTER, y, width - PAD_OUTER, payload);
        }

        y += LINE_H + GAP_SECTION;
        g.text(font, Component.translatable("waypointer.screen.dungeon_export.preview"),
                PAD_OUTER, y, TEXT_DIM, false);
        y += LINE_H;
        drawPreview(g, PAD_OUTER, y, width - PAD_OUTER, height - FOOTER_H - GAP);
    }

    private void updateCopyFeedback() {
        long now = System.currentTimeMillis();
        if (copyFeedbackUntil != 0 && now > copyFeedbackUntil) {
            copyFeedbackUntil = 0;
            copyButton.setMessage(Component.translatable("waypointer.export.copy_clipboard"));
        }
        if (copyCodeBlockFeedbackUntil != 0 && now > copyCodeBlockFeedbackUntil) {
            copyCodeBlockFeedbackUntil = 0;
            copyCodeBlockButton.setMessage(Component.translatable("waypointer.export.copy_code_block"));
        }
    }

    private void drawPreview(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, 0x30FFFFFF);
        g.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, SURFACE_SUBTLE);

        int innerX = x1 + PREVIEW_INSET;
        int innerY = y1 + PREVIEW_INSET;
        int innerW = x2 - x1 - PREVIEW_INSET * 2;
        if (encoding) {
            g.text(font, Component.translatable("waypointer.screen.export.encoding"),
                    innerX, innerY, TEXT_MUTED, false);
            return;
        }
        if (!encodingError.isEmpty()) {
            g.text(font, encodingError, innerX, innerY, 0xFFFF5555, false);
            return;
        }

        List<FormattedCharSequence> lines = font.split(FormattedText.of(payload), innerW);
        int lineH = font.lineHeight + 1;
        int available = (y2 - y1 - PREVIEW_INSET * 2) / lineH;
        int shown = lines.size() <= available ? lines.size() : Math.max(0, available - 1);

        int y = innerY;
        for (int i = 0; i < shown; i++, y += lineH) {
            g.text(font, lines.get(i), innerX, y, TEXT, false);
        }
        if (shown < lines.size()) {
            g.text(font, ExportPolicy.previewOverflowText(lines.size() - shown),
                    innerX, y, TEXT_MUTED, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        MinecraftCompat.setScreen(minecraft, parent);
    }
}
