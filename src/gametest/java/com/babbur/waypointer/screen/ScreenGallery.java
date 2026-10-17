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
        shoot(context, "appearance-waypoint", scale, () -> SettingsScreen.forAppearance(null, config,
                WaypointerClient.dungeonConfig(), AppearanceTarget.waypoint(manager, config,
                        manager.get(routeId), 5, manager::fireDataChanged)));
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
        shoot(context, "add-temp", scale, () -> new AddTempScreen(null, manager, config));
        double opacity = config.menuOpacity();
        context.runOnClient(client -> config.setMenuOpacity(0.45));
        shoot(context, "editor-see-through", scale, () -> new GroupEditScreen(null, manager, config,
                manager.get(routeId), 2));
        context.runOnClient(client -> config.setMenuOpacity(opacity));
        shoot(context, "hub-route-menu", scale, () -> new WaypointerScreen(manager, config));
        context.runOnClient(client -> {
            WaypointerScreen hub = (WaypointerScreen) MinecraftCompat.screen(client);
            hub.openRouteRowMenu(manager.get(routeId), hub.width * 0.35, hub.height * 0.3);
        });
        context.waitTicks(2);
        context.takeScreenshot("gallery-hub-route-menu-open-s" + scale);
        shoot(context, "minimap-editor", scale, () -> new CrystalHollowsMinimapEditorScreen(null, config));
        shoot(context, "debug", scale, () -> new DebugInspectScreen(null));
        // Menus and states inside the everyday screens.
        shoot(context, "hub-new-menu", scale, () -> new WaypointerScreen(manager, config));
        call(context, "toggleNewMenu");
        context.takeScreenshot("gallery-hub-new-menu-open-s" + scale);
        shoot(context, "hub-zones", scale, () -> new WaypointerScreen(manager, config));
        call(context, "openIslandDropdown");
        context.takeScreenshot("gallery-hub-zones-open-s" + scale);
        shoot(context, "settings-search", scale, () -> new SettingsScreen(null, config, WaypointerClient.dungeonConfig()));
        context.runOnClient(client -> {
            SettingsScreen screen = (SettingsScreen) MinecraftCompat.screen(client);
            ((net.minecraft.client.gui.components.EditBox) field(screen, "searchBox")).setValue("tracer");
        });
        context.waitTicks(3);
        context.takeScreenshot("gallery-settings-search-results-s" + scale);
        shoot(context, "appearance-scope", scale, () -> SettingsScreen.forAppearance(null, config,
                WaypointerClient.dungeonConfig(), AppearanceTarget.zone(config, "mineshaft")));
        context.runOnClient(client -> {
            SettingsScreen screen = (SettingsScreen) MinecraftCompat.screen(client);
            invoke(screen, "openScopeMenu", new Class<?>[]{net.minecraft.client.gui.components.Button.class},
                    field(screen, "scopeSwitcher"));
        });
        context.waitTicks(2);
        context.takeScreenshot("gallery-appearance-scope-open-s" + scale);
        shootCatalog(context, scale);
        context.runOnClient(client -> {
            MinecraftCompat.setScreen(client, null);
            RoutePublishScreen.open(null, config, manager.get(routeId));
        });
        context.waitTicks(5);
        context.takeScreenshot("gallery-publish-s" + scale);
        context.runOnClient(client -> MinecraftCompat.setScreen(client, null));
    }

    /** The community browser on a seeded catalog page, so the shots never depend on the live server. */
    private static void shootCatalog(ClientGameTestContext context, int scale) {
        context.runOnClient(client -> {
            var screen = new RouteCatalogScreen(null, com.babbur.waypointer.catalog.RouteCatalogClient.production());
            setField(screen, "listRequested", true);
            MinecraftCompat.setScreen(client, screen);
            invoke(screen, "applyPage", new Class<?>[]{com.babbur.waypointer.catalog.CatalogPage.class, boolean.class},
                    new com.babbur.waypointer.catalog.CatalogPage(catalogRoutes(), false, null), false);
            @SuppressWarnings("unchecked")
            var previews = (java.util.Map<String, Object>) field(screen, "previews");
            previews.put(catalogRoutes().get(0).id(), previewOf(sampleRoute()));
            invoke(screen, "rebuildWidgets", new Class<?>[0]);
        });
        context.waitTicks(3);
        context.takeScreenshot("gallery-catalog-s" + scale);
        context.runOnClient(client -> invoke(MinecraftCompat.screen(client), "toggleSortMenu", new Class<?>[0]));
        context.waitTicks(2);
        context.takeScreenshot("gallery-catalog-sort-s" + scale);
        context.runOnClient(client -> {
            Screen screen = MinecraftCompat.screen(client);
            invoke(screen, "toggleSortMenu", new Class<?>[0]);
            var browser = field(screen, "browser");
            invoke(browser, "setTagFilter", new Class<?>[]{String.class},
                    com.babbur.waypointer.catalog.CatalogRouteSummary.TAG_MINING_CULT);
            invoke(screen, "applyFilters", new Class<?>[0]);
        });
        context.waitTicks(2);
        context.takeScreenshot("gallery-catalog-mining-cult-s" + scale);
        context.runOnClient(client -> {
            Screen screen = MinecraftCompat.screen(client);
            invoke(field(screen, "browser"), "setTagFilter", new Class<?>[]{String.class}, (Object) null);
            invoke(screen, "applyFilters", new Class<?>[0]);
            if (!(boolean) field(screen, "wideLayout")) {
                var route = invoke(screen, "selectedRoute", new Class<?>[0]);
                invoke(screen, "openRoute", new Class<?>[]{com.babbur.waypointer.catalog.CatalogRouteSummary.class}, route);
            }
        });
        context.waitTicks(2);
        context.takeScreenshot("gallery-catalog-detail-s" + scale);
    }

    private static Object previewOf(WaypointGroup route) {
        try {
            var of = Class.forName("com.babbur.waypointer.screen.CatalogRoutePreview")
                    .getDeclaredMethod("of", List.class);
            of.setAccessible(true);
            return of.invoke(null, List.of(route));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** A tungsten loop through the Glacite Tunnels, for the preview map. */
    private static WaypointGroup sampleRoute() {
        WaypointGroup route = WaypointGroup.create("Glacite tungsten", "dwarven_mines");
        int[][] points = {{0, 120, 0}, {14, 118, -6}, {30, 116, -4}, {44, 117, 8}, {52, 119, 24},
                {48, 121, 40}, {36, 120, 52}, {20, 118, 58}, {4, 116, 54}, {-8, 115, 44},
                {-14, 117, 30}, {-6, 119, 22}, {6, 121, 26}, {18, 120, 34}, {26, 118, 22}, {16, 117, 10}};
        for (int[] p : points) route.add(Waypoint.at(p[0], p[1], p[2]));
        route.toggleSubwaypoint(12);
        return route;
    }

    private static List<com.babbur.waypointer.catalog.CatalogRouteSummary> catalogRoutes() {
        String now = java.time.Instant.now().toString();
        String week = java.time.Instant.now().minus(java.time.Duration.ofDays(6)).toString();
        String months = java.time.Instant.now().minus(java.time.Duration.ofDays(70)).toString();
        String mc = com.babbur.waypointer.catalog.CatalogRouteSummary.TAG_MINING_CULT;
        return List.of(
                route("AAAAAAAAAAAAAAAAAAAAA1", "Glacite tungsten loop", "Tungsten and umber nodes through the Glacite Tunnels, ordered for one clean lap with a mineshaft check at the end.",
                        "Kaeso", true, "dwarven_mines", "Dwarven Mines", 16, 412, week, now, List.of(mc)),
                route("AAAAAAAAAAAAAAAAAAAAA2", "Ruby under Amethyst Quadrant", "Ruby veins below the amethyst quadrant.",
                        "AAAHHHHHH", false, "crystal_hollows", "Crystal Hollows", 27, 126, months, week, List.of(mc)),
                route("AAAAAAAAAAAAAAAAAAAAA3", "Hideonleaf spots", "Every hideonleaf spawn on Galatea.",
                        "YourWhiteAngel", false, "galatea", "Galatea", 51, 88, months, months, List.of()),
                route("AAAAAAAAAAAAAAAAAAAAA4", "Invisibug", "Invisibug spawns, clockwise.",
                        "Liquing1", false, "galatea", "Galatea", 44, 61, week, week, List.of()),
                route("AAAAAAAAAAAAAAAAAAAAA5", "Gemstone grinding route", "Topaz and jade in one lap.",
                        "Mining Cult", true, "crystal_hollows", "Crystal Hollows", 38, 302, months, now, List.of(mc)),
                route("AAAAAAAAAAAAAAAAAAAAA6", "Voidling Extremist", "Voidling spawns in the End.",
                        "casihaingoai", false, "the_end", "The End", 5, 22, week, week, List.of()),
                route("AAAAAAAAAAAAAAAAAAAAA7", "Automaton spawns", "Lost Precursor City automatons.",
                        "BABBUR2", true, "crystal_hollows", "Crystal Hollows", 32, 240, months, months, List.of()),
                route("AAAAAAAAAAAAAAAAAAAAA8", "Torrhus tree protection", "Critter trees in Torrhus Canyon.",
                        "M2AT", false, "torrhus_canyon", "Torrhus Canyon", 4, 9, now, now, List.of()));
    }

    private static com.babbur.waypointer.catalog.CatalogRouteSummary route(
            String id, String title, String description, String author, boolean verified, String zoneId,
            String zoneLabel, int waypoints, long installs, String created, String updated, List<String> tags) {
        return new com.babbur.waypointer.catalog.CatalogRouteSummary(id, title, description, author, "", verified,
                "public", zoneId, zoneLabel, waypoints, 1,
                com.babbur.waypointer.catalog.CatalogProtocol.currentCodecVersion(), 1, installs, created, updated, "/r/" + id, tags);
    }

    private static void setField(Object owner, String name, Object value) {
        try {
            var f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(owner, value);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void call(ClientGameTestContext context, String method) {
        context.runOnClient(client -> invoke(MinecraftCompat.screen(client), method, new Class<?>[0]));
        context.waitTicks(2);
    }

    private static Object field(Object owner, String name) {
        try {
            var f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void shoot(ClientGameTestContext context, String name, int scale, Supplier<Screen> open) {
        context.runOnClient(client -> MinecraftCompat.setScreen(client, open.get()));
        context.waitTicks(3);
        context.takeScreenshot("gallery-" + name + "-s" + scale);
    }

    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) {
        try {
            Class<?> type = owner.getClass();
            while (true) {
                try {
                    var method = type.getDeclaredMethod(name, types);
                    method.setAccessible(true);
                    return method.invoke(owner, args);
                } catch (NoSuchMethodException missing) {
                    type = type.getSuperclass();
                    if (type == null) throw missing;
                }
            }
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** A realistic library: folders, styled routes, subwaypoints, named stops, a temp waypoint. */
    private static String seed(ActiveGroupManager manager) {
        String zone = manager.currentZone() == null ? "private_world" : manager.currentZone().id();
        WaypointGroup ruby = WaypointGroup.create("Ruby route", zone);
        String[] names = {"Start", "", "Ledge", "", "", "&aDrop down", "", "", "", "", "", "", "", "Finish"};
        for (int i = 0; i < names.length; i++) {
            ruby.add(new Waypoint(10 + i * 37, 70 + (i % 3), 20 - i * 13,
                    names[i], 0xFF4040 + i * 0x0800, 0, 0.0));
        }
        ruby.toggleSubwaypoint(2);
        ruby.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        ruby.setStyleOverride(WaypointStyle.EMPTY.with("labelScale", 1.5).with("showTracer", true));
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
