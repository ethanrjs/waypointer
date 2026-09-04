package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.WaypointPaint;
import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

/** CPU-only experiment; deliberately never constructs a DynamicTexture or uploads to the GPU. */
public final class NativeRasterBenchmark {
    private static volatile int sink;

    public static void main(String[] args) throws Exception {
        File output = new File(args[0]);
        int[] palette = WaypointPaint.defaultPalette(0x123456);
        byte[] indices = new byte[WaypointPaint.PIXEL_COUNT];
        for (var face : WaypointPaint.Face.values()) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int color = 3 + face.ordinal();
                    if (x < 3 && y < 3) color = 1;
                    if (y == 7 || (x >= 6 && x <= 9 && y > 2 && y < 12)) color = 2;
                    indices[WaypointPaint.pixelOffset(face, x, y)] = (byte) color;
                }
            }
        }
        WaypointPaint paint = new WaypointPaint(palette, indices);
        var before = new BaselineNativePaintPreview();
        var after = new WaypointPaintPreviewTexture.Raster();
        try (var nativeAfter = new NativeImage(256, 256, false)) {
            writeSamples(output, before, after, nativeAfter, paint);
            for (int i = 0; i < 3000; i++) {
                before.update(paint, i);
                after.update(paint, i);
                copyToNative(after, nativeAfter);
            }
            long[] old = new long[9], updated = new long[9];
            for (int run = 0; run < 9; run++) {
                if (run % 2 == 0) {
                    old[run] = baseline(before, paint);
                    updated[run] = updated(after, nativeAfter, paint);
                } else {
                    updated[run] = updated(after, nativeAfter, paint);
                    old[run] = baseline(before, paint);
                }
            }
            System.out.println("Before runs (ns): " + Arrays.toString(old));
            System.out.println("After runs (ns): " + Arrays.toString(updated));
            Arrays.sort(old);
            Arrays.sort(updated);
            System.out.printf("Java %s CPU raster + NativeImage writes; GPU upload excluded.%n",
                    System.getProperty("java.version"));
            System.out.println("1800 changing-angle frames, 9 alternating runs, 3000 warmup frames.");
            System.out.printf("Median before=%.3f ms (%.3f us/frame), after=%.3f ms (%.3f us/frame), reduction=%.1f%%%n",
                    old[4] / 1e6, old[4] / 1.8e6, updated[4] / 1e6, updated[4] / 1.8e6,
                    100.0 * (old[4] - updated[4]) / old[4]);
        } finally {
            before.release();
        }
    }

    private static void writeSamples(File output, BaselineNativePaintPreview before,
                                     WaypointPaintPreviewTexture.Raster after,
                                     NativeImage nativeAfter, WaypointPaint paint) throws Exception {
        BufferedImage sheet = new BufferedImage(1024, 548, BufferedImage.TYPE_INT_ARGB);
        var graphics = sheet.createGraphics();
        graphics.setColor(new Color(0x22262C));
        graphics.fillRect(0, 0, 1024, 548);
        int changed = 0;
        for (int index = 0; index < 4; index++) {
            int angle = new int[]{0, 15, 45, 135}[index];
            before.update(paint, angle);
            after.update(paint, angle);
            copyToNative(after, nativeAfter);
            for (int row = 0; row < 2; row++) {
                NativeImage source = row == 0 ? before.image() : nativeAfter;
                BufferedImage frame = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < 256; y++) {
                    for (int x = 0; x < 256; x++) frame.setRGB(x, y, source.getPixel(x, y));
                }
                String label = row == 0 ? "before" : "after";
                ImageIO.write(frame, "png", new File(output, label + "-" + angle + ".png"));
                graphics.setColor(Color.WHITE);
                graphics.drawString(label + " " + angle + " degrees", index * 256 + 8, row * 274 + 16);
                graphics.drawImage(frame, index * 256, row * 274 + 18, null);
            }
            int[] old = before.image().getPixelsABGR(), updated = nativeAfter.getPixelsABGR();
            for (int p = 0; p < old.length; p++) if (old[p] != updated[p]) changed++;
        }
        graphics.dispose();
        ImageIO.write(sheet, "png", new File(output, "before-after.png"));
        System.out.println("Sample frame changed pixels=" + changed);
    }

    private static void copyToNative(WaypointPaintPreviewTexture.Raster raster, NativeImage image) {
        MemoryUtil.memIntBuffer(image.getPointer(), 256 * 256).put(raster.pixels());
    }

    private static long baseline(BaselineNativePaintPreview before, WaypointPaint paint) {
        long start = System.nanoTime();
        for (int i = 0; i < 1800; i++) {
            before.update(paint, i);
            sink = before.image().getPixel(100, 128);
        }
        return System.nanoTime() - start;
    }

    private static long updated(WaypointPaintPreviewTexture.Raster after, NativeImage image,
                                WaypointPaint paint) {
        long start = System.nanoTime();
        for (int i = 0; i < 1800; i++) {
            after.update(paint, i);
            copyToNative(after, image);
            sink = image.getPixel(100, 128);
        }
        return System.nanoTime() - start;
    }
}
