package com.babbur.waypointer;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.crystal.*;
import com.babbur.waypointer.screen.CrystalHollowsMinimapEditorScreen;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

@SuppressWarnings("UnstableApiUsage")
final class CrystalHollowsMinimapSmokeTest {
    private record Scene(int size, int corner, double altitude, boolean navigating, boolean atNucleus) {}
    private static Scene scene;
    private static int frames;
    private static CrystalHollowsMinimap gameplayMinimap;

    static void run(ClientGameTestContext context) {
        int previousScale = context.computeOnClient(client -> {
            var config = new WaypointerConfig();
            var tracker = new CrystalHollowsTracker(new ActiveGroupManager(), config, null);
            new CrystalHollowsMinimap(tracker, config).extractRenderState(null, null);
            HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                    Identifier.fromNamespaceAndPath("waypointer-smoke", "synthetic_minimap"), (g, tick) -> {
                        if (gameplayMinimap != null) {
                            gameplayMinimap.extractRenderState(g, tick);
                            return;
                        }
                        if (scene == null) return;
                        var layout = CrystalHollowsMinimapLayout.fit(g.guiWidth(), g.guiHeight(),
                                scene.size(), scene.corner(), scene.navigating());
                        if (layout == null || layout.x() < 0 || layout.y() < 0
                                || layout.x() + layout.width() > g.guiWidth()
                                || layout.y() + layout.height() > g.guiHeight()) {
                            throw new AssertionError("Synthetic minimap does not fit actual HUD viewport");
                        }
                        var divan = sighting(CrystalHollowsStructure.MINES_OF_DIVAN, 700, 300,
                                SightingConfidence.COMPASS);
                        double playerX = scene.atNucleus() ? CrystalHollowsGeometry.NUCLEUS_CENTRE_X : 410;
                        double playerZ = scene.atNucleus() ? CrystalHollowsGeometry.NUCLEUS_CENTRE_Z : 470;
                        CrystalHollowsMinimap.draw(g, layout, playerX, scene.altitude(), playerZ, 35,
                                List.of(divan,
                                        sighting(CrystalHollowsStructure.JUNGLE_TEMPLE, 290, 310, SightingConfidence.ENTITY),
                                        sighting(CrystalHollowsStructure.GOBLIN_QUEENS_DEN, 310, 710, SightingConfidence.ROUGH_AREA),
                                        sighting(CrystalHollowsStructure.LOST_PRECURSOR_CITY, 715, 710, SightingConfidence.ENTITY),
                                        sighting(CrystalHollowsStructure.FAIRY_GROTTO, 822, 488, SightingConfidence.ENTITY)),
                                scene.navigating() ? divan : null, true, true);
                        frames++;
                    });
            int scale = client.options.guiScale().get();
            return scale;
        });
        try {
            for (int size : new int[]{96, 144}) for (int scale : new int[]{2, 4}) {
                capture(context, new Scene(size, size == 96 ? 3 : 0, 110, false, size == 144),
                        "synthetic-minimap-idle-" + size + "-gui" + scale, scale);
                capture(context, new Scene(size, 0, 110, true, false),
                        "synthetic-minimap-target-" + size + "-gui" + scale, scale);
            }
            capture(context, new Scene(224, 3, 110, true, false), "synthetic-minimap-small-gui-bottom-right", 4);
            capture(context, new Scene(144, 1, 50, false, false), "synthetic-minimap-magma-top-right", 2);
            capture(context, new Scene(143, 0, 110, true, false), "synthetic-minimap-odd-size-143", 4);
            capture(context, new Scene(143, 0, 110, false, true), "synthetic-minimap-odd-nucleus-player", 4);
            capture(context, new Scene(224, 0, 110, true, false), "synthetic-minimap-large-224", 2);
            checkChatVisibility(context);
            checkEditor(context);
        } finally {
            context.runOnClient(client -> {
                scene = null;
                client.options.guiScale().set(previousScale);
                client.resizeGui();
            });
        }
    }

    private static void checkChatVisibility(ClientGameTestContext context) {
        var previousScreen = context.computeOnClient(MinecraftCompat::screen);
        try {
            context.runOnClient(client -> {
                var config = new WaypointerConfig();
                config.setCrystalHollowsMinimap(true);
                var tracker = new CrystalHollowsTracker(new ActiveGroupManager(), config, null);
                try {
                    var active = CrystalHollowsTracker.class.getDeclaredField("active");
                    active.setAccessible(true);
                    active.setBoolean(tracker, true);
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError("Cannot activate synthetic minimap lobby", failure);
                }
                gameplayMinimap = new CrystalHollowsMinimap(tracker, config);
                var chat = new ChatScreen("", false);
                for (Screen screen : new Screen[]{null, chat, new Screen(Component.literal("Menu")) {}}) {
                    MinecraftCompat.setScreen(client, screen);
                    var state = new GuiRenderState();
                    var graphics = new GuiGraphicsExtractor(client, state, 0, 0);
                    gameplayMinimap.extractRenderState(graphics, null);
                    int[] elements = {0};
                    state.forEachElement(element -> elements[0]++, GuiRenderState.TraverseRange.ALL);
                    boolean expected = screen == null || screen == chat;
                    if ((elements[0] > 0) != expected) {
                        throw new AssertionError("Minimap visibility is incorrect with "
                                + (screen == null ? "no screen" : screen.getClass().getName()));
                    }
                }
                MinecraftCompat.setScreen(client, chat);
            });
            context.waitTicks(2);
            context.takeScreenshot("synthetic-minimap-chat-open");
        } finally {
            context.runOnClient(client -> {
                gameplayMinimap = null;
                MinecraftCompat.setScreen(client, previousScreen);
            });
        }
    }

    private static void capture(ClientGameTestContext context, Scene next, String name, int guiScale) {
        context.runOnClient(client -> {
            scene = next;
            client.options.guiScale().set(guiScale);
            client.resizeGui();
        });
        context.waitTicks(2);
        int before = context.computeOnClient(client -> {
            if (client.getWindow().getGuiScale() != guiScale) {
                throw new AssertionError("Requested GUI scale " + guiScale + " was clamped to "
                        + client.getWindow().getGuiScale() + "; launch the smoke client at least 1708x960");
            }
            Waypointer.LOGGER.info("Synthetic minimap {}: HUD {}x{}, GUI scale {}, font line height {}, title width {}",
                    name, client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight(),
                    client.getWindow().getGuiScale(), client.font.lineHeight,
                    client.font.width(net.minecraft.network.chat.Component.translatable("waypointer.crystal.minimap.title")));
            return frames;
        });
        context.takeScreenshot(name);
        if (context.computeOnClient(client -> frames) <= before) {
            throw new AssertionError("Synthetic minimap screenshot never ran the gameplay HUD callback");
        }
    }

    private static void checkEditor(ClientGameTestContext context) {
        var previousScreen = context.computeOnClient(MinecraftCompat::screen);
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        var config = new WaypointerConfig();
        try {
            context.runOnClient(client -> {
                scene = null;
                client.options.guiScale().set(2);
                client.resizeGui();
                var editor = new CrystalHollowsMinimapEditorScreen(previousScreen, config);
                MinecraftCompat.setScreen(client, editor);
                scrollEditor(editor, 1);
                assertDraft(editor, "scale", 1.05);
                assertPlacement(config, 1, 0, 0, false);
                clickEditorButton(editor, "gui.cancel");
                assertPlacement(config, 1, 0, 0, false);
                if (MinecraftCompat.screen(client) != previousScreen) throw new AssertionError("Cancel did not return to parent");

                editor = new CrystalHollowsMinimapEditorScreen(previousScreen, config);
                MinecraftCompat.setScreen(client, editor);
                scrollEditor(editor, 1);
                var layout = editorLayout(editor);
                double fromX = layout.x() + 20;
                double fromY = layout.y() + 10;
                double toX = CrystalHollowsMinimapLayout.placementX(editor.width, layout.width(), 0.5) + 23;
                double toY = CrystalHollowsMinimapLayout.placementY(editor.height, layout.height(), 0.5) + 13;
                if (!editor.mouseClicked(mouse(fromX, fromY), false)
                        || !editor.mouseDragged(mouse(toX, toY), toX - fromX, toY - fromY)) {
                    throw new AssertionError("Minimap did not accept the native drag gesture");
                }
                assertDraft(editor, "positionX", 0.5);
                assertDraft(editor, "positionY", 0.5);
                assertPlacement(config, 1, 0, 0, false);
            });
            context.waitTicks(2);
            context.takeScreenshot("minimap-editor-snapped-guides-gui2");
            context.runOnClient(client -> {
                var editor = (CrystalHollowsMinimapEditorScreen) MinecraftCompat.screen(client);
                var layout = editorLayout(editor);
                if (!editor.mouseReleased(mouse(layout.x() + 23, layout.y() + 13))) {
                    throw new AssertionError("Minimap did not finish the drag");
                }
                clickEditorButton(editor, "waypointer.minimap.editor.save");
                assertPlacement(config, 1.05, 0.5, 0.5, true);
                if (MinecraftCompat.screen(client) != previousScreen) throw new AssertionError("Save did not return to parent");

                client.options.guiScale().set(4);
                client.resizeGui();
                editor = new CrystalHollowsMinimapEditorScreen(previousScreen, config);
                MinecraftCompat.setScreen(client, editor);
                for (int step = 0; step < 11; step++) scrollEditor(editor, -1);
                assertDraft(editor, "scale", 0.5);
                scrollEditor(editor, -1);
                assertDraft(editor, "scale", 0.5);
                assertPlacement(config, 1.05, 0.5, 0.5, true);
            });
            context.waitTicks(2);
            context.takeScreenshot("minimap-editor-half-scale-gui4");
            context.runOnClient(client -> {
                var editor = (CrystalHollowsMinimapEditorScreen) MinecraftCompat.screen(client);
                clickEditorButton(editor, "waypointer.minimap.editor.reset");
                assertDraft(editor, "scale", 1);
                assertDraft(editor, "positionX", 0);
                assertDraft(editor, "positionY", 0);
                assertPlacement(config, 1.05, 0.5, 0.5, true);
                clickEditorButton(editor, "gui.cancel");
                assertPlacement(config, 1.05, 0.5, 0.5, true);
            });
        } finally {
            context.runOnClient(client -> {
                MinecraftCompat.setScreen(client, previousScreen);
                client.options.guiScale().set(previousScale);
                client.resizeGui();
            });
        }
    }

    private static MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
    }

    private static void clickEditorButton(Screen screen, String key) {
        String label = Component.translatable(key).getString();
        var button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(candidate -> candidate.visible && candidate.active && candidate.getMessage().getString().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("Missing minimap editor button: " + label));
        var click = mouse(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0);
        if (!screen.mouseClicked(click, false)) throw new AssertionError("Minimap editor button rejected click: " + label);
        screen.mouseReleased(click);
    }

    private static void scrollEditor(CrystalHollowsMinimapEditorScreen editor, double amount) {
        var layout = editorLayout(editor);
        if (!editor.mouseScrolled(layout.x() + layout.width() / 2.0,
                layout.y() + layout.height() / 2.0, 0, amount)) {
            throw new AssertionError("Minimap editor did not accept scroll over the map");
        }
    }

    private static CrystalHollowsMinimapLayout editorLayout(CrystalHollowsMinimapEditorScreen editor) {
        try {
            var method = CrystalHollowsMinimapEditorScreen.class.getDeclaredMethod("layout");
            method.setAccessible(true);
            return (CrystalHollowsMinimapLayout) method.invoke(editor);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect minimap editor layout", failure);
        }
    }

    private static void assertDraft(CrystalHollowsMinimapEditorScreen editor, String name, double expected) {
        try {
            var field = CrystalHollowsMinimapEditorScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            if (field.getDouble(editor) != expected) throw new AssertionError("Unexpected minimap draft " + name);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect minimap editor draft", failure);
        }
    }

    private static void assertPlacement(WaypointerConfig config, double scale, double x, double y, boolean custom) {
        if (config.minimapScale() != scale || config.minimapPositionX() != x || config.minimapPositionY() != y
                || config.minimapCustomPosition() != custom) {
            throw new AssertionError("Minimap editor saved an unexpected placement or changed config before Save");
        }
    }

    private static StructureSighting sighting(CrystalHollowsStructure structure, int x, int z,
                                             SightingConfidence confidence) {
        return new StructureSighting(structure, x, 110, z, confidence, "synthetic smoke test", 1);
    }
}
