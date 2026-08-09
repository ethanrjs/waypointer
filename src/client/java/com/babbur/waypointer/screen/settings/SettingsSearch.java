package com.babbur.waypointer.screen.settings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class SettingsSearch {

    static final int MIN_SUBSEQUENCE_TOKEN_LENGTH = 4;

    public record Match(Setting setting, String categoryId, String categoryLabel,
                        String groupLabel, int tier) {}

    @FunctionalInterface
    public interface TranslationResolver {
        String resolve(String key, String fallback);
    }

    private SettingsSearch() {}

    public static List<Match> search(String rawQuery, List<SettingsCatalog.Category> categories) {
        return search(rawQuery, categories, (key, fallback) -> fallback);
    }

    public static List<Match> search(String rawQuery, List<SettingsCatalog.Category> categories,
                                     TranslationResolver translations) {
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return List.of();
        String[] tokens = query.split("\\s+");
        TranslationResolver resolver = translations == null
                ? (key, fallback) -> fallback
                : translations;

        List<Match> out = new ArrayList<>();
        for (SettingsCatalog.Category category : categories) {
            String categoryLabel = resolve(resolver,
                    SettingsCatalog.categoryTranslationKey(category), category.label());
            for (SettingsCatalog.Group group : category.groups()) {
                String groupLabel = group.label() == null ? "" : resolve(resolver,
                        SettingsCatalog.groupTranslationKey(category, group), group.label());
                for (Setting setting : group.settings()) {
                    if (setting.kind() == Setting.Kind.HIDDEN) continue;
                    String label = resolve(resolver, setting.labelTranslationKey(), setting.label());
                    String tooltip = setting.tooltip().isEmpty() ? "" : resolve(resolver,
                            setting.tooltipTranslationKey(), setting.tooltip());
                    int tier = entryTier(tokens, label, setting.aliases(), tooltip,
                            categoryLabel, groupLabel);
                    if (tier >= 0) {
                        out.add(new Match(setting, category.id(), categoryLabel, groupLabel, tier));
                    }
                }
            }
        }
        out.sort(Comparator.comparingInt(Match::tier)); // stable: catalog order breaks ties
        return out;
    }

    static int entryTier(String[] tokens, Setting setting, String categoryLabel, String groupLabel) {
        return entryTier(tokens, setting.label(), setting.aliases(), setting.tooltip(),
                categoryLabel, groupLabel);
    }

    private static int entryTier(String[] tokens, String settingLabel, List<String> aliases,
                                 String settingTooltip, String categoryLabel, String groupLabel) {
        String label = settingLabel.toLowerCase(Locale.ROOT);
        String tooltip = settingTooltip.toLowerCase(Locale.ROOT);
        String category = categoryLabel == null ? "" : categoryLabel.toLowerCase(Locale.ROOT);
        String group = groupLabel == null ? "" : groupLabel.toLowerCase(Locale.ROOT);

        int worst = 0;
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            int tier = tierFor(token, label, aliases, tooltip, category, group);
            if (tier < 0) return -1;
            worst = Math.max(worst, tier);
        }
        return worst;
    }

    private static String resolve(TranslationResolver resolver, String key, String fallback) {
        String resolved = resolver.resolve(key, fallback);
        return resolved == null ? fallback : resolved;
    }

    /** All inputs must already be lowercase. */
    static int tierFor(String token, String label, List<String> aliases, String tooltip,
                       String categoryLabel, String groupLabel) {
        if (label.contains(token)) return 0;
        for (String alias : aliases) {
            if (alias.contains(token)) return 1;
        }
        if (categoryLabel.contains(token) || groupLabel.contains(token)) return 2;
        if (tooltip.contains(token)) return 3;
        if (token.length() >= MIN_SUBSEQUENCE_TOKEN_LENGTH) {
            if (isSubsequence(token, label)) return 4;
            for (String alias : aliases) {
                if (isSubsequence(token, alias)) return 4;
            }
        }
        return -1;
    }

    static boolean isSubsequence(String needle, String haystack) {
        if (needle == null || needle.isEmpty()) return true;
        if (haystack == null || haystack.isEmpty()) return false;
        int needleIndex = 0;
        for (int i = 0; i < haystack.length() && needleIndex < needle.length(); i++) {
            if (haystack.charAt(i) == needle.charAt(needleIndex)) {
                needleIndex++;
            }
        }
        return needleIndex == needle.length();
    }
}
