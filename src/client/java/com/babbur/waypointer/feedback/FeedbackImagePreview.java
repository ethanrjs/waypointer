package com.babbur.waypointer.feedback;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Screen-owned thumbnail. Call render and close on the client thread; recreate after close. */
public final class FeedbackImagePreview implements AutoCloseable {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private final FeedbackImage image;
    private final Identifier id = Identifier.fromNamespaceAndPath("waypointer", "feedback_preview_" + SEQUENCE.incrementAndGet());
    private TextureManager textures;
    private boolean closed;

    /** Does not allocate a GPU texture until the first nonempty render. */
    public FeedbackImagePreview(FeedbackImage image) {
        this.image = Objects.requireNonNull(image);
    }

    /** Draws the full thumbnail centered inside the bounds, with its aspect ratio preserved. */
    public void render(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        if (closed || width <= 0 || height <= 0) return;
        graphics.fill(x, y, x + width, y + height, 0xFF25282D);
        graphics.outline(x, y, width, height, 0xFF565B63);
        if (width <= 2 || height <= 2) return;
        if (textures == null) register();
        double scale = Math.min((double) (width - 2) / image.thumbnailWidth(), (double) (height - 2) / image.thumbnailHeight());
        int drawWidth = Math.max(1, (int) Math.round(image.thumbnailWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.round(image.thumbnailHeight() * scale));
        int left = x + (width - drawWidth) / 2;
        int top = y + (height - drawHeight) / 2;
        graphics.blit(id, left, top, left + drawWidth, top + drawHeight, 0.0f, 1.0f, 0.0f, 1.0f);
    }

    private void register() {
        NativeImage pixels = new NativeImage(image.thumbnailWidth(), image.thumbnailHeight(), false);
        DynamicTexture texture = null;
        try {
            int[] argb = image.thumbnailPixels();
            for (int y = 0; y < image.thumbnailHeight(); y++) {
                for (int x = 0; x < image.thumbnailWidth(); x++) {
                    pixels.setPixel(x, y, argb[y * image.thumbnailWidth() + x]);
                }
            }
            texture = new DynamicTexture(() -> "waypointer_feedback_preview", pixels);
            TextureManager manager = Minecraft.getInstance().getTextureManager();
            manager.register(id, texture);
            textures = manager;
        } catch (RuntimeException | Error failure) {
            if (texture != null) texture.close();
            else pixels.close();
            throw failure;
        }
    }

    /** Releases the registered texture and its native pixels once. Safe before first render. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (textures != null) {
            textures.release(id);
            textures = null;
        }
    }
}
