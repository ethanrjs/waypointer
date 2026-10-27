package com.babbur.waypointer.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.sdl.SDLScancode;

/** Platform key codes used by UI events; polling accepts physical InputConstants keys. */
public final class InputCompat {
    private InputCompat() {}

    public static final int KEY_A = SDLScancode.SDL_SCANCODE_A;
    public static final int KEY_DELETE = SDLScancode.SDL_SCANCODE_DELETE;
    public static final int KEY_DOWN = SDLScancode.SDL_SCANCODE_DOWN;
    public static final int KEY_END = SDLScancode.SDL_SCANCODE_END;
    public static final int KEY_ENTER = SDLScancode.SDL_SCANCODE_RETURN;
    public static final int KEY_EQUAL = SDLScancode.SDL_SCANCODE_EQUALS;
    public static final int KEY_ESCAPE = SDLScancode.SDL_SCANCODE_ESCAPE;
    public static final int KEY_F = SDLScancode.SDL_SCANCODE_F;
    public static final int KEY_F5 = SDLScancode.SDL_SCANCODE_F5;
    public static final int KEY_HOME = SDLScancode.SDL_SCANCODE_HOME;
    public static final int KEY_KP_ADD = SDLScancode.SDL_SCANCODE_KP_PLUS;
    public static final int KEY_KP_ENTER = SDLScancode.SDL_SCANCODE_KP_ENTER;
    public static final int KEY_KP_SUBTRACT = SDLScancode.SDL_SCANCODE_KP_MINUS;
    public static final int KEY_LEFT = SDLScancode.SDL_SCANCODE_LEFT;
    public static final int KEY_LEFT_CONTROL = SDLScancode.SDL_SCANCODE_LCTRL;
    public static final int KEY_LEFT_SHIFT = SDLScancode.SDL_SCANCODE_LSHIFT;
    public static final int KEY_MENU = SDLScancode.SDL_SCANCODE_APPLICATION;
    public static final int KEY_MINUS = SDLScancode.SDL_SCANCODE_MINUS;
    public static final int KEY_PAGE_DOWN = SDLScancode.SDL_SCANCODE_PAGEDOWN;
    public static final int KEY_PAGE_UP = SDLScancode.SDL_SCANCODE_PAGEUP;
    public static final int KEY_RIGHT = SDLScancode.SDL_SCANCODE_RIGHT;
    public static final int KEY_RIGHT_CONTROL = SDLScancode.SDL_SCANCODE_RCTRL;
    public static final int KEY_RIGHT_SHIFT = SDLScancode.SDL_SCANCODE_RSHIFT;
    public static final int KEY_SPACE = SDLScancode.SDL_SCANCODE_SPACE;
    public static final int KEY_TAB = SDLScancode.SDL_SCANCODE_TAB;
    public static final int KEY_U = SDLScancode.SDL_SCANCODE_U;
    public static final int KEY_UP = SDLScancode.SDL_SCANCODE_UP;
    public static final int KEY_Y = SDLScancode.SDL_SCANCODE_Y;
    public static final int KEY_Z = SDLScancode.SDL_SCANCODE_Z;
    public static final int MOD_SHIFT = InputConstants.MOD_SHIFT;
    public static final int MOD_CONTROL = InputConstants.MOD_CONTROL;
    public static final int MOUSE_BUTTON_LEFT = InputConstants.MOUSE_BUTTON_LEFT;

    public static boolean isKeyDown(Window window, int scanCode) {
        return InputConstants.isKeyDown(scanCode);
    }

    public static InputConstants.Type keyboardType() {
        return InputConstants.Type.KEYBOARD;
    }
}
