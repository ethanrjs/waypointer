package com.babbur.waypointer;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.crystal.*;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

/** Synthetic observations rendered by the actual gameplay HUD; these are not live Hypixel evidence. */
@SuppressWarnings("UnstableApiUsage")
final class CrystalHollowsMinimapSmokeTest {
    private record Scene(int size, int corner, double altitude, boolean navigating, boolean atNucleus) {}
    private static Scene scene;
    private static int frames;

    static void run(ClientGameTestContext context) {
        int previousScale = context.computeOnClient(client -> {
            var config = new WaypointerConfig();
            var tracker = new CrystalHollowsTracker(new ActiveGroupManager(), config, null);
            // An inactive lobby must return before touching the render state.
            new CrystalHollowsMinimap(tracker, config).extractRenderState(null, null);
            HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                    Identifier.fromNamespaceAndPath("waypointer-smoke", "synthetic_minimap"), (g, tick) -> {
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
        } finally {
            context.runOnClient(client -> {
                scene = null;
                client.options.guiScale().set(previousScale);
                client.resizeGui();
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
        // Keep the native framebuffer: withSize changes it without resizing the HUD viewport.
        context.takeScreenshot(name);
        if (context.computeOnClient(client -> frames) <= before) {
            throw new AssertionError("Synthetic minimap screenshot never ran the gameplay HUD callback");
        }
    }

    private static StructureSighting sighting(CrystalHollowsStructure structure, int x, int z,
                                             SightingConfidence confidence) {
        return new StructureSighting(structure, x, 110, z, confidence, "synthetic smoke test", 1);
    }
}
