package com.babbur.waypointer.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallTokenStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void tokensAreStablePerRouteDistinctAcrossRoutesAndSurviveReload() {
        Path file = temporaryDirectory.resolve("publisher/install-token.json");
        InstallTokenStore store = new InstallTokenStore(file);

        String first = store.tokenFor("route-aaaaaaaaaaaaaaaaaa");
        String second = store.tokenFor("route-bbbbbbbbbbbbbbbbbb");

        assertTrue(first.matches("[0-9a-f]{64}"),
                "token must satisfy the server's [A-Za-z0-9_-]{16,128} shape");
        assertEquals(first, store.tokenFor("route-aaaaaaaaaaaaaaaaaa"));
        assertNotEquals(first, second,
                "tokens must not link a device across routes");
        assertEquals(first,
                new InstallTokenStore(file).tokenFor("route-aaaaaaaaaaaaaaaaaa"),
                "the device secret must persist across sessions");
    }

    @Test
    void blankRouteIdsMintNothing() {
        InstallTokenStore store = new InstallTokenStore(
                temporaryDirectory.resolve("install-token.json"));
        assertNull(store.tokenFor(null));
        assertNull(store.tokenFor("  "));
    }
}
