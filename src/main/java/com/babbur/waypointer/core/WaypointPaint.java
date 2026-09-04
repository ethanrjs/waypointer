package com.babbur.waypointer.core;

import java.util.Arrays;
import java.util.Base64;

/**
 * Immutable 16x16 texture for the six faces of a waypoint box.
 *
 * <p>Pixels store indices into a fixed sixteen-color palette. That keeps a full
 * six-face paint close to 2 KiB on disk instead of repeating a 24-bit color for
 * every texel, while still matching the painter's sixteen visible swatches.
 */
public final class WaypointPaint {

    public static final int SIZE = 16;
    public static final int PALETTE_SIZE = 16;
    public static final int FACE_PIXELS = SIZE * SIZE;
    public static final int PIXEL_COUNT = FACE_PIXELS * Face.values().length;

    /** T-shaped atlas placement used by both the editor and the world renderer. */
    public enum Face {
        NORTH(1, 1),
        EAST(2, 1),
        SOUTH(3, 1),
        WEST(0, 1),
        UP(1, 0),
        DOWN(1, 2);

        private final int atlasColumn;
        private final int atlasRow;

        Face(int atlasColumn, int atlasRow) {
            this.atlasColumn = atlasColumn;
            this.atlasRow = atlasRow;
        }

        public int atlasX() {
            return atlasColumn * SIZE;
        }

        public int atlasY() {
            return atlasRow * SIZE;
        }

        public boolean isSide() {
            return this != UP && this != DOWN;
        }
    }

    private final int[] palette;
    private final byte[] pixels;
    private final int hashCode;
    private final long contentFingerprint;

    public WaypointPaint(int[] palette, byte[] pixels) {
        if (palette == null || palette.length != PALETTE_SIZE) {
            throw new IllegalArgumentException("waypoint paint palette must contain 16 colors");
        }
        if (pixels == null || pixels.length != PIXEL_COUNT) {
            throw new IllegalArgumentException("waypoint paint must contain 1536 pixels");
        }
        this.palette = palette.clone();
        for (int i = 0; i < this.palette.length; i++) {
            this.palette[i] &= 0xFFFFFF;
        }
        this.pixels = pixels.clone();
        for (byte pixel : this.pixels) {
            if (Byte.toUnsignedInt(pixel) >= PALETTE_SIZE) {
                throw new IllegalArgumentException("waypoint paint pixel uses an invalid palette slot");
            }
        }
        this.hashCode = 31 * Arrays.hashCode(this.palette) + Arrays.hashCode(this.pixels);
        this.contentFingerprint = fingerprint(this.palette, this.pixels);
    }

    public static WaypointPaint solid(int rgb) {
        return new WaypointPaint(defaultPalette(rgb), new byte[PIXEL_COUNT]);
    }

    public static int[] defaultPalette(int firstColor) {
        return new int[] {
                firstColor & 0xFFFFFF,
                0xF5F7FA, 0x171A1F, 0xE64646,
                0xF28C28, 0xF2D94E, 0x4FD15A, 0x45D7D7,
                0x4387F5, 0x7B61D1, 0xC850D1, 0xF06DAD,
                0x8B5A3C, 0x626A73, 0xAAB1BA, 0x343A42
        };
    }

    public int color(Face face, int x, int y) {
        return palette[paletteIndex(face, x, y)];
    }

    public int paletteColor(int slot) {
        checkPaletteSlot(slot);
        return palette[slot];
    }

    public int paletteIndex(Face face, int x, int y) {
        return Byte.toUnsignedInt(pixels[pixelOffset(face, x, y)]);
    }

    public int[] paletteCopy() {
        return palette.clone();
    }

    public byte[] pixelsCopy() {
        return pixels.clone();
    }

    public long contentFingerprint() {
        return contentFingerprint;
    }

    public String pixelsBase64() {
        return Base64.getEncoder().encodeToString(pixels);
    }

    public static byte[] decodePixels(String encoded) {
        if (encoded == null) throw new IllegalArgumentException("waypoint paint pixels are missing");
        byte[] decoded = Base64.getDecoder().decode(encoded);
        if (decoded.length != PIXEL_COUNT) {
            throw new IllegalArgumentException("waypoint paint must contain 1536 pixels");
        }
        return decoded;
    }

    public boolean hasIdenticalFaces() {
        for (int face = 1; face < Face.values().length; face++) {
            int offset = face * FACE_PIXELS;
            for (int i = 0; i < FACE_PIXELS; i++) {
                if (pixels[i] != pixels[offset + i]) return false;
            }
        }
        return true;
    }

    public static int pixelOffset(Face face, int x, int y) {
        if (face == null) throw new IllegalArgumentException("waypoint paint face is missing");
        if (x < 0 || x >= SIZE || y < 0 || y >= SIZE) {
            throw new IndexOutOfBoundsException("waypoint paint pixel outside 16x16 face");
        }
        return face.ordinal() * FACE_PIXELS + y * SIZE + x;
    }

    private static void checkPaletteSlot(int slot) {
        if (slot < 0 || slot >= PALETTE_SIZE) {
            throw new IndexOutOfBoundsException("waypoint paint palette slot outside 0..15");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof WaypointPaint paint)) return false;
        return Arrays.equals(palette, paint.palette) && Arrays.equals(pixels, paint.pixels);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    private static long fingerprint(int[] palette, byte[] pixels) {
        long value = 0xCBF29CE484222325L;
        for (int color : palette) {
            value = (value ^ color) * 0x100000001B3L;
        }
        for (byte pixel : pixels) {
            value = (value ^ Byte.toUnsignedInt(pixel)) * 0x100000001B3L;
        }
        return value;
    }
}
