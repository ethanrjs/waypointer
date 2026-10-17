package com.babbur.waypointer.screen;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.Zone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Blurred, darkened screenshots of each zone ({@code textures/gui/zones/<zone>.png}, built by
 * {@code tools/zones/build_backdrops.py}) shown behind a community route's details. A zone
 * without its own picture falls back to its family: any Catacombs floor to {@code dungeon},
 * any mineshaft to {@code mineshaft}, any Crystal Hollows structure to {@code crystal_hollows}.
 * Resource packs can add or replace pictures.
 */
final class ZoneBackdrops {
    static final int WIDTH = 320;
    static final int HEIGHT = 180;

    private static final Map<String, Optional<Identifier>> FOUND = new HashMap<>();

    private ZoneBackdrops() {}

    /** Forget lookups, e.g. after resource packs change. */
    static void clearCache() {
        FOUND.clear();
    }

    /** Picture names to try for a zone, most specific first. */
    static List<String> candidates(String zoneId) {
        List<String> names = new ArrayList<>();
        if (zoneId == null || zoneId.isBlank()) return names;
        String id = Zone.canonicalId(zoneId).toLowerCase(Locale.ROOT);
        names.add(id);
        if (id.startsWith("dungeon_") && !id.equals("dungeon_hub")) names.add("dungeon");
        if (id.startsWith("mineshaft_")) names.add("mineshaft");
        if (id.startsWith("crystal_hollows_structure_")) names.add("crystal_hollows");
        return names;
    }

    static Identifier find(String zoneId) {
        for (String name : candidates(zoneId)) {
            Optional<Identifier> found = FOUND.computeIfAbsent(name, ZoneBackdrops::lookup);
            if (found.isPresent()) return found.get();
        }
        return null;
    }

    private static Optional<Identifier> lookup(String name) {
        if (!name.matches("[a-z0-9_]+")) return Optional.empty();
        Identifier id = Identifier.fromNamespaceAndPath(Waypointer.MOD_ID, "textures/gui/zones/" + name + ".png");
        Minecraft client = Minecraft.getInstance();
        if (client == null) return Optional.empty();
        return client.getResourceManager().getResource(id).isPresent() ? Optional.of(id) : Optional.empty();
    }

    /**
     * Fills the box with the zone's picture, cropped to cover it, under a shade that keeps text
     * readable. Returns false when the zone has no picture.
     */
    static boolean draw(GuiGraphicsExtractor g, String zoneId, int x1, int y1, int x2, int y2) {
        Identifier texture = find(zoneId);
        if (texture == null || x2 <= x1 || y2 <= y1) return false;
        float boxAspect = (x2 - x1) / (float) (y2 - y1);
        float imageAspect = WIDTH / (float) HEIGHT;
        float u0 = 0f, u1 = 1f, v0 = 0f, v1 = 1f;
        if (boxAspect > imageAspect) {
            float visible = imageAspect / boxAspect;
            v0 = (1f - visible) / 2f;
            v1 = v0 + visible;
        } else {
            float visible = boxAspect / imageAspect;
            u0 = (1f - visible) / 2f;
            u1 = u0 + visible;
        }
        g.blit(texture, x1, y1, x2, y2, u0, u1, v0, v1);
        // Lighter at the top so the place reads, darker towards the description.
        g.fillGradient(x1, y1, x2, y2, 0x66101216, 0xD8101216);
        return true;
    }
}
