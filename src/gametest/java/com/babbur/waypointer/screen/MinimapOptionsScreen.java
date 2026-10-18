package com.babbur.waypointer.screen;

import com.babbur.waypointer.crystal.CrystalHollowsGeometry;
import com.babbur.waypointer.crystal.CrystalHollowsMinimap;
import com.babbur.waypointer.crystal.CrystalHollowsMinimapLayout;
import com.babbur.waypointer.crystal.CrystalHollowsStructure;
import com.babbur.waypointer.crystal.SightingConfidence;
import com.babbur.waypointer.crystal.StructureSighting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

import static com.babbur.waypointer.screen.GuiTokens.*;

final class MinimapOptionsScreen extends Screen {
    private static final double PLAYER_X = 590, PLAYER_Y = 101, PLAYER_Z = 455;
    private static final float YAW = 135;
    private static final int HEADER = 16, FOOTER = 14, PAD = 5;

    private static final List<StructureSighting> SIGHTINGS = List.of(
            sighting(CrystalHollowsStructure.JUNGLE_TEMPLE, 330, 120, 300, SightingConfidence.ENTITY),
            sighting(CrystalHollowsStructure.MINES_OF_DIVAN, 705, 110, 300, SightingConfidence.ENTITY),
            sighting(CrystalHollowsStructure.GOBLIN_QUEENS_DEN, 300, 120, 705, SightingConfidence.ENTITY),
            sighting(CrystalHollowsStructure.LOST_PRECURSOR_CITY, 690, 110, 690, SightingConfidence.ROUGH_AREA),
            sighting(CrystalHollowsStructure.ODAWA, 255, 100, 430, SightingConfidence.NPC_CHAT),
            sighting(CrystalHollowsStructure.KHAZAD_DUM, 420, 40, 560, SightingConfidence.ENTITY));

    private static final int JUNGLE = 0x46B56E, MITHRIL = 0x45A7BD, GOBLIN = 0xC9975A, PRECURSOR = 0x7A8AD0;

    MinimapOptionsScreen() {
        super(Component.literal("Crystal Hollows map options"));
    }

    private static StructureSighting sighting(CrystalHollowsStructure structure, int x, int y, int z,
                                              SightingConfidence confidence) {
        return new StructureSighting(structure, x, y, z, confidence, "sample", 1);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        int size = Math.min(150, (width - 4 * 16) / 3 - PAD * 2);
        int panelW = size + PAD * 2;
        int gap = (width - panelW * 3) / 4;
        int top = 40;
        String[] captions = {"Current", "A  Panel", "B  Grid"};
        for (int i = 0; i < 3; i++) {
            int x = gap + i * (panelW + gap);
            switch (i) {
                case 0 -> CrystalHollowsMinimap.draw(g, new CrystalHollowsMinimapLayout(x, top, size),
                        PLAYER_X, PLAYER_Y, PLAYER_Z, YAW, SIGHTINGS, null, true, true);
                case 1 -> optionA(g, x, top, size);
                default -> optionB(g, x, top, size);
            }
            g.text(font, captions[i], x + (panelW - font.width(captions[i])) / 2, top - 14, TEXT, true);
        }
    }


    private void optionA(GuiGraphicsExtractor g, int x, int y, int size) {
        int w = size + PAD * 2, h = HEADER + size + PAD + FOOTER;
        frame(g, x, y, w, h);
        header(g, x, y, w);
        int mx = x + PAD, my = y + HEADER + 3;
        int half = size / 2;
        g.fill(mx, my, mx + half, my + half, 0x22000000 | JUNGLE);
        g.fill(mx + half, my, mx + size, my + half, 0x22000000 | MITHRIL);
        g.fill(mx, my + half, mx + half, my + size, 0x22000000 | GOBLIN);
        g.fill(mx + half, my + half, mx + size, my + size, 0x22000000 | PRECURSOR);
        g.fill(mx + half, my, mx + half + 1, my + size, BORDER);
        g.fill(mx, my + half, mx + size, my + half + 1, BORDER);
        dots(g, mx, my, size);
        zoneCorners(g, mx, my, size, false);
        nucleus(g, mx, my, size);
        markers(g, mx, my, size);
        player(g, mx, my, size);
        footer(g, x, my + size + 3, w, "Mithril Deposits");
    }


    private void optionB(GuiGraphicsExtractor g, int x, int y, int size) {
        int w = size + PAD * 2, h = HEADER + size + PAD + FOOTER;
        frame(g, x, y, w, h);
        header(g, x, y, w);
        int mx = x + PAD, my = y + HEADER + 3;
        g.fill(mx, my, mx + size, my + size, 0x40000000);
        int step = Math.max(4, Math.round(size * 32f / (CrystalHollowsGeometry.MAX_XZ - CrystalHollowsGeometry.MIN_XZ)));
        for (int i = step; i < size; i += step) {
            g.fill(mx + i, my, mx + i + 1, my + size, 0x0CFFFFFF);
            g.fill(mx, my + i, mx + size, my + i + 1, 0x0CFFFFFF);
        }
        int half = size / 2;
        g.fill(mx + half, my, mx + half + 1, my + size, 0x22FFFFFF);
        g.fill(mx, my + half, mx + size, my + half + 1, 0x22FFFFFF);
        outline(g, mx, my, mx + size, my + size, BORDER);
        zoneCorners(g, mx, my, size, true);
        nucleus(g, mx, my, size);
        markers(g, mx, my, size);
        player(g, mx, my, size);
        footer(g, x, my + size + 3, w, "Mithril Deposits");
    }


