package com.babbur.waypointer.debug;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Exports the debug report using only the categories the user approved.
 * Excluded sections are marked as redacted rather than silently omitted.
 */
public final class DebugReportExport {

    public static final String REDACTED = "[User redacted]";
    private static final int MAX_EXPORTED_LINE_CHARS = 4_000;

    public enum Category {
        CORE("", ""),
        PC_SPECS("PC Specs",
                "OS, Java, CPU, memory, GPU, and display."),
        ACTIVE_MODS("Active Mods and Versions",
                "Loaded mod names, IDs, and versions."),
        SERVER_CONTEXT("Server, Player, and Location",
                "Server, zone, position, and dungeon."),
        ROUTES_AND_WAYPOINTS("Routes, Waypoints, and Coordinates",
                "Routes, waypoints, coords, and clipboard."),
        SETTINGS_AND_CHANGES("Settings and Recent Changes",
                "All settings and this session's changes."),
        RECENT_LOGS_AND_ACTIVITY("Recent Logs and Activity",
                "Relevant logs and recent UI activity.");

        private final String label;
        private final String description;

        Category(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String label() {
            return label;
        }

        public String description() {
            return description;
        }

        public boolean userControlled() {
            return this != CORE;
        }

        public static List<Category> userControlledValues() {
            List<Category> categories = new ArrayList<>();
            for (Category category : values()) {
                if (category.userControlled()) categories.add(category);
            }
            return List.copyOf(categories);
        }
    }

    public record Section(String heading, Category category, List<String> lines) {
        public Section {
            heading = sanitizeLine(heading).trim();
            category = Objects.requireNonNullElse(category, Category.CORE);
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** Core data is included even when it is absent from {@code included}. */
    public record Options(Set<Category> included) {
        public Options {
            EnumSet<Category> copy = included == null || included.isEmpty()
                    ? EnumSet.noneOf(Category.class)
                    : EnumSet.copyOf(included);
            copy.add(Category.CORE);
            included = Set.copyOf(copy);
        }

        public static Options allEnabled() {
            return new Options(EnumSet.allOf(Category.class));
        }

        public boolean includes(Category category) {
            return category == Category.CORE || included.contains(category);
        }
    }

    private DebugReportExport() {
    }

    public static String format(List<Section> sections, Options options) {
        Options approved = options == null ? Options.allEnabled() : options;
        StringBuilder report = new StringBuilder();
        if (sections == null) return "";

        for (Section section : sections) {
            if (section == null) continue;
            if (!report.isEmpty()) report.append('\n');
            report.append("== ").append(section.heading()).append(" ==\n");
            if (!approved.includes(section.category())) {
                report.append("  ").append(REDACTED).append('\n');
                continue;
            }
            for (String line : section.lines()) {
                report.append(sanitizeLine(line)).append('\n');
            }
        }
        return report.toString();
    }

    private static String sanitizeLine(String value) {
        String source = Objects.requireNonNullElse(value, "");
        StringBuilder sanitized = new StringBuilder(Math.min(source.length(), MAX_EXPORTED_LINE_CHARS));
        for (int i = 0; i < source.length() && sanitized.length() < MAX_EXPORTED_LINE_CHARS; i++) {
            char c = source.charAt(i);
            sanitized.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (source.length() > MAX_EXPORTED_LINE_CHARS) sanitized.append("...");
        return sanitized.toString();
    }
}
