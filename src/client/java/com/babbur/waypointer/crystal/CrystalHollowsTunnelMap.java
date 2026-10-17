package com.babbur.waypointer.crystal;

import com.babbur.waypointer.Waypointer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * The Crystal Hollows' natural tunnels, built from terrain scans by
 * {@code tools/crystal_hollows/build_tunnels.py}: for every column, which 10-block bands of
 * height have somewhere to stand. The map draws every tunnel at once, brightest near the
 * player's height and fainter the further above or below it runs.
 */
public final class CrystalHollowsTunnelMap {
    private static final String RESOURCE = "/assets/waypointer/crystal_hollows/tunnels.bin.gz";
    private static final int MAGIC = 0x57504354; // "WPCT"
    /** Blocks per map pixel; close to the map's size on screen, so lines stay crisp. */
    private static final int CELL = 4;
    private static final int COLOR = 0xCFE0E8;

    private static CrystalHollowsTunnelMap instance;
    private static boolean loaded;

    private final int minX, minY, minZ, size, band, bands, pixels;
    /** Per map pixel: the bands any of its columns can be stood in, and how many columns can. */
    private final int[] cellBands;
    private final byte[] cellColumns;

    private DynamicTexture texture;
    private Identifier textureId;
    private int textureBand = Integer.MIN_VALUE;

    private CrystalHollowsTunnelMap(int minX, int minY, int minZ, int size, int band, int bands, short[] masks) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.size = size;
        this.band = band;
        this.bands = bands;
        this.pixels = (size + CELL - 1) / CELL;
        cellBands = new int[pixels * pixels];
        cellColumns = new byte[pixels * pixels];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int mask = masks[z * size + x] & 0xFFFF;
                if (mask == 0) continue;
                int cell = (z / CELL) * pixels + x / CELL;
                cellBands[cell] |= mask;
                cellColumns[cell]++;
            }
        }
    }

    /** The shipped map, or null when the mod was built without one. */
    public static CrystalHollowsTunnelMap get() {
        if (!loaded) {
            loaded = true;
            try (InputStream raw = CrystalHollowsTunnelMap.class.getResourceAsStream(RESOURCE)) {
                if (raw != null) instance = read(raw);
            } catch (IOException | RuntimeException e) {
                Waypointer.LOGGER.warn("Could not read the Crystal Hollows tunnel map", e);
            }
        }
        return instance;
    }

    static CrystalHollowsTunnelMap read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(raw)));
        if (in.readInt() != MAGIC || in.readInt() != 1) throw new IOException("Not a tunnel map");
        int minX = in.readInt(), minY = in.readInt(), minZ = in.readInt();
        int size = in.readInt(), band = in.readInt(), bands = in.readInt();
        if (size <= 0 || size > 1024 || band <= 0 || bands <= 0 || bands > 16) throw new IOException("Bad tunnel map");
        short[] masks = new short[size * size];
        for (int i = 0; i < masks.length; i++) masks[i] = in.readShort();
        return new CrystalHollowsTunnelMap(minX, minY, minZ, size, band, bands, masks);
    }

    /** Draws the tunnels into the map square at (x, y), {@code mapSize} pixels across. */
    public void draw(GuiGraphicsExtractor g, int x, int y, int mapSize, double playerY) {
        int playerBand = Math.clamp(((int) Math.floor(playerY) - minY) / band, 0, bands - 1);
        if (texture == null || playerBand != textureBand) bake(playerBand);
        g.blit(RenderPipelines.GUI_TEXTURED, textureId, x, y, 0f, 0f, mapSize, mapSize,
                pixels, pixels, pixels, pixels);
    }

    private void bake(int playerBand) {
        if (texture == null) {
            texture = new DynamicTexture(() -> "waypointer_crystal_hollows_tunnels", pixels, pixels, false);
            textureId = Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "dynamic/crystal_hollows_tunnels");
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
        }
        textureBand = playerBand;
        NativeImage image = texture.getPixels();
        for (int cell = 0; cell < cellBands.length; cell++) {
            int alpha = alpha(cellBands[cell], cellColumns[cell], playerBand);
            int argb = alpha << 24 | COLOR;
            image.setPixelABGR(cell % pixels, cell / pixels,
                    (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16));
        }
        texture.upload();
    }

    /**
     * Brighter the nearer the tunnel runs to the player's height, and the more of the pixel's
     * columns it fills (which smooths the edges).
     */
    static int alpha(int bandsMask, int columns, int playerBand) {
        if (bandsMask == 0 || columns <= 0) return 0;
        int nearest = Integer.MAX_VALUE;
        for (int b = 0; b < 16; b++) {
            if ((bandsMask & (1 << b)) != 0) nearest = Math.min(nearest, Math.abs(b - playerBand));
        }
        double depth = switch (nearest) {
            case 0 -> 1.0;
            case 1 -> 0.42;
            case 2 -> 0.24;
            default -> 0.12;
        };
        double fill = Math.min(1.0, columns / (double) (CELL * CELL / 2));
        return (int) Math.round(176 * depth * fill);
    }
}