    private static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xE6101216);
        outline(g, x, y, x + w, y + h, BORDER);
    }

    private void header(GuiGraphicsExtractor g, int x, int y, int w) {
        Icons.drawAt(g, Icons.Icon.BADGE_GEM, x + PAD + 4, y + HEADER / 2 + 1, ACCENT);
        g.text(font, "Crystal Hollows", x + PAD + 11, y + 5, TEXT, false);
        g.fill(x + 1, y + HEADER, x + w - 1, y + HEADER + 1, BORDER);
    }

    private void footer(GuiGraphicsExtractor g, int x, int y, int w, String area) {
        g.fill(x + 1, y, x + w - 1, y + 1, BORDER);
        g.text(font, area, x + PAD, y + 4, TEXT_DIM, false);
        String height = "Y " + (int) PLAYER_Y;
        g.text(font, height, x + w - PAD - font.width(height), y + 4, TEXT_MUTED, false);
    }

    private void zoneCorners(GuiGraphicsExtractor g, int mx, int my, int size, boolean colored) {
        String[] names = {"Jungle", "Mithril", "Goblin", "Precursor"};
        int[] colors = {JUNGLE, MITHRIL, GOBLIN, PRECURSOR};
        for (int i = 0; i < 4; i++) {
            boolean right = (i & 1) == 1, bottom = i >= 2;
            int tx = right ? mx + size - 3 - font.width(names[i]) : mx + 3;
            int ty = bottom ? my + size - 11 : my + 3;
            int color = colored ? 0xB0000000 | colors[i] : TEXT_MUTED;
            g.text(font, names[i], tx, ty, color, false);
        }
    }

    private static void dots(GuiGraphicsExtractor g, int mx, int my, int size) {
        for (int i = 1; i < 5; i++) {
            for (int j = 1; j < 5; j++) {
                g.fill(mx + size * i / 5, my + size * j / 5, mx + size * i / 5 + 1, my + size * j / 5 + 1, 0x30FFFFFF);
            }
        }
    }

    private static int project(double coordinate, int size) {
        double fraction = (coordinate - CrystalHollowsGeometry.MIN_XZ)
                / (CrystalHollowsGeometry.MAX_XZ - CrystalHollowsGeometry.MIN_XZ);
        return (int) Math.round(Math.clamp(fraction, 0, 1) * (size - 1));
    }

    private static void nucleus(GuiGraphicsExtractor g, int mx, int my, int size) {
        int cx = mx + project(CrystalHollowsGeometry.NUCLEUS_CENTRE_X, size);
        int cy = my + project(CrystalHollowsGeometry.NUCLEUS_CENTRE_Z, size);
        int r = Math.max(5, size / 18);
        g.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, 0xE0101216);
        outline(g, cx - r, cy - r, cx + r + 1, cy + r + 1, 0x804FB3C4);
        Icons.drawAt(g, Icons.Icon.BADGE_GEM, cx, cy, ACCENT);
    }

    private void markers(GuiGraphicsExtractor g, int mx, int my, int size) {
        for (StructureSighting sighting : SIGHTINGS) {
            int sx = mx + project(sighting.x(), size), sy = my + project(sighting.z(), size);
            int color = 0xFF000000 | sighting.structure().rgb();
            g.fill(sx - 3, sy - 3, sx + 4, sy + 4, 0xE0101216);
            if (sighting.approximate()) {
                outline(g, sx - 2, sy - 2, sx + 3, sy + 3, color);
            } else {
                g.fill(sx - 2, sy - 2, sx + 3, sy + 3, color);
            }
            String label = abbreviation(sighting.structure());
            g.text(font, label, sx + 6, sy - 3, sighting.approximate() ? TEXT_MUTED : TEXT, true);
        }
    }

    private static String abbreviation(CrystalHollowsStructure structure) {
        return switch (structure) {
            case JUNGLE_TEMPLE -> "JT";
            case MINES_OF_DIVAN -> "MD";
            case GOBLIN_QUEENS_DEN -> "QD";
            case LOST_PRECURSOR_CITY -> "PC";
            case KHAZAD_DUM -> "B";
            case ODAWA -> "O";
            default -> "?";
        };
    }

    private static void player(GuiGraphicsExtractor g, int mx, int my, int size) {
        g.pose().pushMatrix();
        g.pose().translate(mx + project(PLAYER_X, size) + 0.5f, my + project(PLAYER_Z, size) + 0.5f);
        g.pose().rotate((float) Math.toRadians(YAW + 180));
        for (int row = 0; row < 7; row++) {
            int halfWidth = row / 2;
            g.fill(-halfWidth - 1, row - 5, halfWidth + 2, row - 3, 0xFF101820);
        }
        for (int row = 0; row < 6; row++) {
            int halfWidth = row / 2;
            g.fill(-halfWidth, row - 5, halfWidth + 1, row - 4, 0xFFF1F8FA);
        }
        g.pose().popMatrix();
    }

    private static void outline(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1 + 1, x1 + 1, y2 - 1, color);
        g.fill(x2 - 1, y1 + 1, x2, y2 - 1, color);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
