package com.babbur.waypointer.screen;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.core.RouteProgress;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.debug.DebugEventLog;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;
import com.babbur.waypointer.dungeon.DungeonRoomRouteProjection;
import com.babbur.waypointer.util.MathUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.babbur.waypointer.screen.GuiTokens.*;
import static com.babbur.waypointer.screen.WaypointerZoneCatalog.*;

final class WaypointerRouteList {

    static final int ROW_PITCH = ROW_H + 4;

    private static final int ROUTE_TOGGLE_CHIP_W = 54;
    private static final int ROUTE_TOGGLE_CHIP_H = 14;
    private static final int MOUSE_BUTTON_LEFT = 0;
    private static final int MOUSE_BUTTON_RIGHT = 1;
    private static final double DRAG_THRESHOLD_SQUARED = 16.0;
    static final int INFO_BUTTON_SIZE = 12;
    private static final String INFO_TITLE = "Route list controls";
    private static final String[] INFO_LABELS = {
            "Double-click", "Shown/Hidden chip", "Shift-right-click",
            "Ctrl / Shift-click", "Delete key",
    };
    private static final String[] INFO_DESCRIPTIONS = {
            "open a route in the editor", "toggle a route in the world",
            "move a world route to another zone", "select multiple routes",
            "delete the selection (confirm)",
    };

    private final WaypointerScreen screen;
    private ListNavigationWidget navigation;
    private String dragGroupId;
    private double dragStartX;
    private double dragStartY;
    private boolean dragging;
    private DropTarget dropTarget;

    WaypointerRouteList(WaypointerScreen screen) {
        this.screen = screen;
    }

    void resetNavigation() {
        navigation = null;
        clearDrag();
    }

    void buildNavigation(int left, int panelTop, int right, int bottom) {
        int rowsTop = rowsTop(panelTop);
        int listHeight = bottom - rowsTop;
        if (listHeight <= 0 || rows().isEmpty()) return;
        navigation = new ListNavigationWidget(
                left, rowsTop, contentRight(left, right) - left,
                listHeight, 0, ROW_PITCH, ROW_H + 2,
                () -> rows().size(), this::initialNavigationIndex,
                this::narration, this::activateRow,
                () -> screen.scrollOffset, this::scrollRowIndexIntoView);
        screen.registerRouteListNavigation(navigation);
    }

