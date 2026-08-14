package com.babbur.waypointer.screen;

import com.babbur.waypointer.catalog.PublisherNamePolicy;
import com.babbur.waypointer.compat.MinecraftCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_SECTION;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE_SUBTLE;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.styledButton;

final class PublisherNameScreen extends Screen {
    private static final int PANEL_W = 336;
    private static final int PANEL_H = 176;
    private static final int PAD = 16;
    private static final int ACTION_W = 104;

    private enum Stage { ENTRY, CONFIRM }

    private final Screen parent;
    private final Consumer<String> confirmed;
    private Stage stage = Stage.ENTRY;
    private String nameValue;
    private EditBox nameBox;
    private Button primaryButton;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    PublisherNameScreen(
            Screen parent, String suggestedName, Consumer<String> confirmed) {
        super(Component.translatable("waypointer.screen.publisher_name.title"));
        this.parent = parent;
        this.confirmed = confirmed;
        this.nameValue = PublisherNamePolicy.valid(suggestedName) ? suggestedName : "";
    }

    static void open(Screen parent, String suggestedName, Consumer<String> confirmed) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new PublisherNameScreen(parent, suggestedName, confirmed));
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, Math.max(220, width - GAP * 2));
        panelH = Math.min(PANEL_H, Math.max(140, height - GAP * 2));
        panelX = Math.max(0, (width - panelW) / 2);
        panelY = Math.max(0, (height - panelH) / 2);
        int contentX = panelX + PAD;
        int contentW = Math.max(1, panelW - PAD * 2);
        int footerY = panelY + panelH - PAD - BTN_H;

        if (stage == Stage.ENTRY) {
            nameBox = new EditBox(font, contentX, panelY + 72, contentW, BTN_H,
                    Component.translatable("waypointer.screen.publisher_name.field"));
            nameBox.setMaxLength(16);
            nameBox.setValue(nameValue);
            nameBox.setResponder(value -> {
                nameValue = value == null ? "" : value;
                if (primaryButton != null) {
                    primaryButton.active = PublisherNamePolicy.valid(nameValue);
                }
            });
            nameBox.setTooltip(Tooltip.create(Component.translatable(
                    "waypointer.screen.publisher_name.field.tooltip")));
            addRenderableWidget(nameBox);
            setInitialFocus(nameBox);
        }

        addRenderableWidget(styledButton(contentX, footerY, ACTION_W, BTN_H,
                Component.translatable(stage == Stage.ENTRY ? "gui.cancel" : "gui.back"),
                button -> back(), null));
        int primaryW = Math.max(ACTION_W, font.width(Component.translatable(
                stage == Stage.ENTRY
                        ? "waypointer.screen.publisher_name.action.continue"
                        : "waypointer.screen.publisher_name.action.confirm")) + 16);
        primaryW = Math.min(primaryW, contentW - ACTION_W - GAP_SECTION);
        primaryButton = styledButton(contentX + contentW - primaryW, footerY,
                primaryW, BTN_H,
                Component.translatable(stage == Stage.ENTRY
                        ? "waypointer.screen.publisher_name.action.continue"
                        : "waypointer.screen.publisher_name.action.confirm"),
                button -> advance(), null);
        primaryButton.active = PublisherNamePolicy.valid(nameValue);
        addRenderableWidget(primaryButton);
    }

    private void advance() {
        if (!PublisherNamePolicy.valid(nameValue)) return;
        if (stage == Stage.ENTRY) {
            stage = Stage.CONFIRM;
            rebuildWidgets();
            return;
        }
        Minecraft client = minecraft == null ? Minecraft.getInstance() : minecraft;
        String chosenName = nameValue;
        MinecraftCompat.setScreen(client, parent);
        client.execute(() -> confirmed.accept(chosenName));
    }

    private void back() {
        if (stage == Stage.CONFIRM) {
            stage = Stage.ENTRY;
            rebuildWidgets();
        } else {
            onClose();
        }
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x80000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);
        int contentX = panelX + PAD;
        int contentW = Math.max(1, panelW - PAD * 2);
        graphics.text(font, font.plainSubstrByWidth(getTitle().getString(), contentW),
                contentX, panelY + PAD, TEXT, false);

        if (stage == Stage.ENTRY) {
            graphics.text(font, font.plainSubstrByWidth(Component.translatable(
                            "waypointer.screen.publisher_name.explanation").getString(), contentW),
                    contentX, panelY + 34, TEXT_DIM, false);
            graphics.text(font, Component.translatable(
                            "waypointer.screen.publisher_name.rules").getString(),
                    contentX, panelY + 52, TEXT_DIM, false);
            graphics.text(font, Component.translatable(
                            "waypointer.screen.publisher_name.field").getString(),
                    contentX, panelY + 63, TEXT_DIM, false);
        } else {
            graphics.text(font, Component.translatable(
                            "waypointer.screen.publisher_name.confirm.question").getString(),
                    contentX, panelY + 38, TEXT_DIM, false);
            graphics.fill(contentX, panelY + 58, contentX + contentW,
                    panelY + 86, SURFACE_SUBTLE);
            graphics.fill(contentX, panelY + 58, contentX + 1,
                    panelY + 86, GuiTokens.ACCENT);
            String clippedName = font.plainSubstrByWidth(nameValue, contentW - GAP * 2);
            graphics.text(font, clippedName,
                    contentX + (contentW - font.width(clippedName)) / 2,
                    panelY + 68, GuiTokens.ACCENT, false);
            var warningLines = font.split(Component.translatable(
                    "waypointer.screen.publisher_name.confirm.warning"), contentW);
            for (int index = 0; index < Math.min(2, warningLines.size()); index++) {
                graphics.text(font, warningLines.get(index), contentX,
                        panelY + 98 + index * 11, TEXT_DIM, false);
            }
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        MinecraftCompat.setScreen(minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
