package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.util.MathUtil;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.babbur.waypointer.screen.GuiTokens.*;
import static org.lwjgl.glfw.GLFW.*;

/** Apply-target chooser and searchable route picker for Waypoint Painter. */
public final class WaypointPaintApplyScreen extends Screen {

    private enum Mode { CHOICE, GROUPS }

    private static final int CHOICE_W = 150;
    private static final int LIST_W_MAX = 460;
    private static final int ROW_PITCH = 28;
    private static final int SEARCH_W = 220;
    private static final int CLEAR_W = 52;

    private final WaypointPainterScreen painter;
    private final ActiveGroupManager manager;
    private Mode mode = Mode.CHOICE;
    private EditBox searchBox;
    private Button searchClearButton;
    private String searchQuery = "";
    private int scrollOffset;
    private int selectedRow;

    WaypointPaintApplyScreen(WaypointPainterScreen painter, ActiveGroupManager manager) {
        super(Component.translatable("waypointer.screen.paint_apply.title"));
        this.painter = painter;
        this.manager = manager;
    }

    @Override
    protected void init() {
        searchBox = null;
        searchClearButton = null;
        if (mode == Mode.CHOICE) {
            buildChoiceButtons();
        } else {
            buildGroupSearch();
        }
        addRenderableWidget(styledButton(PAD_OUTER, height - FOOTER_H, 64, BTN_H,
                Component.translatable("gui.back"), b -> goBack(), null));
    }

    private void buildChoiceButtons() {
        int totalW = CHOICE_W * 2 + GAP_SECTION;
        int x = (width - totalW) / 2;
        int y = Math.max(PAD_OUTER + 48, height / 2 - BTN_H / 2);
        Button all = styledButton(x, y, CHOICE_W, BTN_H,
                Component.translatable("waypointer.screen.paint_apply.all"), b -> applyAll(),
                Tooltip.create(Component.translatable("waypointer.screen.paint_apply.all.tooltip")));
        all.active = !eligibleGroups(manager).isEmpty();
        addRenderableWidget(all);
        Button pick = styledButton(x + CHOICE_W + GAP_SECTION, y, CHOICE_W, BTN_H,
                Component.translatable("waypointer.screen.paint_apply.pick"), b -> showGroups(),
                Tooltip.create(Component.translatable("waypointer.screen.paint_apply.pick.tooltip")));
        pick.active = all.active;
        addRenderableWidget(pick);
    }

    private void buildGroupSearch() {
        int listLeft = listLeft();
        int searchWidth = Math.min(SEARCH_W,
                Math.max(80, listWidth() - CLEAR_W - GAP_TIGHT));
        searchBox = new EditBox(font, listLeft, listTop() - BTN_H - GAP,
                searchWidth, BTN_H, Component.translatable("waypointer.screen.paint_apply.search"));
        searchBox.setMaxLength(80);
        searchBox.setValue(searchQuery);
        searchBox.setHint(Component.translatable("waypointer.screen.paint_apply.search"));
        searchBox.setResponder(value -> {
            searchQuery = value == null ? "" : value;
            scrollOffset = 0;
            selectedRow = 0;
            if (searchClearButton != null) searchClearButton.active = !searchQuery.isEmpty();
        });
        addRenderableWidget(searchBox);
        searchClearButton = styledButton(listLeft + searchWidth + GAP_TIGHT,
                listTop() - BTN_H - GAP, CLEAR_W, BTN_H,
                Component.translatable("waypointer.common.clear"), b -> searchBox.setValue(""),
                Tooltip.create(Component.translatable("waypointer.screen.paint_apply.clear.tooltip")));
        searchClearButton.active = !searchQuery.isEmpty();
        addRenderableWidget(searchClearButton);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, SURFACE);
        String title = getTitle().getString();
        g.text(font, title, (width - font.width(title)) / 2, PAD_OUTER, TEXT, false);

