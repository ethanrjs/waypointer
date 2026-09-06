package com.babbur.waypointer;

import com.babbur.waypointer.api.RouteLoadMode;
import com.babbur.waypointer.api.RouteSpec;
import com.babbur.waypointer.api.WaypointFlags;
import com.babbur.waypointer.api.WaypointSpec;
import com.babbur.waypointer.render.gpu.OverlayRenderer;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.crystal.CrystalHollowsPosition;
import com.babbur.waypointer.crystal.MetalDetectorController;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

@SuppressWarnings("UnstableApiUsage")
public final class LaunchSmokeTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            context.waitFor(client -> client.player != null && client.level != null
                    && WaypointerClient.api() != null
                    && WaypointerClient.api().currentZone() != null
                    && "private_world".equals(WaypointerClient.api().currentZone().id()), 600);
            context.waitForScreen(null);
            context.computeOnClient(client -> {
                checkMetalDetectorVisibility(client);
                checkMetalDetectorMessage(client);
                return true;
            });
            String routeId = context.computeOnClient(client -> {
                var position = client.player.blockPosition().offset(0, 1, 8);
                return WaypointerClient.api().createRoute(RouteSpec.builder()
                        .name("Launch test")
                        .zoneId("private_world")
                        .loadMode(RouteLoadMode.STATIC)
                        .waypoint(WaypointSpec.at(position.getX(), position.getY(), position.getZ())
                                .name("Waypoint").flags(WaypointFlags.THROUGH_WALL))
                        .build());
            });
            context.waitFor(client -> WaypointerClient.api().activeGroups().stream()
                    .anyMatch(group -> group.id().equals(routeId) && group.waypoints().size() == 1), 100);
            context.getInput().lookAt(0.0F, 0.0F);
            context.waitTicks(40);
            context.takeScreenshot("waypoint-rendered");
            if (!context.computeOnClient(client -> OverlayRenderer.ownsWorldGeometry())) {
                throw new AssertionError("Default retained renderer did not remain active");
            }
            var reload = context.computeOnClient(client -> client.reloadResourcePacks());
            context.waitFor(client -> reload.isDone(), 600);
            reload.join();
            context.waitTicks(40);
            if (!context.computeOnClient(client -> OverlayRenderer.ownsWorldGeometry()
                    && WaypointerClient.api().activeGroups().stream()
                            .anyMatch(group -> group.id().equals(routeId) && group.waypoints().size() == 1))) {
                throw new AssertionError("Retained geometry did not survive resource reload");
            }
            context.takeScreenshot("waypoint-after-resource-reload");
        }
    }

    private static void checkMetalDetectorMessage(Minecraft client) {
        var player = client.player;
        var position = player.position();
        var held = player.getMainHandItem();
        var scoreboard = client.level.getScoreboard();
        var slot = net.minecraft.world.scores.DisplaySlot.SIDEBAR;
        var previousSidebar = scoreboard.getDisplayObjective(slot);
        var sidebar = scoreboard.addObjective("waypointer_detector_test",
                net.minecraft.world.scores.criteria.ObjectiveCriteria.DUMMY,
                net.minecraft.network.chat.Component.literal("SKYBLOCK"),
                net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER, false, null);
        MetalDetectorController previousController = null;
        java.lang.reflect.Field installed = null;
        try {
            var manager = new com.babbur.waypointer.core.ActiveGroupManager();
            manager.onZoneChanged(new com.babbur.waypointer.core.Zone("crystal_hollows", "Crystal Hollows"));
            var config = new WaypointerConfig();
            var tracker = new com.babbur.waypointer.crystal.CrystalHollowsTracker(manager, config, null);
            var lobby = new com.babbur.waypointer.crystal.CrystalHollowsLobbyState("synthetic", 1, 1);
            lobby.setDivanCentre(new CrystalHollowsPosition(700, 100, 400));
            for (var entry : java.util.Map.<String, Object>of("active", true, "lobby", lobby,
                    "serverId", "synthetic").entrySet()) {
                var field = tracker.getClass().getDeclaredField(entry.getKey());
                field.setAccessible(true);
                field.set(tracker, entry.getValue());
            }
            var item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COMPASS);
            var attributes = new net.minecraft.nbt.CompoundTag();
            attributes.putString("id", "DWARVEN_METAL_DETECTOR");
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.put("ExtraAttributes", attributes);
            item.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                    net.minecraft.world.item.component.CustomData.of(tag));
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, item);
            player.setPos(663.5, 80, 422.5);
            var controller = new MetalDetectorController(manager, tracker, config);
            installed = MetalDetectorController.class.getDeclaredField("installed");
            installed.setAccessible(true);
            previousController = (MetalDetectorController) installed.get(null);
            installed.set(null, controller);
            var updateSidebar = tracker.getClass().getDeclaredMethod("updateSidebar", Minecraft.class);
            updateSidebar.setAccessible(true);
            var reading = net.minecraft.network.chat.Component.literal("TREASURE: 3.9m");
            var packet = new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(reading);
            client.getConnection().setActionBarText(packet);
            client.getConnection().setActionBarText(packet);
            if (!manager.activeGroups().isEmpty()) throw new AssertionError("Detector ran outside Mines of Divan");
            scoreboard.setDisplayObjective(slot, sidebar);
            var areaLine = net.minecraft.world.scores.ScoreHolder.forNameOnly("\uE067 Mines of Divan");
            scoreboard.getOrCreatePlayerScore(areaLine, sidebar).set(1);
            updateSidebar.invoke(tracker, client);
            client.getConnection().setActionBarText(packet);
            client.getConnection().setActionBarText(packet);
            Object hud = client.gui;
            try { hud = client.gui.getClass().getField("hud").get(client.gui); }
            catch (NoSuchFieldException ignored) { /* 26.1 keeps the HUD on Gui. */ }
            var overlay = hud.getClass().getDeclaredField("overlayMessageString");
            overlay.setAccessible(true);
            String displayed = ((net.minecraft.network.chat.Component) overlay.get(hud)).getString();
            if (!displayed.equals("TREASURE: 3.9m")) {
                throw new AssertionError("Detector changed the server action-bar text: " + displayed);
            }
            var groups = manager.activeGroups();
            if (groups.size() != 1 || !MetalDetectorController.isDetectorGroup(groups.getFirst())
                    || groups.getFirst().size() != 1 || groups.getFirst().get(0).x() != 662
                    || groups.getFirst().get(0).y() != 78 || groups.getFirst().get(0).z() != 426
                    || groups.getFirst().loadMode() != com.babbur.waypointer.core.WaypointGroup.LoadMode.SEQUENCE) {
                throw new AssertionError("Official detector item action bar did not produce the solved waypoint");
            }
            scoreboard.resetSinglePlayerScore(areaLine, sidebar);
            scoreboard.getOrCreatePlayerScore(net.minecraft.world.scores.ScoreHolder.forNameOnly(
                    "\uE067 Lost Precursor City"), sidebar).set(1);
            updateSidebar.invoke(tracker, client);
            client.getConnection().setActionBarText(packet);
            if (!manager.activeGroups().isEmpty()) throw new AssertionError("Detector marker survived leaving Divan");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Metal detector message check failed", e);
        } finally {
            try { if (installed != null) installed.set(null, previousController); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
            scoreboard.setDisplayObjective(slot, previousSidebar);
            scoreboard.removeObjective(sidebar);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
            player.setPos(position.x, position.y, position.z);
        }
    }

    private static void checkMetalDetectorVisibility(Minecraft client) {
        var player = client.player;
        var level = client.level;
        var originalPosition = player.position();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        BlockPos origin = player.blockPosition().above(8);
        var restored = new java.util.LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        try {
            // Synthetic client-only scene: all edits and ray checks happen before another tick.
            for (int z = 0; z <= 5; z++) {
                BlockPos block = origin.offset(0, 1, z);
                restored.put(block, level.getBlockState(block));
                level.setBlock(block, Blocks.AIR.defaultBlockState(), 3);
            }
            player.setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
            player.setYRot(0);
            player.setXRot(0);
            BlockPos chest = origin.offset(0, 1, 5);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            var controller = new MetalDetectorController(null, null, new WaypointerConfig());
            var visible = MetalDetectorController.class.getDeclaredMethod("visibleChests", Minecraft.class);
            visible.setAccessible(true);
            var expected = new CrystalHollowsPosition(chest.getX(), chest.getY(), chest.getZ());
            if (!((java.util.List<?>) visible.invoke(controller, client)).contains(expected)) {
                throw new AssertionError("Metal detector did not detect an aimed visible chest");
            }
            level.setBlock(origin.offset(0, 1, 2), Blocks.STONE.defaultBlockState(), 3);
            if (((java.util.List<?>) visible.invoke(controller, client)).contains(expected)) {
                throw new AssertionError("Metal detector detected a chest through an obstruction");
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Metal detector visibility check failed", e);
        } finally {
            restored.forEach((block, state) -> level.setBlock(block, state, 3));
            player.setPos(originalPosition.x, originalPosition.y, originalPosition.z);
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
    }
}
