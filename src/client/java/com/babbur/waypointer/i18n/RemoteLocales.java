package com.babbur.waypointer.i18n;

import com.babbur.waypointer.Waypointer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class RemoteLocales {
    private static final RemoteLocaleManifest MANIFEST;
    private static final RemoteLocaleCache CACHE;
    private static final RemoteLocaleDownloader DOWNLOADER = new RemoteLocaleDownloader();
    private static final ConcurrentHashMap<String, byte[]> OVERLAYS = new ConcurrentHashMap<>();
    private static final Set<String> CACHE_CHECKED = ConcurrentHashMap.newKeySet();
    private static final Set<String> RELOAD_REQUESTED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Long> RETRY_AFTER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<byte[]>> IN_FLIGHT =
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
        if (!isSupported(locale) || overlay(locale) != null) return;

        long now = System.nanoTime();
        Long retryAfter = RETRY_AFTER.get(locale);
        if (retryAfter != null) {
            if (now < retryAfter) return;
            RETRY_AFTER.remove(locale, retryAfter);
        }

        RemoteLocaleManifest.Entry entry = MANIFEST.entry(locale);
        IN_FLIGHT.computeIfAbsent(locale, ignored -> DOWNLOADER.download(MANIFEST.uri(locale)))
                .thenApply(bytes -> {
                    try {
                        CACHE.store(MANIFEST.commit(), locale, entry, bytes);
                        return bytes;
                    } catch (IOException failure) {
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                })
                .whenComplete((bytes, failure) -> {
                    IN_FLIGHT.remove(locale);
                    if (failure != null) {
                        RETRY_AFTER.put(locale, System.nanoTime() + RETRY_DELAY_NANOS);
                        Waypointer.LOGGER.debug("Could not download the selected language file; English remains active", failure);
                        return;
                    }
                    RETRY_AFTER.remove(locale);
                    OVERLAYS.put(locale, bytes);
                    String token = locale + ":" + entry.sha256();
                    if (!RELOAD_REQUESTED.add(token)) return;
                    minecraft.execute(() -> {
                        if (!locale.equals(minecraft.getLanguageManager().getSelected())) return;
                        minecraft.reloadResourcePacks().exceptionally(reloadFailure -> {
                            Waypointer.LOGGER.debug("Could not reload the downloaded language file", reloadFailure);
                            return null;
                        });
                    });
                });
    }

    private static boolean isEnabled() {
        return MANIFEST != null && CACHE != null && MANIFEST.enabled();
    }

    private static boolean isSupported(String locale) {
        return isEnabled() && locale != null && !"en_us".equals(locale) && MANIFEST.entry(locale) != null;
    }
}
