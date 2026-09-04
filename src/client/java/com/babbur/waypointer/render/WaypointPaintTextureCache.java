package com.babbur.waypointer.render;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.WaypointPaint;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class WaypointPaintTextureCache {

    static final int ATLAS_WIDTH = WaypointPaint.SIZE * 4;
    static final int ATLAS_HEIGHT = WaypointPaint.SIZE * 3;
    private static final int MAX_TEXTURES = 64;
    public static final int MAX_RETAINED_ACTIVE_TEXTURES = 256;
    private static int activeCapacity = MAX_TEXTURES;
    private static final Set<WaypointPaint> RETAINED_PAINTS = new LinkedHashSet<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Map<WaypointPaint, Entry> CACHE =
            new LinkedHashMap<>(16, 0.75f, true);

    public record Entry(Identifier id, RenderType throughWalls, RenderType depthTested) {}

    private WaypointPaintTextureCache() {}

    public static int reserveForActivePaints(Iterable<WaypointPaint> paints) {
        int overflow = selectRetainedPaints(paints, RETAINED_PAINTS);
        activeCapacity = Math.max(MAX_TEXTURES, RETAINED_PAINTS.size());
        evictOldTextures();
        return overflow;
    }

    public static void resetRetainedReservation() {
        RETAINED_PAINTS.clear();
        activeCapacity = MAX_TEXTURES;
        evictOldTextures();
    }

    static boolean isRetained(WaypointPaint paint) {
        return RETAINED_PAINTS.contains(paint);
    }

    static Entry getRetained(WaypointPaint paint) {
        return isRetained(paint) ? get(paint) : null;
    }

    static int selectRetainedPaints(Iterable<WaypointPaint> paints, Set<WaypointPaint> selected) {
        selected.clear();
        int overflow = 0;
        if (paints == null) return 0;
        for (WaypointPaint paint : paints) {
            if (paint == null || selected.contains(paint)) continue;
            if (selected.size() < MAX_RETAINED_ACTIVE_TEXTURES) selected.add(paint);
            else overflow++;
        }
        return overflow;
    }

    public static void clear() {
        List<Entry> entries = List.copyOf(CACHE.values());
        CACHE.clear();
        RETAINED_PAINTS.clear();
        activeCapacity = MAX_TEXTURES;
        for (Entry entry : entries) releaseEntry(entry);
    }

    static Entry get(WaypointPaint paint) {
        Entry cached = CACHE.get(paint);
        if (cached != null) return cached;

        long sequence = SEQUENCE.incrementAndGet();
        Identifier id = Identifier.fromNamespaceAndPath(
                Waypointer.MOD_ID, "waypoint_paint_" + sequence);
        DynamicTexture texture = new DynamicTexture(
                () -> "waypointer_paint_" + sequence,
                ATLAS_WIDTH, ATLAS_HEIGHT, false);
        var textureManager = Minecraft.getInstance().getTextureManager();
        textureManager.register(id, texture);
        boolean created = false;
        try {
            bake(texture, paint);

            Entry entry = new Entry(
                    id,
                    WaypointerRenderPipelines.paintedQuads(id, false),
                    WaypointerRenderPipelines.paintedQuads(id, true));
            CACHE.put(paint, entry);
            com.babbur.waypointer.render.gpu.OverlayRenderer.onPaintTextureCreated(entry);
            evictOldTextures();
            created = true;
            return entry;
        } finally {
            if (!created) {
                CACHE.remove(paint);
                textureManager.release(id);
            }
        }
    }

    private static void bake(DynamicTexture texture, WaypointPaint paint) {
        NativeImage image = texture.getPixels();
        if (image == null) return;
        for (int y = 0; y < ATLAS_HEIGHT; y++) {
            for (int x = 0; x < ATLAS_WIDTH; x++) {
                image.setPixelABGR(x, y, 0);
            }
        }
        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            for (int y = 0; y < WaypointPaint.SIZE; y++) {
                for (int x = 0; x < WaypointPaint.SIZE; x++) {
                    image.setPixelABGR(face.atlasX() + x, face.atlasY() + y,
                            packAbgr(paint.color(face, x, y)));
                }
            }
        }
        texture.upload();
    }

    private static int packAbgr(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    /** Selects the oldest unreserved entry, or null when every entry is reserved. */
    static <K, V> Map.Entry<K, V> selectEvictionEntry(
            Map<K, V> entries, Set<K> reserved) {
        for (Map.Entry<K, V> entry : entries.entrySet()) {
            if (!reserved.contains(entry.getKey())) return entry;
        }
        return null;
    }

    private static void evictOldTextures() {
        while (CACHE.size() > activeCapacity) {
            Map.Entry<WaypointPaint, Entry> eviction =
                    selectEvictionEntry(CACHE, RETAINED_PAINTS);
            if (eviction == null) return;
            Entry entry = eviction.getValue();
            CACHE.remove(eviction.getKey());
            releaseEntry(entry);
        }
    }

    private static void releaseEntry(Entry entry) {
        try {
            com.babbur.waypointer.render.gpu.OverlayRenderer.onPaintTextureEvicted(entry);
        } catch (RuntimeException | LinkageError failure) {
            Waypointer.LOGGER.warn("Could not remove a paint mesh", failure);
        }
        try {
            Minecraft.getInstance().getTextureManager().release(entry.id());
        } catch (RuntimeException | LinkageError failure) {
            Waypointer.LOGGER.warn("Could not release paint texture {}", entry.id(), failure);
        }
    }
}
