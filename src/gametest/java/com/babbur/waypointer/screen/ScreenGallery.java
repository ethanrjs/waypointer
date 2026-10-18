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
        String roomRouteId = context.computeOnClient(client -> {
            var room = com.babbur.waypointer.core.WaypointGroup.create("Arrow Trap", "arrow-trap");
            room.setRouteKind(com.babbur.waypointer.core.WaypointGroup.RouteKind.DUNGEON);
            room.add(new com.babbur.waypointer.core.Waypoint(4, 70, 4, "", 0x9C2EFF,
                    com.babbur.waypointer.core.Waypoint.FLAG_SKIP_ON_STAND
                            | com.babbur.waypointer.core.Waypoint.FLAG_DUNGEON_ETHERWARP, 0.0));
            room.add(new com.babbur.waypointer.core.Waypoint(9, 70, 9, "", 0x2EE0FF,
                    com.babbur.waypointer.core.Waypoint.FLAG_SKIP_ON_INTERACT
                            | com.babbur.waypointer.core.Waypoint.FLAG_DUNGEON_SECRET, 0.0));
            WaypointerClient.manager().add(room);
            return room.id();
        });
        shoot(context, "editor-dungeon", scale, () -> new GroupEditScreen(null, WaypointerClient.manager(), config,
                WaypointerClient.manager().get(roomRouteId), 0));
        double[] typeCell = new double[2];
        context.runOnClient(client -> {
            GroupEditScreen editor = (GroupEditScreen) MinecraftCompat.screen(client);
            setField(editor, "dungeonTypePickerIndex", 0);
            Object bounds = invoke(editor, "dungeonTypePickerBounds", new Class<?>[0]);
            Object cell = invoke(editor, "dungeonTypePickerCell",
                    new Class<?>[]{bounds.getClass(), int.class}, bounds, 0);
            double guiScale = client.getWindow().getGuiScale();
            typeCell[0] = ((int) invoke(cell, "left", new Class<?>[0]) + 6) * guiScale;
            typeCell[1] = ((int) invoke(cell, "top", new Class<?>[0]) + 6) * guiScale;
        });
        context.getInput().setCursorPos(typeCell[0], typeCell[1]);
        context.waitTicks(2);
        context.takeScreenshot("gallery-editor-dungeon-types-s" + scale);
        context.runOnClient(client -> WaypointerClient.manager().remove(roomRouteId));
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
        shoot(context, "minimap-options", scale, MinimapOptionsScreen::new);
        shoot(context, "debug", scale, () -> new DebugInspectScreen(null));
        shoot(context, "hub-new-menu", scale, () -> new WaypointerScreen(manager, config));
        call(context, "toggleNewMenu");
        context.takeScreenshot("gallery-hub-new-menu-open-s" + scale);
        shoot(context, "hub-zones", scale, () -> new WaypointerScreen(manager, config));
        call(context, "openIslandDropdown");
        context.takeScreenshot("gallery-hub-zones-open-s" + scale);
        shoot(context, "hub-before-empty", scale, () -> new WaypointerScreen(manager, config));
        context.runOnClient(client -> {
            WaypointerScreen hub = (WaypointerScreen) MinecraftCompat.screen(client);
            hub.selectedZoneId = "garden";
        });
        context.waitTicks(2);
        context.takeScreenshot("gallery-hub-empty-zone-s" + scale);
        shoot(context, "hub-before-zone-click", scale, () -> new WaypointerScreen(manager, config));
        double[] zoneButton = new double[2];
        context.runOnClient(client -> {
            var button = (net.minecraft.client.gui.components.AbstractWidget)
                    field(MinecraftCompat.screen(client), "islandSelectorBtn");
            double guiScale = client.getWindow().getGuiScale();
            zoneButton[0] = (button.getX() + button.getWidth() / 2.0) * guiScale;
            zoneButton[1] = (button.getY() + button.getHeight() / 2.0) * guiScale;
        });
        context.getInput().setCursorPos(zoneButton[0], zoneButton[1]);
        context.getInput().pressMouse(0);
        context.waitTicks(2);
        context.getInput().typeChars("gal");
        context.waitTicks(2);
        context.takeScreenshot("gallery-hub-zones-typed-s" + scale);
        shoot(context, "settings-search", scale, () -> new SettingsScreen(null, config, WaypointerClient.dungeonConfig()));
        context.runOnClient(client -> {
            SettingsScreen screen = (SettingsScreen) MinecraftCompat.screen(client);
            ((net.minecraft.client.gui.components.EditBox) field(screen, "searchBox")).setValue("tracer");
        });
        context.waitTicks(3);
        context.takeScreenshot("gallery-settings-search-results-s" + scale);
        context.runOnClient(client -> {
            SettingsScreen screen = (SettingsScreen) MinecraftCompat.screen(client);
            ((net.minecraft.client.gui.components.EditBox) field(screen, "searchBox")).setValue("room");
        });
        context.waitTicks(3);
        context.takeScreenshot("gallery-settings-search-room-s" + scale);
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
        shoot(context, "route-icon", scale, () -> new RouteIconPickerScreen(null, "minecraft:diamond_pickaxe*",
                "dwarven_mines", spec -> { }));
        context.runOnClient(client -> {
            Screen screen = MinecraftCompat.screen(client);
            ((net.minecraft.client.gui.components.EditBox) field(screen, "searchBox")).setValue("glass");
        });
        context.waitTicks(3);
        context.takeScreenshot("gallery-route-icon-search-s" + scale);
        context.runOnClient(client -> {
            MinecraftCompat.setScreen(client, null);
            RoutePublishScreen.open(null, config, manager.get(routeId));
        });
        context.waitTicks(5);
        context.takeScreenshot("gallery-publish-s" + scale);
        context.runOnClient(client -> MinecraftCompat.setScreen(client, null));
    }

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
        shootMyRoutes(context, scale);
        shootRouteView(context, scale);
    }

    private static void shootRouteView(ClientGameTestContext context, int scale) {
        context.runOnClient(client -> {
            var base = catalogRoutes().getFirst();
            var summary = new com.babbur.waypointer.catalog.CatalogRouteSummary(base.id(), "4 Vein Diamond Route",
                    "[important]\n-All the waypoints called \"TP(Xend)\" are NOT something you need to worry about "
                            + "too much, they just give an idea of where you should end.\n-Some of the block waypoints "
                            + "will sometimes be bedrock if you have max (or close to) mining spread, but it still "
                            + "works very well.\n\nStart at the Dwarven Base Camp and follow the numbers. Each vein "
                            + "respawns before you loop back, so you can keep going without waiting.",
                    "RokiYT2030", "", false, "public", "dwarven_mines", "Dwarven Mines", 16, 1,
                    com.babbur.waypointer.catalog.CatalogProtocol.currentCodecVersion(), 2, 184,
                    base.createdAt(), base.updatedAt(), base.sharePath(),
                    List.of(com.babbur.waypointer.catalog.CatalogRouteSummary.TAG_MINING_CULT));
            var details = new com.babbur.waypointer.catalog.CatalogRouteDetails(summary,
                    com.babbur.waypointer.codec.WaypointCodec.encodeCatalog(List.of(sampleRoute())));
            var screen = new CatalogRouteInstallScreen(null, com.babbur.waypointer.catalog.RouteCatalogClient.production(),
                    com.babbur.waypointer.WaypointerClient.manager(), summary.id());
            setField(screen, "requested", true);
            MinecraftCompat.setScreen(client, screen);
            setField(screen, "prepared", com.babbur.waypointer.catalog.CatalogRouteInstaller.prepare(details));
            setField(screen, "preview", previewOf(sampleRoute()));
            setField(screen, "phase", enumValue(screen, "phase", "READY"));
            invoke(screen, "refreshInstallState", new Class<?>[0]);
            invoke(screen, "refreshInstallButton", new Class<?>[0]);
        });
        context.waitTicks(3);
        context.takeScreenshot("gallery-route-view-s" + scale);
        context.runOnClient(client -> MinecraftCompat.setScreen(client, null));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Object owner, String fieldName, String constant) {
        try {
            Class type = owner.getClass().getDeclaredField(fieldName).getType();
            return Enum.valueOf(type, constant);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void shootMyRoutes(ClientGameTestContext context, int scale) {
        context.runOnClient(client -> {
            var screen = new PublishedRoutesScreen(null, com.babbur.waypointer.catalog.RouteCatalogClient.production(),
                    com.babbur.waypointer.catalog.PublisherIdentityStore.defaultLocation(),
                    com.babbur.waypointer.catalog.CatalogPublicationRegistry.defaultLocation());
            setField(screen, "loading", false);
            String api = com.babbur.waypointer.catalog.RouteCatalogClient.production().apiRoot();
            String week = java.time.Instant.now().minus(java.time.Duration.ofDays(6)).toString();
            List<com.babbur.waypointer.catalog.CatalogPublication> mine = List.of(
                    publication("AAAAAAAAAAAAAAAAAAAAA1", "Glacite tungsten loop", "dwarven_mines", 3, true, week, api),
                    publication("AAAAAAAAAAAAAAAAAAAAA5", "Gemstone grinding route", "crystal_hollows", 1, true, week, api),
                    publication("AAAAAAAAAAAAAAAAAAAAA9", "Practice route", "hub", 2, false, week, api));
            invoke(field(screen, "model"), "replace", new Class<?>[]{List.class}, mine);
            invoke(field(screen, "model"), "select", new Class<?>[]{String.class}, mine.getFirst().routeId());
            var summaries = (java.util.Map<String, Object>) field(screen, "summaries");
            for (var route : catalogRoutes()) summaries.put(route.id(), route);
            ((java.util.Map<String, Object>) field(screen, "previews")).put(mine.getFirst().routeId(), previewOf(sampleRoute()));
            try {
                Class<Enum> state = (Class<Enum>) Class.forName("com.babbur.waypointer.screen.PublishedRoutesScreen$LinkState");
                var links = (java.util.Map<String, Object>) field(screen, "links");
                links.put(mine.get(0).routeId(), Enum.valueOf(state, "CHANGED"));
                links.put(mine.get(1).routeId(), Enum.valueOf(state, "SAME"));
                links.put(mine.get(2).routeId(), Enum.valueOf(state, "NOT_LINKED"));
            } catch (ClassNotFoundException e) { throw new AssertionError(e); }
            MinecraftCompat.setScreen(client, screen);
        });
        context.waitTicks(3);
        context.takeScreenshot("gallery-my-routes-s" + scale);
    }

    private static com.babbur.waypointer.catalog.CatalogPublication publication(
            String id, String title, String zone, int version, boolean isPublic, String created, String api) {
        return new com.babbur.waypointer.catalog.CatalogPublication(id, "publisher", "Kaeso", title,
                "Ordered for one clean lap; check the mineshaft at the end.",
                isPublic ? com.babbur.waypointer.catalog.CatalogPublishRequest.Visibility.PUBLIC
                        : com.babbur.waypointer.catalog.CatalogPublishRequest.Visibility.UNLISTED,
                zone, version, 9, created, "/r/" + id, api, "0".repeat(64), java.time.Instant.now());
    }

    private static Object previewOf(WaypointGroup route) {
        try {
            var of = Class.forName("com.babbur.waypointer.screen.CatalogRoutePreview")
                    .getDeclaredMethod("of", List.class);
            of.setAccessible(true);
            return of.invoke(null, List.of(route));
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static WaypointGroup sampleRoute() {
        WaypointGroup route = WaypointGroup.create("Glacite tungsten", "dwarven_mines");
        int[][] points = {{0, 120, 0}, {14, 118, -6}, {30, 116, -4}, {44, 117, 8}, {52, 119, 24},
                {48, 121, 40}, {36, 120, 52}, {20, 118, 58}, {4, 116, 54}, {-8, 115, 44},
                {-14, 117, 30}, {-6, 119, 22}, {6, 121, 26}, {18, 120, 34}, {26, 118, 22}, {16, 117, 10}};
        for (int[] p : points) route.add(Waypoint.at(p[0], p[1], p[2]));
        route.toggleSubwaypoint(12);
        return route;
    }

    private static final java.util.Map<String, String> ICONS = java.util.Map.of(
            "AAAAAAAAAAAAAAAAAAAAA1", "minecraft:diamond_pickaxe*",
            "AAAAAAAAAAAAAAAAAAAAA2", "minecraft:red_stained_glass",
            "AAAAAAAAAAAAAAAAAAAAA3", "minecraft:oak_leaves",
            "AAAAAAAAAAAAAAAAAAAAA4", "minecraft:spider_eye",
            "AAAAAAAAAAAAAAAAAAAAA5", "minecraft:amethyst_cluster",
            "AAAAAAAAAAAAAAAAAAAAA6", "",
            "AAAAAAAAAAAAAAAAAAAAA7", "minecraft:redstone",
            "AAAAAAAAAAAAAAAAAAAAA8", "");

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
                com.babbur.waypointer.catalog.CatalogProtocol.currentCodecVersion(), 1, installs, created, updated, "/r/" + id, tags, ICONS.getOrDefault(id, ""));
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
        ruby.setIcon("minecraft:red_stained_glass");
        ruby.setStyleOverride(WaypointStyle.EMPTY.with("labelScale", 1.5).with("showTracer", true));
        ruby.setWaypointStyle(5, WaypointStyle.EMPTY.with("waypointMarkerScale", 1.5));
        manager.add(ruby);
        WaypointGroup topaz = WaypointGroup.create("Topaz shortcut", zone);
        for (int i = 0; i < 6; i++) topaz.add(Waypoint.at(-10 - i * 4, 71, 5 + i));
        topaz.setEnabled(false);
        topaz.setIcon("minecraft:yellow_stained_glass");
        manager.add(topaz);
        WaypointGroup souls = WaypointGroup.create("Fairy souls", zone);
        for (int i = 0; i < 9; i++) souls.add(Waypoint.at(i * 7, 68, -i * 5));
        souls.setStyleOverride(WaypointStyle.EMPTY.with("showTracer", true));
        souls.setIcon("minecraft:ghast_tear*");
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
