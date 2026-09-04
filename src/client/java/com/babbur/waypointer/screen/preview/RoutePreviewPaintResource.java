package com.babbur.waypointer.screen.preview;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.WaypointPaint;
import com.babbur.waypointer.render.WaypointerRenderPipelines;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Active preview route's paint atlas; disposes of old atlases after 2 frames. */
final class RoutePreviewPaintResource {

    static final int PADDING = 1;
    private static final int CELL_SIZE = WaypointPaint.SIZE + PADDING * 2;
    static final int ATLAS_WIDTH = CELL_SIZE * 4;
    static final int ATLAS_HEIGHT = CELL_SIZE * 3;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private final List<Retired> retired = new ArrayList<>();
    private Entry active;
    private long frame;

    record Entry(String routeId, WaypointPaint paint, Identifier id,
                 RenderType throughWalls, RenderType depthTested) {}
    private record Retired(Identifier id, long releaseFrame) {}

    RoutePreviewPaintResource() {}

    synchronized Entry activate(String routeId, WaypointPaint paint) {
        if (paint == null) {
            retireActive();
            return null;
        }
        if (active != null && active.paint().equals(paint)) {
            return active;
        }
        retireActive();

        long sequence = SEQUENCE.incrementAndGet();
        Identifier id = Identifier.fromNamespaceAndPath(
                Waypointer.MOD_ID, "route_preview_paint_" + sequence);
        DynamicTexture texture = new DynamicTexture(
                () -> "waypointer_route_preview_paint_" + sequence,
                ATLAS_WIDTH, ATLAS_HEIGHT, false);
        var textureManager = Minecraft.getInstance().getTextureManager();
        boolean registered = false;
        boolean complete = false;
        try {
            textureManager.register(id, texture);
            registered = true;
            bake(texture, paint);
            active = new Entry(routeId, paint, id,
                    WaypointerRenderPipelines.paintedQuads(id, false),
                    WaypointerRenderPipelines.paintedQuads(id, true));
            complete = true;
            return active;
        } finally {
            if (!complete) {
                if (registered) textureManager.release(id);
                else texture.close();
            }
        }
    }

    synchronized void advanceFrame() {
        frame++;
        Iterator<Retired> iterator = retired.iterator();
        while (iterator.hasNext()) {
            Retired retired = iterator.next();
            if (retired.releaseFrame() > frame) continue;
            Minecraft.getInstance().getTextureManager().release(retired.id());
            iterator.remove();
        }
    }

    synchronized void close() {
        if (active != null) {
            Minecraft.getInstance().getTextureManager().release(active.id());
            active = null;
        }
        for (Retired entry : retired) {
            Minecraft.getInstance().getTextureManager().release(entry.id());
        }
        retired.clear();
    }

    private void retireActive() {
        if (active == null) return;
        retired.add(new Retired(active.id(), frame + 2));
        active = null;
    }

    private static void bake(DynamicTexture texture, WaypointPaint paint) {
        NativeImage image = texture.getPixels();
        if (image == null) throw new IllegalStateException("Preview paint texture has no pixels");
        for (int y = 0; y < ATLAS_HEIGHT; y++) {
            for (int x = 0; x < ATLAS_WIDTH; x++) image.setPixelABGR(x, y, 0);
        }
        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            int faceX = (face.atlasX() / WaypointPaint.SIZE) * CELL_SIZE + PADDING;
            int faceY = (face.atlasY() / WaypointPaint.SIZE) * CELL_SIZE + PADDING;
            for (int y = 0; y < WaypointPaint.SIZE; y++) {
                for (int x = 0; x < WaypointPaint.SIZE; x++) {
                    image.setPixelABGR(faceX + x, faceY + y,
                            packAbgr(paint.color(face, x, y)));
                }
            }
            for (int i = 0; i < WaypointPaint.SIZE; i++) {
                image.setPixelABGR(faceX - 1, faceY + i,
                        packAbgr(paint.color(face, 0, i)));
                image.setPixelABGR(faceX + WaypointPaint.SIZE, faceY + i,
                        packAbgr(paint.color(face, WaypointPaint.SIZE - 1, i)));
                image.setPixelABGR(faceX + i, faceY - 1,
                        packAbgr(paint.color(face, i, 0)));
                image.setPixelABGR(faceX + i, faceY + WaypointPaint.SIZE,
                        packAbgr(paint.color(face, i, WaypointPaint.SIZE - 1)));
            }
            image.setPixelABGR(faceX - 1, faceY - 1, packAbgr(paint.color(face, 0, 0)));
            image.setPixelABGR(faceX + WaypointPaint.SIZE, faceY - 1,
                    packAbgr(paint.color(face, WaypointPaint.SIZE - 1, 0)));
            image.setPixelABGR(faceX - 1, faceY + WaypointPaint.SIZE,
                    packAbgr(paint.color(face, 0, WaypointPaint.SIZE - 1)));
            image.setPixelABGR(faceX + WaypointPaint.SIZE, faceY + WaypointPaint.SIZE,
                    packAbgr(paint.color(face, WaypointPaint.SIZE - 1,
                            WaypointPaint.SIZE - 1)));
        }
        texture.upload();
    }

    private static int packAbgr(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }
}
