package com.babbur.waypointer.crystal;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.screen.GuiTokens;
import com.babbur.waypointer.crystal.CrystalHollowsMinimapLayout.Rect;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Schematic region map backed by the tracker's current lobby, never invented terrain. */
public final class CrystalHollowsMinimap implements HudElement {
    private final CrystalHollowsTracker tracker;
    private final WaypointerConfig config;

    public CrystalHollowsMinimap(CrystalHollowsTracker tracker, WaypointerConfig config) {
        this.tracker = tracker;
        this.config = config;
    }

    public void install() {
        // Inherit vanilla's F1 HUD visibility on both supported Minecraft versions.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "crystal_hollows_minimap"), this);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker tick) {
        Minecraft client = Minecraft.getInstance();
        if (!config.crystalHollowsEnabled() || !config.crystalHollowsMinimap() || !tracker.active()
                || client.player == null || client.level == null
                || MinecraftCompat.screen(client) != null) return;
        var target = tracker.compassNavigationTarget();
        var layout = CrystalHollowsMinimapLayout.fit(g.guiWidth(), g.guiHeight(),
                config.crystalHollowsMinimapSize(), config.crystalHollowsMinimapCorner(), target != null);
        if (layout == null) return;
        var player = client.player;
        draw(g, layout, player.getX(), player.getY(), player.getZ(), player.getYRot(),
                tracker.lobby() == null ? List.of() : tracker.lobby().sightings(),
                target, config.crystalHollowsMinimapLabels(),
                config.crystalHollowsShowRoughMarkers());
    }

