package com.babbur.waypointer.debug;

import com.babbur.waypointer.core.Zone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DebugReportAnonymizer {
    private static final String OMITTED = "[Omitted: raw text can contain player names and account data; structured diagnostics are retained.]";
    private static final Pattern UUID = Pattern.compile("(?i)(?<![\\da-f])(?:[\\da-f]{8}-[\\da-f]{4}-[\\da-f]{4}-[\\da-f]{4}-[\\da-f]{12}|[\\da-f]{32})(?![\\da-f])");
    private static final Pattern EMAIL = Pattern.compile("[\\w.!#$%&'*+/=?^`{|}~-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?(?![\\w.])");
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![\\w:])(?=[\\da-f:]*::|(?:[\\da-f]{1,4}:){7})(?:[\\da-f]{0,4}:){2,}[\\da-f:.]*(?:%[\\w]+)?(?![\\w:])");
    private static final Pattern PATH = Pattern.compile("(?i)(?:[a-z]:[\\\\/]|\\\\\\\\|/(?:home|Users|tmp|var|opt|mnt)/)[^\\r\\n\"<>|]*");
    private static final Pattern SECRET = Pattern.compile("(?i)(\\b(?:access[_-]?token|refresh[_-]?token|session[_-]?token|token|api[_-]?key|password|passwd|secret|session[_-]?id|authorization|cookie)\\b[\"']?[ \\t]*[:=][ \\t]*)(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|(?:Bearer[ \\t]+|Basic[ \\t]+)?[^\\s,;&]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[a-z0-9._~+/=-]+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(https?://)[^\\s/@]+@(?=[^\\s/]+)");
    private static final Pattern SERVER = Pattern.compile("(?i)(\\b(?:serverName|serverId|serverAddress|hostname)\\s*=\\s*)([^,; ]+)");
    private static final Pattern ZONE_FIELD = Pattern.compile("(\\b(?:zone|raw|refined|map|mode)=)(.*?)(?=, [a-zA-Z]+=|$)");
    private static final Pattern WAYPOINT_NAME = Pattern.compile("( name=\")(.*)(\" color=#[0-9A-Fa-f]{6} flags=)");
    private static final Pattern SUMMARY = Pattern.compile("^(.*)(: \\d+ pts, .*)$");
    private static final Pattern ACTIVE_GROUP = Pattern.compile("^\\s*(.*):\\s+(\\d+ pts, .*)$");
    private static final Set<String> EMPTY = Set.of("", "(none)", "(unnamed)", "(unmatched)", "unknown", "Unavailable", "Not connected", "Local or address unavailable");

    private DebugReportAnonymizer() { }

    public static String removeSecrets(String report) {
        if (report == null) return "";
        String result = SECRET.matcher(report).replaceAll("$1[Secret omitted]");
        result = BEARER.matcher(result).replaceAll("Bearer [Secret omitted]");
        return URL_CREDENTIALS.matcher(result).replaceAll("$1[Credentials omitted]@");
    }

    public static String anonymize(List<DebugReportExport.Section> sections, Collection<String> sensitiveValues) {
        Filter filter = new Filter(sensitiveValues);
        List<DebugReportExport.Section> result = new ArrayList<>();
        result.add(new DebugReportExport.Section("Privacy", DebugReportExport.Category.CORE, List.of(
                "  Some data has been anonymized. Some data is kept exact to help debug issues as quickly as possible, "
                        + "such as exact coordinates, route geometry, PC specs, mods, settings, and other diagnostics. "
                        + "You SHOULD review before sending.")));
        if (sections == null) return DebugReportExport.format(result, null);
        for (var section : sections) {
            if (section == null || section.heading().equals("Codec Clipboard")) continue;
            for (String line : section.lines()) filter.sectionLine(section.heading(), flat(line));
        }
        filter.freeze();
        for (var section : sections) {
            if (section == null || section.heading().equals("Codec Clipboard")) continue;
            List<String> lines = new ArrayList<>();
            boolean technical = section.category() == DebugReportExport.Category.PC_SPECS
                    || section.category() == DebugReportExport.Category.ACTIVE_MODS;
            boolean freeText = Set.of("Recent Relevant Log Lines", "Recent UI and Input Activity", "Zone Bridge")
                    .contains(section.heading());
            for (String line : section.lines()) {
                String filtered = filter.sectionLine(section.heading(), flat(line));
                lines.add(filter.scrub(filtered, technical, freeText));
            }
            result.add(new DebugReportExport.Section(filter.scrub(section.heading(), true, false), section.category(), lines));
        }
        return DebugReportExport.format(result, null);
    }

    private static String flat(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ");
    }

    private static final class Filter {
        private final Map<String, String> aliases = new LinkedHashMap<>();
        private final Map<String, String> known = new LinkedHashMap<>();
        private final Map<String, String> identities;
        private Replacement identityReplacement;
        private Replacement allReplacement;

        Filter(Collection<String> values) {
            if (values != null) for (String value : values) {
                if (value == null || value.isBlank()) continue;
                remember(value);
            }
            identities = Map.copyOf(known);
        }

        private void freeze() {
            identityReplacement = new Replacement(identities);
            allReplacement = new Replacement(known);
        }

        private String alias(String value) {
            if (EMPTY.contains(value)) return value;
            return aliases.computeIfAbsent(value, ignored -> "[private-" + (aliases.size() + 1) + "]");
        }

        private String remember(String value) {
            String replacement = alias(value);
            if (!EMPTY.contains(value) && !value.matches("[\\d\\W]+")) {
                known.put(value, replacement);
                String normalized = flat(value).trim();
                known.put(normalized, replacement);
                known.put(value.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"), replacement);
                if (normalized.length() > 1_000) known.put(normalized.substring(0, 1_000) + "...", replacement);
            }
            return replacement;
        }

        private String zone(String value) {
            if (EMPTY.contains(value) || value.equals("private_world") || value.equals("Private World")) return value;
            for (Zone zone : Zone.knownZones()) {
                if (value.equals(zone.id()) || value.equals(zone.displayName())
                        || value.equals(zone.displayName() + " (" + zone.id() + ")")) return value;
            }
            int split = value.lastIndexOf(" (");
            if (split > 0 && value.endsWith(")")) {
                return zone(value.substring(0, split)) + " (" + zone(value.substring(split + 2, value.length() - 1)) + ")";
            }
            return remember(value);
        }

        private String sectionLine(String heading, String line) {
            int colon = line.indexOf(':');
            String key = colon < 0 ? "" : line.substring(0, colon).trim();
            String value = colon < 0 ? line : line.substring(colon + 1).stripLeading();
            String prefix = colon < 0 ? "" : line.substring(0, colon + 1) + " ";
            if (key.equals("Scoreboard text") || key.equals("Tab text")) return prefix + OMITTED;
            if (heading.equals("Publishing Identity") && (key.equals("Minecraft account")
                    || key.endsWith("publisher ID") || key.endsWith("publisher name"))) return prefix + remember(value);
            if (heading.equals("Recent Relevant Log Lines") && (value.contains("[CHAT]")
                    || value.matches(".*(?:^|\\s)<[^>]+>\\s+.*"))) return prefix + "[Chat payload omitted: may contain unknown player names and private messages.]";
            if (key.equals("Privacy") && heading.equals("Troubleshooting Report")) return prefix + "See privacy notice above.";
            if (key.equals("Connection")) {
                if (value.matches("(?i)(?:[a-z0-9-]+\\.)*hypixel\\.net(?::25565)?")) return prefix + value;
                return prefix + remember(value);
            }
            if (heading.equals("Active Routes and Waypoints") && key.equals("Route code")) {
                return prefix + "[Route code omitted: contains waypoint names.]";
            }
            if (heading.equals("Active Routes and Waypoints") && (key.equals("Name") || key.equals("ID"))
                    || heading.equals("Dungeon Entry Path Outcomes") && Set.of("Group", "Target name").contains(key)) {
                return prefix + remember(value);
            }
            if (key.equals("Group ID / zone")) {
                int split = value.indexOf(" / ");
                return prefix + (split < 0 ? remember(value)
                        : remember(value.substring(0, split)) + " / " + zone(value.substring(split + 3)));
            }
            if (Set.of("Zone", "Waypointer zone", "Current zone", "Last broad").contains(key)) return prefix + zone(value);
            if (key.equals("Largest group") || key.equals("Largest active")) {
                Matcher m = SUMMARY.matcher(value);
                if (m.matches()) return prefix + remember(m.group(1)) + m.group(2);
            }
            if (heading.equals("Active Groups")) {
                Matcher m = ACTIVE_GROUP.matcher(line);
                if (m.matches()) return "  " + remember(m.group(1)) + ": " + m.group(2);
            }
            if (heading.equals("Active Routes and Waypoints")) {
                Matcher m = WAYPOINT_NAME.matcher(line);
                if (m.find()) return line.substring(0, m.start(2)) + remember(m.group(2)) + line.substring(m.end(2));
                int name = line.indexOf(" name=\"");
                if (name >= 0) return line.substring(0, name) + " name=" + remember(line.substring(name + 7));
            }
            if (key.equals("chatCoordSenderBlacklist")) return prefix + "[Player list omitted]";
            if (heading.equals("Recent Settings Changes") && value.contains("chatCoordSenderBlacklist:")) {
                return prefix + "chatCoordSenderBlacklist: [Player list change omitted]";
            }
            if (heading.equals("Recent UI and Input Activity") && key.equals("Event")) {
                value = replaceGroup(value, Pattern.compile("((?:route|room-header|waypoint)=)(.*?)(?= row=)"), 2, false);
                value = replaceGroup(value, Pattern.compile("(selected=)(.*?)(?= dbl=)"), 2, false);
                return prefix + value;
            }
            if (heading.equals("Zone Bridge") || key.equals("Hypixel Mod API")) {
                line = replaceGroup(line, ZONE_FIELD, 2, true);
            }
            return replaceGroup(line, SERVER, 2, false);
        }

        private String replaceGroup(String source, Pattern pattern, int group, boolean zoneValue) {
            Matcher matcher = pattern.matcher(source);
            StringBuilder result = new StringBuilder();
            int start = 0;
            while (matcher.find()) {
                result.append(source, start, matcher.start(group));
                result.append(zoneValue ? zone(matcher.group(group)) : remember(matcher.group(group)));
                start = matcher.end(group);
            }
            return result.append(source, start, source.length()).toString();
        }

        private String scrub(String source, boolean technical, boolean freeText) {
            boolean stackFrame = source.stripLeading().startsWith("Log: at ") || source.stripLeading().startsWith("at ");
            String result = (freeText && !stackFrame ? allReplacement : identityReplacement).apply(source);
            result = removeSecrets(result);
            result = replaceMatches(result, EMAIL);
            result = replaceMatches(result, PATH);
            result = replaceMatches(result, UUID);
            if (!technical) {
                result = replaceMatches(result, IPV4);
                result = replaceMatches(result, IPV6);
            }
            return result;
        }

        private String replaceMatches(String value, Pattern pattern) {
            return pattern.matcher(value).replaceAll(m -> Matcher.quoteReplacement(alias(m.group())));
        }
    }

    private static final class Replacement {
        private final Pattern pattern;
        private final Map<String, String> values = new LinkedHashMap<>();

        Replacement(Map<String, String> source) {
            source.forEach((key, value) -> values.putIfAbsent(key.toLowerCase(Locale.ROOT), value));
            List<String> needles = source.keySet().stream().filter(s -> !s.isBlank())
                    .sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote).toList();
            pattern = needles.isEmpty() ? null : Pattern.compile("\\[private-\\d+\\]|(?<![\\p{L}\\p{N}_])(?:"
                    + String.join("|", needles) + ")(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }

        String apply(String source) {
            if (pattern == null) return source;
            return pattern.matcher(source).replaceAll(m -> Matcher.quoteReplacement(
                    m.group().matches("\\[private-\\d+\\]") ? m.group()
                            : values.getOrDefault(m.group().toLowerCase(Locale.ROOT), m.group())));
        }
    }
}
