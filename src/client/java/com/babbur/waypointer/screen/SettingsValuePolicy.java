package com.babbur.waypointer.screen;

import com.babbur.waypointer.screen.settings.Setting;

import java.util.List;
import java.util.Objects;

final class SettingsValuePolicy {

    private SettingsValuePolicy() {}

    static boolean searchClearActive(String query) {
        return query != null && !query.isEmpty();
    }

    static Object nextEnumValue(Setting setting, Object current) {
        List<Setting.EnumOption> options = setting.enumOptions();
        for (int i = 0; i < options.size(); i++) {
            if (Objects.equals(options.get(i).value(), current)) {
                return options.get((i + 1) % options.size()).value();
            }
        }
        return options.getFirst().value();
    }

    static Double acceptedNumberValue(Setting setting, String rawValue) {
        if (setting == null || rawValue == null || rawValue.isBlank()) return null;
        Double displayedValue = setting.numberDisplayValue();
        if (displayedValue != null) {
            String normalized = rawValue.trim();
            String localizedDisplay = SettingsText.localizedValue(
                    setting, displayedValue).getString();
            if (normalized.equalsIgnoreCase(localizedDisplay)
                    || normalized.equalsIgnoreCase(setting.numberDisplayFallback())) {
                return displayedValue;
            }
        }
        try {
            double value = Double.parseDouble(rawValue.trim());
            return setting.acceptsNumber(value) ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static List<String> visiblePresetIds() {
        return List.of("minimal", "default", "nothing");
    }
}
