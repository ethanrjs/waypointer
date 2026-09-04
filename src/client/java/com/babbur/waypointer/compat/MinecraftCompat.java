package com.babbur.waypointer.compat;

import com.mojang.blaze3d.systems.GpuDevice;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Bridge for Mojang API moves between Minecraft 26.1 and 26.2.
 * Resolves whichever accessor exists at runtime via reflection.
 */
public final class MinecraftCompat {

    // Screen: Minecraft.screen / setScreen (26.1) vs Gui.screen / setScreen (26.2)
    private static final Field MINECRAFT_SCREEN = findField(Minecraft.class, "screen");
    private static final Method MINECRAFT_SET_SCREEN =
            findMethod(Minecraft.class, "setScreen", Screen.class);
    private static final Method GUI_SCREEN = findMethod(Gui.class, "screen");
    private static final Method GUI_SET_SCREEN = findMethod(Gui.class, "setScreen", Screen.class);

    // Chat / overlay / toasts: Gui helpers vs nested Hud object
    private static final Method GUI_GET_CHAT = findMethod(Gui.class, "getChat");
    private static final Method GUI_SET_OVERLAY_MESSAGE =
            findMethod(Gui.class, "setOverlayMessage", Component.class, boolean.class);
    private static final Method MINECRAFT_GET_TOAST_MANAGER =
            findMethod(Minecraft.class, "getToastManager");
    private static final Method GUI_TOAST_MANAGER = findMethod(Gui.class, "toastManager");
    private static final Field GUI_HUD = findField(Gui.class, "hud");
    private static final Method HUD_GET_CHAT = GUI_HUD == null
            ? null : findMethod(GUI_HUD.getType(), "getChat");
    private static final Method HUD_SET_OVERLAY_MESSAGE = GUI_HUD == null
            ? null : findMethod(GUI_HUD.getType(), "setOverlayMessage", Component.class, boolean.class);

    // getX vs bare x naming on GameRenderer
    private static final Method GAME_RENDERER_GET_MAIN_CAMERA =
            findMethod(GameRenderer.class, "getMainCamera");
    private static final Method GAME_RENDERER_MAIN_CAMERA =
            findMethod(GameRenderer.class, "mainCamera");
    private static final Method GAME_RENDERER_GET_GAME_RENDER_STATE =
            findMethod(GameRenderer.class, "getGameRenderState");
    private static final Method GAME_RENDERER_GAME_RENDER_STATE =
            findMethod(GameRenderer.class, "gameRenderState");

    // Flat GpuDevice getters vs DeviceInfo object
    private static final Method GPU_GET_VENDOR = findMethod(GpuDevice.class, "getVendor");
    private static final Method GPU_GET_BACKEND_NAME = findMethod(GpuDevice.class, "getBackendName");
    private static final Method GPU_GET_IMPLEMENTATION_INFORMATION =
            findMethod(GpuDevice.class, "getImplementationInformation");
    private static final Method GPU_GET_DEVICE_INFO = findMethod(GpuDevice.class, "getDeviceInfo");
    private static final Method DEVICE_INFO_VENDOR_NAME = deviceInfoMethod("vendorName");
    private static final Method DEVICE_INFO_BACKEND_NAME = deviceInfoMethod("backendName");
    private static final Method DEVICE_INFO_NAME = deviceInfoMethod("name");
    private static final Method DEVICE_INFO_DRIVER_INFO = deviceInfoMethod("driverInfo");

    private MinecraftCompat() {}

    public static Screen screen(Minecraft minecraft) {
        if (minecraft == null) return null;
        if (MINECRAFT_SCREEN != null) {
            return (Screen) read(MINECRAFT_SCREEN, minecraft);
        }
        return (Screen) invokeRequired(GUI_SCREEN, minecraft.gui, "current screen");
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        if (minecraft == null) return;
        if (MINECRAFT_SET_SCREEN != null) {
            invoke(MINECRAFT_SET_SCREEN, minecraft, screen);
            return;
        }
        invokeRequired(GUI_SET_SCREEN, minecraft.gui, "screen setter", screen);
    }

    public static void addClientSystemMessage(Minecraft minecraft, Component message) {
        if (minecraft == null || minecraft.gui == null || message == null) return;
        ChatComponent chat;
        if (GUI_GET_CHAT != null) {
            chat = (ChatComponent) invoke(GUI_GET_CHAT, minecraft.gui);
        } else {
            chat = (ChatComponent) invokeRequired(HUD_GET_CHAT, hud(minecraft.gui), "chat component");
        }
        chat.addClientSystemMessage(message);
    }

