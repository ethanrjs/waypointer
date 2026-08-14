package com.babbur.waypointer.i18n;

import com.babbur.waypointer.screen.settings.PerfScenarios;
import com.babbur.waypointer.screen.settings.Setting;
import com.babbur.waypointer.screen.settings.SettingsCatalog;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract for Waypointer's language resources.
 *
 * <p>{@code en_us.json} is the canonical catalog. Every remote locale must
 * contain exactly the canonical key set with formatting-compatible values.
 */
class TranslationCatalogTest {

    private static final Path ENGLISH_FILE = Path.of(
            "src", "main", "resources", "assets", "waypointer", "lang");
    private static final Path REMOTE_LANG_DIR = Path.of("translations", "lang");
    private static final Path MOJANG_LOCALE_MANIFEST = Path.of(
            "src", "test", "resources", "mojang_asset_locales.txt");
    private static final List<Path> JAVA_SOURCE_ROOTS = List.of(
            Path.of("src", "main", "java"),
            Path.of("src", "client", "java"));

    private static final Pattern TRANSLATABLE_LITERAL = Pattern.compile(
            "\\bComponent\\.translatable(?:WithFallback)?\\s*\\(\\s*"
                    + "\"((?:\\\\.|[^\"\\\\])*)\"(?=\\s*[,)])");
    private static final Pattern FORMAT_TOKEN = Pattern.compile(
            "%(?:(\\d+)\\$)?([A-Za-z%])");

    @Test
    void everySupportedLocaleMatchesTheCanonicalCatalog() throws IOException {
        Map<String, String> english = readCatalog(ENGLISH_FILE.resolve("en_us.json"));
        assertFalse(english.isEmpty(), "en_us.json must not be empty");

        for (Path localeFile : localeFiles()) {
            Map<String, String> locale = readCatalog(localeFile);
            String localeName = localeName(localeFile);

            assertEquals(english.keySet(), locale.keySet(),
                    localeName + " must have exactly the canonical translation keys");
            for (Map.Entry<String, String> entry : english.entrySet()) {
                String key = entry.getKey();
                String value = locale.get(key);
                assertFalse(value.isBlank(), localeName + " has a blank value for " + key);
                assertEquals(
                        placeholderSignature(entry.getValue(), "en_us", key),
                        placeholderSignature(value, localeName, key),
                        localeName + " changes the placeholder arguments for " + key);
            }
        }
    }

    @Test
    void languageDirectoryMatchesTheMojangLocaleManifest() throws IOException {
        List<String> manifestEntries = Files.readAllLines(
                        MOJANG_LOCALE_MANIFEST, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        Set<String> mojangLocales = new LinkedHashSet<>(manifestEntries);

        assertEquals(manifestEntries.size(), mojangLocales.size(),
                "Mojang locale manifest contains duplicate codes");
        assertEquals(142, mojangLocales.size(),
                "Mojang 26.1.2/26.2 shared locale manifest size changed");
        assertEquals(new ArrayList<>(new TreeSet<>(mojangLocales)), manifestEntries,
                "Mojang locale manifest must stay sorted");
        assertTrue(mojangLocales.contains("fil_ph"), "manifest must include fil_ph");
        assertTrue(mojangLocales.contains("tl_ph"), "manifest must include tl_ph");
        assertFalse(mojangLocales.contains("en_us"),
                "en_us is bundled separately from Mojang asset locales");

        Set<String> expected = new TreeSet<>(mojangLocales);
        expected.add("en_us");
        Set<String> actual = localeFiles().stream()
                .map(TranslationCatalogTest::localeName)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        assertEquals(expected, actual,
                "language directory must contain the 142 Mojang asset locales plus en_us");
    }

    @Test
    void canonicalKeysUseTheWaypointerNamespace() throws IOException {
        for (String key : readCatalog(ENGLISH_FILE.resolve("en_us.json")).keySet()) {
            assertTrue(isWaypointerKey(key), "translation key is outside Waypointer's namespace: " + key);
        }
    }

    @Test
    void tooLongForChatMessageDoesNotSuggestDiscord() throws IOException {
        String key = "waypointer.export.fit.too_long";
        String englishValue = readCatalog(ENGLISH_FILE.resolve("en_us.json")).get(key);
        assertEquals("Too long for chat", englishValue);
        for (Path localeFile : localeFiles()) {
            String value = readCatalog(localeFile).getOrDefault(key, englishValue);
            assertFalse(value.toLowerCase(java.util.Locale.ROOT).contains("discord"),
                    localeName(localeFile) + " still suggests Discord for " + key);
        }
    }

    @Test
    void literalComponentTranslationKeysExistInTheCanonicalCatalog() throws IOException {
        Set<String> englishKeys = readCatalog(ENGLISH_FILE.resolve("en_us.json")).keySet();
        Map<String, Set<String>> references = literalTranslationReferences();

        Set<String> missing = new TreeSet<>(references.keySet());
        missing.removeAll(englishKeys);
        assertTrue(missing.isEmpty(), () -> "Component.translatable references missing from en_us.json: "
                + describeReferences(missing, references));
    }

    @Test
    void everyVisibleSettingHasCanonicalTranslationKeys() throws IOException {
        Set<String> englishKeys = readCatalog(ENGLISH_FILE.resolve("en_us.json")).keySet();
        Set<String> required = new LinkedHashSet<>();

        for (SettingsCatalog.Category category : SettingsCatalog.categories()) {
            required.add(SettingsCatalog.categoryTranslationKey(category));
            for (SettingsCatalog.Group group : category.groups()) {
                String groupKey = SettingsCatalog.groupTranslationKey(category, group);
                if (groupKey != null) required.add(groupKey);

                for (Setting setting : group.settings()) {
                    if (setting.kind() == Setting.Kind.HIDDEN) continue;
                    required.add(setting.labelTranslationKey());
                    if (!setting.tooltip().isBlank()) {
                        required.add(setting.tooltipTranslationKey());
                    }
                    if (!setting.colorPickerTitle().isBlank()) {
                        required.add(setting.colorPickerTitleTranslationKey());
                    }
                    if (!setting.colorSwatchTooltip().isBlank()) {
                        required.add(setting.colorSwatchTooltipTranslationKey());
                    }
                    for (int i = 0; i < setting.enumOptions().size(); i++) {
                        required.add(setting.enumOptionTranslationKey(i));
                    }
                }
            }
        }

        for (Setting.Impact impact : Setting.Impact.values()) {
            required.add(impact.wordTranslationKey());
            required.add(impact.chipTranslationKey());
        }
        for (PerfScenarios.Scenario scenario : PerfScenarios.all()) {
            required.add(PerfScenarios.labelTranslationKey(scenario));
            required.add(PerfScenarios.descriptionTranslationKey(scenario));
        }

        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(englishKeys);
        assertTrue(missing.isEmpty(), () -> "visible settings translations missing from en_us.json: " + missing);
    }

    @Test
    void placeholderSignatureAllowsReorderingButRejectsChangedArguments() {
        assertEquals(
                placeholderSignature("%s then %s", "test", "key"),
                placeholderSignature("%2$s then %1$s", "test", "key"));
        assertEquals(List.of(1, 1),
                placeholderSignature("%1$s / %1$s", "test", "key"));
        assertEquals(List.of(),
                placeholderSignature("100%% complete", "test", "key"));
        assertThrows(AssertionError.class,
                () -> placeholderSignature("unsupported %d", "test", "key"));
    }

    private static List<Path> localeFiles() throws IOException {
        assertTrue(Files.isDirectory(REMOTE_LANG_DIR), "missing remote language directory: " + REMOTE_LANG_DIR);
        try (Stream<Path> files = Files.list(REMOTE_LANG_DIR)) {
            List<Path> remote = files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
            List<Path> all = new ArrayList<>(remote.size() + 1);
            all.add(ENGLISH_FILE.resolve("en_us.json"));
            all.addAll(remote);
            return List.copyOf(all);
        }
    }

    private static Map<String, String> readCatalog(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "missing language catalog: " + path);
        Map<String, String> entries = new LinkedHashMap<>();

        try (BufferedReader input = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             JsonReader json = new JsonReader(input)) {
            assertEquals(JsonToken.BEGIN_OBJECT, json.peek(), path + " must contain one JSON object");
            json.beginObject();
            while (json.hasNext()) {
                String key = json.nextName();
                assertFalse(entries.containsKey(key), path + " contains duplicate key " + key);
                assertEquals(JsonToken.STRING, json.peek(), path + " value for " + key + " must be a string");
                entries.put(key, json.nextString());
            }
            json.endObject();
            assertEquals(JsonToken.END_DOCUMENT, json.peek(), path + " has trailing JSON content");
        }
        return entries;
    }

