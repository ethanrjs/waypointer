package com.babbur.waypointer.config;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.crystal.CrystalHollowsStructureZones;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class WaypointStyles {
    private WaypointStyles() {}
    private record Property(Method getter, Method setter) {}
    private static final Map<String, Property> PROPERTIES = properties();
    private static Map<String, Property> properties() {
        Map<String, Property> properties = new LinkedHashMap<>();
        for (String name : List.of("defaultWaypointColor",
                "tracerColor",
                "matchTracerToWaypointColor",
                "tracerOpacity",
                "tracerThickness",
                "waypointOutlineThickness",
                "waypointMarkerScale",
                "waypointOutlineOpacity",
                "matchWaypointOutlineToWaypointColor",
                "waypointOutlineColor",
                "beaconOpacity",
                "showWaypointNames",
                "numberUnnamedWaypoints",
                "showWaypointDistances",
                "showRouteProgress",
                "labelScale",
                "labelOpacity",
                "waypointSkipFadeMs",
                "scaleWaypointTextWithDistance",
                "matchWaypointTextToWaypointColor",
                "labelTextColor",
                "labelColorForAllText",
                "colorSequenceWaypointsByRole",
                "sequencePreviousWaypointColor",
                "sequenceCurrentWaypointColor",
                "sequenceNextWaypointColor",
                "showTracer",
                "dimSequenceContextWaypoints",
                "hideTracerOnStaticRoutes",
                "hideWaypointsNearPlayer",
                "hideWaypointsNearRadius",
                "hideWaypointLabelsNearPlayer",
                "hideWaypointLabelsNearRadius",
                "showLabels",
                "labelMaxDistance",
                "showRouteLines",
                "useEtherwarpHeight",
                "lineOfSightOnly",
                "routeLineColor",
                "showLabelBackdrop",
                "showLabelTextShadow",
                "maxStaticWaypointRenderDistance",
                "labelHeightOffset",
                "boxStyle",
                "beaconBeamMode",
                "beaconBeamExtendsBelowWaypoint",
                "useBeaconBeamTextures",
                "routeMinRenderDistance",
                "routeMaxRenderDistance",
                "routeLineWidth",
                "routeLineOpacity",
                "beaconWidthScale",
                "beaconMotionSpeed",
                "tracerCenterGap",
                "labelBackdropOpacity",
                "labelDetailDistance",
                "completedWaypointOpacity",
                "currentWaypointOpacity",
                "upcomingWaypointOpacity",
                "sequenceContextOpacity",
                "routeLineDashed",
                "tracerEdgeArrow",
                "labelShowRouteName",
                "labelShowCoordinates",
                "labelDetailOnDemand",
                "preventLabelOverlap",
                "showWaypointRoleBadge",
                "waypointIcon")) {
            try {
                Method getter = WaypointerConfig.class.getMethod(name);
                Method setter = WaypointerConfig.class.getMethod(
                        "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1), getter.getReturnType());
                properties.put(name, new Property(getter, setter));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        return Map.copyOf(properties);
    }
    public static Set<String> supportedProperties() { return PROPERTIES.keySet(); }
    public static WaypointStyle capture(WaypointerConfig config) {
        Map<String, String> values = new LinkedHashMap<>();
        PROPERTIES.forEach((name, property) -> {
            try { values.put(name, String.valueOf(property.getter().invoke(config))); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        });
        return new WaypointStyle(values);
    }

    public static String valueOf(WaypointerConfig config, String name) {
        Property property = PROPERTIES.get(name);
        if (property == null || config == null) return null;
        try { return String.valueOf(property.getter().invoke(config)); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static final Map<String, List<String>> ZONE_KEYS = new ConcurrentHashMap<>();

    public static List<String> zoneKeys(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) return List.of();
        List<String> cached = ZONE_KEYS.get(zoneId);
        if (cached != null) return cached;
        List<String> keys = computeZoneKeys(zoneId);
        if (ZONE_KEYS.size() >= 512) ZONE_KEYS.clear();
        ZONE_KEYS.put(zoneId, keys);
        return keys;
    }
    private static List<String> computeZoneKeys(String zoneId) {
        String id = Zone.canonicalId(zoneId);
        String family = zoneFamily(id);
        return family == null || family.equals(id) ? List.of(id) : List.of(family, id);
    }
    static String zoneFamily(String id) {
        if (Zone.isSpecificMineshaftZone(id) || id.startsWith("mineshaft")) return "mineshaft";
        if (id.equals("dungeon") || id.startsWith("dungeon_f") || id.startsWith("dungeon_m")
                || DungeonRoomData.entry(id) != null) return "dungeon";
        if (CrystalHollowsStructureZones.isStructureZone(id)) return "crystal_hollows";
        return null;
    }

    public static WaypointStyle zoneStyle(WaypointerConfig base, String zoneId) {
        List<String> keys = zoneKeys(zoneId);
        if (keys.isEmpty()) return WaypointStyle.EMPTY;
        WaypointStyle family = base.zoneStyle(keys.getFirst());
        if (keys.size() == 1) return family;
        WaypointStyle exact = base.zoneStyle(keys.getLast());
        if (family.isEmpty()) return exact;
        if (exact.isEmpty()) return family;
        Map<String, String> merged = new LinkedHashMap<>(family.values());
        merged.putAll(exact.values());
        return new WaypointStyle(merged);
    }

    public static WaypointerConfig resolve(WaypointerConfig base, WaypointGroup group, Waypoint waypoint) {
        if (base.resolvedStyleSnapshot) return base;
        if (group == null) return resolveLayers(base, null, waypoint);
        GroupStyles styles = groupStyles(base, group);
        WaypointStyle point = waypoint == null ? WaypointStyle.EMPTY : waypoint.styleOverride();
        if (point.isEmpty()) return styles.resolved;
        WaypointerConfig cached = styles.points.get(point);
        if (cached != null) return cached;
        WaypointerConfig resolved = resolveLayers(base, group, waypoint);
        if (styles.points.size() < MAX_CACHED_POINT_STYLES) styles.points.put(point, resolved);
        return resolved;
    }

    private static final int MAX_CACHED_GROUPS = 4096;
    private static final int MAX_CACHED_POINT_STYLES = 256;
    private static final int NO_COLOR = -1;

    static final class GroupStyles {
        private final WaypointerConfig base;
        private final int generation;
        private final WaypointStyle route;
        private final String presetId;
        private final String zoneId;
        private final WaypointerConfig resolved;
        private final int color;
        private final Map<WaypointStyle, WaypointerConfig> points = new ConcurrentHashMap<>();

        private GroupStyles(WaypointerConfig base, WaypointGroup group) {
            this.base = base;
            generation = base.styleGeneration;
            route = group.styleOverride();
            presetId = group.stylePresetId();
            zoneId = group.zoneId();
            resolved = resolveLayers(base, group, null);
            int layered = styleColor(base.namedStyle(base.preferredStyleId()), NO_COLOR);
            layered = styleColor(zoneStyle(base, zoneId), layered);
            layered = styleColor(base.namedStyle(presetId), layered);
            color = styleColor(route, layered);
        }

        private boolean matches(WaypointerConfig base, WaypointGroup group) {
            return this.base == base && generation == base.styleGeneration
                    && route == group.styleOverride() && presetId.equals(group.stylePresetId())
                    && zoneId.equals(group.zoneId());
        }
    }

    private static GroupStyles groupStyles(WaypointerConfig base, WaypointGroup group) {
        if (group.resolvedStyleCache() instanceof GroupStyles attached && attached.matches(base, group)) {
            return attached;
        }
        GroupStyles styles = base.groupStyles.get(group);
        if (styles == null || !styles.matches(base, group)) {
            styles = new GroupStyles(base, group);
            if (base.groupStyles.size() >= MAX_CACHED_GROUPS) base.groupStyles.clear();
            base.groupStyles.put(group, styles);
        }
        group.setResolvedStyleCache(styles);
        return styles;
    }

    public static int routeColorOverride(WaypointerConfig base, WaypointGroup group) {
        return groupStyles(base, group).color;
    }

    private static WaypointerConfig resolveLayers(WaypointerConfig base, WaypointGroup group, Waypoint waypoint) {
        WaypointStyle preferred = base.namedStyle(base.preferredStyleId());
        WaypointStyle zone = group == null ? WaypointStyle.EMPTY : zoneStyle(base, group.zoneId());
        WaypointStyle routePreset = group == null ? WaypointStyle.EMPTY : base.namedStyle(group.stylePresetId());
        WaypointStyle route = group == null ? WaypointStyle.EMPTY : group.styleOverride();
        WaypointStyle point = waypoint == null ? WaypointStyle.EMPTY : waypoint.styleOverride();
        if (preferred.isEmpty() && zone.isEmpty() && routePreset.isEmpty() && route.isEmpty() && point.isEmpty()) {
            return base;
        }
        List<WaypointStyle> key = List.of(preferred, zone, routePreset, route, point);
        WaypointerConfig cached = base.resolvedStyles.get(key);
        if (cached != null) return cached;
        WaypointerConfig resolved = base.unsavedStyleCopy();
        for (WaypointStyle style : key) applyTo(resolved, style);

        if (base.resolvedStyles.size() >= 1024) base.resolvedStyles.clear();
        base.resolvedStyles.put(key, resolved);
        return resolved;
    }
    public static WaypointerConfig apply(WaypointerConfig base, WaypointStyle style) {
        return preview(base, style);
    }
    public static int waypointColor(WaypointerConfig base, WaypointGroup group, Waypoint waypoint) {
        if (waypoint == null) return base.defaultWaypointColor();
        int color = waypoint.color();
        if (group == null) {
            color = styleColor(base.namedStyle(base.preferredStyleId()), color);
        } else {
            int layered = groupStyles(base, group).color;
            if (layered != NO_COLOR) color = layered;
        }
        return styleColor(waypoint.styleOverride(), color);
    }
    private static int styleColor(WaypointStyle style, int fallback) {
        String value = style.values().get("defaultWaypointColor");
        if (value == null) return fallback;
        try { return Integer.parseInt(value) & 0xFFFFFF; }
        catch (NumberFormatException ignored) { return fallback; }
    }

    public static WaypointerConfig preview(WaypointerConfig base, WaypointStyle style) {
        WaypointerConfig draft = base.unsavedStyleCopy();
        applyTo(draft, style);
        return draft;
    }

    public static void applyTo(WaypointerConfig config, WaypointStyle style) {
        if (style == null) return;
        style.values().forEach((name, value) -> applyProperty(config, name, value));
    }
    public static WaypointStyle normalize(WaypointStyle style) {
        if (style == null || style.isEmpty()) return WaypointStyle.EMPTY;
        WaypointerConfig draft = new WaypointerConfig();
        Map<String, String> values = new LinkedHashMap<>();
        style.values().forEach((name, value) -> {
            if (!applyProperty(draft, name, value)) return;
            values.put(name, value);
        });
        return new WaypointStyle(values);
    }

    public static WaypointStyle normalizeKeepingUnknown(WaypointStyle style) {
        if (style == null || style.isEmpty()) return WaypointStyle.EMPTY;
        WaypointerConfig draft = new WaypointerConfig();
        Map<String, String> values = new LinkedHashMap<>();
        style.values().forEach((name, value) -> {
            if (PROPERTIES.containsKey(name) ? !applyProperty(draft, name, value)
                    : isOtherSetting(name)) return;
            values.put(name, value);
        });
        return values.isEmpty() ? WaypointStyle.EMPTY : new WaypointStyle(values);
    }
    private static boolean isOtherSetting(String name) {
        try {
            WaypointerConfig.class.getMethod(name);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    public static int setCount(WaypointStyle style) {
        if (style == null) return 0;
        int count = 0;
        for (String name : style.values().keySet()) if (PROPERTIES.containsKey(name)) count++;
        return count;
    }
    private static boolean applyProperty(WaypointerConfig config, String name, String value) {
        Property property = PROPERTIES.get(name);
        if (property == null) return false;
        try {
            Class<?> type = property.getter().getReturnType();
            Object parsed;
            if (type == boolean.class) {
                if (!value.equals("true") && !value.equals("false")) return false;
                parsed = Boolean.parseBoolean(value);
            } else if (type == double.class) {
                double number = Double.parseDouble(value);
                if (!Double.isFinite(number)) return false;
                parsed = number;
            } else if (type == int.class) parsed = Integer.parseInt(value);
            else if (type == String.class) parsed = value;
            else if (type.isEnum()) parsed = enumValue(type, value);
            else return false;
            property.setter().invoke(config, parsed);
            return true;
        } catch (IllegalArgumentException ignored) {

            return false;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumValue(Class<?> type, String value) {
        return Enum.valueOf((Class) type, value);
    }
}