    public static void setOverlayMessage(Minecraft minecraft, Component message, boolean animate) {
        if (minecraft == null || minecraft.gui == null || message == null) return;
        if (GUI_SET_OVERLAY_MESSAGE != null) {
            invoke(GUI_SET_OVERLAY_MESSAGE, minecraft.gui, message, animate);
            return;
        }
        invokeRequired(HUD_SET_OVERLAY_MESSAGE, hud(minecraft.gui), "overlay message", message, animate);
    }

    public static ToastManager toastManager(Minecraft minecraft) {
        if (minecraft == null || minecraft.gui == null) return null;
        if (MINECRAFT_GET_TOAST_MANAGER != null) {
            return (ToastManager) invoke(MINECRAFT_GET_TOAST_MANAGER, minecraft);
        }
        return (ToastManager) invokeRequired(GUI_TOAST_MANAGER, minecraft.gui, "toast manager");
    }

    public static Camera mainCamera(GameRenderer renderer) {
        if (renderer == null) return null;
        Method accessor = GAME_RENDERER_GET_MAIN_CAMERA != null
                ? GAME_RENDERER_GET_MAIN_CAMERA : GAME_RENDERER_MAIN_CAMERA;
        return (Camera) invokeRequired(accessor, renderer, "main camera");
    }

    public static GameRenderState gameRenderState(GameRenderer renderer) {
        if (renderer == null) return null;
        Method accessor = GAME_RENDERER_GET_GAME_RENDER_STATE != null
                ? GAME_RENDERER_GET_GAME_RENDER_STATE : GAME_RENDERER_GAME_RENDER_STATE;
        return (GameRenderState) invokeRequired(accessor, renderer, "game render state");
    }

    public static Integer legacyColor(ChatFormatting formatting) {
        if (formatting == null) return null;
        TextColor color = TextColor.fromLegacyFormat(formatting);
        return color == null ? null : color.getValue();
    }

    public static GpuInfo gpuInfo(GpuDevice device) {
        if (device == null) return new GpuInfo("unknown", "unknown", "");
        if (GPU_GET_DEVICE_INFO == null) {
            return new GpuInfo(
                    stringValue(invokeRequired(GPU_GET_VENDOR, device, "GPU vendor"), "unknown"),
                    stringValue(invokeRequired(GPU_GET_BACKEND_NAME, device, "GPU backend"), "unknown"),
                    stringValue(invokeRequired(
                            GPU_GET_IMPLEMENTATION_INFORMATION, device, "GPU implementation"), ""));
        }

        Object info = invoke(GPU_GET_DEVICE_INFO, device);
        String name = stringValue(invokeRequired(DEVICE_INFO_NAME, info, "GPU name"), "");
        String driver = stringValue(invokeRequired(DEVICE_INFO_DRIVER_INFO, info, "GPU driver"), "");
        String implementation = name.isBlank() ? driver
                : driver.isBlank() ? name : name + " - " + driver;
        return new GpuInfo(
                stringValue(invokeRequired(DEVICE_INFO_VENDOR_NAME, info, "GPU vendor"), "unknown"),
                stringValue(invokeRequired(DEVICE_INFO_BACKEND_NAME, info, "GPU backend"), "unknown"),
                implementation);
    }

    public record GpuInfo(String vendor, String backend, String implementation) {}

    private static Object hud(Gui gui) {
        if (GUI_HUD == null) {
            throw new IllegalStateException("Minecraft HUD binding is unavailable");
        }
        return read(GUI_HUD, gui);
    }

    private static Method deviceInfoMethod(String name) {
        return GPU_GET_DEVICE_INFO == null
                ? null : findMethod(GPU_GET_DEVICE_INFO.getReturnType(), name);
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static Field findField(Class<?> owner, String name) {
        try {
            return owner.getField(name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Object read(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not read Minecraft compatibility field "
                    + field.getName(), e);
        }
    }

    private static Object invokeRequired(Method method, Object owner, String binding,
                                         Object... arguments) {
        if (method == null) {
            throw new IllegalStateException("Minecraft " + binding + " binding is unavailable");
        }
        return invoke(method, owner, arguments);
    }

    private static Object invoke(Method method, Object owner, Object... arguments) {
        try {
            return method.invoke(owner, arguments);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not call Minecraft compatibility method "
                    + method.getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Minecraft compatibility method failed: "
                    + method.getName(), cause);
        }
    }
}