    public static void draw(GuiGraphicsExtractor g, CrystalHollowsMinimapLayout layout,
                            double playerX, double playerY, double playerZ, float yaw,
                            List<StructureSighting> sightings, StructureSighting target,
                            boolean labels, boolean showRough) {
        var font = Minecraft.getInstance().font;
        int x = layout.mapX(), y = layout.mapY(), size = layout.size();
        panel(g, layout.x(), layout.y(), layout.width(), layout.height(), 0xFF39454D);
        panel(g, layout.x() + 1, layout.y() + 1, layout.width() - 2, layout.height() - 2, 0xF213191F);
        gem(g, x + 3, layout.y() + CrystalHollowsMinimapLayout.HEADER / 2, GuiTokens.ACCENT);
        // The seven-pixel gem has five clear pixels to the frame and five to the title.
        int titleX = x + CrystalHollowsMinimapLayout.PADDING * 2;
        int titleWidth = x + size - titleX;
        String title = font.width(text("title")) <= titleWidth ? text("title") : text("short_title");
        title = font.plainSubstrByWidth(title, titleWidth);
        drawText(g, title, titleX,
                layout.y() + 1 + (CrystalHollowsMinimapLayout.HEADER - 1 - inkHeight(title)) / 2f,
                GuiTokens.TEXT);

        boolean magma = playerY < 64;
        int half = size / 2;
        if (magma) region(g, x, y, size, size, 0xFF493026, 0xFF291F20);
        else {
            region(g, x, y, half, half, 0xFF294D3D, 0xFF1B302A);
            region(g, x + half, y, size - half, half, 0xFF294751, 0xFF1C2D38);
            region(g, x, y + half, half, size - half, 0xFF493D2D, 0xFF302921);
            region(g, x + half, y + half, size - half, size - half, 0xFF353D56, 0xFF252B40);
            g.pose().pushMatrix();
            g.pose().translate(x + size / 2f - 0.5f, y);
            g.fill(0, 0, 1, size, 0x244FB3C4);
            g.pose().popMatrix();
            g.pose().pushMatrix();
            g.pose().translate(x, y + size / 2f - 0.5f);
            g.fill(0, 0, size, 1, 0x244FB3C4);
            g.pose().popMatrix();
        }
        for (int coordinate = 256; coordinate < CrystalHollowsGeometry.MAX_XZ; coordinate += 128) {
            int offset = layout.project(coordinate);
            g.fill(x + offset, y, x + offset + 1, y + 3, 0x408CA3AB);
            g.fill(x, y + offset, x + 3, y + offset + 1, 0x408CA3AB);
            g.fill(x + offset, y + size - 3, x + offset + 1, y + size, 0x408CA3AB);
            g.fill(x + size - 3, y + offset, x + size, y + offset + 1, 0x408CA3AB);
            for (int coordinateZ = 256; coordinateZ < CrystalHollowsGeometry.MAX_XZ; coordinateZ += 128) {
                int offsetZ = layout.project(coordinateZ);
                g.fill(x + offset, y + offsetZ, x + offset + 1, y + offsetZ + 1, 0x248CA3AB);
            }
        }
        List<Rect> occupied = new ArrayList<>();
        if (labels) {
            if (magma) {
                occupied.add(centered(g, text("magma"), x + size / 2f, y + 13, size - 12, 0xFFE6B493));
            } else {
                occupied.add(centered(g, text("jungle"), x + size / 4f, y + size / 4f, half - 8, 0xFFA3CBB0));
                occupied.add(centered(g, text("mithril"), x + size * 3 / 4f, y + size / 4f, half - 8, 0xFFA5CED8));
                occupied.add(centered(g, text("goblin"), x + size / 4f, y + size * 3 / 4f, half - 8, 0xFFD0BD99));
                occupied.add(centered(g, text("precursor"), x + size * 3 / 4f, y + size * 3 / 4f, half - 8, 0xFFB6C0DF));
            }
        }
        Rect nucleus = layout.nucleus();
        int radius = nucleus.width() / 2;
        octagon(g, nucleus.x(), nucleus.y(), nucleus.width(), 0xFF527C86);
        octagon(g, nucleus.x() + 1, nucleus.y() + 1, nucleus.width() - 2, 0xFF172A33);
        octagon(g, nucleus.x() + 3, nucleus.y() + 3, nucleus.width() - 6, 0xFF2B4651);
        octagon(g, nucleus.x() + 4, nucleus.y() + 4, nucleus.width() - 8, 0xFF1C303A);
        g.pose().pushMatrix();
        // Odd-width symbols have their pixel center at +0.5; retain that half pixel at even map sizes.
        g.pose().translate(x + size / 2f - 0.5f, y + size / 2f - 0.5f);
        for (var entrance : CrystalHollowsGeometry.NUCLEUS_ENTRANCES) {
            if (entrance.id().startsWith("magma") != magma) continue;
            // The four quadrant gates are symmetric accents on the schematic platform.
            int ex = magma ? layout.project(entrance.position().x()) - layout.project(CrystalHollowsGeometry.NUCLEUS_CENTRE_X)
                    : (entrance.position().x() < CrystalHollowsGeometry.NUCLEUS_CENTRE_X ? -radius - 1 : radius + 1);
            int ey = magma ? layout.project(entrance.position().z()) - layout.project(CrystalHollowsGeometry.NUCLEUS_CENTRE_Z)
                    : (entrance.position().z() < CrystalHollowsGeometry.NUCLEUS_CENTRE_Z ? -radius - 1 : radius + 1);
            diamond(g, ex, ey, 2, 0xFF172A33, true);
            diamond(g, ex, ey, 1, 0xFFAACACC, true);
        }
        // A larger faceted crystal distinguishes the nucleus from ordinary point markers.
        int crystalRadius = radius < 10 ? 3 : 4;
        diamond(g, 0, 0, crystalRadius + 1, 0xFF101C25, true);
        for (int row = -crystalRadius; row <= crystalRadius; row++) {
            int span = crystalRadius - Math.abs(row);
            g.fill(-span, row, 1, row + 1, 0xFF8DE5E2);
            g.fill(1, row, span + 1, row + 1, 0xFF3C91AE);
        }
        g.fill(0, -crystalRadius, 1, 1, 0xFFD0FAEE);
        g.pose().popMatrix();
        occupied.add(new Rect(nucleus.x() - 3, nucleus.y() - 3, nucleus.width() + 6, nucleus.height() + 6));
        int px = x + layout.marker(playerX), py = y + layout.marker(playerZ);
        occupied.add(new Rect(px - 7, py - 7, 15, 15));

        g.enableScissor(x, y, x + size, y + size);
        if (target != null && CrystalHollowsMinimapLayout.contains(target.x(), target.z())) {
            line(g, px, py, x + layout.marker(target.x()), y + layout.marker(target.z()), 0xC0F4D99F);
        }
        List<StructureSighting> visible = sightings.stream().filter(sighting ->
                CrystalHollowsMinimapLayout.contains(sighting.x(), sighting.z())
                        && (showRough || sighting.confidence() != SightingConfidence.ROUGH_AREA)
                        && sighting.structure() != CrystalHollowsStructure.CRYSTAL_NUCLEUS).toList();
        for (StructureSighting sighting : visible) {
            occupied.add(new Rect(x + layout.marker(sighting.x()) - 5,
                    y + layout.marker(sighting.z()) - 5, 11, 11));
        }
        for (StructureSighting sighting : visible) {
            boolean rough = sighting.confidence() == SightingConfidence.ROUGH_AREA;
            int sx = x + layout.marker(sighting.x()), sy = y + layout.marker(sighting.z());
            int color = mix(0xFF000000 | sighting.structure().rgb(), 0xFFF2F8EF, 0.3);
            diamond(g, sx, sy, 4, 0xFF101216, true);
            if (rough) diamond(g, sx, sy, 3, color, false);
            else gem(g, sx, sy, color);
            if (labels) {
                String label = abbreviation(sighting.structure());
                Rect placed = layout.label(sx, sy, inkWidth(label), occupied);
                if (placed != null) {
                    Rect plate = new Rect(placed.x() - 2, placed.y() - 1, placed.width() + 4, 11);
                    occupied.add(plate);
                    panel(g, plate.x(), plate.y(), plate.width(), plate.height(), 0xBC111B22);
                    g.text(font, label, placed.x(), placed.y() + 1, GuiTokens.TEXT, false);
                }
            }
        }
        if (target != null && CrystalHollowsMinimapLayout.contains(target.x(), target.z())) {
            int tx = x + layout.marker(target.x()), ty = y + layout.marker(target.z());
            for (int dx : new int[]{-1, 1}) for (int dy : new int[]{-1, 1}) {
                int cx = tx + dx * 6, cy = ty + dy * 6;
                g.fill(Math.min(cx, cx - dx * 2), cy, Math.max(cx, cx - dx * 2) + 1, cy + 1, 0xFFFFE6A5);
                g.fill(cx, Math.min(cy, cy - dy * 2), cx + 1, Math.max(cy, cy - dy * 2) + 1, 0xFFFFE6A5);
            }
        }
        if (CrystalHollowsMinimapLayout.contains(playerX, playerZ)) {
            g.pose().pushMatrix();
            g.pose().translate(px, py);
            // Minecraft yaw zero is south; the arrow is drawn facing north before rotation.
            g.pose().rotate((float) Math.toRadians(yaw + 180));
            arrow(g);
            g.pose().popMatrix();
        }
        g.disableScissor();

        if (target == null || !layout.navigation()) return;
        String area = Component.translatable("waypointer.crystal.structure." + target.structure().id()).getString();
        g.fill(x, y + size + 1, x + size, y + size + 2, 0xFF2B3B42);
        String distance = Math.round(Math.hypot(target.y() - playerY,
                Math.hypot(target.x() - playerX, target.z() - playerZ))) + "m";
        int reservedWidth = size < 120 ? 0 : font.width(distance) + 7;
        area = font.plainSubstrByWidth(area, size - reservedWidth);
        int footerTop = y + size + 2;
        int footerBottom = layout.y() + layout.height() - 1;
        float footerY = footerTop + (footerBottom - footerTop - inkHeight(area)) / 2f;
        drawText(g, area, x, footerY, 0xFFFFE6A5);
        if (reservedWidth > 0) drawText(g, distance, x + size - inkWidth(distance), footerY, GuiTokens.TEXT_DIM);
    }

