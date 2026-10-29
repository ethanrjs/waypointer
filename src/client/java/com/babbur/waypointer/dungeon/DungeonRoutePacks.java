package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RoutePack;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.data.DungeonRouteImporter;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DungeonRoutePacks {

    public static final String UNLABELED_KEY = "";

    public enum State { ACTIVE, PARTLY_ACTIVE, HIDDEN }

    public record Pack(String key, String name, List<WaypointGroup> routes) {

        public boolean unlabeled() { return UNLABELED_KEY.equals(key); }

        public int roomCount() {
            return roomIds(false).size();
        }

        public int activeRoomCount() {
            return roomIds(true).size();
        }

        public State state() {
            if (routes.stream().noneMatch(WaypointGroup::enabled)) return State.HIDDEN;
            boolean allOn = activeRoomCount() == roomCount()
                    && routes.stream().filter(r -> !isRoomRoute(r)).allMatch(WaypointGroup::enabled);
            return allOn ? State.ACTIVE : State.PARTLY_ACTIVE;
        }

        private Set<String> roomIds(boolean enabledOnly) {
            Set<String> rooms = new LinkedHashSet<>();
            for (WaypointGroup route : routes) {
                if (isRoomRoute(route) && (!enabledOnly || route.enabled())) rooms.add(route.zoneId());
            }
            return rooms;
        }
    }

    private DungeonRoutePacks() {}

    public static RoutePack importedPack(DungeonRouteImporter.Format format) {
        String source = switch (format == null ? DungeonRouteImporter.Format.WAYPOINTER : format) {
            case WAYPOINTER -> "Waypointer";
            case SECRET_ROUTES -> "SecretRoutes";
            case ODIN_PACK -> "Odin";
        };
        return importedPack(source);
    }

    public static RoutePack importedPack(String source) {
        String day = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d", Locale.ROOT));
        return RoutePack.fresh(Component.translatable(
                "waypointer.dungeon.packs.imported_name", source, day).getString());
    }

    public static List<Pack> list(Iterable<WaypointGroup> groups) {
        Map<String, List<WaypointGroup>> byKey = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        if (groups != null) {
            for (WaypointGroup group : groups) {
                if (!stored(group)) continue;
                RoutePack pack = group.routePack();
                if (pack == null && group.routeKind() != WaypointGroup.RouteKind.DUNGEON) continue;
                String key = pack == null ? UNLABELED_KEY : pack.id();
                byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(group);
                if (pack != null) names.putIfAbsent(key, pack.name());
            }
        }
        List<Pack> out = new ArrayList<>();
        for (Map.Entry<String, List<WaypointGroup>> entry : byKey.entrySet()) {
            if (UNLABELED_KEY.equals(entry.getKey())) continue;
            out.addFirst(new Pack(entry.getKey(), names.getOrDefault(entry.getKey(), ""),
                    List.copyOf(entry.getValue())));
        }
        List<WaypointGroup> unlabeled = byKey.get(UNLABELED_KEY);
        if (unlabeled != null) out.add(new Pack(UNLABELED_KEY, "", List.copyOf(unlabeled)));
        return out;
    }

    public static int activate(ActiveGroupManager manager, Pack pack) {
        if (manager == null || pack == null) return 0;
        Set<String> packIds = ids(pack);
        Set<String> zones = new HashSet<>();
        Set<String> chosenRooms = new HashSet<>();
        List<WaypointGroup> enable = new ArrayList<>();
        for (WaypointGroup route : pack.routes()) {
            if (isRoomRoute(route)) {

                if (!chosenRooms.add(route.zoneId())) continue;
                WaypointGroup alreadyOn = pack.routes().stream()
                        .filter(r -> r.enabled() && route.zoneId().equals(r.zoneId()))
                        .findFirst().orElse(route);
                enable.add(alreadyOn);
            } else {
                enable.add(route);
            }
            if (route.usesDungeonSteps()) zones.add(route.zoneId());
        }
        Set<String> enableIds = new HashSet<>();
        for (WaypointGroup route : enable) enableIds.add(route.id());
        int[] changed = { 0 };
        DungeonRoomRouteSync.batched(() -> {
            for (WaypointGroup group : manager.allGroups()) {
                if (!stored(group) || !group.enabled()) continue;
                boolean otherPackSameRoom = !packIds.contains(group.id())
                        && group.usesDungeonSteps() && zones.contains(group.zoneId());
                boolean packVariant = packIds.contains(group.id()) && !enableIds.contains(group.id())
                        && isRoomRoute(group);
                if (otherPackSameRoom || packVariant) {
                    group.setEnabled(false);
                    changed[0]++;
                }
            }
            for (WaypointGroup route : enable) {
                if (!route.enabled()) {
                    route.setEnabled(true);
                    changed[0]++;
                }
            }
        });
        if (changed[0] > 0) manager.fireDataChanged();
        return changed[0];
    }

    public static int hide(ActiveGroupManager manager, Pack pack) {
        if (manager == null || pack == null) return 0;
        int[] changed = { 0 };
        DungeonRoomRouteSync.batched(() -> {
            for (WaypointGroup route : pack.routes()) {
                if (route.enabled()) {
                    route.setEnabled(false);
                    changed[0]++;
                }
            }
        });
        if (changed[0] > 0) manager.fireDataChanged();
        return changed[0];
    }

    public static int delete(ActiveGroupManager manager, Pack pack) {
        if (manager == null || pack == null || pack.routes().isEmpty()) return 0;
        List<String> ids = new ArrayList<>(ids(pack));
        DungeonRoomRouteSync.batched(() -> manager.removeAll(ids));
        manager.fireDataChanged();
        return ids.size();
    }

    static boolean isRoomRoute(WaypointGroup group) {
        return group.routeKind() == WaypointGroup.RouteKind.DUNGEON;
    }

    private static boolean stored(WaypointGroup group) {
        return group != null && !group.temp() && !group.runtimeOnly() && !group.isEmpty();
    }

    private static Set<String> ids(Pack pack) {
        Set<String> ids = new HashSet<>();
        for (WaypointGroup route : pack.routes()) ids.add(route.id());
        return ids;
    }
}
