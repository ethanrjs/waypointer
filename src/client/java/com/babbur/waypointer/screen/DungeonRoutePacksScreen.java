package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.dungeon.DungeonRoutePacks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

import static com.babbur.waypointer.screen.GuiTokens.ACCENT;
import static com.babbur.waypointer.screen.GuiTokens.BORDER;
import static com.babbur.waypointer.screen.GuiTokens.BTN_H;
import static com.babbur.waypointer.screen.GuiTokens.DANGER;
import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.GAP_SECTION;
import static com.babbur.waypointer.screen.GuiTokens.GAP_TIGHT;
import static com.babbur.waypointer.screen.GuiTokens.SUCCESS;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE;
import static com.babbur.waypointer.screen.GuiTokens.SURFACE_SUBTLE;
import static com.babbur.waypointer.screen.GuiTokens.TEXT;
import static com.babbur.waypointer.screen.GuiTokens.TEXT_MUTED;
import static com.babbur.waypointer.screen.GuiTokens.styledButton;

final class DungeonRoutePacksScreen extends Screen {

    private static final int PANEL_MAX_W = 420;
    private static final int PANEL_MARGIN = 8;
    private static final int ROW_PITCH = 28;
    private static final int HEADER_H = 30;
    private static final int ICON_BTN_W = 22;
    private static final long CONFIRM_WINDOW_MS = 2500L;

    private final Screen parent;
    private final ActiveGroupManager manager;
    private final TimedIdConfirmation deleteConfirmation = new TimedIdConfirmation();

    private List<DungeonRoutePacks.Pack> packs = List.of();
    private int scrollRows;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int rowsTop;
    private int visibleRows;
    private int textRight;

    DungeonRoutePacksScreen(Screen parent, ActiveGroupManager manager) {
        super(Component.translatable("waypointer.dungeon.packs.title"));
        this.parent = parent;
        this.manager = manager;
    }

    @Override
    protected void init() {
        packs = DungeonRoutePacks.list(manager.allGroups());
        panelW = Math.min(PANEL_MAX_W, Math.max(260, width - PANEL_MARGIN * 2));
        int footerH = BTN_H + GAP * 2;
        int wantedH = HEADER_H + Math.max(1, packs.size()) * ROW_PITCH + footerH + GAP;
        panelH = Math.min(height - PANEL_MARGIN * 2, wantedH);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        rowsTop = panelY + HEADER_H;
        visibleRows = Math.max(1, (panelY + panelH - footerH - rowsTop) / ROW_PITCH);
        scrollRows = Math.max(0, Math.min(scrollRows, packs.size() - visibleRows));

        int buttonsRight = panelX + panelW - GAP_SECTION;
        Component use = Component.translatable("waypointer.dungeon.packs.use");
        Component hide = Component.translatable("waypointer.dungeon.packs.hide");
        int toggleW = GuiTokens.iconButtonWidth(font, font.width(use) > font.width(hide) ? use : hide,
                Icons.Icon.EYE);
        Component confirm = GuiTokens.colored(Component.translatable("waypointer.common.confirm"), DANGER);
        int confirmW = font.width(confirm) + 16;
        textRight = buttonsRight - Math.max(ICON_BTN_W, confirmW) - GAP_TIGHT - toggleW - GAP;
        for (int row = 0; row < visibleRows && scrollRows + row < packs.size(); row++) {
            DungeonRoutePacks.Pack pack = packs.get(scrollRows + row);
            int y = rowsTop + row * ROW_PITCH + (ROW_PITCH - BTN_H) / 2;

            boolean armed = deleteConfirmation.matches(List.of(pack.key()), System.currentTimeMillis());
            Button delete = styledButton(buttonsRight - ICON_BTN_W, y, ICON_BTN_W, BTN_H,
                    armed ? confirm : Component.empty(),
                    ignored -> onDelete(pack),
                    Tooltip.create(Component.translatable("waypointer.dungeon.packs.delete")),
                    armed ? null : Icons.Icon.TRASH);
            if (armed) {
                delete.setX(buttonsRight - confirmW);
                delete.setWidth(confirmW);
            } else {
                ((GuiTokens.StyledButton) delete).setIconOnly(true);
            }
            addRenderableWidget(delete);

            boolean active = pack.state() == DungeonRoutePacks.State.ACTIVE;
            Button toggle = styledButton(delete.getX() - GAP_TIGHT - toggleW, y, toggleW, BTN_H,
                    active ? hide : use,
                    ignored -> {
                        if (active) DungeonRoutePacks.hide(manager, pack);
                        else DungeonRoutePacks.activate(manager, pack);
                        deleteConfirmation.clear();
                        rebuildWidgets();
                    },
                    null, active ? Icons.Icon.EYE_OFF : Icons.Icon.EYE);
            addRenderableWidget(toggle);
        }

        Component doneLabel = Component.translatable("gui.done");
        int doneW = Math.max(70, font.width(doneLabel) + 18);
        addRenderableWidget(styledButton(panelX + panelW - GAP_SECTION - doneW,
                panelY + panelH - BTN_H - GAP, doneW, BTN_H, doneLabel, ignored -> onClose(), null));
    }