    private static String abbreviation(CrystalHollowsStructure structure) {
        return switch (structure) {
            case JUNGLE_TEMPLE -> "JT";
            case MINES_OF_DIVAN -> "MD";
            case GOBLIN_QUEENS_DEN -> "QD";
            case LOST_PRECURSOR_CITY -> "PC";
            case KHAZAD_DUM -> "B";
            case FAIRY_GROTTO -> "FG";
            case DRAGONS_LAIR -> "DL";
            case CORLEONE -> "C";
            case KING_YOLKAR -> "KY";
            case ODAWA -> "O";
            case KEY_GUARDIAN -> "KG";
            case XALX -> "X";
            case CRYSTAL_NUCLEUS -> "N";
            case WISHING_TARGET -> "?";
        };
    }

    private static String text(String key) {
        return Component.translatable("waypointer.crystal.minimap." + key).getString();
    }

    // Minecraft's bitmap font adds one trailing advance pixel; its eighth row holds descenders.
    private static int inkWidth(String text) {
        return Math.max(0, Minecraft.getInstance().font.width(text) - 1);
    }

    private static int inkHeight(String text) {
        return text.codePoints().anyMatch(c -> c > 127 || "gjpqy".indexOf(c) >= 0) ? 8 : 7;
    }

    private static void drawText(GuiGraphicsExtractor g, String text, float x, float y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.text(Minecraft.getInstance().font, text, 0, 0, color, false);
        g.pose().popMatrix();
    }

