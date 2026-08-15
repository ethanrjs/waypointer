package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.RouteProgress;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.WaypointGroup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.babbur.waypointer.screen.GuiTokens.GAP;
import static com.babbur.waypointer.screen.GuiTokens.HOVER;
import static com.babbur.waypointer.screen.GuiTokens.SELECTED;

final class RouteListPresentation {

    static final int CURRENT_DUNGEON_ROOM_ACCENT = 0xFF58C878;

    private static final int DUNGEON_ROOM_ACCENT = 0xFFFF8A8A;
    private static final int CURRENT_DUNGEON_ROOM_BG = 0x332A7040;
    private static final int CURRENT_DUNGEON_ROOM_SELECTED_BG = 0x553A8A50;
    private static final int ROUTE_TOGGLE_CHIP_W = 54;
    private static final int ROUTE_TOGGLE_HIT_PAD = 2;
    private static final int DUNGEON_ROUTE_CHILD_INDENT = 18;
    private static final int FOLDER_ROUTE_BAND_INDENT = 18;
    private static final int FOLDER_ROUTE_TEXT_INDENT = 15;
    private static final int FOLDER_CONTROL_WIDTH = 54;

    private RouteListPresentation() {}

    static boolean groupMatchesSearch(WaypointGroup group, String query, String zoneLabel) {
        if (group == null) return false;
        if (contains(group.name(), query)
                || contains(group.zoneId(), query)
                || contains(zoneLabel, query)
                || contains(group.loadMode().name(), query)
                || contains(RouteProgress.summary(group), query)) {
            return true;
        }
        for (int i = 0; i < group.size(); i++) {
            if (waypointMatchesSearch(group, i, query)) return true;
        }
        return false;
    }

    static boolean waypointMatchesSearch(WaypointGroup group, int index, String query) {
        if (group == null || index < 0 || index >= group.size()) return false;
        var waypoint = group.get(index);
        return contains(waypoint.name(), query)
                || contains(group.displayIndexLabel(index), query)
                || contains(waypoint.x() + "," + waypoint.y() + "," + waypoint.z(), query);
    }

    static int hideRoutes(Iterable<WaypointGroup> groups) {
        int changed = 0;
        if (groups == null) return changed;
        for (WaypointGroup group : groups) {
            if (group == null || !group.enabled()) continue;
            group.setEnabled(false);
            changed++;
        }
        return changed;
    }

    static String nextRouteName(Iterable<WaypointGroup> zoneGroups) {
        Set<String> taken = new HashSet<>();
        if (zoneGroups != null) {
            for (WaypointGroup group : zoneGroups) {
                if (group != null) taken.add(group.name().trim());
            }
        }
        for (int number = 1; ; number++) {
            String candidate = "Route " + number;
            if (!taken.contains(candidate)) return candidate;
        }
    }

    static int routeToggleChipX(int rowRight) {
        return rowRight - ROUTE_TOGGLE_CHIP_W - GAP;
    }

    static int routeToggleHitLeft(int rowRight) {
        return routeToggleChipX(rowRight) - ROUTE_TOGGLE_HIT_PAD;
    }

    static int routeRowTextX(
            int rowLeft, boolean indented, boolean folderChild) {
        int indent = folderChild ? FOLDER_ROUTE_TEXT_INDENT
                : indented ? DUNGEON_ROUTE_CHILD_INDENT : 0;
        return rowLeft + GAP + 2 + indent;
    }

    static int routeRowBandLeft(int rowLeft, boolean folderChild) {
        return rowLeft + (folderChild ? FOLDER_ROUTE_BAND_INDENT : 0);
    }

    static int folderEditControlX(int rowRight) {
        return rowRight - GAP - FOLDER_CONTROL_WIDTH;
    }

    static int folderSelectControlX(int rowRight) {
        return folderEditControlX(rowRight) - GAP - FOLDER_CONTROL_WIDTH;
    }

    static boolean isFolderEditControlHit(double mouseX, int rowRight) {
        int left = folderEditControlX(rowRight);
        return mouseX >= left && mouseX < left + FOLDER_CONTROL_WIDTH;
    }

    static boolean isFolderSelectControlHit(double mouseX, int rowRight) {
        int left = folderSelectControlX(rowRight);
        return mouseX >= left && mouseX < left + FOLDER_CONTROL_WIDTH;
    }

    static FolderHeaderAction folderHeaderAction(
            double mouseX, int rowRight, boolean doubleClick) {
        if (isFolderEditControlHit(mouseX, rowRight)) return FolderHeaderAction.EDIT;
        if (isFolderSelectControlHit(mouseX, rowRight)) return FolderHeaderAction.SELECT;
        return doubleClick ? FolderHeaderAction.NONE : FolderHeaderAction.TOGGLE;
    }

