package com.babbur.waypointer;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

@SuppressWarnings("UnstableApiUsage")
final class CommandSmokeTest {

    private CommandSmokeTest() {}

    static void run(ClientGameTestContext context) {
        ActiveGroupManager manager = WaypointerClient.manager();
        context.runOnClient(client -> {
            wp(client, "route new Command smoke");
            WaypointGroup route = target(manager, "Command smoke");
            wp(client, "add");
            wp(client, "add at 5 80 5");
            wp(client, "add at 9 80 9 Finish");
            check(route.size() == 3, "add should append to the new target route");

            wp(client, "waypoint 2 sub");
            check(route.isSubwaypoint(1), "waypoint 2 sub should make a subwaypoint");
            wp(client, "waypoint 1.1 flag tiny on");
            check(route.get(1).hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT), "tiny flag on 1.1");
            wp(client, "waypoint 2 rename Middle");
            check("Middle".equals(route.get(2).name()), "2 is the third stored waypoint once 1.1 exists");
            wp(client, "waypoint 1 color 123456");
            check(route.get(0).hasFlag(Waypoint.FLAG_LOCKED_COLOR), "own waypoint color");
            wp(client, "skipto 2");
            check(route.currentIndex() == 2, "skipto uses the editor label");
            wp(client, "route mode static");
            check(route.loadMode() == WaypointGroup.LoadMode.STATIC, "route mode static");
            wp(client, "route radius 2.5");
            check(route.defaultRadius() == 2.5, "route radius");

            wp(client, "folder new Smoke folder");
            wp(client, "route folder Smoke folder");
            check(manager.folderForGroup(route.id()) != null, "route folder");
            wp(client, "target 1");
            check(manager.recordingTarget() != null, "target pins a route");
            wp(client, "target auto");
            check(manager.recordingTarget() == null, "target auto unpins");

            wp(client, "list");
            wp(client, "waypoints");
            MinecraftCompat.setScreen(client, new ChatScreen("", false));
        });
        context.waitTicks(5);
        context.takeScreenshot("commands-chat");
        context.runOnClient(client -> {
            MinecraftCompat.setScreen(client, null);
            WaypointGroup route = target(manager, "Command smoke");
            manager.setRecordingTarget(route);
            wp(client, "route delete confirm");
            check(manager.get(route.id()) == null, "route delete confirm removes the target route");
            wp(client, "folder delete Smoke folder");
        });
    }

    private static WaypointGroup target(ActiveGroupManager manager, String name) {
        WaypointGroup target = manager.targetRoute();
        check(target != null && name.equals(target.name()), "target route should be " + name
                + " but was " + (target == null ? "none" : target.name()));
        return target;
    }

    private static void wp(Minecraft client, String command) {
        try {
            ClientCommands.getActiveDispatcher().execute("wp " + command,
                    (FabricClientCommandSource) client.getConnection().getSuggestionsProvider());
        } catch (Exception e) {
            throw new AssertionError("/wp " + command + " failed", e);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
