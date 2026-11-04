package com.babbur.waypointer.screen;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.dungeon.DungeonWaypointType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class Icons {

    public static final int SIZE = 16;
    private static final int COLUMNS = 16;
    private static final Identifier ATLAS = Identifier.fromNamespaceAndPath(
            Waypointer.MOD_ID, "textures/gui/icons.png");

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

    public static void draw(GuiGraphicsExtractor g, Icon icon, int x, int y, int argb) {
        g.blit(RenderPipelines.GUI_TEXTURED, ATLAS, x, y, icon.u(), icon.v(),
                SIZE, SIZE, ATLAS_WIDTH, ATLAS_HEIGHT, argb);
    }

    public static void drawAt(GuiGraphicsExtractor g, Icon icon, int centerX, int centerY, int argb) {
        draw(g, icon, centerX - SIZE / 2, centerY - SIZE / 2, argb);
    }

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
