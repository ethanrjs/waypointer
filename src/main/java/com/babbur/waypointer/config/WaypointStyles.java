package com.babbur.waypointer.config;

import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves only explicitly supported local appearance properties, never behavioral preferences. */
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
                "showWaypointDistances",
                "showRouteProgress",
                "labelScale",
                "labelOpacity",
                "waypointSkipFadeMs",
                "scaleWaypointTextWithDistance",
                "matchWaypointTextToWaypointColor",
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
                "showRouteLines",
                "useEtherwarpHeight",
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
    public static WaypointerConfig resolve(WaypointerConfig base, WaypointGroup group, Waypoint waypoint) {
        if (base.resolvedStyleSnapshot) return base;
        WaypointStyle preferred = base.namedStyle(base.preferredStyleId());
        WaypointStyle routePreset = group == null ? WaypointStyle.EMPTY : base.namedStyle(group.stylePresetId());
        WaypointStyle route = group == null ? WaypointStyle.EMPTY : group.styleOverride();
        WaypointStyle point = waypoint == null ? WaypointStyle.EMPTY : waypoint.styleOverride();
        if (preferred.isEmpty() && routePreset.isEmpty() && route.isEmpty() && point.isEmpty()) return base;
        List<WaypointStyle> key = List.of(preferred, routePreset, route, point);
        WaypointerConfig cached = base.resolvedStyles.get(key);
        if (cached != null) return cached;
        WaypointerConfig resolved = base.unsavedStyleCopy();
        for (WaypointStyle style : key) applyTo(resolved, style);
        // Bound snapshot memory when more than 1024 distinct styles are resolved.
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
        color = styleColor(base.namedStyle(base.preferredStyleId()), color);
        if (group != null) {
            color = styleColor(base.namedStyle(group.stylePresetId()), color);
            color = styleColor(group.styleOverride(), color);
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
    /** Applies validated appearance values to an unsaved draft or settings edited by the user. */
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
            // An invalid optional style property must not break other valid properties.
            return false;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumValue(Class<?> type, String value) {
        return Enum.valueOf((Class) type, value);
    }
}
