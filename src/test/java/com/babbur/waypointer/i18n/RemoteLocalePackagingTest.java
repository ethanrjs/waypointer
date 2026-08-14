package com.babbur.waypointer.i18n;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteLocalePackagingTest {
    private static final Path BUNDLED = Path.of(
            "src", "main", "resources", "assets", "waypointer", "lang");
    private static final Path REMOTE = Path.of("translations", "lang");
    private static final Path GENERATED_MANIFEST = Path.of(
            "build", "generated", "remote-locale-resources", "assets", "waypointer", "i18n", "remote-locales.json");

    @Test
    void onlyEnglishIsInThePackagedSourceDirectory() throws Exception {
        try (Stream<Path> files = Files.list(BUNDLED)) {
            assertEquals(Set.of("en_us.json"), files
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet()));
        }
    }

    @Test
    void generatedManifestMatchesEveryRemoteCatalog() throws Exception {
        JsonObject manifest = JsonParser.parseString(Files.readString(GENERATED_MANIFEST)).getAsJsonObject();
        assertEquals(1, manifest.get("schema").getAsInt());
        assertEquals("ethanrjs/waypointer", manifest.get("repository").getAsString());
        assertEquals("translations/lang/{locale}.json", manifest.get("pathTemplate").getAsString());
        if (manifest.get("enabled").getAsBoolean()) {
            assertTrue(manifest.get("commit").getAsString().matches("[0-9a-f]{40}"));
        } else {
            assertEquals("", manifest.get("commit").getAsString(),
                    "a disabled manifest must not claim a trusted commit");
        }

        JsonObject entries = manifest.getAsJsonObject("locales");
        Set<String> files;
        try (Stream<Path> paths = Files.list(REMOTE)) {
            files = paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(files, entries.keySet());
        for (String locale : files) {
            Path path = REMOTE.resolve(locale + ".json");
            byte[] bytes = Files.readAllBytes(path);
            JsonObject entry = entries.getAsJsonObject(locale);
            assertEquals(bytes.length, entry.get("bytes").getAsInt());
            assertEquals(
                    java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    entry.get("sha256").getAsString());
            assertTrue(locale.matches("[a-z0-9]+(?:_[a-z0-9]+)*"));
        }
    }
}