        if (mode == Mode.GROUPS) drawGroupList(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partial);
    }

    private void drawGroupList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int left = listLeft();
        int right = left + listWidth();
        int top = listTop();
        int bottom = listBottom();
        g.fill(left, top, right, bottom, SURFACE_SUBTLE);

        List<WaypointGroup> groups = matchingGroups(manager, searchQuery);
        int maxScroll = maxScroll(groups.size(), bottom - top);
        scrollOffset = MathUtil.clamp(scrollOffset, 0, maxScroll);
        if (groups.isEmpty()) {
            Component empty = Component.translatable(searchQuery.isBlank()
                    ? "waypointer.screen.paint_apply.empty"
                    : "waypointer.screen.paint_apply.empty_search");
            g.text(font, empty, left + GAP, top + GAP, TEXT_DIM, false);
            return;
        }

        selectedRow = MathUtil.clamp(selectedRow, 0, groups.size() - 1);
        g.enableScissor(left, top, right, bottom);
        String activeZone = activeZoneId(manager);
        for (int i = 0; i < groups.size(); i++) {
            int rowY = top + i * ROW_PITCH - scrollOffset;
            if (rowY + ROW_PITCH <= top) continue;
            if (rowY >= bottom) break;
            WaypointGroup group = groups.get(i);
            boolean active = isActive(group, activeZone);
            boolean hovered = mouseX >= left && mouseX < right
                    && mouseY >= rowY && mouseY < rowY + ROW_PITCH
                    && mouseY >= top && mouseY < bottom;
            if (hovered) g.requestCursor(CursorTypes.POINTING_HAND);
            if (i == selectedRow || hovered) {
                g.fill(left, rowY, right, rowY + ROW_PITCH,
                        i == selectedRow ? SELECTED : HOVER);
            }
            if (active) g.fill(left, rowY, left + 2, rowY + ROW_PITCH, ACCENT);

            int textX = left + GAP;
            int rightText = right - GAP;
            Component state = Component.translatable(active
                    ? "waypointer.route_state.active"
                    : group.enabled() ? "waypointer.route_state.shown" : "waypointer.route_state.hidden");
            int stateColor = active ? ACCENT : group.enabled() ? TEXT_DIM : TEXT_MUTED;
            int stateX = rightText - font.width(state);
            g.text(font, state, stateX, rowY + 5, stateColor, false);

            String name = group.name().isBlank()
                    ? Component.translatable("waypointer.group.unnamed").getString()
                    : group.name();
            name = font.plainSubstrByWidth(name, Math.max(20, stateX - GAP - textX));
            g.text(font, name, textX, rowY + 4, TEXT, false);
            String detail = Component.translatable("waypointer.screen.paint_apply.detail",
                    Zone.fromId(group.zoneId()).displayName(),
                    Component.translatable(group.size() == 1
                            ? "waypointer.waypoint_count.one"
                            : "waypointer.waypoint_count.many", group.size())).getString();
            detail = font.plainSubstrByWidth(detail, Math.max(20, rightText - textX));
            g.text(font, detail, textX, rowY + 15, TEXT_MUTED, false);
        }
        g.disableScissor();
        drawScrollbar(g, right - 3, top, bottom, groups.size(), scrollOffset);
    }

    private void drawScrollbar(GuiGraphicsExtractor g, int x, int top, int bottom,
                               int groupCount, int scroll) {
        int viewport = bottom - top;
        int content = groupCount * ROW_PITCH;
        if (content <= viewport) return;
        int thumbH = Math.max(12, viewport * viewport / content);
        int max = Math.max(1, content - viewport);
        int thumbY = top + scroll * (viewport - thumbH) / max;
        g.fill(x, top + 2, x + 2, bottom - 2, BORDER);
        g.fill(x, thumbY, x + 2, thumbY + thumbH, TEXT_MUTED);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (mode != Mode.GROUPS || event.button() != 0) return false;
        if (event.x() < listLeft() || event.x() >= listLeft() + listWidth()
                || event.y() < listTop() || event.y() >= listBottom()) return false;
        List<WaypointGroup> groups = matchingGroups(manager, searchQuery);
        int index = (int) ((event.y() - listTop() + scrollOffset) / ROW_PITCH);
        if (index < 0 || index >= groups.size()) return true;
        selectedRow = index;
        painter.applyToGroup(groups.get(index));
        MinecraftCompat.setScreen(minecraft, painter);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
        if (mode != Mode.GROUPS) return false;
        List<WaypointGroup> groups = matchingGroups(manager, searchQuery);
        scrollOffset = MathUtil.clamp(scrollOffset - (int) (vert * ROW_PITCH),
                0, maxScroll(groups.size(), listBottom() - listTop()));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (mode == Mode.GROUPS) {
            List<WaypointGroup> groups = matchingGroups(manager, searchQuery);
            if (event.key() == GLFW_KEY_DOWN && !groups.isEmpty()) {
                selectedRow = Math.min(groups.size() - 1, selectedRow + 1);
                scrollSelectedIntoView(groups.size());
                return true;
            }
            if (event.key() == GLFW_KEY_UP && !groups.isEmpty()) {
                selectedRow = Math.max(0, selectedRow - 1);
                scrollSelectedIntoView(groups.size());
                return true;
            }
            if (event.key() == GLFW_KEY_ENTER && !groups.isEmpty()) {
                painter.applyToGroup(groups.get(MathUtil.clamp(selectedRow, 0, groups.size() - 1)));
                MinecraftCompat.setScreen(minecraft, painter);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private void scrollSelectedIntoView(int groupCount) {
        int viewport = listBottom() - listTop();
        int rowTop = selectedRow * ROW_PITCH;
        int rowBottom = rowTop + ROW_PITCH;
        if (rowTop < scrollOffset) scrollOffset = rowTop;
        if (rowBottom > scrollOffset + viewport) scrollOffset = rowBottom - viewport;
        scrollOffset = MathUtil.clamp(scrollOffset, 0, maxScroll(groupCount, viewport));
    }

    private void applyAll() {
        painter.applyToAllGroups();
        MinecraftCompat.setScreen(minecraft, painter);
    }

    private void showGroups() {
        mode = Mode.GROUPS;
        scrollOffset = 0;
        selectedRow = 0;
        rebuildWidgets();
    }

    private void goBack() {
        if (mode == Mode.GROUPS) {
            mode = Mode.CHOICE;
            rebuildWidgets();
        } else {
            onClose();
        }
    }

    @Override
    public void onClose() {
        MinecraftCompat.setScreen(minecraft, painter);
    }

    private int listWidth() {
        return Math.min(LIST_W_MAX, Math.max(180, width - PAD_OUTER * 2));
    }

    private int listLeft() {
        return (width - listWidth()) / 2;
    }

    private int listTop() {
        return PAD_OUTER + font.lineHeight + GAP + BTN_H + GAP;
    }

    private int listBottom() {
        return height - FOOTER_H - GAP;
    }

    private static int maxScroll(int groupCount, int viewportHeight) {
        return Math.max(0, groupCount * ROW_PITCH - Math.max(0, viewportHeight));
    }

    static List<WaypointGroup> eligibleGroups(ActiveGroupManager manager) {
        if (manager == null) return List.of();
        List<WaypointGroup> groups = new ArrayList<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (!group.temp() && !group.runtimeOnly()) groups.add(group);
        }
        String activeZone = activeZoneId(manager);
        groups.sort(Comparator.comparingInt(group -> targetRank(group, activeZone)));
        return groups;
    }

    static List<WaypointGroup> matchingGroups(ActiveGroupManager manager, String query) {
        List<WaypointGroup> groups = eligibleGroups(manager);
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return groups;
        return groups.stream().filter(group -> {
            String zoneName = Zone.fromId(group.zoneId()).displayName();
            return group.name().toLowerCase(Locale.ROOT).contains(needle)
                    || group.zoneId().toLowerCase(Locale.ROOT).contains(needle)
                    || zoneName.toLowerCase(Locale.ROOT).contains(needle);
        }).toList();
    }

    private static int targetRank(WaypointGroup group, String activeZone) {
        if (isActive(group, activeZone)) return 0;
        return group.enabled() ? 1 : 2;
    }

    private static boolean isActive(WaypointGroup group, String activeZone) {
        return activeZone != null && group.enabled() && activeZone.equals(group.zoneId());
    }

    private static String activeZoneId(ActiveGroupManager manager) {
        return manager == null || manager.currentZone() == null ? null : manager.currentZone().id();
    }
}