    static boolean canStartRouteDrag(
            WaypointGroup group, String query, boolean shiftDown, boolean controlDown) {
        return group != null && !group.temp() && !group.runtimeOnly()
                && (query == null || query.isBlank()) && !shiftDown && !controlDown;
    }

    enum FolderHeaderAction {
        SELECT,
        EDIT,
        TOGGLE,
        NONE
    }

    static Map<String, Integer> routeCommandIndices(List<WaypointGroup> groups) {
        Map<String, Integer> indices = new HashMap<>();
        if (groups == null) return indices;
        for (int i = 0; i < groups.size(); i++) {
            WaypointGroup group = groups.get(i);
            if (group != null) indices.put(group.id(), i);
        }
        return indices;
    }

    static String routeRowName(WaypointGroup group, int routeIndex, boolean showRouteIndex) {
        String name = displayGroupName(group);
        return showRouteIndex && routeIndex >= 0 ? "[" + routeIndex + "] " + name : name;
    }

    static String routeToggleLabel(boolean enabled) {
        return enabled
                ? Component.translatableWithFallback(
                        "waypointer.screen.main.route.shown", "Shown").getString()
                : Component.translatableWithFallback(
                        "waypointer.screen.main.route.hidden", "Hidden").getString();
    }

    static Component folderSubtitle(int routeCount, boolean searchReveal) {
        MutableComponent subtitle = Component.translatable(folderRouteCountKey(routeCount), routeCount);
        if (searchReveal) {
            subtitle.append(Component.translatable(folderSearchSuffixKey()));
        }
        return subtitle;
    }

    static String folderRouteCountKey(int routeCount) {
        return routeCount == 1
                ? "waypointer.screen.main.folder.routes.one"
                : "waypointer.screen.main.folder.routes.many";
    }

    static String folderSearchSuffixKey() {
        return "waypointer.screen.main.folder.search_suffix";
    }

    static boolean shouldOpenEditor(
            boolean doubleClick, boolean alreadyPrimarySelected,
            boolean shiftDown, boolean controlDown) {
        return doubleClick && alreadyPrimarySelected && !shiftDown && !controlDown;
    }

    static int roomHeaderAccent(boolean currentRoom) {
        return currentRoom ? CURRENT_DUNGEON_ROOM_ACCENT : DUNGEON_ROOM_ACCENT;
    }

    static int roomHeaderBackground(boolean selected, boolean hovered, boolean currentRoom) {
        if (currentRoom) {
            return selected ? CURRENT_DUNGEON_ROOM_SELECTED_BG : CURRENT_DUNGEON_ROOM_BG;
        }
        return selected ? SELECTED : hovered ? HOVER : 0;
    }

    static String roomHeaderSubtitle(
            int routeCount, int secretCount, boolean currentRoom, boolean searchOnly) {
        StringBuilder subtitle = new StringBuilder();
        subtitle.append(routeCount == 1
                ? Component.translatableWithFallback(
                        "waypointer.screen.main.room.routes.one",
                        "%1$s route", routeCount).getString()
                : Component.translatableWithFallback(
                        "waypointer.screen.main.room.routes.many",
                        "%1$s routes", routeCount).getString());
        if (secretCount > 0) {
            subtitle.append("  ").append(secretCount == 1
                    ? Component.translatableWithFallback(
                            "waypointer.screen.main.room.secrets.one",
                            "%1$s secret", secretCount).getString()
                    : Component.translatableWithFallback(
                            "waypointer.screen.main.room.secrets.many",
                            "%1$s secrets", secretCount).getString());
        }
        if (currentRoom) {
            subtitle.append("  ").append(Component.translatableWithFallback(
                    "waypointer.screen.main.room.current", "current").getString());
        }
        if (searchOnly) {
            subtitle.append("  ").append(Component.translatableWithFallback(
                    "waypointer.screen.main.room.search", "search").getString());
        }
        return subtitle.toString();
    }

    static int displayedInstalledSecretCount(
            int installedSecretCount, Iterable<WaypointGroup> roomGroups) {
        if (roomGroups != null) {
            for (WaypointGroup group : roomGroups) {
                if (group != null && !group.temp() && !group.runtimeOnly() && !group.isEmpty()) {
                    return 0;
                }
            }
        }
        return installedSecretCount;
    }

    private static boolean contains(String text, String query) {
        if (text == null || query == null) return false;
        return text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static String displayGroupName(WaypointGroup group) {
        if (group == null) return unnamedRouteLabel();
        String name = group.name().trim();
        if (!group.temp()) return name.isEmpty() ? unnamedRouteLabel() : name;
        if (name.isEmpty() || name.startsWith("Temp --")) {
            return Component.translatableWithFallback(
                    "waypointer.screen.main.route.temporary", "Temporary").getString();
        }
        return name;
    }

    private static String unnamedRouteLabel() {
        return Component.translatableWithFallback(
                "waypointer.screen.main.route.unnamed", "(unnamed)").getString();
    }
}
