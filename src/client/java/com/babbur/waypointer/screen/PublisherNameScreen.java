package com.babbur.waypointer.screen;

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
import static com.babbur.waypointer.screen.GuiTokens.SURFACE;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE_SUBTLE;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.styledButton;

final class PublisherNameScreen extends Screen {
    private final Screen parent;
    private final Consumer<String> confirmed;
    private final PublisherNameModel model;
    private EditBox nameBox;
    private Button primaryButton;

    private PublisherNameLayout.Layout layout;

    PublisherNameScreen(
            Screen parent, String suggestedName, Consumer<String> confirmed) {
        super(Component.translatable("waypointer.screen.publisher_name.title"));
        this.parent = parent;
        this.confirmed = confirmed;
        this.model = new PublisherNameModel(suggestedName);
    }

    static void open(Screen parent, String suggestedName, Consumer<String> confirmed) {
        MinecraftCompat.setScreen(Minecraft.getInstance(),
                new PublisherNameScreen(parent, suggestedName, confirmed));
    }

    @Override
    protected void init() {
        int preferredPrimaryWidth = Math.max(104, font.width(Component.translatable(
                model.stage() == PublisherNameModel.Stage.ENTRY
                        ? "waypointer.screen.publisher_name.action.continue"
                        : "waypointer.screen.publisher_name.action.confirm")) + 16);
        layout = PublisherNameLayout.calculate(width, height, preferredPrimaryWidth);

        if (model.stage() == PublisherNameModel.Stage.ENTRY) {
            nameBox = new EditBox(font, layout.contentX(), layout.fieldY(),
                    layout.contentWidth(), BTN_H,
                    Component.translatable("waypointer.screen.publisher_name.field"));
            nameBox.setMaxLength(16);
            nameBox.setValue(model.name());
            nameBox.setResponder(value -> {
                model.edit(value);
                if (primaryButton != null) {
                    primaryButton.active = model.valid();
                }
            });
            nameBox.setTooltip(Tooltip.create(Component.translatable(
                    "waypointer.screen.publisher_name.field.tooltip")));
            addRenderableWidget(nameBox);
            setInitialFocus(nameBox);
        }

        addRenderableWidget(styledButton(
                layout.secondaryX(), layout.footerY(), layout.secondaryWidth(), BTN_H,
                Component.translatable(model.stage() == PublisherNameModel.Stage.ENTRY
                        ? "gui.cancel" : "gui.back"),
                button -> back(), null));
        primaryButton = styledButton(
                layout.primaryX(), layout.footerY(), layout.primaryWidth(), BTN_H,
                Component.translatable(model.stage() == PublisherNameModel.Stage.ENTRY
                        ? "waypointer.screen.publisher_name.action.continue"
                        : "waypointer.screen.publisher_name.action.confirm"),
                button -> advance(), null);
        primaryButton.active = model.valid();
        addRenderableWidget(primaryButton);
    }

    private void advance() {
        PublisherNameModel.AdvanceResult result = model.advance();
        if (result == PublisherNameModel.AdvanceResult.REJECTED) return;
        if (result == PublisherNameModel.AdvanceResult.SHOW_CONFIRMATION) {
            rebuildWidgets();
        } else {
            Minecraft client = minecraft == null ? Minecraft.getInstance() : minecraft;
            String chosenName = model.confirmedName();
            MinecraftCompat.setScreen(client, parent);
            client.execute(() -> confirmed.accept(chosenName));
        }
    }

    private void back() {
        if (!model.back()) {
            rebuildWidgets();
        } else {
            onClose();
        }
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0x80000000);
        graphics.fill(layout.panelX(), layout.panelY(),
                layout.panelX() + layout.panelWidth(), layout.panelBottom(), SURFACE);
        graphics.text(font, font.plainSubstrByWidth(
                        getTitle().getString(), layout.contentWidth()),
                layout.contentX(), layout.titleY(), TEXT, false);

        if (model.stage() == PublisherNameModel.Stage.ENTRY) {
            if (layout.entryDetailsVisible()) {
                graphics.text(font, font.plainSubstrByWidth(Component.translatable(
                                "waypointer.screen.publisher_name.explanation").getString(),
                                layout.contentWidth()),
                        layout.contentX(), layout.panelY() + 34, TEXT_DIM, false);
                graphics.text(font, font.plainSubstrByWidth(Component.translatable(
                                "waypointer.screen.publisher_name.rules").getString(),
                                layout.contentWidth()),
                        layout.contentX(), layout.panelY() + 52, TEXT_DIM, false);
            }
            graphics.text(font, Component.translatable(
                            "waypointer.screen.publisher_name.field").getString(),
                    layout.contentX(), layout.fieldLabelY(), TEXT_DIM, false);
        } else {
            if (layout.questionVisible()) {
                graphics.text(font, font.plainSubstrByWidth(Component.translatable(
                                "waypointer.screen.publisher_name.confirm.question").getString(),
                                layout.contentWidth()),
                        layout.contentX(), layout.questionY(), TEXT_DIM, false);
            }
            graphics.fill(layout.contentX(), layout.cardY(), layout.contentRight(),
                    layout.cardY() + 28, SURFACE_SUBTLE);
            graphics.fill(layout.contentX(), layout.cardY(), layout.contentX() + 1,
                    layout.cardY() + 28, GuiTokens.ACCENT);
            String clippedName = font.plainSubstrByWidth(
                    model.name(), Math.max(1, layout.contentWidth() - GAP * 2));
            graphics.text(font, clippedName,
                    layout.contentX() + (layout.contentWidth() - font.width(clippedName)) / 2,
                    layout.cardY() + 10, GuiTokens.ACCENT, false);
            if (layout.warningVisible()) {
                var warningLines = font.split(Component.translatable(
                        "waypointer.screen.publisher_name.confirm.warning"),
                        layout.contentWidth());
                for (int index = 0; index < Math.min(2, warningLines.size()); index++) {
                    graphics.text(font, warningLines.get(index), layout.contentX(),
                            layout.warningY() + index * 11, TEXT_DIM, false);
                }
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
