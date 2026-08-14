package com.babbur.waypointer.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogInstallRegistryTest {
    private static final String FIRST = "Abcdefghijklmnopqrstuv";
    private static final String SECOND = "Zyxwvutsrqponmlkjihgfe";

    @TempDir
    Path temporaryDirectory;

    @Test
    void successfulInstallIdSurvivesRestartAndDoesNotDuplicate() throws Exception {
        Path file = temporaryDirectory.resolve("catalog-installs.json");
        CatalogInstallRegistry registry = new CatalogInstallRegistry(file);

        registry.record(FIRST);
        registry.record(FIRST);
        registry.record(SECOND);

        assertEquals(Set.of(FIRST, SECOND),
                new CatalogInstallRegistry(file).load());
        String json = Files.readString(file);
        assertEquals(1, occurrences(json, FIRST));
        assertEquals(1, occurrences(json, SECOND));
    }

    @Test
    void rejectsMalformedAndOversizedRegistries() throws Exception {
        Path file = temporaryDirectory.resolve("bad.json");
        Files.writeString(file, "{\"schema\":1,\"installedRouteIds\":[\"bad\"]}");
        assertThrows(IllegalStateException.class,
                () -> new CatalogInstallRegistry(file).load());

        Files.writeString(file, "x".repeat(128 * 1024 + 1));
        assertThrows(IllegalStateException.class,
                () -> new CatalogInstallRegistry(file).load());
    }

    private static int occurrences(String value, String target) {
        int count = 0;
        for (int index = value.indexOf(target); index >= 0;
             index = value.indexOf(target, index + target.length())) {
            count++;
        }
        return count;
    }
}
