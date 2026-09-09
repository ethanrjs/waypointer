package com.babbur.waypointer;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.*;
import com.babbur.waypointer.screen.StyleLibraryScreen;
import com.babbur.waypointer.screen.SettingsScreen;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.screen.WaypointAppearanceScreen;
import com.babbur.waypointer.screen.preview.WaypointInspectorWidget;
import com.babbur.waypointer.screen.preview.RoutePreviewWidget;
import com.babbur.waypointer.screen.preview.RoutePreviewScene;
import com.babbur.waypointer.screen.preview.RoutePreviewOrbit;
import com.babbur.waypointer.screen.preview.RoutePreviewZoom;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Actual screen/render exercise on isolated, unsaved settings and route data. */
@SuppressWarnings("UnstableApiUsage")
final class AppearanceSmokeTest {
    static void run(ClientGameTestContext context) {
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        WaypointerConfig config = new WaypointerConfig();
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup group = WaypointGroup.create("Appearance sample", "private_world");
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.add(Waypoint.at(10, 70, 10).withName("Waypoint preview"));
        group.add(Waypoint.at(20, 70, 20).withName("Second waypoint"));
        group.set(0, group.get(0).withStyleOverride(WaypointStyle.EMPTY.with("labelOpacity", 0.9)));
        group.set(1, group.get(1).withStyleOverride(WaypointStyle.EMPTY.with("labelOpacity", 0.6)));
        List<Waypoint> original = List.copyOf(group.waypoints());
        try {
            for (int scale : new int[]{2, 4}) {
                context.runOnClient(client -> {
                    client.options.guiScale().set(scale); client.resizeGui();
                    WaypointAppearanceScreen.openPoints(null, config, manager, group, List.of(0), () -> {});
                    if (MinecraftCompat.screen(client).width < 620) press(MinecraftCompat.screen(client), Component.translatable("waypointer.appearance.preview").getString());
                    checkBounds(MinecraftCompat.screen(client));
                    if (MinecraftCompat.screen(client).children().stream().noneMatch(WaypointInspectorWidget.class::isInstance))
                        throw new AssertionError("Appearance preview was not visible");
                });
                context.waitTicks(4);
                context.takeScreenshot("appearance-inspector-gui" + scale);
                for (String state : List.of("next", "completed")) {
                    context.runOnClient(client -> {
                        var inspector = MinecraftCompat.screen(client).children().stream()
                                .filter(WaypointInspectorWidget.class::isInstance)
                                .map(WaypointInspectorWidget.class::cast).findFirst().orElseThrow();
                        inspector.setFocused(true);
                        inspector.keyPressed(new KeyEvent(32, 0, 0));
                    });
                    context.waitTicks(2);
                    context.takeScreenshot("appearance-sequence-" + state + "-gui" + scale);
                }
                context.runOnClient(client -> press(MinecraftCompat.screen(client), Component.translatable("waypointer.appearance.cancel").getString()));
            }
            context.runOnClient(client -> {
                client.options.guiScale().set(2); client.resizeGui();
                WaypointAppearanceScreen.openPoints(null, config, manager, group, List.of(0, 1), () -> {});
                press(MinecraftCompat.screen(client), Component.translatable("waypointer.appearance.section.static_distance").getString());
                firstNumber(MinecraftCompat.screen(client)).setValue("640oops");
                MinecraftCompat.screen(client).children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> button.getX() == 16 && button.getY() == 38).findFirst().orElseThrow()
                        .onPress(new KeyEvent(257, 0, 0));
                press(MinecraftCompat.screen(client), Component.translatable("waypointer.appearance.section.static_distance").getString());
                if (!"640oops".equals(firstNumber(MinecraftCompat.screen(client)).getValue()))
                    throw new AssertionError("Navigation discarded invalid draft input");
                String applyLabel = Component.translatable("waypointer.appearance.apply").getString();
                if (MinecraftCompat.screen(client).children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .anyMatch(button -> button.getMessage().getString().equals(applyLabel) && button.active))
                    throw new AssertionError("Navigation allowed Apply with an invalid draft");
                firstNumber(MinecraftCompat.screen(client)).setValue("640");
                if (!group.waypoints().equals(original)) throw new AssertionError("Draft changed live points before Apply");
                press(MinecraftCompat.screen(client), Component.translatable("waypointer.appearance.cancel").getString());
                if (!group.waypoints().equals(original)) throw new AssertionError("Cancel changed live points");
                WaypointAppearanceScreen.openPoints(null, config, manager, group, List.of(0, 1), () -> {});
                press(MinecraftCompat.screen(client), Component.translatable("waypointer.appearance.section.static_distance").getString());
                firstNumber(MinecraftCompat.screen(client)).setValue("640");
                press(MinecraftCompat.screen(client), Component.translatable("waypointer.appearance.apply").getString());
                for (int i = 0; i < group.size(); i++) {
                    if (!"640.0".equals(group.get(i).styleOverride().values().get("maxStaticWaypointRenderDistance")))
                        throw new AssertionError("Batch appearance did not apply the edited distance");
                    if (!original.get(i).styleOverride().values().get("labelOpacity").equals(group.get(i).styleOverride().values().get("labelOpacity")))
                        throw new AssertionError("Batch appearance erased an untouched mixed override");
                }
                WaypointAppearanceScreen.openPoints(null, config, manager, group, List.of(0), () -> {});
                checkBounds(MinecraftCompat.screen(client));
            });
            context.waitTicks(2);
            context.takeScreenshot("appearance-direct-sections");
            List<Waypoint> beforeHighlights = List.copyOf(group.waypoints());
            var beforeHighlightConfig = com.babbur.waypointer.config.WaypointStyles.capture(config);
            var categoryButtons = context.computeOnClient(client -> MinecraftCompat.screen(client).children().stream()
                    .filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> button.getY() >= 68 && button.getY() < 170 && button.getX() < MinecraftCompat.screen(client).width / 2)
                    .toList());
            if (categoryButtons.size() != 8) throw new AssertionError("Expected all eight appearance categories");
            for (int category = 0; category < categoryButtons.size(); category++) {
                Button categoryButton = categoryButtons.get(category);
                double guiScale = context.computeOnClient(client -> client.getWindow().getGuiScale());
                context.getInput().setCursorPos((categoryButton.getX() + categoryButton.getWidth() / 2.0) * guiScale,
                        (categoryButton.getY() + categoryButton.getHeight() / 2.0) * guiScale);
                context.waitTicks(2);
                context.runOnClient(client -> {
                    var inspector = MinecraftCompat.screen(client).children().stream().filter(WaypointInspectorWidget.class::isInstance)
                            .map(WaypointInspectorWidget.class::cast).findFirst().orElseThrow();
                    if (inspector.highlight() == null) throw new AssertionError("Category hover did not highlight preview: " + categoryButton.getMessage());
                });
                context.takeScreenshot("appearance-category-highlight-" + category);
            }
            context.getInput().setCursorPos(0, 0);
            context.runOnClient(client -> MinecraftCompat.screen(client).setFocused(categoryButtons.get(4)));
            context.waitTicks(2);
            context.takeScreenshot("appearance-category-highlight-keyboard");
            context.runOnClient(client -> MinecraftCompat.screen(client).setFocused(null));
            context.waitTicks(2);
            context.runOnClient(client -> {
                var inspector = MinecraftCompat.screen(client).children().stream().filter(WaypointInspectorWidget.class::isInstance)
                        .map(WaypointInspectorWidget.class::cast).findFirst().orElseThrow();
                if (inspector.highlight() != null) throw new AssertionError("Preview hover highlight did not clear");
            });
            context.takeScreenshot("appearance-category-highlight-cleared");
            context.runOnClient(client -> {
                categoryButtons.get(5).onPress(new KeyEvent(257, 0, 0));
                client.options.guiScale().set(4); client.resizeGui();
                press(MinecraftCompat.screen(client), Component.translatable("waypointer.appearance.preview").getString());
            });
            context.waitTicks(2);
            context.takeScreenshot("appearance-category-highlight-compact");
            if (!group.waypoints().equals(beforeHighlights)
                    || !com.babbur.waypointer.config.WaypointStyles.capture(config).equals(beforeHighlightConfig))
                throw new AssertionError("Category highlighting changed saved appearance");
            context.runOnClient(client -> {
                MinecraftCompat.screen(client).onClose();
                client.options.guiScale().set(2); client.resizeGui();
                config.putNamedStyle("Mining", WaypointStyle.EMPTY.with("routeLineWidth", 3));
                config.putNamedStyle("Exploring", WaypointStyle.EMPTY.with("labelScale", 1.2));
                MinecraftCompat.setScreen(client, new StyleLibraryScreen(null, config, false));
                checkBounds(MinecraftCompat.screen(client));
            });
            context.waitTicks(2);
            context.takeScreenshot("appearance-style-library");
            context.runOnClient(client -> {
                firstNumber(MinecraftCompat.screen(client)).setValue("Sparse style");
                press(MinecraftCompat.screen(client), Component.translatable("waypointer.screen.styles.create").getString());
                press(MinecraftCompat.screen(client), Component.translatable("waypointer.appearance.section.static_distance").getString());
                firstNumber(MinecraftCompat.screen(client)).setValue("640");
                press(MinecraftCompat.screen(client), Component.translatable("waypointer.appearance.apply").getString());
                if (!config.namedStyle("Sparse style").equals(WaypointStyle.EMPTY.with("maxStaticWaypointRenderDistance", "640.0")))
                    throw new AssertionError("New named style captured untouched appearance settings");
                MinecraftCompat.setScreen(client, SettingsScreen.atSetting(null, config, new DungeonConfig(), "boxStyle"));
                MinecraftCompat.screen(client).keyPressed(new KeyEvent(256, 0, 0));
            });
            context.waitTicks(2);
            context.takeScreenshot("settings-appearance-sections");
            context.runOnClient(client -> {
                WaypointStyle named = WaypointStyle.EMPTY.with("waypointIcon", "STAR")
                        .with("showWaypointRoleBadge", true).with("labelShowRouteName", true)
                        .with("labelShowCoordinates", true).with("beaconBeamMode", "CURRENT")
                        .with("beaconWidthScale", 1.5).with("beaconMotionSpeed", 0.5);
                config.putNamedStyle("Preview custom", named);
                group.setStylePresetId("Preview custom");
                group.setStyleOverride(WaypointStyle.EMPTY.with("showRouteLines", true)
                        .with("routeLineWidth", 3).with("routeLineDashed", true));
                WaypointAppearanceScreen.openRoute(null, config, manager, group);
                if (MinecraftCompat.screen(client).width < 620) press(MinecraftCompat.screen(client), Component.translatable("waypointer.appearance.preview").getString());
                checkBounds(MinecraftCompat.screen(client));
                if (MinecraftCompat.screen(client).children().stream().noneMatch(WaypointInspectorWidget.class::isInstance))
                    throw new AssertionError("Customized appearance preview was not visible");
            });
            context.waitTicks(4);
            context.takeScreenshot("appearance-inspector-customized");
            var accuracyConfig = new WaypointerConfig();
            var accuracyGroup = WaypointGroup.create("Rendering rules", "private_world");
            accuracyGroup.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
            accuracyGroup.add(Waypoint.at(10, 70, 10).withName("Edited point"));
            accuracyGroup.add(Waypoint.at(16, 70, 12).withName("Context"));
            context.runOnClient(client -> {
                accuracyConfig.setBeaconBeamMode(WaypointerConfig.BeaconBeamMode.ALL_VISIBLE);
                accuracyConfig.setShowRouteLines(true);
                accuracyGroup.setWaypointStyle(0, WaypointStyle.EMPTY.with("useBeaconBeamTextures", false)
                        .with("waypointMarkerScale", 1.5).with("waypointOutlineColor", 0xFFAA00)
                        .with("matchWaypointOutlineToWaypointColor", false).with("labelScale", 1.3));
                WaypointAppearanceScreen.openPoints(null, accuracyConfig, manager, accuracyGroup, List.of(0), () -> {});
            });
            context.waitTicks(4);
            context.takeScreenshot("appearance-accuracy-point-scope-mixed-beams");
            context.runOnClient(client -> {
                accuracyGroup.setWaypointStyle(0, WaypointStyle.EMPTY);
                accuracyGroup.setLoadMode(WaypointGroup.LoadMode.STATIC);
                accuracyConfig.setMaxStaticWaypointRenderDistance(4);
                accuracyConfig.setHideTracerOnStaticRoutes(false);
                WaypointAppearanceScreen.openRoute(null, accuracyConfig, manager, accuracyGroup);
            });
            context.waitTicks(3);
            context.takeScreenshot("appearance-accuracy-static-distance-hidden");
            context.runOnClient(client -> {
                accuracyConfig.setMaxStaticWaypointRenderDistance(0);
                WaypointAppearanceScreen.openRoute(null, accuracyConfig, manager, accuracyGroup);
            });
            context.waitTicks(3);
            context.takeScreenshot("appearance-accuracy-static-nearest-tracer");
            context.runOnClient(client -> {
                accuracyGroup.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
                accuracyConfig.setHideWaypointsNearPlayer(true);
                accuracyConfig.setHideWaypointsNearRadius(100);
                WaypointAppearanceScreen.openRoute(null, accuracyConfig, manager, accuracyGroup);
            });
            context.waitTicks(3);
            context.takeScreenshot("appearance-accuracy-near-hidden");
            context.runOnClient(client -> {
                accuracyConfig.setHideWaypointsNearPlayer(false);
                accuracyConfig.setWaypointSkipFadeMs(600);
                accuracyConfig.setCurrentWaypointOpacity(0.8);
                accuracyConfig.setUpcomingWaypointOpacity(0.4);
                accuracyConfig.setCompletedWaypointOpacity(0.15);
                WaypointAppearanceScreen.openRoute(null, accuracyConfig, manager, accuracyGroup);
            });
            context.waitTicks(3);
            context.runOnClient(client -> {
                var inspector = MinecraftCompat.screen(client).children().stream().filter(WaypointInspectorWidget.class::isInstance)
                        .map(WaypointInspectorWidget.class::cast).findFirst().orElseThrow();
                inspector.setFocused(true);
                inspector.keyPressed(new KeyEvent(32, 0, 0));
            });
            context.waitTicks(3);
            context.takeScreenshot("appearance-accuracy-transition-fading");
            context.waitTicks(12);
            context.takeScreenshot("appearance-accuracy-transition-settled");
            context.runOnClient(client -> MinecraftCompat.setScreen(client,
                    new Screen(Component.literal("Route overview after inspector")) {
                        private RoutePreviewWidget preview;
                        @Override protected void init() {
                            preview = addRenderableWidget(new RoutePreviewWidget(16, 16, width - 32, height - 32,
                                    RoutePreviewScene.build(group, config, client.level), "1 / 1",
                                    new RoutePreviewOrbit(), new RoutePreviewZoom()));
                        }
                        @Override public void removed() { if (preview != null) preview.releaseResources(); }
                    }));
            context.waitTicks(4);
            context.takeScreenshot("route-overview-after-inspector");
        } finally {
            context.runOnClient(client -> {
                MinecraftCompat.setScreen(client, null);
                client.options.guiScale().set(previousScale); client.resizeGui();
            });
        }
    }
    private static EditBox firstNumber(Screen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
    }
    private static void press(Screen screen, String label) {
        Button button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> b.visible && b.active && b.getMessage().getString().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing screen action: " + label));
        button.onPress(new KeyEvent(257, 0, 0));
    }
    private static void checkBounds(Screen screen) {
        for (var child : screen.children()) if (child instanceof AbstractWidget widget && widget.visible)
            if (widget.getX() < 0 || widget.getY() < 0 || widget.getX() + widget.getWidth() > screen.width
                    || widget.getY() + widget.getHeight() > screen.height)
                throw new AssertionError("Appearance control outside screen: " + widget.getMessage().getString());
    }
}
