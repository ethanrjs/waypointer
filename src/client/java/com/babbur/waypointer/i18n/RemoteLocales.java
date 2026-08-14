package com.babbur.waypointer.i18n;

import com.babbur.waypointer.Waypointer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class RemoteLocales {
    private static final RemoteLocaleManifest MANIFEST;
    private static final RemoteLocaleCache CACHE;
    private static final RemoteLocaleDownloader DOWNLOADER = new RemoteLocaleDownloader();
    private static final ConcurrentHashMap<String, byte[]> OVERLAYS = new ConcurrentHashMap<>();
    private static final Set<String> CACHE_CHECKED = ConcurrentHashMap.newKeySet();
    private static final RemoteLocaleReloadState RELOADS = new RemoteLocaleReloadState();
    private static final ConcurrentHashMap<String, Long> RETRY_AFTER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CompletableFuture<byte[]>> IN_FLIGHT =
            new ConcurrentHashMap<>();
    private static final long RETRY_DELAY_NANOS = TimeUnit.SECONDS.toNanos(60);

    static {
        RemoteLocaleManifest manifest = null;
        RemoteLocaleCache cache = null;
        try {
            manifest = RemoteLocaleManifest.load();
            TranslationCatalogValidator validator = new TranslationCatalogValidator();
            Path cacheRoot = FabricLoader.getInstance().getConfigDir()
                    .resolve(Waypointer.MOD_ID).resolve("languages");
            cache = new RemoteLocaleCache(cacheRoot, validator);
        } catch (IOException | RuntimeException failure) {
            Waypointer.LOGGER.warn("Remote language files are disabled because their trust data is invalid", failure);
        }
        MANIFEST = manifest;
        CACHE = cache;
    }

    private RemoteLocales() {}

    public static void install() {
        if (!isEnabled()) {
            Waypointer.LOGGER.debug("Remote language files are disabled for this build");
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(RemoteLocales::checkSelectedLocale);
        checkSelectedLocale(Minecraft.getInstance());
    }

    static byte[] overlay(String locale) {
        if (!isSupported(locale)) return null;
        byte[] loaded = OVERLAYS.get(locale);
        if (loaded != null) return Arrays.copyOf(loaded, loaded.length);
        if (CACHE_CHECKED.add(locale)) {
            RemoteLocaleManifest.Entry entry = MANIFEST.entry(locale);
            CACHE.load(MANIFEST.commit(), locale, entry).ifPresent(bytes -> OVERLAYS.put(locale, bytes));
        }
        loaded = OVERLAYS.get(locale);
        return loaded == null ? null : Arrays.copyOf(loaded, loaded.length);
    }

    private static void checkSelectedLocale(Minecraft minecraft) {
        if (!isEnabled() || minecraft == null || minecraft.getLanguageManager() == null) return;
        String locale = minecraft.getLanguageManager().getSelected();
        if (!isSupported(locale)) return;

        RemoteLocaleManifest.Entry entry = MANIFEST.entry(locale);
        String token = locale + ":" + entry.sha256();
        long now = System.nanoTime();
        Long retryAfter = RETRY_AFTER.get(locale);
        if (retryAfter != null) {
            if (now < retryAfter) return;
            RETRY_AFTER.remove(locale, retryAfter);
        }
        if (overlay(locale) != null) {
            requestReload(minecraft, locale, token);
            return;
        }

        SharedOperation<byte[]> shared = shareOperation(
                IN_FLIGHT, locale, () -> downloadAndCache(locale, entry));
        if (!shared.started()) return;
        CompletableFuture<byte[]> operation = shared.future();
        operation.whenComplete((bytes, failure) -> finishDownload(
                minecraft, locale, token, operation, bytes, failure));
    }

    private static CompletableFuture<byte[]> downloadAndCache(
            String locale, RemoteLocaleManifest.Entry entry) {
        return cacheDownload(DOWNLOADER.download(MANIFEST.uri(locale)), bytes ->
                CACHE.store(MANIFEST.commit(), locale, entry, bytes));
    }

    static CompletableFuture<byte[]> cacheDownload(
            CompletableFuture<byte[]> download, OverlayStore store) {
        Objects.requireNonNull(download, "download");
        Objects.requireNonNull(store, "store");
        return download.thenApply(bytes -> {
            try {
                store.store(bytes);
                return bytes;
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private static void finishDownload(
            Minecraft minecraft, String locale, String token,
            CompletableFuture<byte[]> operation, byte[] bytes, Throwable failure) {
        DownloadCompletion completion = finishDownloadState(
                IN_FLIGHT, RETRY_AFTER, OVERLAYS, locale, operation, bytes, failure,
                System.nanoTime(), RETRY_DELAY_NANOS);
        if (completion == DownloadCompletion.FAILED) {
            Waypointer.LOGGER.debug(
                    "Could not download the selected language file; English remains active",
                    failure);
        } else if (completion == DownloadCompletion.STORED) {
            requestReload(minecraft, locale, token);
        }
    }

    static DownloadCompletion finishDownloadState(
            ConcurrentHashMap<String, CompletableFuture<byte[]>> inFlight,
            ConcurrentHashMap<String, Long> retryAfter,
            ConcurrentHashMap<String, byte[]> overlays,
            String locale, CompletableFuture<byte[]> operation,
            byte[] bytes, Throwable failure, long nowNanos, long retryDelayNanos) {
        Objects.requireNonNull(inFlight, "inFlight");
        Objects.requireNonNull(retryAfter, "retryAfter");
        Objects.requireNonNull(overlays, "overlays");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(operation, "operation");
        if (inFlight.get(locale) != operation) return DownloadCompletion.STALE;
        try {
            if (failure != null) {
                retryAfter.put(locale, nowNanos + retryDelayNanos);
                return DownloadCompletion.FAILED;
            }
            retryAfter.remove(locale);
            overlays.put(locale, Objects.requireNonNull(bytes, "downloaded bytes"));
            return DownloadCompletion.STORED;
        } finally {
            inFlight.remove(locale, operation);
        }
    }

    static <T> SharedOperation<T> shareOperation(
            ConcurrentHashMap<String, CompletableFuture<T>> operations,
            String key, Supplier<CompletableFuture<T>> starter) {
        Objects.requireNonNull(operations, "operations");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(starter, "starter");
        AtomicReference<CompletableFuture<T>> created = new AtomicReference<>();
        CompletableFuture<T> operation = operations.computeIfAbsent(key, ignored -> {
            CompletableFuture<T> next = Objects.requireNonNull(
                    starter.get(), "started operation");
            created.set(next);
            return next;
        });
        return new SharedOperation<>(operation, operation == created.get());
    }

    record SharedOperation<T>(CompletableFuture<T> future, boolean started) {
    }

    private static void requestReload(Minecraft minecraft, String locale, String token) {
        if (!RELOADS.begin(token)) return;
        minecraft.execute(() -> {
            if (minecraft.getLanguageManager() == null
                    || !locale.equals(minecraft.getLanguageManager().getSelected())) {
                RELOADS.finish(token, false);
                return;
            }
            try {
                minecraft.reloadResourcePacks().whenComplete((ignored, failure) ->
                        finishReload(minecraft, locale, token, failure));
            } catch (RuntimeException failure) {
                finishReload(minecraft, locale, token, failure);
            }
        });
    }

    private static void finishReload(
            Minecraft minecraft, String locale, String token, Throwable failure) {
        String selectedLocale = minecraft == null || minecraft.getLanguageManager() == null
                ? null : minecraft.getLanguageManager().getSelected();
        ReloadCompletion completion = finishReloadState(
                RELOADS, RETRY_AFTER, locale, token, selectedLocale, failure,
                System.nanoTime(), RETRY_DELAY_NANOS);
        if (completion.retryScheduled()) {
            Waypointer.LOGGER.debug("Could not reload the downloaded language file", failure);
        }
    }

    static ReloadCompletion finishReloadState(
            RemoteLocaleReloadState reloads,
            ConcurrentHashMap<String, Long> retryAfter,
            String locale, String token, String selectedLocale, Throwable failure,
            long nowNanos, long retryDelayNanos) {
        Objects.requireNonNull(reloads, "reloads");
        Objects.requireNonNull(retryAfter, "retryAfter");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(token, "token");
        boolean stillSelected = Objects.equals(locale, selectedLocale);
        boolean applied = reloads.finishSelected(
                token, locale, selectedLocale, failure == null);
        if (stillSelected && failure != null) {
            retryAfter.put(locale, nowNanos + retryDelayNanos);
            return new ReloadCompletion(applied, true);
        }
        retryAfter.remove(locale);
        return new ReloadCompletion(applied, false);
    }

    private static boolean isEnabled() {
        return MANIFEST != null && CACHE != null && MANIFEST.enabled();
    }

    private static boolean isSupported(String locale) {
        return isEnabled() && locale != null && !"en_us".equals(locale) && MANIFEST.entry(locale) != null;
    }

    @FunctionalInterface
    interface OverlayStore {
        void store(byte[] bytes) throws IOException;
    }

    enum DownloadCompletion {
        STALE,
        FAILED,
        STORED
    }

    record ReloadCompletion(boolean applied, boolean retryScheduled) {
    }
}
