package com.babbur.waypointer.screen;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.WaypointStyle;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.function.Supplier;

/**
 * Opens the everyday screens on a seeded library and saves a screenshot of each at GUI
 * scales 2 and 4, so UI changes can be reviewed side by side. Run it alone with
 * {@code ./gradlew runClientGameTest -PscreenGallery}.
 */
@SuppressWarnings("UnstableApiUsage")
public final class ScreenGallery {

    private static final int[] SCALES = {2, 4};

    private ScreenGallery() {}

    public static void run(ClientGameTestContext context) {
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        try {
            String routeId = context.computeOnClient(client -> seed(WaypointerClient.manager()));
            for (int scale : SCALES) {
                context.runOnClient(client -> {
                    client.options.guiScale().set(scale);
                    client.resizeGui();
                });
                shootAll(context, routeId, scale);
            }
        } finally {
            context.runOnClient(client -> {
                MinecraftCompat.setScreen(client, null);
                client.options.guiScale().set(previousScale);
                client.resizeGui();
            });
        }
    }

    private static void shootAll(ClientGameTestContext context, String routeId, int scale) {
        ActiveGroupManager manager = WaypointerClient.manager();
        var config = WaypointerClient.config();
        shoot(context, "hub", scale, () -> new WaypointerScreen(manager, config));
        shoot(context, "editor", scale, () -> new GroupEditScreen(null, manager, config,
                manager.get(routeId), 2));
        context.runOnClient(client -> {
            GroupEditScreen editor = (GroupEditScreen) MinecraftCompat.screen(client);
            invoke(editor, "openWaypointMenu", new Class<?>[]{int.class, double.class, double.class},
                    2, editor.width * 0.45, editor.height * 0.3);
        });
        context.waitTicks(2);
        context.takeScreenshot("gallery-editor-menu-s" + scale);
        shoot(context, "settings", scale, () -> new SettingsScreen(null, config, WaypointerClient.dungeonConfig()));
        shoot(context, "settings-appearance", scale, () -> SettingsScreen.atSetting(null, config,
                WaypointerClient.dungeonConfig(), "showTracer"));
        shoot(context, "appearance-route", scale, () -> {
            WaypointGroup route = manager.get(routeId);
            return SettingsScreen.forAppearance(null, config, WaypointerClient.dungeonConfig(),
                    AppearanceTarget.route(manager, config, route, manager::fireDataChanged));
        });
        shoot(context, "appearance-zone", scale, () -> SettingsScreen.forAppearance(null, config,
                WaypointerClient.dungeonConfig(), AppearanceTarget.zone(config, "mineshaft")));
        shoot(context, "add-named", scale, () -> new AddNamedWaypointScreen(null, manager, config,
                manager.get(routeId)));
        shoot(context, "export", scale, () -> new ExportScreen(null, config, List.of(manager.get(routeId)),
                manager.get(routeId).name()));
        shoot(context, "folder", scale, () -> new RouteFolderEditScreen(null, manager,
                manager.get(routeId).zoneId(), null, List.of()));
        shoot(context, "color-picker", scale, () -> new ColorPickerScreen(null, "Waypoint color",
                0x4FB3C4, rgb -> { }));
        shoot(context, "painter", scale, () -> new WaypointPainterScreen(null, config, manager,
                manager.get(routeId)));
        shoot(context, "feedback", scale, () -> new FeedbackScreen(null, config));
        context.runOnClient(client -> MinecraftCompat.setScreen(client, null));
    }

    private static void shoot(ClientGameTestContext context, String name, int scale, Supplier<Screen> open) {
        context.runOnClient(client -> MinecraftCompat.setScreen(client, open.get()));
        context.waitTicks(3);
        context.takeScreenshot("gallery-" + name + "-s" + scale);
    }

    private static void invoke(Object owner, String name, Class<?>[] types, Object... args) {
        try {
            var method = owner.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(owner, args);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** A realistic library: folders, styled routes, subwaypoints, named stops, a temp waypoint. */
    private static String seed(ActiveGroupManager manager) {
        String zone = manager.currentZone() == null ? "private_world" : manager.currentZone().id();
        WaypointGroup ruby = WaypointGroup.create("Ruby loop", zone);
        String[] names = {"Start", "", "Ledge", "", "", "&aDrop down", "", "", "", "", "", "", "", "Finish"};
        for (int i = 0; i < names.length; i++) {
            ruby.add(new Waypoint(10 + i * 37, 70 + (i % 3), 20 - i * 13,
                    names[i], 0xFF4040 + i * 0x0800, 0, 0.0));
        }
        ruby.toggleSubwaypoint(2);
        ruby.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        ruby.setWaypointStyle(5, WaypointStyle.EMPTY.with("waypointMarkerScale", 1.5));
        manager.add(ruby);
        WaypointGroup topaz = WaypointGroup.create("Topaz shortcut", zone);
        for (int i = 0; i < 6; i++) topaz.add(Waypoint.at(-10 - i * 4, 71, 5 + i));
        topaz.setEnabled(false);
        manager.add(topaz);
        WaypointGroup souls = WaypointGroup.create("Fairy souls", zone);
        for (int i = 0; i < 9; i++) souls.add(Waypoint.at(i * 7, 68, -i * 5));
        souls.setStyleOverride(WaypointStyle.EMPTY.with("showTracer", true));
        manager.add(souls);
        manager.createFolder("Gemstones", zone, List.of(ruby.id(), topaz.id()));
        for (int i = 0; i < 4; i++) {
            WaypointGroup extra = WaypointGroup.create("Practice route " + (i + 1), zone);
            extra.add(Waypoint.at(i, 70, i));
            manager.add(extra);
        }
        manager.addTempWaypoint(3, 70, 3, "Teammate");
        manager.noteRouteEdited(ruby);
        manager.fireDataChanged();
        return ruby.id();
    }
}
