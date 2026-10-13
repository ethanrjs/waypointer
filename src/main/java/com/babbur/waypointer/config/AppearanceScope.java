package com.babbur.waypointer.config;

import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class AppearanceScope {

    public enum Level {
        GLOBAL, ZONE, ROUTE, WAYPOINT,

        SAVED_STYLE
    }

    public record Layer(Level level, String key, WaypointStyle style) {}

    private final Level level;
    private final String key;
    private final WaypointerConfig base;
    private final Supplier<WaypointStyle> reader;
    private final Consumer<WaypointStyle> writer;
    private final Supplier<List<Layer>> parents;

    private AppearanceScope(Level level, String key, WaypointerConfig base,
                            Supplier<WaypointStyle> reader, Consumer<WaypointStyle> writer,
                            Supplier<List<Layer>> parents) {
        this.level = level;
        this.key = key;
        this.base = Objects.requireNonNull(base);
        this.reader = reader;
        this.writer = writer;
        this.parents = parents;
    }

    public static AppearanceScope zone(WaypointerConfig base, String zoneKey) {
        return new AppearanceScope(Level.ZONE, zoneKey, base,
                () -> base.zoneStyle(zoneKey),
                style -> base.setZoneStyle(zoneKey, style),
                () -> {
                    List<Layer> layers = new ArrayList<>();
                    addPreferred(base, layers);
                    for (String key : WaypointStyles.zoneKeys(zoneKey)) {
                        if (key.equals(zoneKey)) break;
                        layers.add(new Layer(Level.ZONE, key, base.zoneStyle(key)));
                    }
                    return layers;
                });
    }

    public static AppearanceScope route(WaypointerConfig base, WaypointGroup group,
                                        Consumer<WaypointStyle> writer) {
        return new AppearanceScope(Level.ROUTE, group.id(), base,
                group::styleOverride, writer, () -> routeParents(base, group));
    }

    public static AppearanceScope waypoint(WaypointerConfig base, WaypointGroup group, int index,
                                           Consumer<WaypointStyle> writer) {
        return new AppearanceScope(Level.WAYPOINT, group.id() + "#" + index, base,
                () -> index >= 0 && index < group.size()
                        ? group.get(index).styleOverride() : WaypointStyle.EMPTY,
                writer,
                () -> {
                    List<Layer> layers = routeParents(base, group);
                    layers.add(new Layer(Level.ROUTE, group.id(), group.styleOverride()));
                    return layers;
                });
    }

    private static List<Layer> routeParents(WaypointerConfig base, WaypointGroup group) {
        List<Layer> layers = new ArrayList<>();
        addPreferred(base, layers);
        for (String key : WaypointStyles.zoneKeys(group.zoneId())) {
            layers.add(new Layer(Level.ZONE, key, base.zoneStyle(key)));
        }
        String preset = group.stylePresetId();
        if (preset != null && !preset.isBlank()) {
            layers.add(new Layer(Level.SAVED_STYLE, preset, base.namedStyle(preset)));
        }
        return layers;
    }

    private static void addPreferred(WaypointerConfig base, List<Layer> layers) {
        String preferred = base.preferredStyleId();
        if (!preferred.isBlank()) {
            layers.add(new Layer(Level.SAVED_STYLE, preferred, base.namedStyle(preferred)));
        }
    }

    public Level level() { return level; }

    public String key() { return key; }

    public WaypointerConfig base() { return base; }

    public WaypointStyle style() {
        WaypointStyle style = reader.get();
        return style == null ? WaypointStyle.EMPTY : style;
    }

    public List<Layer> parents() { return parents.get(); }

    public int count() { return WaypointStyles.setCount(style()); }

    public boolean isSetHere(String property) {
        return WaypointStyles.supportedProperties().contains(property)
                && style().values().containsKey(property);
    }

    public Layer source(String property) {
        List<Layer> layers = parents();
        for (int i = layers.size() - 1; i >= 0; i--) {
            if (layers.get(i).style().values().containsKey(property)) return layers.get(i);
        }
        return null;
    }

    public WaypointerConfig inherited() {
        WaypointerConfig draft = base.unsavedStyleCopy();
        for (Layer layer : parents()) WaypointStyles.applyTo(draft, layer.style());
        return draft;
    }

    public WaypointerConfig effective() {
        WaypointerConfig draft = inherited();
        WaypointStyles.applyTo(draft, style());
        return draft;
    }

    public String inheritedValue(String property) {
        return WaypointStyles.valueOf(inherited(), property);
    }

    public void capture(WaypointerConfig draft, String property) {
        String value = WaypointStyles.valueOf(draft, property);
        if (value == null) return;
        if (value.equals(inheritedValue(property))) inherit(property);
        else write(style().with(property, value));
    }

    public void inherit(String property) {
        WaypointStyle current = style();
        if (current.values().containsKey(property)) write(current.without(property));
    }

    public void clear() {
        Map<String, String> kept = new LinkedHashMap<>(style().values());
        kept.keySet().removeAll(WaypointStyles.supportedProperties());
        write(kept.isEmpty() ? WaypointStyle.EMPTY : new WaypointStyle(kept));
    }

    private void write(WaypointStyle style) {
        writer.accept(style.isEmpty() ? WaypointStyle.EMPTY : style);
        base.resolvedStyles.clear();
    }
}
