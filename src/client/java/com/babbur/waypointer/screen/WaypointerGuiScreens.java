package com.babbur.waypointer.screen;

import net.minecraft.client.gui.screens.Screen;

public final class WaypointerGuiScreens {

    private WaypointerGuiScreens() {}

    public static boolean owns(Screen screen) {
        return screen != null
                && screen.getClass().getPackageName().equals(WaypointerScreen.class.getPackageName());
    }
}