    void renderNavigationOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                 float partialTick) {
        if (navigation != null) {
            navigation.extractOverlay(graphics, mouseX, mouseY, partialTick);
        }
    }

    void renderInfoButton(GuiGraphicsExtractor graphics, int x, int y, boolean hovered) {
        int border = hovered ? 0xFFFFFFFF : BORDER;
        int fill = hovered ? 0xFF26343A : 0xFF1A1F24;
        graphics.fill(x, y, x + INFO_BUTTON_SIZE, y + INFO_BUTTON_SIZE, border);
        graphics.fill(x + 1, y + 1, x + INFO_BUTTON_SIZE - 1, y + INFO_BUTTON_SIZE - 1, fill);
        int glyphX = x + (INFO_BUTTON_SIZE - font().width("i")) / 2;
        graphics.text(font(), "i", glyphX, y + 2, hovered ? ACCENT : TEXT_DIM, false);
    }

    void renderInfoTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                           int screenWidth, int contentBottom) {
        int lineCount = Math.min(INFO_LABELS.length, INFO_DESCRIPTIONS.length);
        int padding = 7;
        int lineGap = 3;
        int maxLabelWidth = 0;
        int maxLineWidth = font().width(INFO_TITLE);
        for (String label : INFO_LABELS) {
            maxLabelWidth = Math.max(maxLabelWidth, font().width(label));
        }
        for (int i = 0; i < lineCount; i++) {
            maxLineWidth = Math.max(maxLineWidth,
                    maxLabelWidth + GAP + font().width(INFO_DESCRIPTIONS[i]));
        }

        int width = maxLineWidth + padding * 2;
        int height = padding * 2 + font().lineHeight + 5
                + lineCount * font().lineHeight + Math.max(0, lineCount - 1) * lineGap;
        int x = Math.max(PAD_OUTER,
                Math.min(mouseX + 12, Math.max(PAD_OUTER, screenWidth - PAD_OUTER - width)));
        int y = Math.max(PAD_OUTER,
                Math.min(mouseY + 12, Math.max(PAD_OUTER, contentBottom - height)));
        screen.fillOutlinedOverlay(graphics, x, y, x + width, y + height);

        int textX = x + padding;
        int textY = y + padding;
        graphics.text(font(), INFO_TITLE, textX, textY, ACCENT, false);
        int separatorY = textY + font().lineHeight + 2;
        graphics.fill(textX, separatorY, x + width - padding, separatorY + 1, 0x55FFFFFF);
        int rowY = separatorY + 4;
        for (int i = 0; i < lineCount; i++) {
            graphics.text(font(), INFO_LABELS[i], textX, rowY, TEXT, false);
            graphics.text(font(), INFO_DESCRIPTIONS[i], textX + maxLabelWidth + GAP,
                    rowY, TEXT_DIM, false);
            rowY += font().lineHeight + lineGap;
        }
    }

    void selectGroupById(String id) {
        if (id == null) return;
        WaypointGroup storedGroup = screen.manager.get(id);
        if (storedGroup != null && !storedGroup.temp()
                && storedGroup.routeKind() == WaypointGroup.RouteKind.DUNGEON) {
            screen.selectedZoneId = DUNGEON_ROOMS_ZONE_ID;
            screen.selectedDungeonRoomZoneId = storedGroup.zoneId();
            screen.expandDungeonRoom(storedGroup.zoneId());
        }
        RouteFolder folder = screen.manager.folderForGroup(id);
        if (folder != null && folder.collapsed()) {
            screen.manager.setFolderCollapsed(folder.id(), false);
        }
        List<Row> rows = rows();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (!row.roomHeader && row.group != null && row.group.id().equals(id)) {
                screen.selectOnlyGroupId(id);
                scrollRowIndexIntoView(i);
                return;
            }
        }
    }

    void focusRoomByZoneId(String roomZoneId) {
        if (roomZoneId == null || roomZoneId.isBlank()) return;
        screen.selectedZoneId = DUNGEON_ROOMS_ZONE_ID;
        screen.selectedDungeonRoomZoneId = roomZoneId;
        screen.expandDungeonRoom(roomZoneId);
        screen.clearRouteSelection();
        List<Row> rows = rows();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.roomHeader && roomZoneId.equals(row.roomZoneId)) {
                scrollRowIndexIntoView(i);
                return;
            }
        }
    }

    void scrollRowIndexIntoView(int rowIndex) {
        if (rowIndex < 0) return;
        WaypointerScreen.Layout layout = screen.layoutForRouteList();
        int listHeight = Math.max(0, layout.bottom() - rowsTop(layout.top()));
        screen.scrollOffset = scrollOffsetToRevealRow(
                screen.scrollOffset, rowIndex, rows().size(), listHeight);
    }

    static int scrollOffsetToRevealRow(int currentOffset, int rowIndex,
                                       int rowCount, int listHeight) {
        if (rowIndex < 0 || rowIndex >= rowCount) return currentOffset;
        int rowTop = rowIndex * ROW_PITCH;
        int rowBottom = rowTop + ROW_H + 2;
        int next = currentOffset;
        if (rowTop < next) {
            next = rowTop;
        } else if (rowBottom > next + listHeight) {
            next = rowBottom - listHeight + GAP;
        }
        return MathUtil.clamp(next, 0, maxScroll(rowCount, listHeight));
    }

    List<WaypointGroup> visibleGroups() {
        List<WaypointGroup> out = new ArrayList<>();
        if (isTemporaryZone(screen.selectedZoneId)) {
            out.addAll(temporaryGroups());
        } else if (isDungeonRoomsZone(screen.selectedZoneId)) {
            out.addAll(dungeonRoomGroups());
        } else {
            for (WaypointGroup group : screen.manager.groupsForZone(screen.selectedZoneId)) {
                if (!group.temp()) out.add(group);
            }
        }

        String query = normalizedSearchQuery();
        if (query.isEmpty()) return out;

        List<WaypointGroup> filtered = new ArrayList<>();
        for (WaypointGroup group : out) {
            if (RouteListPresentation.groupMatchesSearch(
                    group, query, displayZoneLabel(group.zoneId()))) {
                filtered.add(group);
            }
        }
        return filtered;
    }

    List<WaypointGroup> selectedVisibleGroups() {
        List<WaypointGroup> selectedGroups = new ArrayList<>();
        if (screen.selectedGroupIds.isEmpty()) return selectedGroups;
        for (WaypointGroup group : visibleGroups()) {
            if (screen.selectedGroupIds.contains(group.id())) selectedGroups.add(group);
        }
        return selectedGroups;
    }

    List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (!isDungeonRoomsZone(screen.selectedZoneId)) {
            if (isTemporaryZone(screen.selectedZoneId)) {
                for (WaypointGroup group : visibleGroups()) {
                    rows.add(Row.group(null, group, false, false));
                }
                return rows;
            }
            RouteFolderListModel.Snapshot snapshot = RouteFolderListModel.build(
                    screen.manager, screen.selectedZoneId, normalizedSearchQuery());
            for (RouteFolderListModel.FolderSection section : snapshot.folders()) {
                rows.add(Row.folder(section.folder(), section.groups().size(),
                        section.searchReveal()));
                if (!section.folder().collapsed() || section.searchReveal()) {
                    for (WaypointGroup group : section.groups()) {
                        rows.add(Row.group(null, group, true, true));
                    }
                }
            }
            for (WaypointGroup group : snapshot.unfiled()) {
                rows.add(Row.group(null, group, false, false));
            }
            return rows;
        }

        String query = normalizedSearchQuery();
        boolean searching = !query.isEmpty();
        String currentRoomZoneId = currentDungeonRoomZoneId(screen.manager);
        for (String roomZoneId : dungeonRoomZoneIds(currentRoomZoneId)) {
            List<WaypointGroup> roomGroups = dungeonRoomGroupsForZone(roomZoneId);
            boolean roomMatches = searching && containsSearch(displayZoneLabel(roomZoneId), query);
            List<WaypointGroup> displayGroups = new ArrayList<>();
            for (WaypointGroup group : roomGroups) {
                if (!searching || roomMatches || RouteListPresentation.groupMatchesSearch(
                        group, query, displayZoneLabel(group.zoneId()))) {
                    displayGroups.add(group);
                }
            }
            if (searching && !roomMatches && displayGroups.isEmpty()) continue;

            boolean currentRoom = roomZoneId.equals(currentRoomZoneId);
            int secretCount = RouteListPresentation.displayedInstalledSecretCount(
                    installedSecretCountForRoom(roomZoneId), roomGroups);
            rows.add(Row.room(roomZoneId, roomGroups.size(), secretCount,
                    true, currentRoom, searching));
            for (WaypointGroup group : displayGroups) {
                rows.add(Row.group(roomZoneId, group, true, false));
            }
        }
        return rows;
    }

    void render(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2,
                int mouseX, int mouseY) {
        List<Row> rows = rows();
        graphics.fill(x1, y1, x2, y2, SURFACE_SUBTLE);
        int rowsTop = rowsTop(y1);
        screen.renderMainNotice(graphics, x1, y1, x2);
        if (rows.isEmpty()) {
            renderEmptyState(graphics, x1, rowsTop);
            return;
        }

        graphics.enableScissor(x1, rowsTop, x2, y2);
        int rowRight = contentRight(x1, x2);
        int y = rowsTop - screen.scrollOffset;
        Map<String, Integer> routeIndices = RouteListPresentation.routeCommandIndices(
                screen.config.showRouteIndicesInGui()
                        ? screen.manager.allGroupsList() : List.of());
        for (int i = 0; i < rows.size(); i++, y += ROW_PITCH) {
            int rowTop = y;
            int rowBottom = y + ROW_H + 2;
            if (rowBottom < rowsTop || rowTop > y2) continue;

            boolean hovered = mouseX >= x1 + 2 && mouseX <= rowRight - 2
                    && mouseY >= rowTop && mouseY <= rowBottom;
            Row row = rows.get(i);
            if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
            if (row.roomHeader) {
                renderRoomHeader(graphics, row, x1 + 2, rowTop, rowRight - 2, hovered);
            } else if (row.folder != null) {
                renderFolderHeader(graphics, row, x1 + 2, rowTop, rowRight - 2,
                        mouseX, hovered);
            } else if (row.group != null) {
                boolean selected = screen.selectedGroupIds.contains(row.group.id());
                int routeIndex = routeIndices.getOrDefault(row.group.id(), -1);
                renderGroupRow(graphics, row.group, routeIndex,
                        x1 + 2, rowTop, rowRight - 2,
                        hovered, selected, row.indented, row.folderChild);
            }
        }
        renderDropTarget(graphics, x1 + 2, rowRight - 2);
        graphics.disableScissor();
        renderScrollbar(graphics, rowRight - 4, rowsTop, y2, rows.size(), screen.scrollOffset);
    }

    boolean mouseClicked(MouseButtonEvent event, boolean doubleClick,
                         int left, int top, int right, int bottom) {
        boolean leftClick = event.button() == MOUSE_BUTTON_LEFT;
        boolean shiftRightClick = event.button() == MOUSE_BUTTON_RIGHT
                && WaypointerScreen.routeSelectionShiftDown();
        if (!leftClick && !shiftRightClick) return false;
        if (leftClick) clearDrag();

        double mouseX = event.x();
        double mouseY = event.y();
        if (mouseX < left || mouseX > right || mouseY < top || mouseY > bottom) return false;

        List<Row> rows = rows();
        if (rows.isEmpty()) return false;
        int rowsTop = rowsTop(top);
        if (mouseY < rowsTop) return false;

        int index = (int) ((mouseY - rowsTop + screen.scrollOffset) / ROW_PITCH);
        if (index < 0 || index >= rows.size()) return false;
        int rowTop = rowsTop - screen.scrollOffset + index * ROW_PITCH;
        if (mouseY > rowTop + ROW_H + 2) return false;

        Row row = rows.get(index);
        if (navigation != null) navigation.setCursor(index);
        int rowRight = contentRight(left, right) - 2;
        if (row.roomHeader) {
            String selectedBefore = screen.selectedGroupId == null
                    ? "(none)" : screen.selectedGroupId;
            screen.selectedDungeonRoomZoneId = row.roomZoneId;
            screen.clearRouteSelection();
            screen.scrollOffset = MathUtil.clamp(screen.scrollOffset, 0,
                    maxScroll(rows().size(), bottom - rowsTop));
            screen.refreshActionButtons();
            DebugEventLog.record("WaypointerScreen", "room-header", row.roomZoneId,
                    index, selectedBefore, "(none)", doubleClick,
                    WaypointerScreen.routeSelectionShiftDown(),
                    WaypointerScreen.routeSelectionControlDown(),
                    "room-header", "select-room");
            return true;
        }

        if (row.folder != null) {
            switch (RouteListPresentation.folderHeaderAction(
                    mouseX, rowRight, doubleClick)) {
                case SELECT -> screen.selectFolderRoutes(row.folder);
                case EDIT -> screen.openFolderEditor(row.folder, List.of());
                case TOGGLE -> {
                    screen.manager.toggleFolderCollapsed(row.folder.id());
                    screen.scrollOffset = MathUtil.clamp(screen.scrollOffset, 0,
                            maxScroll(rows().size(), bottom - rowsTop));
                }
                case NONE -> {
                }
            }
            return true;
        }

        WaypointGroup group = row.group;
        if (group == null) return false;
        if (shiftRightClick) {
            screen.startZoneMove(group);
            return true;
        }
        if (row.roomZoneId != null) screen.selectedDungeonRoomZoneId = row.roomZoneId;
        String selectedBefore = screen.selectedGroupId == null ? "(none)" : screen.selectedGroupId;
        boolean wasAlreadyPrimarySelected = group.id().equals(screen.selectedGroupId)
                && screen.selectedGroupIds.contains(group.id());
        boolean shiftDown = WaypointerScreen.routeSelectionShiftDown();
        boolean controlDown = WaypointerScreen.routeSelectionControlDown();
        screen.applyRouteRowSelection(group, visibleGroupIds(rows));
        screen.refreshActionButtons();
        String selectedAfter = screen.selectedGroupId == null ? "(none)" : screen.selectedGroupId;

        if (mouseX >= RouteListPresentation.routeToggleHitLeft(rowRight) && mouseX <= rowRight) {
            DungeonRoomRouteLibrary.setRouteEnabled(
                    screen.manager, WaypointerClient.dungeonConfig(), group, !group.enabled());
            screen.manager.fireDataChanged();
            DebugEventLog.record("WaypointerScreen", "route", group.id(), index,
                    selectedBefore, selectedAfter, doubleClick, shiftDown, controlDown,
                    "toggle-chip", group.enabled() ? "show-route" : "hide-route");
            return true;
        }

        boolean openEditor = RouteListPresentation.shouldOpenEditor(
                doubleClick, wasAlreadyPrimarySelected, shiftDown, controlDown);
        if (openEditor) {
            DebugEventLog.record("WaypointerScreen", "route", group.id(), index,
                    selectedBefore, selectedAfter, true, false, false,
                    "route-row", "open-editor");
            screen.openGroupEditor(group);
        } else {
            if (leftClick && RouteListPresentation.canStartRouteDrag(
                    group, normalizedSearchQuery(), shiftDown, controlDown)) {
                beginDragCandidate(group.id(), mouseX, mouseY);
            }
            DebugEventLog.record("WaypointerScreen", "route", group.id(), index,
                    selectedBefore, selectedAfter, doubleClick, shiftDown, controlDown,
                    "route-row", doubleClick
                            ? "double-click ignored: route was not already primary selected"
                            : "select");
        }
        return true;
    }

    boolean mouseDragged(
            MouseButtonEvent event, int left, int top, int right, int bottom) {
        if (dragGroupId == null || event.button() != MOUSE_BUTTON_LEFT) return false;
        if (!normalizedSearchQuery().isEmpty()) {
            clearDrag();
            return false;
        }
        double dx = event.x() - dragStartX;
        double dy = event.y() - dragStartY;
        if (!dragging && dx * dx + dy * dy < DRAG_THRESHOLD_SQUARED) return false;
        dragging = true;
        dropTarget = dropTargetAt(event.x(), event.y(), left, top, right, bottom);
        return true;
    }

    boolean mouseReleased(MouseButtonEvent event) {
        if (dragGroupId == null || event.button() != MOUSE_BUTTON_LEFT) return false;
        boolean consumed = dragging;
        String movedGroupId = dragGroupId;
        DropTarget target = dropTarget;
        clearDrag();
        if (consumed && target != null && screen.manager.moveGroupToContainer(
                movedGroupId, target.folderId, target.beforeGroupId)) {
            screen.selectOnlyGroupId(movedGroupId);
            screen.refreshActionButtons();
        }
        return consumed;
    }

    private void beginDragCandidate(String groupId, double x, double y) {
        dragGroupId = groupId;
        dragStartX = x;
        dragStartY = y;
        dragging = false;
        dropTarget = null;
    }

    private void clearDrag() {
        dragGroupId = null;
        dragging = false;
        dropTarget = null;
    }

    private DropTarget dropTargetAt(
            double mouseX, double mouseY,
            int left, int top, int right, int bottom) {
        if (mouseX < left || mouseX > right || mouseY < top || mouseY > bottom) return null;
        List<Row> rows = rows();
        int rowsTop = rowsTop(top);
        if (mouseY < rowsTop) return null;
        int index = (int) ((mouseY - rowsTop + screen.scrollOffset) / ROW_PITCH);
        if (index < 0) return null;
        if (index >= rows.size()) {
            int lineY = Math.max(rowsTop, Math.min(bottom - 2,
                    rowsTop - screen.scrollOffset + rows.size() * ROW_PITCH));
            if (!screen.manager.canMoveGroupToContainer(dragGroupId, null, null)) return null;
            return new DropTarget(null, null, lineY, lineY, false);
        }
        int rowTop = rowsTop - screen.scrollOffset + index * ROW_PITCH;
        int rowBottom = rowTop + ROW_H + 2;
        if (mouseY > rowBottom) return null;

        Row row = rows.get(index);
        String folderId;
        String beforeGroupId;
        boolean band;
        if (row.folder != null) {
            folderId = row.folder.id();
            beforeGroupId = null;
            band = true;
        } else if (row.group != null && !row.group.temp() && !row.group.runtimeOnly()) {
            folderId = row.group.routeKind() == WaypointGroup.RouteKind.REGULAR
                    ? screen.manager.folderIdForGroup(row.group.id()) : null;
            if (mouseY < rowTop + (ROW_H + 2) / 2.0) {
                beforeGroupId = row.group.id();
                band = false;
                rowBottom = rowTop;
            } else {
                beforeGroupId = nextGroupIdInContainer(rows, index, row.group, folderId);
                band = false;
                rowTop = rowBottom;
            }
        } else {
            return null;
        }
        if (!screen.manager.canMoveGroupToContainer(
                dragGroupId, folderId, beforeGroupId)) return null;
        return new DropTarget(folderId, beforeGroupId, rowTop, rowBottom, band);
    }

    private String nextGroupIdInContainer(
            List<Row> rows, int index, WaypointGroup target, String folderId) {
        for (int next = index + 1; next < rows.size(); next++) {
            Row candidateRow = rows.get(next);
            WaypointGroup candidate = candidateRow.group;
            if (candidate == null || candidate.id().equals(dragGroupId)
                    || candidate.temp() || candidate.runtimeOnly()) continue;
            if (candidate.routeKind() != target.routeKind()
                    || !candidate.zoneId().equals(target.zoneId())) continue;
            if (candidate.routeKind() == WaypointGroup.RouteKind.REGULAR
                    && !java.util.Objects.equals(
                    folderId, screen.manager.folderIdForGroup(candidate.id()))) continue;
            return candidate.id();
        }
        return null;
    }

    private void renderDropTarget(GuiGraphicsExtractor graphics, int left, int right) {
        if (!dragging || dropTarget == null) return;
        graphics.requestCursor(CursorTypes.POINTING_HAND);
        if (dropTarget.band) {
            graphics.fill(left, dropTarget.top, right, dropTarget.bottom, 0x3038CFE8);
            graphics.fill(left, dropTarget.top, left + 3, dropTarget.bottom, ACCENT);
            return;
        }
        int y = dropTarget.top;
        graphics.fill(left + GAP, y - 1, right - GAP, y + 2, ACCENT);
    }

    void scroll(double wheelDelta, int panelTop, int bottom) {
        int listHeight = bottom - rowsTop(panelTop);
        int maxScroll = maxScroll(rows().size(), listHeight);
        screen.scrollOffset = MathUtil.clamp(
                screen.scrollOffset - (int) (wheelDelta * ROW_PITCH), 0, maxScroll);
    }

    static int contentRight(int mainLeft, int mainRight) {
        return mainRight;
    }

    static int rowsTop(int panelTop) {
        return panelTop + 4 + BTN_H + GAP;
    }

    private int initialNavigationIndex() {
        List<Row> rows = rows();
        int roomIndex = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.group != null && row.group.id().equals(screen.selectedGroupId)) return i;
            if (row.roomHeader && row.roomZoneId.equals(screen.selectedDungeonRoomZoneId)) {
                roomIndex = i;
            }
        }
        return roomIndex;
    }

    private Component narration(int index) {
        List<Row> rows = rows();
        if (index < 0 || index >= rows.size()) return Component.empty();
        Row row = rows.get(index);
        if (row.roomHeader) {
            return Component.literal(displayZoneLabel(row.roomZoneId) + ". "
                    + RouteListPresentation.roomHeaderSubtitle(
                    row.roomRouteCount, row.roomSecretCount,
                    row.currentRoom, row.searchReveal && !row.expanded));
        }
        if (row.folder != null) {
            return Component.literal(row.folder.name() + ". ").append(
                    RouteListPresentation.folderSubtitle(row.folderRouteCount,
                            row.searchReveal));
        }
        if (row.group == null) return Component.empty();
        return Component.literal(RouteListPresentation.routeRowName(row.group, -1, false) + ". "
                + routeRowSubtitle(row.group) + ". "
                + RouteListPresentation.routeToggleLabel(row.group.enabled()));
    }

    private void renderFolderHeader(GuiGraphicsExtractor graphics, Row row,
                                    int x1, int y1, int x2, int mouseX, boolean hovered) {
        int rowBottom = y1 + ROW_H + 2;
        if (hovered) graphics.fill(x1, y1, x2, rowBottom, HOVER);
        int folderColor = 0xFF000000 | row.folder.color();
        graphics.fill(x1, y1, x1 + 3, rowBottom, folderColor);
        String glyph = RouteListPresentation.folderGlyph(row.folder, row.searchReveal);
        int textX = x1 + GAP + 2;
        graphics.text(font(), glyph, textX, y1 + 4, folderColor, false);
        int nameX = textX + font().width(glyph) + GAP;
        int editX = RouteListPresentation.folderEditControlX(x2);
        int selectX = RouteListPresentation.folderSelectControlX(x2);
        int maxWidth = Math.max(16, selectX - nameX - GAP);
        graphics.text(font(), font().plainSubstrByWidth(row.folder.name(), maxWidth),
                nameX, y1 + 4, TEXT, false);
        Component subtitle = RouteListPresentation.folderSubtitle(row.folderRouteCount,
                row.searchReveal);
        graphics.text(font(), font().plainSubstrByWidth(subtitle.getString(), maxWidth),
                nameX, y1 + 14, TEXT_DIM, false);
        int chipY = y1 + 5;
        List<String> folderMembers = screen.manager.groupIdsInFolder(row.folder.id());
        renderCompactChip(graphics,
                Component.translatable("waypointer.screen.main.folder.select").getString(),
                selectX, chipY,
                !folderMembers.isEmpty() && screen.selectedGroupIds.containsAll(folderMembers),
                hovered && RouteListPresentation.isFolderSelectControlHit(mouseX, x2));
        renderCompactChip(graphics,
                Component.translatable("waypointer.screen.main.folder.edit").getString(),
                editX, chipY, false,
                hovered && RouteListPresentation.isFolderEditControlHit(mouseX, x2));
    }

    private void activateRow(int index) {
        List<Row> rows = rows();
        if (index < 0 || index >= rows.size()) return;
        Row row = rows.get(index);
        if (row.roomHeader) {
            screen.selectedDungeonRoomZoneId = row.roomZoneId;
            screen.clearRouteSelection();
            screen.refreshActionButtons();
            return;
        }
        if (row.folder != null) {
            screen.manager.toggleFolderCollapsed(row.folder.id());
            return;
        }
        WaypointGroup group = row.group;
        if (group == null) return;
        if (row.roomZoneId != null) screen.selectedDungeonRoomZoneId = row.roomZoneId;
        boolean alreadySelected = group.id().equals(screen.selectedGroupId)
                && screen.selectedGroupIds.contains(group.id());
        if (alreadySelected) {
            screen.openGroupEditor(group);
            return;
        }
        screen.selectOnlyGroupId(group.id());
        screen.refreshActionButtons();
    }

    private List<String> dungeonRoomZoneIds(String currentRoomZoneId) {
        List<String> roomIds = new ArrayList<>();
        for (WaypointGroup group : screen.manager.allGroups()) {
            if (!group.temp() && !group.runtimeOnly()
                    && group.routeKind() == WaypointGroup.RouteKind.DUNGEON
                    && !roomIds.contains(group.zoneId())) {
                roomIds.add(group.zoneId());
            }
        }
        if (isDungeonRoomZone(currentRoomZoneId) && !roomIds.contains(currentRoomZoneId)) {
            roomIds.add(0, currentRoomZoneId);
        }
        Set<String> populated = new HashSet<>();
        for (String roomId : roomIds) {
            if (dungeonRoomHasRoutes(roomId)) populated.add(roomId);
        }
        return orderedDungeonRoomIds(roomIds, populated, currentRoomZoneId);
    }

    private boolean dungeonRoomHasRoutes(String roomZoneId) {
        return !dungeonRoomGroupsForZone(roomZoneId).isEmpty();
    }

    private List<WaypointGroup> dungeonRoomGroupsForZone(String roomZoneId) {
        List<WaypointGroup> out = new ArrayList<>();
        boolean hasStoredRoute = false;
        for (WaypointGroup group : screen.manager.allGroups()) {
            if (!group.temp() && group.routeKind() == WaypointGroup.RouteKind.DUNGEON
                    && roomZoneId != null && roomZoneId.equals(group.zoneId())) {
                out.add(group);
                if (!group.runtimeOnly() && !group.isEmpty()) hasStoredRoute = true;
            }
        }
        if (hasStoredRoute) out.removeIf(DungeonRoomRouteProjection::isGeneratedGroup);
        return out;
    }

    private List<WaypointGroup> temporaryGroups() {
        List<WaypointGroup> out = new ArrayList<>();
        for (WaypointGroup group : screen.manager.allGroups()) {
            if (group.temp() && !group.isEmpty()) out.add(group);
        }
        return out;
    }

    private List<WaypointGroup> dungeonRoomGroups() {
        List<WaypointGroup> out = new ArrayList<>();
        for (WaypointGroup group : screen.manager.allGroups()) {
            if (!group.temp() && group.routeKind() == WaypointGroup.RouteKind.DUNGEON) out.add(group);
        }
        return out;
    }

    private String normalizedSearchQuery() {
        return screen.searchQuery == null
                ? "" : screen.searchQuery.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean containsSearch(String text, String query) {
        return text != null && text.toLowerCase(java.util.Locale.ROOT).contains(query);
    }

    private int installedSecretCountForRoom(String roomZoneId) {
        int count = 0;
        for (WaypointGroup group : screen.manager.groupsForZone(roomZoneId)) {
            if (!group.temp() && !group.runtimeOnly()
                    && group.routeKind() == WaypointGroup.RouteKind.DUNGEON) {
                count = Math.max(count, group.mainWaypointCount());
            }
        }
        return count;
    }

    private boolean hasSelectedGroupInRoom(String roomZoneId) {
        if (roomZoneId == null || screen.selectedGroupIds.isEmpty()) return false;
        for (String selectedId : screen.selectedGroupIds) {
            WaypointGroup group = screen.manager.get(selectedId);
            if (group != null && roomZoneId.equals(group.zoneId())) return true;
        }
        return false;
    }

    private void renderEmptyState(GuiGraphicsExtractor graphics, int x1, int y1) {
        int textX = x1 + GAP;
        if (!normalizedSearchQuery().isEmpty()) {
            graphics.text(font(), Component.translatable("waypointer.screen.main.empty.search"),
                    textX, y1 + 8, TEXT, false);
            graphics.text(font(), Component.translatable("waypointer.screen.main.empty.search_hint"),
                    textX, y1 + 22, TEXT_DIM, false);
            return;
        }
        if (isTemporaryZone(screen.selectedZoneId)) {
            graphics.text(font(), Component.translatable("waypointer.screen.main.empty.temporary"),
                    textX, y1 + 8, TEXT, false);
            graphics.text(font(), Component.translatable(
                            "waypointer.screen.main.empty.temporary_hint"),
                    textX, y1 + 22, TEXT_DIM, false);
            return;
        }
        if (isDungeonRoomsZone(screen.selectedZoneId)) {
            graphics.text(font(), Component.translatable("waypointer.screen.main.empty.dungeons"),
                    textX, y1 + 8, TEXT, false);
            graphics.text(font(), Component.translatable("waypointer.screen.main.empty.dungeons_hint"),
                    textX, y1 + 22, TEXT_DIM, false);
            return;
        }
        graphics.text(font(), Component.translatable("waypointer.screen.main.empty.zone"),
                textX, y1 + 8, TEXT, false);
        graphics.text(font(), Component.translatable("waypointer.screen.main.empty.zone_hint"),
                textX, y1 + 22, TEXT_DIM, false);
    }

    private void renderRoomHeader(GuiGraphicsExtractor graphics, Row row,
                                  int x1, int y1, int x2, boolean hovered) {
        int rowBottom = y1 + ROW_H + 2;
        boolean selected = !hasSelectedGroupInRoom(row.roomZoneId)
                && (row.roomZoneId.equals(screen.selectedDungeonRoomZoneId)
                || screen.selectedDungeonRoomZoneId == null && row.currentRoom);
        int accent = RouteListPresentation.roomHeaderAccent(row.currentRoom);
        int background = RouteListPresentation.roomHeaderBackground(
                selected, hovered, row.currentRoom);
        if (background != 0) graphics.fill(x1, y1, x2, rowBottom, background);
        if (selected || row.currentRoom) graphics.fill(x1, y1, x1 + 2, rowBottom, accent);

        int labelX = x1 + GAP + 2;
        boolean hasRoutes = row.roomRouteCount > 0 || row.roomSecretCount > 0;
        int textColor = row.currentRoom
                ? accent : hasRoutes ? WaypointerScreen.DUNGEON_ROOM_ACCENT : TEXT_DIM;
        int labelMaxWidth = Math.max(24, x2 - GAP - labelX);
        String label = font().plainSubstrByWidth(displayZoneLabel(row.roomZoneId), labelMaxWidth);
        graphics.text(font(), label, labelX, y1 + 4, textColor, false);

        String subtitle = RouteListPresentation.roomHeaderSubtitle(
                row.roomRouteCount, row.roomSecretCount,
                row.currentRoom, row.searchReveal && !row.expanded);
        graphics.text(font(), font().plainSubstrByWidth(subtitle, labelMaxWidth),
                labelX, y1 + 14, TEXT_MUTED, false);
    }

    private void renderGroupRow(GuiGraphicsExtractor graphics, WaypointGroup group, int index,
                                int x1, int y1, int x2,
                                boolean hovered, boolean selected,
                                boolean indented, boolean folderChild) {
        int rowBottom = y1 + ROW_H + 2;
        int background = selected ? SELECTED : hovered ? HOVER : 0;
        int bandX = RouteListPresentation.routeRowBandLeft(x1, folderChild);
        if (background != 0) graphics.fill(bandX, y1, x2, rowBottom, background);
        int accent = group.temp() ? WaypointerScreen.TEMPORARY_ACCENT
                : group.routeKind() == WaypointGroup.RouteKind.DUNGEON
                ? WaypointerScreen.DUNGEON_ROOM_ACCENT
                : ACCENT;
        if (selected) graphics.fill(bandX, y1, bandX + 2, rowBottom, accent);

        int chipX = RouteListPresentation.routeToggleChipX(x2);
        int textX = RouteListPresentation.routeRowTextX(x1, indented);
        int textMaxWidth = Math.max(12, chipX - GAP - textX);
        int textColor = group.enabled() ? TEXT : TEXT_MUTED;
        String name = RouteListPresentation.routeRowName(
                group, index, screen.config.showRouteIndicesInGui());
        graphics.text(font(), font().plainSubstrByWidth(name, textMaxWidth),
                textX, y1 + 4, textColor, false);

        String subtitle = routeRowSubtitle(group);
        graphics.text(font(), font().plainSubstrByWidth(subtitle, textMaxWidth),
                textX, y1 + 14, TEXT_DIM, false);
        renderRouteToggleChip(graphics, group.enabled(), x2, y1);

        String zoneId = group.zoneId();
        if (!group.temp() && !zoneId.equals(screen.selectedZoneId)
                && !isDungeonRoomsZone(screen.selectedZoneId)) {
            String hint = "(" + displayZoneLabel(zoneId) + ")";
            String clippedHint = font().plainSubstrByWidth(
                    hint, Math.max(0, chipX - GAP - textX));
            graphics.text(font(), clippedHint, chipX - GAP - font().width(clippedHint),
                    y1 + 10, TEXT_MUTED, false);
        }
    }

    private void renderRouteToggleChip(GuiGraphicsExtractor graphics, boolean enabled,
                                       int rowRight, int rowY) {
        int chipX = RouteListPresentation.routeToggleChipX(rowRight);
        int chipY = rowY + 5;
        renderCompactChip(graphics, RouteListPresentation.routeToggleLabel(enabled),
                chipX, chipY, enabled, false);
    }

    private void renderCompactChip(
            GuiGraphicsExtractor graphics, String label,
            int chipX, int chipY, boolean selected, boolean hovered) {
        int chipRight = chipX + ROUTE_TOGGLE_CHIP_W;
        int chipBottom = chipY + ROUTE_TOGGLE_CHIP_H;
        graphics.fill(chipX, chipY, chipRight, chipBottom,
                selected ? ACCENT : hovered ? 0xB0FFFFFF : BORDER);
        graphics.fill(chipX + 1, chipY + 1, chipRight - 1, chipBottom - 1, 0xE0181D22);
        String clipped = font().plainSubstrByWidth(label, ROUTE_TOGGLE_CHIP_W - 6);
        graphics.text(font(), clipped,
                chipX + (ROUTE_TOGGLE_CHIP_W - font().width(clipped)) / 2,
                chipY + 3, selected ? TEXT : hovered ? ACCENT : TEXT_DIM, false);
    }

    private void renderScrollbar(GuiGraphicsExtractor graphics, int x, int y1, int y2,
                                 int rowCount, int currentScrollOffset) {
        int viewportHeight = y2 - y1;
        int contentHeight = rowCount * ROW_PITCH;
        if (viewportHeight <= 0 || contentHeight <= viewportHeight) return;
        int thumbHeight = Math.max(12, viewportHeight * viewportHeight / contentHeight);
        int maxScroll = maxScroll(rowCount, viewportHeight);
        int travel = viewportHeight - thumbHeight;
        if (maxScroll <= 0 || travel <= 0) return;
        int thumbY = y1 + currentScrollOffset * travel / maxScroll;
        graphics.fill(x, y1 + 2, x + 2, y2 - 2, BORDER);
        graphics.fill(x, thumbY, x + 2, thumbY + thumbHeight, TEXT_MUTED);
    }

    private String routeRowSubtitle(WaypointGroup group) {
        if (group.temp()) {
            return group.size() + " temp pts  " + displayZoneLabel(group.zoneId());
        }
        if (group.isEmpty()) return "empty - double-click to add waypoints";
        return group.size() + " pts  " + RouteProgress.summary(group)
                + "  " + (group.loadMode() == WaypointGroup.LoadMode.SEQUENCE
                ? "sequenced" : "static");
    }

    private Font font() {
        return screen.routeListFont();
    }

    private static int maxScroll(int rowCount, int listHeight) {
        int contentHeight = rowCount * ROW_PITCH;
        return Math.max(0, contentHeight - Math.max(0, listHeight) + 8);
    }

    private static List<String> visibleGroupIds(List<Row> rows) {
        List<String> ids = new ArrayList<>();
        if (rows == null) return ids;
        for (Row row : rows) {
            if (row == null || row.roomHeader || row.folder != null || row.group == null) continue;
            ids.add(row.group.id());
        }
        return ids;
    }

    private record DropTarget(
            String folderId, String beforeGroupId,
            int top, int bottom, boolean band) {
    }

    private record Row(boolean roomHeader,
                       String roomZoneId, WaypointGroup group,
                       RouteFolder folder, int roomRouteCount, int roomSecretCount,
                       int folderRouteCount, boolean expanded, boolean currentRoom,
                       boolean searchReveal, boolean indented, boolean folderChild) {
        static Row group(
                String roomZoneId, WaypointGroup group,
                boolean indented, boolean folderChild) {
            return new Row(false, roomZoneId, group, null, 0, 0, 0,
                    false, false, false, indented, folderChild);
        }

        static Row room(String roomZoneId, int routeCount, int secretCount,
                        boolean expanded, boolean currentRoom, boolean searchReveal) {
            return new Row(true, roomZoneId, null, null,
                    routeCount, secretCount, 0,
                    expanded, currentRoom, searchReveal, false, false);
        }

        static Row folder(RouteFolder folder, int routeCount, boolean searchReveal) {
            return new Row(false, null, null, folder, 0, 0, routeCount,
                    !folder.collapsed(), false, searchReveal, false, false);
        }
    }
}
