package com.babbur.waypointer;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.screen.WaypointPainterScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import java.util.Arrays;
import static com.babbur.waypointer.compat.InputCompat.*;

@SuppressWarnings("UnstableApiUsage")
final class PainterHistorySmokeTest {
    static void run(ClientGameTestContext context) {
        context.runOnClient(client -> {
            Screen previous = MinecraftCompat.screen(client);
            try {
                var painter = new WaypointPainterScreen(null, new WaypointerConfig(), new ActiveGroupManager());
                MinecraftCompat.setScreen(client, painter);
                byte[] pixels = (byte[]) field(painter, "pixels");
                byte[] initial = pixels.clone();
                click(painter, ((AbstractWidget[]) field(painter, "paletteButtons"))[1]);
                Object layout = call(painter, "layout");
                int cell = (Integer) call(layout, "cell");
                double x = (Integer) call(layout, "gridLeft") + cell * 2.5;
                double y = (Integer) call(layout, "gridTop") + cell * 3.5;
                stroke(painter, x, y, x + cell, y);
                byte[] painted = pixels.clone();
                check(!Arrays.equals(initial, painted), "Texture stroke did not paint");
                shortcut(painter, KEY_Z, 0);
                check(Arrays.equals(initial, pixels), "Texture undo did not undo the whole stroke");
                click(painter, (AbstractWidget) field(painter, "redoButton"));
                check(Arrays.equals(painted, pixels), "Texture redo button did not restore stroke");
                stroke(painter, x, y, x + cell, y);
                click(painter, (AbstractWidget) field(painter, "undoButton"));
                check(Arrays.equals(initial, pixels), "No-op stroke added an undo step");
                shortcut(painter, KEY_Y, 0);
                check(Arrays.equals(painted, pixels), "Ctrl+Y did not redo texture stroke");

            } finally {
                MinecraftCompat.setScreen(client, previous);
            }
        });
    }

    private static void stroke(Screen screen, double x, double y, double endX, double endY) {
        screen.mouseClicked(mouse(x, y), false);
        screen.mouseDragged(mouse(endX, endY), endX - x, endY - y);
        screen.mouseReleased(mouse(endX, endY));
    }

    private static void click(Screen screen, AbstractWidget button) {
        check(button.active, "History action unexpectedly disabled: " + button.getMessage().getString());
        double x = button.getX() + button.getWidth() / 2.0;
        double y = button.getY() + button.getHeight() / 2.0;
        screen.mouseClicked(mouse(x, y), false);
        screen.mouseReleased(mouse(x, y));
    }

    private static MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT, 0));
    }

    private static void shortcut(Screen screen, int key, int modifiers) {
        check(screen.keyPressed(new KeyEvent(key, 0, MOD_CONTROL | modifiers)), "Shortcut not handled");
    }

    private static Object field(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Object call(Object target, String name) {
        try {
            var method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