    private static Rect centered(GuiGraphicsExtractor g, String text, float x, float y, int width, int color) {
        var font = Minecraft.getInstance().font;
        float scale = Math.min(1.0f, (float) width / Math.max(1, inkWidth(text)));
        if (scale < 0.7f) return new Rect((int) x, (int) y, 0, 0);
        float actualWidth = inkWidth(text) * scale;
        float actualHeight = inkHeight(text) * scale;
        g.pose().pushMatrix();
        g.pose().translate(x - actualWidth / 2f, y - actualHeight / 2f);
        g.pose().scale(scale, scale);
        g.text(font, text, 0, 0, color, false);
        g.pose().popMatrix();
        return new Rect((int) Math.floor(x - actualWidth / 2f), (int) Math.floor(y - actualHeight / 2f),
                (int) Math.ceil(actualWidth) + 1, (int) Math.ceil(actualHeight) + 1);
    }

    private static void panel(GuiGraphicsExtractor g, int x, int y, int width, int height, int color) {
        g.fill(x + 2, y, x + width - 2, y + height, color);
        g.fill(x + 1, y + 1, x + 2, y + height - 1, color);
        g.fill(x + width - 2, y + 1, x + width - 1, y + height - 1, color);
        g.fill(x, y + 2, x + 1, y + height - 2, color);
        g.fill(x + width - 1, y + 2, x + width, y + height - 2, color);
    }

    private static void region(GuiGraphicsExtractor g, int x, int y, int width, int height, int top, int bottom) {
        for (int row = 0; row < height; row += 4) {
            g.fill(x, y + row, x + width, y + Math.min(row + 4, height),
                    mix(top, bottom, (double) row / Math.max(1, height - 1)));
        }
    }

    private static void octagon(GuiGraphicsExtractor g, int x, int y, int diameter, int color) {
        int bevel = Math.max(1, diameter / 4);
        for (int row = 0; row < diameter; row++) {
            int inset = Math.max(0, bevel - Math.min(row, diameter - 1 - row));
            g.fill(x + inset, y + row, x + diameter - inset, y + row + 1, color);
        }
    }

    private static int mix(int a, int b, double amount) {
        int color = 0xFF000000;
        for (int shift = 0; shift < 24; shift += 8) {
            int channel = (a >> shift) & 255;
            color |= (int) Math.round(channel + (((b >> shift) & 255) - channel) * amount) << shift;
        }
        return color;
    }

    private static void gem(GuiGraphicsExtractor g, int x, int y, int color) {
        diamond(g, x, y, 3, color, true);
        g.fill(x - 1, y - 2, x, y + 1, mix(color, 0xFFFFFFFF, 0.5));
        g.fill(x, y + 1, x + 1, y + 3, mix(color, 0xFF111B22, 0.35));
    }

    private static void diamond(GuiGraphicsExtractor g, int x, int y, int radius, int color, boolean filled) {
        for (int dy = -radius; dy <= radius; dy++) {
            int span = radius - Math.abs(dy);
            if (filled) g.fill(x - span, y + dy, x + span + 1, y + dy + 1, color);
            else {
                g.fill(x - span, y + dy, x - span + 1, y + dy + 1, color);
                g.fill(x + span, y + dy, x + span + 1, y + dy + 1, color);
            }
        }
    }

    private static void arrow(GuiGraphicsExtractor g) {
        for (int row = 0; row < 7; row++) {
            int half = row / 2;
            g.fill(-half - 1, row - 5, half + 2, row - 3, 0xFF101820);
        }
        for (int row = 0; row < 7; row++) {
            int half = row / 2;
            if (row < 5) g.fill(-half, row - 5, half + 1, row - 4, 0xFFF1F8FA);
            else {
                g.fill(-half, row - 5, 0, row - 4, 0xFFF1F8FA);
                g.fill(1, row - 5, half + 1, row - 4, 0xFFF1F8FA);
            }
        }
    }

    private static void line(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int step = 0; step <= steps; step += 3) {
            double t = steps == 0 ? 0 : (double) step / steps;
            int x = (int) Math.round(x1 + (x2 - x1) * t);
            int y = (int) Math.round(y1 + (y2 - y1) * t);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
}
