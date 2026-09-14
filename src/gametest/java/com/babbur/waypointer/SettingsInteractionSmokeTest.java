package com.babbur.waypointer;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.screen.SettingsScreen;
import com.babbur.waypointer.screen.settings.Setting;
import com.babbur.waypointer.screen.settings.SettingsCatalog;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Exercise the actual checkbox/rebuild path that used to move the category heading. */
@SuppressWarnings("UnstableApiUsage")
final class SettingsInteractionSmokeTest {
    static void run(ClientGameTestContext context) {
        int scale = context.computeOnClient(client -> client.options.guiScale().get());
        WaypointerConfig config = new WaypointerConfig();
        config.setCrystalHollowsEnabled(true);
        try {
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                MinecraftCompat.setScreen(client, SettingsScreen.atSetting(null, config, null, "crystalHollowsEnabled"));
            });
            context.waitTicks(2);
            int[] original = context.computeOnClient(client -> positions((SettingsScreen) MinecraftCompat.screen(client)));
            for (boolean enabled : new boolean[]{false, true}) {
                context.runOnClient(client -> {
                    SettingsScreen screen = (SettingsScreen) MinecraftCompat.screen(client);
                    AbstractWidget checkbox = control(screen, "crystalHollowsEnabled");
                    screen.mouseClicked(new MouseButtonEvent(checkbox.getX() + checkbox.getWidth() / 2.0,
                            checkbox.getY() + checkbox.getHeight() / 2.0, new MouseButtonInfo(0, 0)), false);
                });
                context.waitTicks(2);
                context.runOnClient(client -> {
                    if (config.crystalHollowsEnabled() != enabled) throw new AssertionError("Settings checkbox did not toggle");
                    int[] next = positions((SettingsScreen) MinecraftCompat.screen(client));
                    if (!java.util.Arrays.equals(original, next))
                        throw new AssertionError("Crystal Hollows caption or master checkbox moved on toggle");
                });
                context.takeScreenshot("settings-crystal-hollows-" + (enabled ? "enabled" : "disabled"));
            }
            context.runOnClient(client -> MinecraftCompat.setScreen(client,
                    SettingsScreen.atSetting(null, config, null, "showRouteLines")));
            context.waitTicks(2);
            context.runOnClient(client -> {
                SettingsScreen screen = (SettingsScreen) MinecraftCompat.screen(client);
                AbstractWidget checkbox = control(screen, "showRouteLines");
                screen.mouseClicked(new MouseButtonEvent(checkbox.getX() + checkbox.getWidth() / 2.0,
                        checkbox.getY() + checkbox.getHeight() / 2.0, new MouseButtonInfo(0, 0)), false);
                if (!config.showRouteLines()) throw new AssertionError("Route-line section did not expand");
                MinecraftCompat.setScreen(client, SettingsScreen.atSetting(null, config, null, "useEtherwarpHeight"));
            });
            context.waitTicks(2);
            context.runOnClient(client -> {
                SettingsScreen screen = (SettingsScreen) MinecraftCompat.screen(client);
                if (rows(screen).size() <= 1) throw new AssertionError("Appearance settings were not restored inline");
                if (config.useEtherwarpHeight()) throw new AssertionError("Etherwarp height should default off");
                AbstractWidget checkbox = control(screen, "useEtherwarpHeight");
                screen.mouseClicked(new MouseButtonEvent(checkbox.getX() + checkbox.getWidth() / 2.0,
                        checkbox.getY() + checkbox.getHeight() / 2.0, new MouseButtonInfo(0, 0)), false);
                if (!config.useEtherwarpHeight()) throw new AssertionError("Inline appearance setting did not toggle");
            });
            context.waitTicks(2);
            context.takeScreenshot("settings-inline-appearance");
            context.runOnClient(client -> MinecraftCompat.setScreen(client,
                    SettingsScreen.atSetting(null, config, null, SettingsCatalog.ACTION_WAYPOINT_PAINT)));
            context.waitTicks(2);
            context.runOnClient(client -> {
                SettingsScreen screen = (SettingsScreen) MinecraftCompat.screen(client);
                AbstractWidget painter = control(screen, SettingsCatalog.ACTION_WAYPOINT_PAINT);
                screen.mouseClicked(new MouseButtonEvent(painter.getX() + painter.getWidth() / 2.0,
                        painter.getY() + painter.getHeight() / 2.0, new MouseButtonInfo(0, 0)), false);
                if (!(MinecraftCompat.screen(client) instanceof com.babbur.waypointer.screen.WaypointPainterScreen))
                    throw new AssertionError("Painter must open without enabling Extras");
            });
            context.runOnClient(client -> MinecraftCompat.setScreen(client,
                    SettingsScreen.atSetting(null, config, null, SettingsCatalog.ACTION_FEEDBACK)));
            context.waitTicks(2);
            context.takeScreenshot("settings-feedback-alignment");
        } finally {
            context.runOnClient(client -> {
                MinecraftCompat.setScreen(client, null);
                client.options.guiScale().set(scale);
                client.resizeGui();
            });
        }
    }

    private static int[] positions(SettingsScreen screen) {
        String caption = Component.translatableWithFallback(
                "waypointer.settings.group.mining.crystal_hollows_structures", "Crystal Hollows structures").getString();
        Object heading = rows(screen).stream().filter(row -> caption.equals(field(row, "caption")))
                .findFirst().orElseThrow(() -> new AssertionError("Crystal Hollows caption disappeared"));
        return new int[]{(int) field(heading, "y"), control(screen, "crystalHollowsEnabled").getY()};
    }

    private static AbstractWidget control(SettingsScreen screen, String id) {
        Object row = rows(screen).stream().filter(item -> field(item, "setting") instanceof Setting setting
                && setting.id().equals(id)).findFirst().orElseThrow();
        return (AbstractWidget) field(((List<?>) field(row, "widgets")).getFirst(), "widget");
    }

    private static List<?> rows(SettingsScreen screen) { return (List<?>) field(screen, "rows"); }
    private static Object field(Object owner, String name) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
