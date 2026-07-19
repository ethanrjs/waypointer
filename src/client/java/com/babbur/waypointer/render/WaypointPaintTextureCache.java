package com.babbur.waypointer.render;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.WaypointPaint;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Builds and caches the tiny 64x48 T-atlas used by painted waypoint boxes. */
final class WaypointPaintTextureCache {

    static final int ATLAS_WIDTH = WaypointPaint.SIZE * 4;
    static final int ATLAS_HEIGHT = WaypointPaint.SIZE * 3;
    private static final int MAX_TEXTURES = 64;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Map<WaypointPaint, Entry> CACHE =
            new LinkedHashMap<>(16, 0.75f, true);

    record Entry(Identifier id, RenderType throughWalls, RenderType depthTested) {}

    private WaypointPaintTextureCache() {}

    static Entry get(WaypointPaint paint) {
        Entry cached = CACHE.get(paint);
        if (cached != null) return cached;

        long sequence = SEQUENCE.incrementAndGet();
        Identifier id = Identifier.fromNamespaceAndPath(
                Waypointer.MOD_ID, "waypoint_paint_" + sequence);
        DynamicTexture texture = new DynamicTexture(
                () -> "waypointer_paint_" + sequence,
                ATLAS_WIDTH, ATLAS_HEIGHT, false);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        bake(texture, paint);

        Entry entry = new Entry(
                id,
                WaypointerRenderPipelines.paintedQuads(id, false),
                WaypointerRenderPipelines.paintedQuads(id, true));
        CACHE.put(paint, entry);
        evictOldTextures();
        return entry;
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

    private static void evictOldTextures() {
        Minecraft minecraft = Minecraft.getInstance();
        Iterator<Entry> entries = CACHE.values().iterator();
        while (CACHE.size() > MAX_TEXTURES && entries.hasNext()) {
            Entry oldest = entries.next();
            entries.remove();
            minecraft.getTextureManager().release(oldest.id());
        }
    }
}
