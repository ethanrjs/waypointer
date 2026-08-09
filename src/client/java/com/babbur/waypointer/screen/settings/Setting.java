package com.babbur.waypointer.screen.settings;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.dungeon.config.DungeonConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class Setting {

    public enum Store { MAIN, DUNGEON, NONE }

    public enum Kind { BOOL, NUMBER, COLOR, ENUM, ACTION, HIDDEN }

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

        public String word() { return word; }

        public String chip() { return chip; }

        public String wordTranslationKey() {
            return "waypointer.settings.impact." + name().toLowerCase(Locale.ROOT) + ".word";
        }

        public String chipTranslationKey() {
            return "waypointer.settings.impact." + name().toLowerCase(Locale.ROOT) + ".chip";
        }
    }

    public record EnumOption(String id, String label, Object value) {
        public EnumOption {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("enum option id must not be blank");
            }
            label = label == null ? "" : label;
        }

        public EnumOption(String label, Object value) {
            this(String.valueOf(value).toLowerCase(Locale.ROOT), label, value);
        }
    }

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

    private Impact impact;
    private List<String> aliases = new ArrayList<>();
    private List<EnumOption> enumOptions = List.of();
    private EnabledWhen enabledWhen;
    private String colorPickerTitle = "";
    private String colorSwatchTooltip = "";
    private double minimum = Double.NEGATIVE_INFINITY;
    private double maximum = Double.POSITIVE_INFINITY;
    private boolean wholeNumber;

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

    public Setting impact(Impact impact) {
        this.impact = impact;
        return aliases("performance", impact.word().toLowerCase(Locale.ROOT));
    }

    public Setting aliases(String... extra) {
        for (String alias : extra) {
            String normalized = alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !aliases.contains(normalized)) aliases.add(normalized);
        }
        return this;
    }

    public Setting enabledWhen(EnabledWhen predicate) {
        this.enabledWhen = predicate;
        return this;
    }

    public Setting range(double minimum, double maximum) {
        if (kind != Kind.NUMBER || !Double.isFinite(minimum) || Double.isNaN(maximum)
                || maximum < minimum) {
            throw new IllegalArgumentException("invalid numeric range for " + id);
        }
        this.minimum = minimum;
        this.maximum = maximum;
        return this;
    }

    public Setting wholeNumber() {
        if (kind != Kind.NUMBER) throw new IllegalStateException(id + " is not numeric");
        wholeNumber = true;
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
    public double minimum() { return minimum; }
    public double maximum() { return maximum; }
    public boolean requiresWholeNumber() { return wholeNumber; }

    public boolean acceptsNumber(double value) {
        return kind == Kind.NUMBER
                && Double.isFinite(value)
                && value >= minimum
                && value <= maximum
                && (!wholeNumber || value == Math.rint(value));
    }

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
        return "waypointer.settings.setting." + id + ".option." + enumOptions.get(index).id();
    }

    public String legacyEnumOptionTranslationKey(int index) {
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
