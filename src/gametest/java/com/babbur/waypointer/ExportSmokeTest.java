package com.babbur.waypointer;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.codec.WaypointImporter;
import com.babbur.waypointer.screen.ExportScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

@SuppressWarnings("UnstableApiUsage")
final class ExportSmokeTest {
    static void run(ClientGameTestContext context) {
        String previousClipboard = context.computeOnClient(client -> client.keyboardHandler.getClipboard());
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        WaypointerConfig config = new WaypointerConfig();
        try {
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                WaypointGroup route = WaypointGroup.create("SkySoft export smoke", "hub");
                route.add(Waypoint.at(-12, 70, 34).withName("Exported point"));
                config.setExportIncludeNames(true);
                ExportScreen.openForGroup(null, config, route);
                press(MinecraftCompat.screen(client), Component.translatable(
                        "waypointer.screen.export.target", "Waypointer").getString());
                press(MinecraftCompat.screen(client), "SkySoft");
            });
            context.waitFor(client -> !Boolean.TRUE.equals(field(MinecraftCompat.screen(client), "encodePending")), 200);
            context.runOnClient(client -> {
                Screen screen = MinecraftCompat.screen(client);
                long supportedToggles = ((java.util.List<?>) field(screen, "toggleButtons")).stream()
                        .filter(toggle -> ((Button) toggle).active).count();
                if (supportedToggles != 4) throw new AssertionError("SkySoft inclusion toggles missing");
                if (((EditBox) field(screen, "labelInput")).active)
                    throw new AssertionError("SkySoft export label customization enabled");
                press(screen, Component.translatable("waypointer.export.copy_clipboard").getString());
                String code = client.keyboardHandler.getClipboard();
                if (!code.startsWith("SKYSOFT:")) throw new AssertionError("Copy did not produce a SkySoft code");
                var exported = WaypointImporter.importAny(code).groups().getFirst();
                var point = exported.waypoints().getFirst();
                if (!exported.name().equals("SkySoft export smoke") || !exported.zoneId().equals("hub")
                        || exported.size() != 1 || point.x() != -12 || point.y() != 70 || point.z() != 34
                        || !point.name().equals("Exported point"))
                    throw new AssertionError("Copied SkySoft code changed the route");
            });
            context.takeScreenshot("skysoft-export-copied");
        } finally {
            context.runOnClient(client -> {
                MinecraftCompat.setScreen(client, null);
                client.keyboardHandler.setClipboard(previousClipboard);
                client.options.guiScale().set(previousScale);
                client.resizeGui();
            });
        }
    }
    private static Object field(Object object, String name) {
        try {
            var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Button button(Screen screen, String label) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> b.visible && b.getMessage().getString().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing screen action: " + label));
    }
    private static void press(Screen screen, String label) {
        Button button = button(screen, label);
        if (!button.active) throw new AssertionError("Disabled screen action: " + label);
        button.onPress(new KeyEvent(257, 0, 0));
    }
}
