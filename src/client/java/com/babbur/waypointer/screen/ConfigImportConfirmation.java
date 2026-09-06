package com.babbur.waypointer.screen;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.screen.settings.SettingsCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.Consumer;

/** Confirms configuration imports before applying changes. */
public final class ConfigImportConfirmation {

    public record Outcome(boolean confirmed, int changedSettings) {}

    private ConfigImportConfirmation() {}

    public static void open(Screen parent, WaypointerConfig current,
                            WaypointerConfig imported, Consumer<Outcome> completion) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(imported, "imported");
        Objects.requireNonNull(completion, "completion");

        int changedSettings = SettingsCatalog.countChangedSettings(current, imported);
        Minecraft minecraft = Minecraft.getInstance();
        ConfirmScreen confirmScreen = new ConfirmScreen(
                confirmed -> {
                    Outcome outcome = complete(current, imported, confirmed);
                    MinecraftCompat.setScreen(minecraft, parent);
                    completion.accept(outcome);
                },
                Component.translatable("waypointer.screen.settings.config.confirm.title"),
                Component.translatable(changedSettings == 1
                        ? "waypointer.screen.settings.config.confirm.one"
                        : "waypointer.screen.settings.config.confirm.many", changedSettings),
                Component.translatable("waypointer.screen.settings.config.import_settings"),
                Component.translatable("gui.cancel"));
        MinecraftCompat.setScreen(minecraft, confirmScreen);
    }

    public static Outcome complete(WaypointerConfig current, WaypointerConfig imported,
                                   boolean confirmed) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(imported, "imported");
        int changedSettings = SettingsCatalog.countChangedSettings(current, imported);
        if (confirmed) current.replaceShareableSettingsWith(imported);
        return new Outcome(confirmed, changedSettings);
    }
}
