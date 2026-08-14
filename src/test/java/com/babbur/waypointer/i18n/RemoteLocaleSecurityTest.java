package com.babbur.waypointer.i18n;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteLocaleSecurityTest {
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final URI TRUSTED_URI = URI.create(
            "https://raw.githubusercontent.com/ethanrjs/waypointer/" + COMMIT
                    + "/translations/lang/de_de.json");

    @Test
    void manifestBuildsOnlyTheCommitPinnedRawGitHubUrl() throws Exception {
        RemoteLocaleManifest manifest = manifest("de_de", "0".repeat(64), 2);
        assertEquals(
                URI.create("https://raw.githubusercontent.com/ethanrjs/waypointer/" + COMMIT
                        + "/translations/lang/de_de.json"),
                manifest.uri("de_de"));
        assertThrows(IllegalArgumentException.class, () -> manifest.uri("../en_us"));
        assertThrows(IllegalArgumentException.class, () -> manifest.uri("en_us"));
    }

    @Test
    void manifestRejectsTraversalDuplicateAndUnknownFields() {
        assertThrows(IOException.class, () -> readManifest(json("../evil", "0".repeat(64), 2)));
        assertThrows(IOException.class, () -> readManifest(json("de_de", "0".repeat(64), 2)
                .replace("\"schema\":1", "\"schema\":1,\"schema\":1")));
        assertThrows(IOException.class, () -> readManifest(json("de_de", "0".repeat(64), 2)
                .replace("\"enabled\":true", "\"enabled\":true,\"url\":\"https://evil.invalid\"")));
    }

    @Test
    void downloaderRejectsStatusAndOversizedBodies() {
        RemoteLocaleDownloader badStatus = new RemoteLocaleDownloader(
                ignored -> java.util.concurrent.CompletableFuture.completedFuture(
                        new RemoteLocaleDownloader.Response(302, new byte[]{1})));
        assertThrows(CompletionException.class, () -> badStatus.download(TRUSTED_URI).join());

        byte[] oversized = new byte[RemoteLocaleDownloader.MAX_BYTES + 1];
        RemoteLocaleDownloader tooLarge = new RemoteLocaleDownloader(
                ignored -> java.util.concurrent.CompletableFuture.completedFuture(
                        new RemoteLocaleDownloader.Response(200, oversized)));
        assertThrows(CompletionException.class, () -> tooLarge.download(TRUSTED_URI).join());
    }

    @Test
    void boundedSubscriberCancelsBeforeBufferingTooMuch() {
        RemoteLocaleDownloader.BoundedBodySubscriber subscriber =
                new RemoteLocaleDownloader.BoundedBodySubscriber(3);
        TestSubscription subscription = new TestSubscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3, 4})));
        assertTrue(subscription.cancelled);
        assertThrows(CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join());
    }

    @Test
    void catalogValidationAcceptsSparseOverlaysAndRejectsUnsafeContent() throws Exception {
        TranslationCatalogValidator validator = new TranslationCatalogValidator();
        byte[] valid = bytes("{\"waypointer.chat.import.size\":\"Größe: %1$s Zeichen\"}");
        validator.validate(valid, entry(valid), "de_de");

        byte[] empty = bytes("{}");
        validator.validate(empty, entry(empty), "de_de");

        byte[] unknown = bytes("{\"evil.key\":\"value\"}");
        assertThrows(IOException.class, () -> validator.validate(unknown, entry(unknown), "de_de"));

        byte[] blank = bytes("{\"waypointer.export.fit.too_long\":\"  \"}");
        assertThrows(IOException.class, () -> validator.validate(blank, entry(blank), "de_de"));

        byte[] changedPlaceholder = bytes("{\"waypointer.chat.import.size\":\"Größe: %2$s Zeichen\"}");
        assertThrows(IOException.class,
                () -> validator.validate(changedPlaceholder, entry(changedPlaceholder), "de_de"));

        byte[] duplicate = bytes("{\"waypointer.export.fit.too_long\":\"A\","
                + "\"waypointer.export.fit.too_long\":\"B\"}");
        assertThrows(IOException.class, () -> validator.validate(duplicate, entry(duplicate), "de_de"));
    }

    @Test
    void downloaderReturnsExactAcceptedBytes() {
        byte[] expected = {1, 2, 3};
        RemoteLocaleDownloader downloader = new RemoteLocaleDownloader(
                ignored -> java.util.concurrent.CompletableFuture.completedFuture(
                        new RemoteLocaleDownloader.Response(200, expected)));
        assertArrayEquals(expected, downloader.download(TRUSTED_URI).join());
        assertThrows(IllegalArgumentException.class,
                () -> downloader.download(URI.create("https://evil.invalid/" + COMMIT + "/de_de.json")));
    }

    @Test
    void cacheRevalidatesBytesBeforeEveryUse(@TempDir Path directory) throws Exception {
        byte[] catalog = bytes("{\"waypointer.export.fit.too_long\":\"Zu lang für den Chat\"}");
        RemoteLocaleManifest.Entry entry = entry(catalog);
        RemoteLocaleCache cache = new RemoteLocaleCache(directory, new TranslationCatalogValidator());
        cache.store(COMMIT, "de_de", entry, catalog);
        assertArrayEquals(catalog, cache.load(COMMIT, "de_de", entry).orElseThrow());

        Files.write(directory.resolve(COMMIT).resolve("de_de.json"), bytes("tampered"));
        assertFalse(cache.load(COMMIT, "de_de", entry).isPresent());
    }

    private static RemoteLocaleManifest manifest(String locale, String digest, int bytes) throws IOException {
        return readManifest(json(locale, digest, bytes));
    }

    private static RemoteLocaleManifest readManifest(String text) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            return RemoteLocaleManifest.read(reader);
        }
    }

    private static String json(String locale, String digest, int bytes) {
        return "{\"schema\":1,\"enabled\":true,\"repository\":\"ethanrjs/waypointer\","
                + "\"commit\":\"" + COMMIT + "\","
                + "\"pathTemplate\":\"translations/lang/{locale}.json\","
                + "\"locales\":{\"" + locale + "\":{\"sha256\":\"" + digest
                + "\",\"bytes\":" + bytes + "}}}";
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static RemoteLocaleManifest.Entry entry(byte[] value) throws Exception {
        return new RemoteLocaleManifest.Entry(
                java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)),
                value.length);
    }

    private static final class TestSubscription implements Flow.Subscription {
        private boolean cancelled;
        @Override public void request(long count) {}
        @Override public void cancel() { cancelled = true; }
    }
}
