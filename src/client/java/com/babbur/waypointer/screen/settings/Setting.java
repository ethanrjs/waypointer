package com.babbur.waypointer.screen.settings;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.dungeon.config.DungeonConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Declarative descriptor for one settings-screen entry.
 *
 * <p>This is the single source of truth the settings UI is built from: label,
 * tooltip, search aliases, performance impact, widget kind, graying predicate,
 * and unbound accessors. The accessors take the config instances as parameters
 * so one descriptor serves live editing, the config-code import diff
 * ({@link SettingsCatalog#countChangedSettings}), per-row reset against a
 * defaults instance, presets, and the parity tests.
 *
 * <p>Deliberately free of Minecraft classes so plain JUnit can construct and
 * iterate the whole catalog; {@code Component}s and {@code Tooltip}s are built
 * at row-construction time in the screen.
 */
public final class Setting {

    /** Which config object backs this entry. {@code NONE} = action rows. */
    public enum Store { MAIN, DUNGEON, NONE }

    /**
     * Widget family. {@code HIDDEN} entries have no row but still participate
     * in the import diff and parity tests (legacy/codec-only fields).
     */
    public enum Kind { BOOL, NUMBER, COLOR, ENUM, ACTION, HIDDEN }

    /** Performance impact rating, rendered as a colored chip on the row. */
    public enum Impact {
        HIGH("HIGH", "HIGH"),
        MEDIUM("MEDIUM", "MED"),
        LOW("LOW", "LOW");

        private final String word;
        private final String chip;

        Impact(String word, String chip) {
            this.word = word;
            this.chip = chip;
        }

        /** Full word used in the tooltip suffix ("Impact: HIGH"). */
        public String word() { return word; }

        /** Short label rendered inline on the row. */
        public String chip() { return chip; }

        public String wordTranslationKey() {
            return "waypointer.settings.impact." + name().toLowerCase(Locale.ROOT) + ".word";
        }

        public String chipTranslationKey() {
            return "waypointer.settings.impact." + name().toLowerCase(Locale.ROOT) + ".chip";
        }
    }

    /** One choice of an ENUM entry, in cycle order. */
    public record EnumOption(String label, Object value) {}

    @FunctionalInterface
    public interface Getter { Object get(WaypointerConfig config, DungeonConfig dungeon); }

    @FunctionalInterface
    public interface Setter { void set(WaypointerConfig config, DungeonConfig dungeon, Object value); }

    @FunctionalInterface
    public interface EnabledWhen { boolean test(WaypointerConfig config, DungeonConfig dungeon); }

    private final String id;
    private final Store store;
    private final Kind kind;
    private final String label;
    private final String tooltip;
    private final Getter getter;
    private final Setter setter;

    // Decorated during catalog construction only; effectively immutable afterwards.
    private Impact impact;
    private List<String> aliases = new ArrayList<>();
    private List<EnumOption> enumOptions = List.of();
    private EnabledWhen enabledWhen;
    private String colorPickerTitle = "";
    private String colorSwatchTooltip = "";

    private Setting(String id, Store store, Kind kind, String label, String tooltip,
                    Getter getter, Setter setter) {
        this.id = id;
        this.store = store;
        this.kind = kind;
        this.label = label == null ? "" : label;
        this.tooltip = tooltip == null ? "" : tooltip;
        this.getter = getter;
        this.setter = setter;
    }

    public static Setting bool(String id, Store store, String label, String tooltip,
                               Getter getter, Setter setter) {
        return new Setting(id, store, Kind.BOOL, label, tooltip, getter, setter);
    }

    public static Setting number(String id, Store store, String label, String tooltip,
                                 Getter getter, Setter setter) {
        return new Setting(id, store, Kind.NUMBER, label, tooltip, getter, setter);
    }

    public static Setting color(String id, Store store, String label, String tooltip,
                                String pickerTitle, String swatchTooltip,
                                Getter getter, Setter setter) {
        Setting out = new Setting(id, store, Kind.COLOR, label, tooltip, getter, setter);
        out.colorPickerTitle = pickerTitle == null ? "" : pickerTitle;
        out.colorSwatchTooltip = swatchTooltip == null ? "" : swatchTooltip;
        return out.aliases("color", "colour", "hex");
    }

    public static Setting enumCycle(String id, Store store, String label, String tooltip,
                                    List<EnumOption> options, Getter getter, Setter setter) {
        Setting out = new Setting(id, store, Kind.ENUM, label, tooltip, getter, setter);
        out.enumOptions = List.copyOf(options);
        return out;
    }

    public static Setting action(String id, String label, String tooltip) {
        return new Setting(id, Store.NONE, Kind.ACTION, label, tooltip, null, null);
    }

    public static Setting hidden(String id, Getter getter, Setter setter) {
        return new Setting(id, Store.MAIN, Kind.HIDDEN, id, "", getter, setter);
    }

    /** Attach an impact rating; auto-adds "performance" and the impact word as aliases. */
    public Setting impact(Impact impact) {
        this.impact = impact;
        return aliases("performance", impact.word().toLowerCase(Locale.ROOT));
    }

    /** Add hidden search terms so users can find the row in their own words. */
    public Setting aliases(String... extra) {
        for (String alias : extra) {
            String normalized = alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !aliases.contains(normalized)) aliases.add(normalized);
        }
        return this;
    }

    /** Graying predicate: the row stays visible but its controls disable when false. */
    public Setting enabledWhen(EnabledWhen predicate) {
        this.enabledWhen = predicate;
        return this;
    }

    public String id() { return id; }
    public Store store() { return store; }
    public Kind kind() { return kind; }
    public String label() { return label; }
    public String tooltip() { return tooltip; }
    public Impact impact() { return impact; }
    public List<String> aliases() { return List.copyOf(aliases); }
    public List<EnumOption> enumOptions() { return enumOptions; }
    public String colorPickerTitle() { return colorPickerTitle; }
    public String colorSwatchTooltip() { return colorSwatchTooltip; }

    public String labelTranslationKey() {
        return "waypointer.settings.setting." + id + ".label";
    }

    public String tooltipTranslationKey() {
        return "waypointer.settings.setting." + id + ".tooltip";
    }

    public String colorPickerTitleTranslationKey() {
        return "waypointer.settings.setting." + id + ".color_picker_title";
    }

    public String colorSwatchTooltipTranslationKey() {
        return "waypointer.settings.setting." + id + ".color_swatch_tooltip";
    }

    public String enumOptionTranslationKey(int index) {
        return "waypointer.settings.setting." + id + ".option." + index;
    }

    public Object get(WaypointerConfig config, DungeonConfig dungeon) {
        return getter == null ? null : getter.get(config, dungeon);
    }

    public void set(WaypointerConfig config, DungeonConfig dungeon, Object value) {
        if (setter != null) setter.set(config, dungeon, value);
    }

    public boolean isEnabled(WaypointerConfig config, DungeonConfig dungeon) {
        return enabledWhen == null || enabledWhen.test(config, dungeon);
    }

    public Object defaultValue(WaypointerConfig defaults, DungeonConfig dungeonDefaults) {
        return get(defaults, dungeonDefaults);
    }

    public boolean isModified(WaypointerConfig live, DungeonConfig dungeonLive,
                              WaypointerConfig defaults, DungeonConfig dungeonDefaults) {
        if (store == Store.NONE) return false;
        return !Objects.equals(get(live, dungeonLive), get(defaults, dungeonDefaults));
    }

    /** Human-readable value string for reset tooltips ("default: On", "default: #00FF00"). */
    public String formatValue(Object value) {
        return formatValue(kind, value, enumOptions);
    }

    public static String formatValue(Kind kind, Object value, List<EnumOption> options) {
        if (value == null) return "";
        return switch (kind) {
            case BOOL -> Boolean.TRUE.equals(value) ? "On" : "Off";
            case NUMBER -> stripTrailingZeros(((Number) value).doubleValue());
            case COLOR -> String.format(Locale.ROOT, "#%06X", ((Number) value).intValue() & 0xFFFFFF);
            case ENUM -> {
                for (EnumOption option : options) {
                    if (Objects.equals(option.value(), value)) yield option.label();
                }
                yield String.valueOf(value);
            }
            case ACTION, HIDDEN -> String.valueOf(value);
        };
    }

    static String stripTrailingZeros(double v) {
        if (v == Math.floor(v)) {
            return String.format(Locale.ROOT, "%.0f", v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
