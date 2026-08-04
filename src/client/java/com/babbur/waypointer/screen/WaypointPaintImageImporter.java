package com.babbur.waypointer.screen;

import com.babbur.waypointer.core.WaypointPaint;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Converts ordinary images into the painter's fixed sixteen-color format. */
final class WaypointPaintImageImporter {

    static final int ATLAS_WIDTH = WaypointPaint.SIZE * 4;
    static final int ATLAS_HEIGHT = WaypointPaint.SIZE * 3;
    static final int MAX_IMAGE_DIMENSION = 8192;
    static final long MAX_IMAGE_PIXELS = 8L * 1024 * 1024;
    private static final int K_MEANS_ITERATIONS = 16;

    private WaypointPaintImageImporter() {}

    @FunctionalInterface
    interface ImageSource {
        BufferedImage read() throws IOException;
    }

    /** Loads and converts an image without changing the caller's current paint on failure. */
    static WaypointPaint importFrom(ImageSource source, WaypointPaint existing,
                                    boolean atlas) throws IOException {
        if (source == null) throw new IllegalArgumentException("image source is required");
        BufferedImage image = source.read();
        requireSupportedDimensions(image);
        return atlas ? importAtlas(image, existing) : importFace(image, existing);
    }

    static boolean acceptsImageDimensions(int width, int height) {
        return width > 0 && height > 0
                && width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION
                && (long) width * height <= MAX_IMAGE_PIXELS;
    }

    private static void requireSupportedDimensions(BufferedImage image) {
        if (image == null || !acceptsImageDimensions(image.getWidth(), image.getHeight())) {
            throw new IllegalArgumentException("image dimensions are outside the supported range");
        }
    }

