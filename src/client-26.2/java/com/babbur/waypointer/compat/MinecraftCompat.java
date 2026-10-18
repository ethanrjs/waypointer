package com.babbur.waypointer.compat;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

public final class MinecraftCompat {

    private MinecraftCompat() {}

    public static Screen screen(Minecraft minecraft) {
        if (minecraft == null || minecraft.gui == null) return null;
        return minecraft.gui.screen();
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        if (minecraft == null || minecraft.gui == null) return;
        minecraft.gui.setScreen(screen);
    }

    public static void addClientSystemMessage(Minecraft minecraft, Component message) {
        if (minecraft == null || minecraft.gui == null || message == null) return;
        minecraft.gui.hud.getChat().addClientSystemMessage(message);
    }

    public static void setOverlayMessage(Minecraft minecraft, Component message, boolean animate) {
        if (minecraft == null || minecraft.gui == null || message == null) return;
        minecraft.gui.hud.setOverlayMessage(message, animate);
    }

    public static ToastManager toastManager(Minecraft minecraft) {
        if (minecraft == null || minecraft.gui == null) return null;
        return minecraft.gui.toastManager();
    }

    public static Camera mainCamera(GameRenderer renderer) {
        return renderer == null ? null : renderer.mainCamera();
    }

    public static GameRenderState gameRenderState(GameRenderer renderer) {
        return renderer == null ? null : renderer.gameRenderState();
    }

    public static Integer legacyColor(ChatFormatting formatting) {
        if (formatting == null) return null;
        TextColor color = TextColor.fromLegacyFormat(formatting);
        return color == null ? null : color.getValue();
    }

    public static GpuInfo gpuInfo(GpuDevice device) {
        if (device == null) return new GpuInfo("unknown", "unknown", "");
        DeviceInfo info = device.getDeviceInfo();
        String name = stringValue(info.name(), "");
        String driver = stringValue(info.driverInfo(), "");
        String implementation = name.isBlank() ? driver
                : driver.isBlank() ? name : name + " - " + driver;
        return new GpuInfo(stringValue(info.vendorName(), "unknown"),
                stringValue(info.backendName(), "unknown"), implementation);
    }

    public record GpuInfo(String vendor, String backend, String implementation) {}

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }
}
