package dev.ethan.waypointer.screen.settings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Ranked settings search over the catalog.
 *
 * <p>Per-token tiers, best (lowest) wins per token; an entry's rank is its
 * worst token tier so multi-token queries stay an AND. Tiers:
 *
 * <ol start="0">
 *   <li>substring of the label</li>
 *   <li>substring of a hidden alias ("esp", "fade", "performance", ...)</li>
 *   <li>substring of the category or group label (typing "chat" surfaces the family)</li>
 *   <li>substring of the tooltip</li>
 *   <li>subsequence of label+aliases, only for tokens of {@value #MIN_SUBSEQUENCE_TOKEN_LENGTH}+
 *       characters — the old any-length subsequence fallback matched nearly everything
 *       on short queries, which recreated the overwhelm inside search</li>
 * </ol>
 *
 * <p>Final order: tier ascending, catalog declaration order as the stable
 * tiebreak. Results are complete — the screen scrolls them, never truncates.
 */
public final class SettingsSearch {

    static final int MIN_SUBSEQUENCE_TOKEN_LENGTH = 4;

    public record Match(Setting setting, String categoryId, String categoryLabel,
                        String groupLabel, int tier) {}

    private SettingsSearch() {}

    public static List<Match> search(String rawQuery, List<SettingsCatalog.Category> categories) {
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return List.of();
        String[] tokens = query.split("\\s+");

        List<Match> out = new ArrayList<>();
        for (SettingsCatalog.Category category : categories) {
            for (SettingsCatalog.Group group : category.groups()) {
                for (Setting setting : group.settings()) {
                    if (setting.kind() == Setting.Kind.HIDDEN) continue;
                    int tier = entryTier(tokens, setting, category.label(), group.label());
                    if (tier >= 0) {
                        out.add(new Match(setting, category.id(), category.label(), group.label(), tier));
                    }
                }
            }
        }
        out.sort(Comparator.comparingInt(Match::tier)); // stable: catalog order breaks ties
        return out;
    }

    /** Worst token tier, or -1 when any token fails to match. */
    static int entryTier(String[] tokens, Setting setting, String categoryLabel, String groupLabel) {
        String label = setting.label().toLowerCase(Locale.ROOT);
        String tooltip = setting.tooltip().toLowerCase(Locale.ROOT);
        String category = categoryLabel == null ? "" : categoryLabel.toLowerCase(Locale.ROOT);
        String group = groupLabel == null ? "" : groupLabel.toLowerCase(Locale.ROOT);
        List<String> aliases = setting.aliases();

        int worst = 0;
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            int tier = tierFor(token, label, aliases, tooltip, category, group);
            if (tier < 0) return -1;
            worst = Math.max(worst, tier);
        }
        return worst;
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
