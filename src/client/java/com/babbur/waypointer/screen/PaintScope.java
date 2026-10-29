package com.babbur.waypointer.screen;

import com.babbur.waypointer.config.AppearanceScope;
import com.babbur.waypointer.config.WaypointStyles;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;

record PaintScope(AppearanceScope.Level level, String zoneKey, WaypointGroup group, int waypointIndex) {

    static final PaintScope GLOBAL = new PaintScope(AppearanceScope.Level.GLOBAL, null, null, -1);

    static PaintScope zone(String zoneKey) {
        return new PaintScope(AppearanceScope.Level.ZONE, zoneKey, null, -1);
    }

    static PaintScope route(WaypointGroup group) {
        return new PaintScope(AppearanceScope.Level.ROUTE, null, group, -1);
    }

    static PaintScope waypoint(WaypointGroup group, int index) {
        return new PaintScope(AppearanceScope.Level.WAYPOINT, null, group, index);
    }

    static PaintScope of(AppearanceTarget target, ActiveGroupManager manager) {
        if (target == null || manager == null) return GLOBAL;
        AppearanceScope scope = target.scope();
        return switch (scope.level()) {
            case ZONE -> zone(scope.key());
            case ROUTE -> {
                WaypointGroup group = durable(manager, manager.get(scope.key()));
                yield group == null ? GLOBAL : route(group);
            }
            case WAYPOINT -> {
                int split = scope.key().lastIndexOf('#');
                WaypointGroup group = split < 0 ? null : durable(manager, manager.get(scope.key().substring(0, split)));
                int index = split < 0 ? -1 : parseIndex(scope.key().substring(split + 1));
                yield group == null || index < 0 || index >= group.size() ? GLOBAL : waypoint(group, index);
            }
            default -> GLOBAL;
        };
    }

    WaypointerConfig style(WaypointerConfig config) {
        return switch (level) {
            case ZONE -> AppearanceScope.zone(config, zoneKey).effective();
            case ROUTE -> WaypointStyles.resolve(config, group, null);
            case WAYPOINT -> WaypointStyles.resolve(config, group, waypoint());
            default -> config;
        };
    }

    WaypointPaint ownPaint(WaypointerConfig config) {
        return switch (level) {
            case ZONE -> WaypointPaint.fromCode(config.zoneStyle(zoneKey).values().get(WaypointStyles.PAINT));
            case ROUTE -> group.paint();
            case WAYPOINT -> WaypointPaint.fromCode(waypoint().styleOverride().values().get(WaypointStyles.PAINT));
            default -> config.waypointPainterDefaultPaint();
        };
    }

    WaypointPaint shownPaint(WaypointerConfig config) {
        WaypointPaint own = ownPaint(config);
        if (own != null) return own;
        if (level == AppearanceScope.Level.WAYPOINT && group.paint() != null) return group.paint();
        return style(config).waypointPainterDefaultPaint();
    }

    void apply(WaypointerConfig config, ActiveGroupManager manager, WaypointPaint paint) {
        switch (level) {
            case ZONE -> writeStyle(config, AppearanceScope.zone(config, zoneKey), paint);
            case ROUTE -> {
                group.setPaint(paint);
                group.setPaintEnabled(true);
                manager.fireDataChangedFor(group);
            }
            case WAYPOINT -> {
                writeStyle(config, waypointScope(config), paint);
                manager.fireDataChangedFor(group);
            }
            default -> {
                config.setWaypointPainterDefaultPaint(paint);
                manager.fireDataChanged();
            }
        }
    }

    void clear(WaypointerConfig config, ActiveGroupManager manager) {
        switch (level) {
            case ZONE -> {
                AppearanceScope.zone(config, zoneKey).inherit(WaypointStyles.PAINT);
                manager.fireDataChanged();
            }
            case ROUTE -> {
                group.setPaint(null);
                manager.fireDataChangedFor(group);
            }
            case WAYPOINT -> {
                waypointScope(config).inherit(WaypointStyles.PAINT);
                manager.fireDataChangedFor(group);
            }
            default -> {
                config.setWaypointPainterDefaultPaint(null);
                manager.fireDataChanged();
            }
        }
    }

    int previewColor() {
        if (group == null) return Waypoint.DEFAULT_COLOR;
        if (waypointIndex >= 0) return waypoint().color();
        return group.isEmpty() ? group.staticColor() : group.get(0).color();
    }

    private Waypoint waypoint() {
        return group.get(waypointIndex);
    }

    private AppearanceScope waypointScope(WaypointerConfig config) {
        return AppearanceScope.waypoint(config, group, waypointIndex,
                style -> group.setWaypointStyle(waypointIndex, style));
    }

    private static void writeStyle(WaypointerConfig config, AppearanceScope scope, WaypointPaint paint) {
        WaypointerConfig draft = scope.effective();
        draft.setWaypointPaint(paint == null ? "" : paint.toCode());
        scope.capture(draft, WaypointStyles.PAINT);
    }

    private static WaypointGroup durable(ActiveGroupManager manager, WaypointGroup group) {
        return group == null ? null : DungeonRoomRouteLibrary.durableEditTarget(manager, group);
    }

    private static int parseIndex(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }
}
