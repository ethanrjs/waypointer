package com.babbur.waypointer.screen;

import com.babbur.waypointer.screen.settings.Setting;
import com.babbur.waypointer.screen.settings.SettingsCatalog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Objects;

/** Localized labels, values, and tooltip composition for the settings UI. */
final class SettingsText {

    private SettingsText() {}

    static Component tooltip(Setting setting) {
        Component description = setting.tooltip().isBlank()
                ? Component.empty()
                : Component.translatableWithFallback(
                        setting.tooltipTranslationKey(), normalizeTooltip(setting.tooltip()));
        return tooltipComponent(setting, description);
    }

    static Tooltip tooltip(Setting setting, Component description) {
        return Tooltip.create(tooltipComponent(setting, description));
    }

    static Tooltip tooltipOrNull(Setting setting) {
        Component tooltip = tooltip(setting);
        return tooltip.getString().isEmpty() ? null : Tooltip.create(tooltip);
    }

    static Component label(Setting setting) {
        return Component.translatableWithFallback(setting.labelTranslationKey(), setting.label());
    }

    static Component category(SettingsCatalog.Category category) {
        return Component.translatableWithFallback(
                SettingsCatalog.categoryTranslationKey(category), category.label());
    }

    static String categoryLabel(SettingsCatalog.Category category) {
        return category(category).getString();
    }

    static Component localizedValue(Setting setting, Object value) {
        if (value == null) return Component.empty();
        if (setting.kind() == Setting.Kind.BOOL) {
            return Component.translatable(Boolean.TRUE.equals(value) ? "options.on" : "options.off");
        }
        if (setting.kind() == Setting.Kind.ENUM) {
            for (int i = 0; i < setting.enumOptions().size(); i++) {
                if (Objects.equals(setting.enumOptions().get(i).value(), value)) {
                    return enumOption(setting, i);
                }
            }
        }
        return Component.literal(setting.formatValue(value));
    }

    static Component enumOption(Setting setting, int index) {
        String fallback = Component.translatableWithFallback(
                setting.legacyEnumOptionTranslationKey(index),
                setting.enumOptions().get(index).label()).getString();
        return Component.translatableWithFallback(setting.enumOptionTranslationKey(index), fallback);
    }

    static String normalizeTooltip(String raw) {
        if (raw == null) return "";
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder result = new StringBuilder(raw.length());
        boolean hasText = false;
        boolean paragraphBreak = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (hasText) paragraphBreak = true;
                continue;
            }
            if (!hasText) {
                result.append(trimmed);
                hasText = true;
            } else if (paragraphBreak) {
                result.append("\n\n").append(trimmed);
                paragraphBreak = false;
            } else {
                result.append(' ').append(trimmed);
            }
        }
        return result.toString();
    }

    private static Component tooltipComponent(Setting setting, Component text) {
        MutableComponent result = label(setting).copy().withStyle(ChatFormatting.GRAY);
        if (text != null && !text.getString().isEmpty()) {
            result.append(Component.literal("\n"));
            result.append(text.copy().withStyle(ChatFormatting.WHITE));
        }
        return result;
    }
}
