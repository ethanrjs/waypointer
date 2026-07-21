package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.debug.DebugReportExport;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static com.babbur.waypointer.screen.GuiTokens.ACCENT;
import static com.babbur.waypointer.screen.GuiTokens.BORDER;
import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_SECTION;
import static com.babbur.waypointer.screen.GuiTokens.GAP_TIGHT;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE_SUBTLE;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_DIM;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;
import static com.babbur.waypointer.screen.GuiTokens.styledButton;
import static com.babbur.waypointer.screen.GuiTokens.styledCheckbox;

/**
 * Disclosure confirmation shown before a troubleshooting report reaches the
 * clipboard. Every category starts enabled, and cancelling never copies data.
 */
public final class DebugReportConsentScreen extends net.minecraft.client.gui.screens.Screen {

    private static final int PANEL_MAX_W = 460;
    private static final int PANEL_MARGIN = 8;
    private static final int CHECKBOX_SIZE = 16;
    private static final int ENTRY_MIN_H = 19;
    private static final int ENTRY_MAX_H = 31;

    private final net.minecraft.client.gui.screens.Screen parent;
    private final Consumer<DebugReportExport.Options> onConfirm;
    private final List<DebugReportExport.Category> categories =
            DebugReportExport.Category.userControlledValues();
    private final Map<DebugReportExport.Category, Boolean> enabled =
            new EnumMap<>(DebugReportExport.Category.class);
    private final List<GuiTokens.StyledCheckbox> checkboxes = new ArrayList<>();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int entriesTop;
    private int entryH;

    public DebugReportConsentScreen(net.minecraft.client.gui.screens.Screen parent,
                                    Consumer<DebugReportExport.Options> onConfirm) {
        super(Component.literal("Review Troubleshooting Report"));
        this.parent = parent;
        this.onConfirm = onConfirm;
        for (DebugReportExport.Category category : categories) enabled.put(category, true);
    }

    @Override
    protected void init() {
        checkboxes.clear();
        panelW = Math.min(PANEL_MAX_W, Math.max(260, width - PANEL_MARGIN * 2));
        panelH = Math.min(height - PANEL_MARGIN * 2,
                54 + categories.size() * ENTRY_MAX_H + BTN_H + GAP_SECTION);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        entriesTop = panelY + 43;
        int footerSpace = BTN_H + GAP + GAP_TIGHT;
        entryH = Math.max(ENTRY_MIN_H, Math.min(ENTRY_MAX_H,
                (panelH - (entriesTop - panelY) - footerSpace) / Math.max(1, categories.size())));

        for (int i = 0; i < categories.size(); i++) {
            DebugReportExport.Category category = categories.get(i);
            int checkboxY = entriesTop + i * entryH + Math.max(0, (entryH - CHECKBOX_SIZE) / 2);
            GuiTokens.StyledCheckbox checkbox = styledCheckbox(
                    panelX + GAP_SECTION,
                    checkboxY,
                    CHECKBOX_SIZE,
                    Component.literal(category.label()),
                    Boolean.TRUE.equals(enabled.get(category)),
                    selected -> enabled.put(category, selected),
                    Tooltip.create(Component.literal(category.description())));
            checkboxes.add(checkbox);
            addRenderableWidget(checkbox);
        }

        int buttonY = panelY + panelH - BTN_H - GAP;
        int confirmW = Math.max(112, font.width("Confirm & Copy") + 18);
        int cancelW = Math.max(70, font.width("Cancel") + 18);
        int buttonsW = confirmW + GAP + cancelW;
        int buttonX = panelX + panelW - GAP_SECTION - buttonsW;
        Button confirm = styledButton(buttonX, buttonY, confirmW, BTN_H,
                Component.literal("Confirm & Copy"), ignored -> confirmAndCopy(), null);
        Button cancel = styledButton(buttonX + confirmW + GAP, buttonY, cancelW, BTN_H,
                Component.literal("Cancel"), ignored -> onClose(), null);
        addRenderableWidget(confirm);
        addRenderableWidget(cancel);
    }

    private void confirmAndCopy() {
        Set<DebugReportExport.Category> included = EnumSet.of(DebugReportExport.Category.CORE);
        for (DebugReportExport.Category category : categories) {
            if (Boolean.TRUE.equals(enabled.get(category))) included.add(category);
        }
        if (onConfirm != null) onConfirm.accept(new DebugReportExport.Options(included));
        MinecraftCompat.setScreen(minecraft, parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x80000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);
        g.fill(panelX, panelY, panelX + panelW, panelY + 1, BORDER);
        g.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, BORDER);

        int titleX = panelX + GAP_SECTION;
        int titleY = panelY + GAP;
        g.text(font, getTitle(), titleX, titleY, TEXT, false);
        g.fill(titleX, titleY + font.lineHeight + GAP_TIGHT,
                panelX + panelW - GAP_SECTION, titleY + font.lineHeight + GAP_TIGHT + 1, ACCENT);
        String intro = font.plainSubstrByWidth(
                "Choose what leaves your PC. Everything is included by default.",
                panelW - GAP_SECTION * 2);
        g.text(font, intro, titleX, titleY + font.lineHeight + GAP, TEXT_DIM, false);

        for (int i = 0; i < categories.size(); i++) {
            DebugReportExport.Category category = categories.get(i);
            int rowY = entriesTop + i * entryH;
            if ((i & 1) == 1) {
                g.fill(panelX + GAP, rowY, panelX + panelW - GAP, rowY + entryH, SURFACE_SUBTLE);
            }
            int textX = panelX + GAP_SECTION + CHECKBOX_SIZE + GAP;
            g.text(font, "• " + category.label(), textX, rowY + 2, TEXT, false);
            if (entryH >= 23) {
                String description = font.plainSubstrByWidth(category.description(),
                        panelX + panelW - GAP_SECTION - textX);
                g.text(font, description, textX, rowY + 13, TEXT_MUTED, false);
            }
        }
        super.extractRenderState(g, mouseX, mouseY, partial);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0
                || event.x() < panelX + GAP
                || event.x() > panelX + panelW - GAP
                || event.y() < entriesTop) {
            return false;
        }
        int index = (int) ((event.y() - entriesTop) / entryH);
        if (index < 0 || index >= checkboxes.size()
                || event.y() >= entriesTop + categories.size() * entryH) {
            return false;
        }
        checkboxes.get(index).onPress(event);
        return true;
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