    /** Fits an image to one face and immediately repeats it across all six faces. */
    static WaypointPaint importFace(BufferedImage source, WaypointPaint existing) {
        requireInputs(source, existing);
        requireSupportedDimensions(source);
        int[] scaled = resize(source, WaypointPaint.SIZE, WaypointPaint.SIZE);
        int[] colors = new int[WaypointPaint.FACE_PIXELS];
        for (int y = 0; y < WaypointPaint.SIZE; y++) {
            for (int x = 0; x < WaypointPaint.SIZE; x++) {
                int offset = y * WaypointPaint.SIZE + x;
                colors[offset] = composite(scaled[offset],
                        existing.color(WaypointPaint.Face.NORTH, x, y));
            }
        }

        Quantized quantized = quantize(colors, existing.paletteCopy());
        byte[] pixels = new byte[WaypointPaint.PIXEL_COUNT];
        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            System.arraycopy(quantized.indices(), 0, pixels,
                    face.ordinal() * WaypointPaint.FACE_PIXELS,
                    WaypointPaint.FACE_PIXELS);
        }
        return new WaypointPaint(quantized.palette(), pixels);
    }

    /** Fits an image to the full 64x48 UV atlas and imports its six face regions. */
    static WaypointPaint importAtlas(BufferedImage source, WaypointPaint existing) {
        requireInputs(source, existing);
        requireSupportedDimensions(source);
        int[] scaled = resize(source, ATLAS_WIDTH, ATLAS_HEIGHT);
        int[] colors = new int[WaypointPaint.PIXEL_COUNT];
        for (WaypointPaint.Face face : WaypointPaint.Face.values()) {
            for (int y = 0; y < WaypointPaint.SIZE; y++) {
                for (int x = 0; x < WaypointPaint.SIZE; x++) {
                    int atlasOffset = (face.atlasY() + y) * ATLAS_WIDTH + face.atlasX() + x;
                    int paintOffset = WaypointPaint.pixelOffset(face, x, y);
                    colors[paintOffset] = composite(scaled[atlasOffset],
                            existing.color(face, x, y));
                }
            }
        }

        Quantized quantized = quantize(colors, existing.paletteCopy());
        return new WaypointPaint(quantized.palette(), quantized.indices());
    }

    static int[] resize(BufferedImage source, int targetWidth, int targetHeight) {
        if (source == null || source.getWidth() < 1 || source.getHeight() < 1) {
            throw new IllegalArgumentException("image must contain pixels");
        }
        if (targetWidth < 1 || targetHeight < 1) {
            throw new IllegalArgumentException("target dimensions must be positive");
        }
        int targetPixelCount = checkedTargetPixelCount(targetWidth, targetHeight);

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        if (sourceWidth > targetWidth || sourceHeight > targetHeight) {
            return resizeArea(source, sourceWidth, sourceHeight,
                    targetWidth, targetHeight, targetPixelCount);
        }
        int[] sourcePixels = source.getRGB(0, 0, sourceWidth, sourceHeight,
                null, 0, sourceWidth);
        int[] result = new int[targetPixelCount];
        for (int y = 0; y < targetHeight; y++) {
            double sourceY = (y + 0.5) * sourceHeight / targetHeight - 0.5;
            int y0 = clamp((int) Math.floor(sourceY), 0, sourceHeight - 1);
            int y1 = Math.min(y0 + 1, sourceHeight - 1);
            double fy = sourceY <= 0 ? 0 : sourceY >= sourceHeight - 1 ? 0 : sourceY - y0;
            for (int x = 0; x < targetWidth; x++) {
                double sourceX = (x + 0.5) * sourceWidth / targetWidth - 0.5;
                int x0 = clamp((int) Math.floor(sourceX), 0, sourceWidth - 1);
                int x1 = Math.min(x0 + 1, sourceWidth - 1);
                double fx = sourceX <= 0 ? 0 : sourceX >= sourceWidth - 1 ? 0 : sourceX - x0;
                result[y * targetWidth + x] = bilinear(
                        sourcePixels[y0 * sourceWidth + x0],
                        sourcePixels[y0 * sourceWidth + x1],
                        sourcePixels[y1 * sourceWidth + x0],
                        sourcePixels[y1 * sourceWidth + x1], fx, fy);
            }
        }
        return result;
    }

    private static int[] resizeArea(BufferedImage source, int sourceWidth, int sourceHeight,
                                    int targetWidth, int targetHeight, int targetPixelCount) {
        int[] result = new int[targetPixelCount];
        for (int y = 0; y < targetHeight; y++) {
            double top = (double) y * sourceHeight / targetHeight;
            double bottom = (double) (y + 1) * sourceHeight / targetHeight;
            int firstY = (int) Math.floor(top);
            int lastY = Math.min(sourceHeight - 1, (int) Math.ceil(bottom) - 1);
            int bandHeight = lastY - firstY + 1;
            int[] sourceBand = source.getRGB(0, firstY, sourceWidth, bandHeight,
                    null, 0, sourceWidth);
            for (int x = 0; x < targetWidth; x++) {
                double left = (double) x * sourceWidth / targetWidth;
                double right = (double) (x + 1) * sourceWidth / targetWidth;
                int firstX = (int) Math.floor(left);
                int lastX = Math.min(sourceWidth - 1, (int) Math.ceil(right) - 1);
                double totalWeight = 0;
                double alpha = 0;
                double red = 0;
                double green = 0;
                double blue = 0;
                for (int sourceY = firstY; sourceY <= lastY; sourceY++) {
                    double yWeight = Math.min(bottom, sourceY + 1.0) - Math.max(top, sourceY);
                    for (int sourceX = firstX; sourceX <= lastX; sourceX++) {
                        double xWeight = Math.min(right, sourceX + 1.0) - Math.max(left, sourceX);
                        double weight = xWeight * yWeight;
                        int color = sourceBand[(sourceY - firstY) * sourceWidth + sourceX];
                        int a = color >>> 24;
                        double alphaWeight = weight * a;
                        totalWeight += weight;
                        alpha += alphaWeight;
                        red += alphaWeight * ((color >>> 16) & 0xFF);
                        green += alphaWeight * ((color >>> 8) & 0xFF);
                        blue += alphaWeight * (color & 0xFF);
                    }
                }
                int a = clamp((int) Math.round(alpha / totalWeight), 0, 255);
                if (alpha > 0) {
                    int r = clamp((int) Math.round(red / alpha), 0, 255);
                    int g = clamp((int) Math.round(green / alpha), 0, 255);
                    int b = clamp((int) Math.round(blue / alpha), 0, 255);
                    result[y * targetWidth + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
        }
        return result;
    }

    private static int checkedTargetPixelCount(int width, int height) {
        final int pixels;
        try {
            pixels = Math.multiplyExact(width, height);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("target image is too large", overflow);
        }
        if (width > MAX_IMAGE_DIMENSION
                || height > MAX_IMAGE_DIMENSION
                || pixels > MAX_IMAGE_PIXELS) {
            throw new IllegalArgumentException("target image is too large");
        }
        return pixels;
    }

    private static void requireInputs(BufferedImage source, WaypointPaint existing) {
        if (source == null) throw new IllegalArgumentException("image is required");
        if (existing == null) throw new IllegalArgumentException("existing paint is required");
    }

    private static int bilinear(int topLeft, int topRight, int bottomLeft, int bottomRight,
                                double fx, double fy) {
        double topWeight = 1.0 - fy;
        double leftWeight = 1.0 - fx;
        double[] weights = {
                leftWeight * topWeight,
                fx * topWeight,
                leftWeight * fy,
                fx * fy
        };
        int[] colors = {topLeft, topRight, bottomLeft, bottomRight};
        double alpha = 0;
        double red = 0;
        double green = 0;
        double blue = 0;
        for (int i = 0; i < colors.length; i++) {
            int a = colors[i] >>> 24;
            double alphaWeight = weights[i] * a;
            alpha += alphaWeight;
            red += alphaWeight * ((colors[i] >>> 16) & 0xFF);
            green += alphaWeight * ((colors[i] >>> 8) & 0xFF);
            blue += alphaWeight * (colors[i] & 0xFF);
        }
        int a = clamp((int) Math.round(alpha), 0, 255);
        if (alpha <= 0) return 0;
        int r = clamp((int) Math.round(red / alpha), 0, 255);
        int g = clamp((int) Math.round(green / alpha), 0, 255);
        int b = clamp((int) Math.round(blue / alpha), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int composite(int argb, int backgroundRgb) {
        int alpha = argb >>> 24;
        if (alpha == 255) return argb & 0xFFFFFF;
        if (alpha == 0) return backgroundRgb & 0xFFFFFF;
        int inverse = 255 - alpha;
        int red = (((argb >>> 16) & 0xFF) * alpha
                + ((backgroundRgb >>> 16) & 0xFF) * inverse + 127) / 255;
        int green = (((argb >>> 8) & 0xFF) * alpha
                + ((backgroundRgb >>> 8) & 0xFF) * inverse + 127) / 255;
        int blue = ((argb & 0xFF) * alpha
                + (backgroundRgb & 0xFF) * inverse + 127) / 255;
        return (red << 16) | (green << 8) | blue;
    }

    private static Quantized quantize(int[] colors, int[] fallbackPalette) {
        TreeMap<Integer, Integer> histogram = new TreeMap<>();
        for (int color : colors) histogram.merge(color & 0xFFFFFF, 1, Integer::sum);

        List<ColorCount> samples = histogram.entrySet().stream()
                .map(entry -> new ColorCount(entry.getKey(), entry.getValue()))
                .toList();
        List<Integer> centers = new ArrayList<>(WaypointPaint.PALETTE_SIZE);
        if (samples.size() <= WaypointPaint.PALETTE_SIZE) {
            samples.stream()
                    .sorted(Comparator.comparingInt(ColorCount::count).reversed()
                            .thenComparingInt(ColorCount::rgb))
                    .map(ColorCount::rgb)
                    .forEach(centers::add);
        } else {
            centers.add(samples.stream()
                    .max(Comparator.comparingInt(ColorCount::count)
                            .thenComparing(Comparator.comparingInt(ColorCount::rgb).reversed()))
                    .orElseThrow().rgb());
            while (centers.size() < WaypointPaint.PALETTE_SIZE) {
                ColorCount next = samples.stream()
                        .filter(sample -> !centers.contains(sample.rgb()))
                        .max(Comparator.comparingLong((ColorCount sample) ->
                                (long) nearestDistance(sample.rgb(), centers) * sample.count())
                                .thenComparing(Comparator.comparingInt(ColorCount::rgb).reversed()))
                        .orElseThrow();
                centers.add(next.rgb());
            }
            refineCenters(samples, centers);
        }

        int[] palette = completePalette(centers, fallbackPalette);
        byte[] indices = new byte[colors.length];
        for (int i = 0; i < colors.length; i++) {
            indices[i] = (byte) nearestIndex(colors[i] & 0xFFFFFF, centers);
        }
        return new Quantized(palette, indices);
    }

    private static void refineCenters(List<ColorCount> samples, List<Integer> centers) {
        for (int iteration = 0; iteration < K_MEANS_ITERATIONS; iteration++) {
            long[] red = new long[centers.size()];
            long[] green = new long[centers.size()];
            long[] blue = new long[centers.size()];
            long[] counts = new long[centers.size()];
            for (ColorCount sample : samples) {
                int cluster = nearestIndex(sample.rgb(), centers);
                counts[cluster] += sample.count();
                red[cluster] += (long) ((sample.rgb() >>> 16) & 0xFF) * sample.count();
                green[cluster] += (long) ((sample.rgb() >>> 8) & 0xFF) * sample.count();
                blue[cluster] += (long) (sample.rgb() & 0xFF) * sample.count();
            }

            boolean changed = false;
            for (int i = 0; i < centers.size(); i++) {
                if (counts[i] == 0) continue;
                int center = ((int) ((red[i] + counts[i] / 2) / counts[i]) << 16)
                        | ((int) ((green[i] + counts[i] / 2) / counts[i]) << 8)
                        | (int) ((blue[i] + counts[i] / 2) / counts[i]);
                if (center != centers.get(i)) {
                    centers.set(i, center);
                    changed = true;
                }
            }
            if (!changed) return;
        }
    }

    static int[] completePalette(List<Integer> centers, int[] fallbackPalette) {
        int[] palette = new int[WaypointPaint.PALETTE_SIZE];
        int size = 0;
        for (int color : centers) {
            palette[size++] = color;
        }
        for (int color : fallbackPalette) {
            color &= 0xFFFFFF;
            if (size == palette.length) break;
            if (!contains(palette, size, color)) palette[size++] = color;
        }
        while (size < palette.length) palette[size++] = 0;
        return palette;
    }

    private static boolean contains(int[] colors, int size, int color) {
        for (int i = 0; i < size; i++) {
            if (colors[i] == color) return true;
        }
        return false;
    }

    private static int nearestIndex(int rgb, List<Integer> palette) {
        int nearest = 0;
        int distance = colorDistance(rgb, palette.get(0));
        for (int i = 1; i < palette.size(); i++) {
            int candidate = colorDistance(rgb, palette.get(i));
            if (candidate < distance) {
                nearest = i;
                distance = candidate;
            }
        }
        return nearest;
    }

    private static int nearestDistance(int rgb, List<Integer> palette) {
        return colorDistance(rgb, palette.get(nearestIndex(rgb, palette)));
    }

    // Red-mean distance weights RGB channels without the cost of a color-space conversion.
    private static int colorDistance(int first, int second) {
        int redMean = (((first >>> 16) & 0xFF) + ((second >>> 16) & 0xFF)) >>> 1;
        int red = ((first >>> 16) & 0xFF) - ((second >>> 16) & 0xFF);
        int green = ((first >>> 8) & 0xFF) - ((second >>> 8) & 0xFF);
        int blue = (first & 0xFF) - (second & 0xFF);
        return ((512 + redMean) * red * red >> 8)
                + 4 * green * green
                + ((767 - redMean) * blue * blue >> 8);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ColorCount(int rgb, int count) {}

    private record Quantized(int[] palette, byte[] indices) {
        private Quantized {
            palette = Arrays.copyOf(palette, palette.length);
            indices = Arrays.copyOf(indices, indices.length);
        }
    }
}
