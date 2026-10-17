package com.babbur.waypointer.screen;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.dungeon.DungeonWaypointType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Waypointer's GUI icons: one greyscale 16x16 atlas ({@code textures/gui/icons.png}, drawn in
 * {@code tools/icons/icons.txt}) tinted at draw time, so the same icon can follow the text
 * colour, the accent on hover, or a folder's own colour. Resource packs can replace the atlas.
 */
public final class Icons {

    public static final int SIZE = 16;
    private static final int COLUMNS = 16;
    private static final Identifier ATLAS = Identifier.fromNamespaceAndPath(
            Waypointer.MOD_ID, "textures/gui/icons.png");

    /** Atlas order; must match {@code tools/icons/icon-names.txt}. */
    public enum Icon {
        SUB_SMALL, SUB_FILLED, SUB_HIDE_AFTER_PARENT,
        SKIP_STAND, SKIP_INTERACT, SKIP_MINE, DEPTH_CHECK,
        DUNGEON_SECRET, DUNGEON_ETHERWARP, DUNGEON_BREAKER, DUNGEON_SUPERBOOM,
        DUNGEON_PEARL, DUNGEON_PEARL_TARGET, DUNGEON_ITEM, DUNGEON_BAT, TAG,
        FOLDER, FOLDER_OPEN, CHECK, EYE, EYE_OFF, PLUS, TRASH, PENCIL, UNDO, REDO,
        INFO, GEAR, GLOBE, UPLOAD, DOWNLOAD, EXPORT, PIN, TARGET,
        RESET, BRUSH, CLOCK, SPARKLE, STAR, SKULL, MARKER, PREV_NEXT, TRACER, BEACON, ROUTE,
        CHAT, SHARE, MAIL, CLOSE, DOT, BADGE_GEM, BADGE_CHECK;

        float u() { return (ordinal() % COLUMNS) * (float) SIZE; }

        float v() { return (ordinal() / COLUMNS) * (float) SIZE; }
    }

    private static final int ATLAS_WIDTH = COLUMNS * SIZE;
    private static final int ATLAS_HEIGHT = ((Icon.values().length + COLUMNS - 1) / COLUMNS) * SIZE;

    private Icons() {}

    /** Draws {@code icon} with its top-left corner at (x, y), tinted with {@code argb}. */
    public static void draw(GuiGraphicsExtractor g, Icon icon, int x, int y, int argb) {
        g.blit(RenderPipelines.GUI_TEXTURED, ATLAS, x, y, icon.u(), icon.v(),
                SIZE, SIZE, ATLAS_WIDTH, ATLAS_HEIGHT, argb);
    }

    /** Draws {@code icon} with its cell centred on (centerX, centerY); glyphs are centred in their cell. */
    public static void drawAt(GuiGraphicsExtractor g, Icon icon, int centerX, int centerY, int argb) {
        draw(g, icon, centerX - SIZE / 2, centerY - SIZE / 2, argb);
    }

    /** Draws {@code icon} centred in the box (x, y, width, height). */
    public static void drawCentered(GuiGraphicsExtractor g, Icon icon,
                                    int x, int y, int width, int height, int argb) {
        draw(g, icon, x + (width - SIZE) / 2, y + (height - SIZE) / 2, argb);
    }

    public static Icon forDungeonType(DungeonWaypointType type) {
        if (type == null) return Icon.TAG;
        return switch (type) {
            case SECRET -> Icon.DUNGEON_SECRET;
            case ETHERWARP -> Icon.DUNGEON_ETHERWARP;
            case DUNGEONBREAKER -> Icon.DUNGEON_BREAKER;
            case SUPERBOOM -> Icon.DUNGEON_SUPERBOOM;
            case PEARL -> Icon.DUNGEON_PEARL;
            case PEARL_TARGET -> Icon.DUNGEON_PEARL_TARGET;
            case ITEM -> Icon.DUNGEON_ITEM;
            case BAT -> Icon.DUNGEON_BAT;
        };
    }
}