    private void onDelete(DungeonRoutePacks.Pack pack) {
        long now = System.currentTimeMillis();
        List<String> key = List.of(pack.key());
        if (!deleteConfirmation.matches(key, now)) {
            deleteConfirmation.arm(key, now, CONFIRM_WINDOW_MS);
        } else {
            deleteConfirmation.clear();
            DungeonRoutePacks.delete(manager, pack);
        }
        rebuildWidgets();
    }

    @Override
    public void tick() {
        if (deleteConfirmation.expire(System.currentTimeMillis())) rebuildWidgets();
    }

    static Component packName(DungeonRoutePacks.Pack pack) {
        if (pack.unlabeled()) return Component.translatable("waypointer.dungeon.packs.unlabeled");
        if (pack.name().isBlank()) return Component.translatable("waypointer.dungeon.packs.unnamed");
        return Component.literal(pack.name());
    }

    static Component packDetail(DungeonRoutePacks.Pack pack) {
        Component rooms = Component.translatable("waypointer.dungeon.packs.rooms", pack.roomCount());
        Component state = switch (pack.state()) {
            case ACTIVE -> Component.translatable("waypointer.dungeon.packs.state.active");
            case HIDDEN -> Component.translatable("waypointer.dungeon.packs.state.hidden");
            case PARTLY_ACTIVE -> Component.translatable(
                    "waypointer.dungeon.packs.state.partly", pack.activeRoomCount());
        };
        return Component.empty().append(rooms).append(" · ").append(state);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0x80000000);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, SURFACE);
        g.fill(panelX, panelY, panelX + panelW, panelY + 1, BORDER);
        g.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, BORDER);

        int textX = panelX + GAP_SECTION;
        int titleY = panelY + GAP;
        g.text(font, getTitle(), textX, titleY, TEXT, false);
        g.fill(textX, titleY + font.lineHeight + GAP_TIGHT,
                panelX + panelW - GAP_SECTION, titleY + font.lineHeight + GAP_TIGHT + 1, ACCENT);

        if (packs.isEmpty()) {
            g.text(font, Component.translatable("waypointer.dungeon.packs.empty"),
                    textX, rowsTop + (ROW_PITCH - font.lineHeight) / 2, TEXT_MUTED, false);
        }
        for (int row = 0; row < visibleRows && scrollRows + row < packs.size(); row++) {
            DungeonRoutePacks.Pack pack = packs.get(scrollRows + row);
            int y = rowsTop + row * ROW_PITCH;
            if (((scrollRows + row) & 1) == 1) {
                g.fill(panelX + GAP, y, panelX + panelW - GAP, y + ROW_PITCH, SURFACE_SUBTLE);
            }
            boolean hidden = pack.state() == DungeonRoutePacks.State.HIDDEN;
            int nameWidth = Math.max(20, textRight - textX);
            String name = font.plainSubstrByWidth(packName(pack).getString(), nameWidth);
            g.text(font, name, textX, y + 4, hidden ? TEXT_MUTED : TEXT, false);
            String detail = font.plainSubstrByWidth(packDetail(pack).getString(), nameWidth);
            int detailColor = pack.state() == DungeonRoutePacks.State.ACTIVE ? SUCCESS : TEXT_MUTED;
            g.text(font, detail, textX, y + 15, detailColor, false);
        }
        if (packs.size() > visibleRows) {
            int trackTop = rowsTop;
            int trackH = visibleRows * ROW_PITCH;
            int thumbH = Math.max(8, trackH * visibleRows / packs.size());
            int thumbY = trackTop + (trackH - thumbH) * scrollRows / Math.max(1, packs.size() - visibleRows);
            int x = panelX + panelW - GAP_TIGHT - 2;
            g.fill(x, thumbY, x + 2, thumbY + thumbH, BORDER);
        }
        super.extractRenderState(g, mouseX, mouseY, partial);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int max = Math.max(0, packs.size() - visibleRows);
        if (max > 0 && vertical != 0) {
            int next = Math.max(0, Math.min(max, scrollRows - (int) Math.signum(vertical)));
            if (next != scrollRows) {
                scrollRows = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
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
