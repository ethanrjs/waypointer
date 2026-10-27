package com.babbur.waypointer.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;

/** Platform key codes used by UI events; polling accepts physical InputConstants keys. */
public final class InputCompat {
    private InputCompat() {}

    public static final int KEY_A = GLFW.GLFW_KEY_A;
    public static final int KEY_DELETE = GLFW.GLFW_KEY_DELETE;
    public static final int KEY_DOWN = GLFW.GLFW_KEY_DOWN;
    public static final int KEY_END = GLFW.GLFW_KEY_END;
    public static final int KEY_ENTER = GLFW.GLFW_KEY_ENTER;
    public static final int KEY_EQUAL = GLFW.GLFW_KEY_EQUAL;
    public static final int KEY_ESCAPE = GLFW.GLFW_KEY_ESCAPE;
    public static final int KEY_F = GLFW.GLFW_KEY_F;
    public static final int KEY_F5 = GLFW.GLFW_KEY_F5;
    public static final int KEY_HOME = GLFW.GLFW_KEY_HOME;
    public static final int KEY_KP_ADD = GLFW.GLFW_KEY_KP_ADD;
    public static final int KEY_KP_ENTER = GLFW.GLFW_KEY_KP_ENTER;
    public static final int KEY_KP_SUBTRACT = GLFW.GLFW_KEY_KP_SUBTRACT;
    public static final int KEY_LEFT = GLFW.GLFW_KEY_LEFT;
    public static final int KEY_LEFT_CONTROL = GLFW.GLFW_KEY_LEFT_CONTROL;
    public static final int KEY_LEFT_SHIFT = GLFW.GLFW_KEY_LEFT_SHIFT;
    public static final int KEY_MENU = GLFW.GLFW_KEY_MENU;
    public static final int KEY_MINUS = GLFW.GLFW_KEY_MINUS;
    public static final int KEY_PAGE_DOWN = GLFW.GLFW_KEY_PAGE_DOWN;
    public static final int KEY_PAGE_UP = GLFW.GLFW_KEY_PAGE_UP;
    public static final int KEY_RIGHT = GLFW.GLFW_KEY_RIGHT;
    public static final int KEY_RIGHT_CONTROL = GLFW.GLFW_KEY_RIGHT_CONTROL;
    public static final int KEY_RIGHT_SHIFT = GLFW.GLFW_KEY_RIGHT_SHIFT;
    public static final int KEY_SPACE = GLFW.GLFW_KEY_SPACE;
    public static final int KEY_TAB = GLFW.GLFW_KEY_TAB;
    public static final int KEY_U = GLFW.GLFW_KEY_U;
    public static final int KEY_UP = GLFW.GLFW_KEY_UP;
    public static final int KEY_Y = GLFW.GLFW_KEY_Y;
    public static final int KEY_Z = GLFW.GLFW_KEY_Z;
    public static final int MOD_SHIFT = InputConstants.MOD_SHIFT;
    public static final int MOD_CONTROL = InputConstants.MOD_CONTROL;
    public static final int MOUSE_BUTTON_LEFT = InputConstants.MOUSE_BUTTON_LEFT;

    public static boolean isKeyDown(Window window, int scanCode) {
        return InputConstants.isKeyDown(window, scanCode);
    }

    public static InputConstants.Type keyboardType() {
        return InputConstants.Type.KEYSYM;
    }
}
