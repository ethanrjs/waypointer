package com.babbur.waypointer;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.crystal.CrystalHollowsStructure;
import com.babbur.waypointer.crystal.CrystalHollowsStructureRoutes;
import com.babbur.waypointer.crystal.CrystalHollowsStructureZones;
import com.babbur.waypointer.screen.DebugReportConsentScreen;
import com.babbur.waypointer.screen.GroupEditScreen;
import com.babbur.waypointer.screen.GuiTokens;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

@SuppressWarnings("UnstableApiUsage")
final class StructureEditorSmokeTest {
    static void run(ClientGameTestContext context) {
        int scale = context.computeOnClient(client -> client.options.guiScale().get());
        try {
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeGui();
                var manager = new ActiveGroupManager();
                var config = new WaypointerConfig();
                config.setShowWaypointChatShareButtons(false);
                var group = WaypointGroup.create("Unlocated structure", CrystalHollowsStructureZones.zoneId(
                        CrystalHollowsStructure.JUNGLE_TEMPLE));
                if (CrystalHollowsStructureRoutes.pivotFor(group) != null)
                    throw new AssertionError("Fixture unexpectedly has a structure pivot");
                var editor = new GroupEditScreen(null, manager, config, group);
                MinecraftCompat.setScreen(client, editor);
                String addLabel = Component.translatable("waypointer.screen.group_edit.add").getString();
                AbstractWidget add = editor.children().stream().filter(AbstractWidget.class::isInstance)
                        .map(AbstractWidget.class::cast).filter(w -> w.getMessage().getString().equals(addLabel))
                        .findFirst().orElseThrow(() -> new AssertionError("Missing Add action: " + addLabel));
                click(editor, add);
                if (group.size() != 1 || group.get(0).x() != 0 || group.get(0).y() != 0 || group.get(0).z() != 0)
                    throw new AssertionError("Unlocated structure Add did not use NPC-relative origin");
                ((EditBox) field(editor, "coordXBox")).setValue("12");
                ((EditBox) field(editor, "coordYBox")).setValue("-3");
                ((EditBox) field(editor, "coordZBox")).setValue("45");
                if (group.get(0).x() != 12 || group.get(0).y() != -3 || group.get(0).z() != 45)
                    throw new AssertionError("Manual structure coordinates were not stored relatively");
                click(editor, (AbstractWidget) field(editor, "undoButton"));
                if (group.get(0).z() != 0) throw new AssertionError("Editor undo did not restore coordinate");
                click(editor, (AbstractWidget) field(editor, "redoButton"));
                if (group.get(0).z() != 45) throw new AssertionError("Editor redo did not restore coordinate");
            });
            context.waitTicks(2);
            context.takeScreenshot("structure-editor-before-discovery");
            context.runOnClient(client -> {
                var report = new DebugReportConsentScreen(null, ignored -> {});
                MinecraftCompat.setScreen(client, report);
                long categories = report.children().stream().filter(GuiTokens.StyledCheckbox.class::isInstance)
                        .map(GuiTokens.StyledCheckbox.class::cast).peek(checkbox -> {
                            String text = checkbox.getMessage().getString();
                            if (text.isBlank() || text.contains("\u00c3") || text.contains("\u00c2") || text.contains("\ufffd"))
                                throw new AssertionError("Malformed report category: " + text);
                        }).count();
                if (categories != 6) throw new AssertionError("Missing report categories");
            });
            context.waitTicks(2);
            context.takeScreenshot("report-consent-category-labels");
            context.runOnClient(client -> {
                var route = WaypointGroup.create("Aligned rows", "dungeon_f7");
                route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
                for (int i = 0; i < 14; i++) {
                    var waypoint = Waypoint.at(i * 37 - 200, 70 + i, i * 11);
                    if (i == 4) waypoint = waypoint.withName("Named waypoint");
                    if (i == 6) waypoint = waypoint.withFlags(Waypoint.FLAG_DUNGEON_SECRET
                            | Waypoint.FLAG_SKIP_ON_INTERACT);
                    route.add(waypoint);
                }
                route.toggleSubwaypoint(1);
                route.toggleSubwaypoint(2);
                route.toggleSubwaypoint(13);
                MinecraftCompat.setScreen(client, new GroupEditScreen(
                        null, new ActiveGroupManager(), new WaypointerConfig(), route));
            });
            context.waitTicks(2);
            context.takeScreenshot("route-editor-aligned-rows");
        } finally {
            context.runOnClient(client -> {
                MinecraftCompat.setScreen(client, null);
                client.options.guiScale().set(scale);
                client.resizeGui();
            });
        }
    }

    private static void click(Screen screen, AbstractWidget widget) {
        if (!widget.active || !widget.visible) throw new AssertionError("Inactive action: " + widget.getMessage().getString());
        var mouse = new MouseButtonEvent(widget.getX() + widget.getWidth() / 2.0,
                widget.getY() + widget.getHeight() / 2.0, new MouseButtonInfo(0, 0));
        screen.mouseClicked(mouse, false);
        screen.mouseReleased(mouse);
    }

    private static Object field(Object owner, String name) {
        try {
            var field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
