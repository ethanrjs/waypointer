package dev.ethan.waypointer.util;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class MinecraftClientCompat {

    private MinecraftClientCompat() {}

    public static Screen screen(Minecraft minecraft) {
        if (minecraft == null) return null;

        Object gui = fieldValue(minecraft, "gui");
        Object screen = invokeNoArgs(gui, "screen");
        if (screen instanceof Screen current) return current;

        Object legacyScreen = fieldValue(minecraft, "screen");
        return legacyScreen instanceof Screen current ? current : null;
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        if (minecraft == null) return;

        Object gui = fieldValue(minecraft, "gui");
        if (invoke(gui, "setScreen", new Class<?>[]{Screen.class}, screen)) return;
        if (invoke(minecraft, "setScreen", new Class<?>[]{Screen.class}, screen)) return;

        throw new IllegalStateException("No supported Minecraft screen setter is available");
    }

    public static ToastManager toastManager(Minecraft minecraft) {
        if (minecraft == null) return null;

        Object gui = fieldValue(minecraft, "gui");
        Object manager = invokeNoArgs(gui, "toastManager");
        if (manager instanceof ToastManager toastManager) return toastManager;

        Object legacyManager = invokeNoArgs(minecraft, "getToastManager");
        return legacyManager instanceof ToastManager toastManager ? toastManager : null;
    }

    public static Camera mainCamera(GameRenderer renderer) {
        if (renderer == null) return null;

        Object camera = invokeNoArgs(renderer, "mainCamera");
        if (camera instanceof Camera current) return current;

        Object legacyCamera = invokeNoArgs(renderer, "getMainCamera");
        return legacyCamera instanceof Camera current ? current : null;
    }

    public static void sendChatMessage(LocalPlayer player, Component message) {
        if (player == null || message == null) return;

        if (invoke(player, "sendSystemMessage", new Class<?>[]{Component.class}, message)) return;
        invoke(player, "displayClientMessage", new Class<?>[]{Component.class, boolean.class}, message, false);
    }

    public static void sendOverlayMessage(Minecraft minecraft, Component message) {
        if (minecraft == null || message == null) return;

        LocalPlayer player = minecraft.player;
        if (player != null && invoke(player, "sendOverlayMessage", new Class<?>[]{Component.class}, message)) {
            return;
        }

        Object gui = fieldValue(minecraft, "gui");
        Object hud = fieldValue(gui, "hud");
        if (invoke(hud, "setOverlayMessage", new Class<?>[]{Component.class, boolean.class}, message, false)) {
            return;
        }
        invoke(gui, "setOverlayMessage", new Class<?>[]{Component.class, boolean.class}, message, false);
    }

    public static void addChatMessage(Minecraft minecraft, Component message) {
        if (minecraft == null || message == null) return;

        Object gui = fieldValue(minecraft, "gui");
        Object hud = fieldValue(gui, "hud");
        Object chat = invokeNoArgs(hud, "getChat");
        if (chat == null) chat = invokeNoArgs(gui, "getChat");
        invoke(chat, "addMessage", new Class<?>[]{Component.class}, message);
    }

    private static Object fieldValue(Object target, String name) {
        if (target == null) return null;

        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object invokeNoArgs(Object target, String name) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (IllegalAccessException | NoSuchMethodException | RuntimeException ignored) {
            return null;
        } catch (InvocationTargetException ignored) {
            return null;
        }
    }

    private static boolean invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) {
        if (target == null) return false;
        try {
            Method method = target.getClass().getMethod(name, parameterTypes);
            method.invoke(target, args);
            return true;
        } catch (IllegalAccessException | NoSuchMethodException | RuntimeException ignored) {
            return false;
        } catch (InvocationTargetException ignored) {
            return false;
        }
    }
}