    private static boolean isWaypointerKey(String key) {
        return key.startsWith("waypointer.")
                || key.startsWith("key.waypointer.")
                || key.startsWith("key.category.waypointer.");
    }

    /**
     * Returns the argument indexes used by Minecraft's supported {@code %s}
     * and {@code %1$s} placeholders. Sorting deliberately permits translators
     * to reorder positional arguments while retaining the same signature.
     */
    private static List<Integer> placeholderSignature(String value, String locale, String key) {
        List<Integer> arguments = new ArrayList<>();
        Matcher matcher = FORMAT_TOKEN.matcher(value);
        int nextImplicitIndex = 1;
        while (matcher.find()) {
            String conversion = matcher.group(2);
            if ("%".equals(conversion)) continue;
            if (!"s".equals(conversion)) {
                fail(locale + " uses unsupported placeholder %" + conversion + " for " + key
                        + "; Minecraft translation components support %s, %1$s, and %%");
            }

            int argumentIndex = matcher.group(1) == null
                    ? nextImplicitIndex++
                    : Integer.parseInt(matcher.group(1));
            assertTrue(argumentIndex > 0,
                    locale + " uses a zero-indexed placeholder for " + key);
            arguments.add(argumentIndex);
        }
        Collections.sort(arguments);
        return List.copyOf(arguments);
    }

    private static Map<String, Set<String>> literalTranslationReferences() throws IOException {
        Map<String, Set<String>> references = new LinkedHashMap<>();
        for (Path sourceRoot : JAVA_SOURCE_ROOTS) {
            if (!Files.isDirectory(sourceRoot)) continue;
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                for (Path path : files.filter(file -> file.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(path, StandardCharsets.UTF_8);
                    Matcher matcher = TRANSLATABLE_LITERAL.matcher(source);
                    while (matcher.find()) {
                        String key = matcher.group(1);
                        // Vanilla keys such as gui.cancel are owned by Minecraft,
                        // not Waypointer's catalog.
                        if (!isWaypointerKey(key)) continue;
                        references.computeIfAbsent(key, ignored -> new TreeSet<>())
                                .add(path.toString());
                    }
                }
            }
        }
        return references;
    }

    private static String describeReferences(
            Set<String> missing, Map<String, Set<String>> references) {
        List<String> details = new ArrayList<>();
        for (String key : missing) {
            details.add(key + " in " + references.get(key));
        }
        return String.join("; ", details);
    }

    private static String localeName(Path localeFile) {
        String name = localeFile.getFileName().toString();
        return name.substring(0, name.length() - ".json".length());
    }
}
