package com.babbur.waypointer.screen;

import net.minecraft.client.gui.screens.Screen;

/**
 * Small ownership helper for Waypointer GUI screens.
 */
public final class WaypointerGuiScreens {

    private WaypointerGuiScreens() {}

    public static boolean owns(Screen screen) {
        return screen != null
                && screen.getClass().getPackageName().equals(WaypointerScreen.class.getPackageName());
    }
}
