package com.babbur.waypointer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.chat.ChatImportCache;
import com.babbur.waypointer.codec.WaypointCodec;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.Direction;
import com.babbur.waypointer.dungeon.DungeonDetectionConfidence;
import com.babbur.waypointer.dungeon.DungeonRoom;
import com.babbur.waypointer.dungeon.DungeonRoomShape;
import com.babbur.waypointer.dungeon.DungeonRoomType;
import com.babbur.waypointer.dungeon.DungeonStateTracker;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.input.WaypointAddFlow;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

class WaypointerCommandTreeTest {

    @Test
    void registerAddsEveryBranchAndRedirectsAliases() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher();
        List<String> branches = List.of(
                "help", "gui", "settings", "community", "routes", "edit", "editmode", "appearance",
                "publish", "minimap", "debug", "list", "target", "waypoints", "route", "folder",
                "show", "hide", "clear", "add", "insert", "remove", "move", "waypoint", "skip",
                "unskip", "skipto", "reset", "addtemp", "chattemp", "blacklist", "export", "import",
                "importfile", "importchat");

        CommandNode<FabricClientCommandSource> rootNode = dispatcher.getRoot().getChild("wp");
        assertNotNull(rootNode, "missing canonical command root wp");
        for (String branch : branches) {
            assertNotNull(rootNode.getChild(branch), "missing /wp " + branch + " command branch");
        }
        for (String removed : List.of("group", "area", "sub", "tiny", "filled", "hap", "sts", "its",
                "los", "mode", "radius")) {
            assertNull(rootNode.getChild(removed), "/wp " + removed + " was folded into route or waypoint commands");
        }
        for (String alias : List.of("waypointer", "wptr")) {
            CommandNode<FabricClientCommandSource> aliasNode = dispatcher.getRoot().getChild(alias);
            assertNotNull(aliasNode, "missing command alias " + alias);
            assertSame(rootNode, aliasNode.getRedirect(), "/" + alias + " should redirect to /wp");
            assertNotNull(aliasNode.getCommand(), "/" + alias + " should retain the root action");
        }
    }

    @Test
    void everyDocumentedCommandParsesWithoutSyntaxErrors() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher();
        List<String> commands = List.of(
                "wp help", "wp help waypoints", "wp help all", "wptr help", "waypointer list",
                "wp gui 123e4567-e89b-12d3-a456-426614174000", "waypointer gui \"route:id with spaces\"",
                "wp gui dungeon", "wp settings", "wp community", "wp routes", "wp edit", "wp edit mode",
                "wp editmode", "wp appearance", "wp publish", "wp minimap", "wp debug",
                "wp list", "wp target", "wp target 2", "wp target Ruby route", "wp target auto",
                "wp waypoints",
                "wp route", "wp route new Fairy souls", "wp route rename Ruby route",
                "wp route delete", "wp route delete confirm", "wp route mode sequence", "wp route mode static",
                "wp route radius 4.5", "wp route skipahead", "wp route skipahead off",
                "wp route colormode gradient", "wp route color 4FE05A", "wp route gradient 00BFFF FF3040",
                "wp route zone current", "wp route zone the_park", "wp route move up", "wp route move down",
                "wp route folder Gemstones", "wp route folder none",
                "wp show 2", "wp show all", "wp hide", "wp hide Ruby route", "wp hide all",
                "wp folder new Gemstones", "wp folder rename \"Gem stuff\" Gemstones", "wp folder color Gemstones 4FB3C4", "wp folder delete Gemstones",
                "wp clear", "wp clear confirm",
                "wp add", "wp add Secret Lever", "wp add at 10 64 -20", "wp add at 10 64 -20 Secret Lever",
                "wp insert 2", "wp insert 2 Chest", "wp insert 2.1 at 10 64 -20 Chest",
                "wp remove 3", "wp remove 2.1", "wp move 4 2",
                "wp waypoint 3", "wp waypoint 3 rename", "wp waypoint 3 rename Lever", "wp waypoint 3 here",
                "wp waypoint 3 at 10 64 -20", "wp waypoint 3 up", "wp waypoint 3 down",
                "wp waypoint 3 color 58C878", "wp waypoint 3 color reset", "wp waypoint 3 radius 1.5",
                "wp waypoint 3 current", "wp waypoint 3 enable", "wp waypoint 3 disable",
                "wp waypoint 4 sub", "wp waypoint 3 addsub", "wp waypoint 3 addsub Ledge",
                "wp waypoint 2.1 flag tiny", "wp waypoint 3 flag line-of-sight off",
                "wp waypoint 3 type secret", "wp waypoint 3 appearance", "wp waypoint 3 remove",
                "wp skip", "wp unskip", "wp skipto 3", "wp skipto 3.2", "wp reset",
                "wp addtemp", "wp addtemp at -5 70 9", "wp addtemp at -5 70 9 Party Chat",
                "wp chattemp 1 2 3 Babbur party", "wp blacklist", "wp blacklist add Babbur",
                "wp blacklist remove Babbur",
                "wp export", "wp export routes", "wp export config", "wp export dungeon", "wp export bare",
                "wp export names", "wp export nonames", "wp import", "wp import WP:abc",
                "wp importfile C:\\routes\\waypoints.json", "wp importchat A1b2",
                "wp importchat config A1b2", "wp importchat dungeon A1b2", "wp importchat catalog A1b2");

        for (String command : commands) {
            ParseResults<FabricClientCommandSource> parsed = dispatcher.parse(command, null);
            assertTrue(parsed.getExceptions().isEmpty(),
                    command + " should parse without syntax errors: " + parsed.getExceptions());
            assertTrue(parsed.getReader().getRemaining().isEmpty(),
                    command + " left an unparsed suffix: " + parsed.getReader().getRemaining());
            assertNotNull(parsed.getContext().getCommand(), command + " should be runnable");
        }
    }

    @Test
    void reachRadiusCommandsRejectValuesAboveTheCanonicalMaximum() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher();

        for (String command : List.of(
                "wp route radius 100.1",
                "wp waypoint 1 radius 100.1")) {
            ParseResults<FabricClientCommandSource> parsed = dispatcher.parse(command, null);
            assertFalse(parsed.getExceptions().isEmpty(), command + " should reject unsafe reach radii");
        }
    }

    @Test
    void cliExportDefaultOptionsMirrorConfigDefaultsWithoutImplicitLabel() throws Exception {
        WaypointCodec.Options options = invokeExportOptionsFromConfig(new WaypointerConfig());

        assertFalse(options.includeNames);
        assertFalse(options.includeColors);
        assertFalse(options.includeRadii);
        assertFalse(options.includeWaypointFlags);
        assertFalse(options.includeGroupMeta);
        assertFalse(options.includeZone);
        assertEquals("", options.label);
    }

    @Test
    void successfulCommandExportDoesNotShowRedundantManualCopyAction() {
        Component copied = WaypointerCommands.exportSuccessMessage(
                1, "WP:payload", WaypointCodec.Options.WITH_NAMES, true);
        Component failed = WaypointerCommands.exportSuccessMessage(
                1, "WP:payload", WaypointCodec.Options.WITH_NAMES, false);

        assertTrue(copied.getSiblings().stream()
                .noneMatch(component -> component.getStyle().getClickEvent() != null));
        Component manualCopy = failed.getSiblings().stream()
                .filter(component -> component.getStyle().getClickEvent() != null)
                .findFirst()
                .orElseThrow();
        assertInstanceOf(ClickEvent.CopyToClipboard.class, manualCopy.getStyle().getClickEvent());
    }

    @Test
    void addPersistentWaypointAtCreatesCurrentZoneRouteWaypoint() {
        ActiveGroupManager manager = new ActiveGroupManager();
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointerConfig config = new WaypointerConfig();

        int index = WaypointerCommands.addPersistentWaypointAt(manager, config,
                new WaypointAddFlow(), 10, 64, -20, null);

        List<WaypointGroup> hubGroups = manager.groupsForZone("hub");
        assertEquals(1, hubGroups.size());
        WaypointGroup group = hubGroups.get(0);
        assertEquals(0, index);
        assertEquals("Route -- hub", group.name());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, group.loadMode());
        assertEquals(1, group.size());

        Waypoint waypoint = group.get(0);
        assertEquals(10, waypoint.x());
        assertEquals(64, waypoint.y());
        assertEquals(-20, waypoint.z());
        assertEquals("", waypoint.name());
    }

    @Test
    void addPersistentWaypointAtStoresDungeonRoomLocalCoordinates() throws Exception {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerConfig config = new WaypointerConfig();
        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.PUZZLE,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.SE,
                -74,
                -138,
                List.of(DungeonRoom.packSegment(-104, -168)),
                "command-room",
                "Command Room",
                DungeonDetectionConfidence.CORE_CONFIRMED);
        DungeonStateTracker tracker = new DungeonStateTracker(manager, new DungeonConfig());
        Field trackerField = WaypointerClient.class.getDeclaredField("dungeonTracker");
        trackerField.setAccessible(true);
        Object previousTracker = trackerField.get(null);
        Method setCurrentRoom = DungeonStateTracker.class.getDeclaredMethod(
                "setCurrentRoom", DungeonRoom.class);
        setCurrentRoom.setAccessible(true);

        try {
            trackerField.set(null, tracker);
            setCurrentRoom.invoke(tracker, room);
            manager.onZoneChanged(new Zone("command-room", "Command Room"));
            WaypointGroup route = WaypointGroup.create("Route", "command-room");
            route.setRouteKind(WaypointGroup.RouteKind.DUNGEON);
            manager.add(route);

            int index = WaypointerCommands.addPersistentWaypointAt(manager, config,
                    new WaypointAddFlow(), -95, 68, -121, "beam");

            Waypoint waypoint = manager.groupsForZone("command-room").get(0).get(index);
            assertEquals(21, waypoint.x());
            assertEquals(68, waypoint.y());
            assertEquals(-17, waypoint.z());
            assertEquals("beam", waypoint.name());
        } finally {
            trackerField.set(null, previousTracker);
        }
    }

    @Test
    void insertPersistentWaypointAtAcceptsZeroThroughSizeOnly() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(Waypoint.at(0, 64, 0));
        group.add(Waypoint.at(2, 64, 2));
        WaypointerConfig config = new WaypointerConfig();
        WaypointAddFlow addFlow = new WaypointAddFlow();

        assertEquals(-1, WaypointerCommands.insertPersistentWaypointAt(
                group, config, addFlow, -1, 9, 70, 9, "Bad"));
        assertEquals(-1, WaypointerCommands.insertPersistentWaypointAt(
                group, config, addFlow, 3, 9, 70, 9, "Bad"));
        assertEquals(2, group.size());

        assertEquals(1, WaypointerCommands.insertPersistentWaypointAt(
                group, config, addFlow, 1, 1, 65, 1, "Middle"));

        assertEquals(3, group.size());
        assertEquals("Middle", group.get(1).name());
        assertEquals(1, group.get(1).x());
        assertEquals(65, group.get(1).y());
        assertEquals(1, group.get(1).z());
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, group.loadMode());
    }

    @Test
    void clearCurrentZoneGroupsRequiresConfirmAndOnlyDeletesCurrentZone() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup hubOne = WaypointGroup.create("Hub 1", "hub");
        WaypointGroup hubTwo = WaypointGroup.create("Hub 2", "hub");
        WaypointGroup dungeon = WaypointGroup.create("Dungeon", "dungeon_f7");
        manager.add(hubOne);
        manager.add(hubTwo);
        manager.add(dungeon);
        manager.onZoneChanged(new Zone("hub", "Hub"));

        assertEquals(0, WaypointerCommands.clearCurrentZoneGroups(manager, false));
        assertEquals(3, manager.allGroupsList().size());

        assertEquals(2, WaypointerCommands.clearCurrentZoneGroups(manager, true));

        assertEquals(List.of(dungeon), manager.allGroupsList());
        assertEquals(List.of(), manager.groupsForZone("hub"));
        assertEquals(List.of(dungeon), manager.groupsForZone("dungeon_f7"));
    }

    @Test
    void importEditorHintUsesExplicitOpenCommand() {
        Component hint = WaypointerCommands.importEditorHintComponent();
        Component openRun = hint.getSiblings().get(0);

        ClickEvent clickEvent = openRun.getStyle().getClickEvent();

        ClickEvent.RunCommand runCommand = assertInstanceOf(ClickEvent.RunCommand.class, clickEvent);
        assertEquals("/waypointer gui", runCommand.command());
    }

    @Test
    void focusedGuiResolutionFindsOnlyCurrentManagerGroups() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup route = new WaypointGroup("route:id with spaces", "Route", "hub");
        manager.add(route);

        assertSame(route, WaypointerCommands.resolveGuiFocus(manager, route.id()));
        assertNull(WaypointerCommands.resolveGuiFocus(manager, "stale"));
        assertNull(WaypointerCommands.resolveGuiFocus(null, route.id()));
        assertNull(WaypointerCommands.resolveGuiFocus(manager, ""));
    }

    @Test
    void guiParsesFocusedIdsWithoutShadowingTheDungeonLiteral() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher();
        CommandNode<FabricClientCommandSource> gui = dispatcher.getRoot()
                .getChild("wp").getChild("gui");

        assertNotNull(gui.getChild("dungeon"));
        assertNotNull(gui.getChild("groupId"));
        for (String command : List.of(
                "wp gui 123e4567-e89b-12d3-a456-426614174000",
                "waypointer gui \"route:id with spaces\"",
                "wp gui dungeon")) {
            ParseResults<FabricClientCommandSource> parsed = dispatcher.parse(command, null);
            assertTrue(parsed.getExceptions().isEmpty(), parsed.getExceptions().toString());
            assertTrue(parsed.getReader().getRemaining().isEmpty(),
                    () -> "unparsed command suffix: " + parsed.getReader().getRemaining());
        }
    }

    @Test
    void importSuccessCombinesStatusAndOpenActionIntoOneChatComponent() {
        Component line = WaypointerCommands.importSuccessMessage(
                1, "clipboard", "WAYPOINTER", 1,
                new Zone("crystal_hollows", "Crystal Hollows"), "");

        assertTrue(line.toString().contains("waypointer.command.import.success"));
        assertTrue(line.toString().contains("waypointer.command.import.retargeted.one"));
        assertTrue(line.getString().contains(" \u00B7 "));
        assertFalse(line.getString().contains("\u00C2"));
        Component openRun = line.getSiblings().get(line.getSiblings().size() - 1);
        ClickEvent.RunCommand runCommand = assertInstanceOf(
                ClickEvent.RunCommand.class, openRun.getStyle().getClickEvent());
        assertEquals("/waypointer gui", runCommand.command());
    }

    @Test
    void helpTopicsResolveSectionsCommandsAndNumbers() {
        assertEquals(0, WaypointerCommandHelp.resolvePage(null));
        assertEquals(0, WaypointerCommandHelp.resolvePage("target"));
        assertEquals(0, WaypointerCommandHelp.resolvePage("list"));
        assertEquals(1, WaypointerCommandHelp.resolvePage("routes"));
        assertEquals(1, WaypointerCommandHelp.resolvePage("route"));
        assertEquals(1, WaypointerCommandHelp.resolvePage("hide"));
        assertEquals(1, WaypointerCommandHelp.resolvePage("folder"));
        assertEquals(2, WaypointerCommandHelp.resolvePage("waypoints"));
        assertEquals(2, WaypointerCommandHelp.resolvePage("waypoint"));
        assertEquals(2, WaypointerCommandHelp.resolvePage("add"));
        assertEquals(3, WaypointerCommandHelp.resolvePage("skipto"));
        assertEquals(3, WaypointerCommandHelp.resolvePage("editmode"));
        assertEquals(4, WaypointerCommandHelp.resolvePage("export"));
        assertEquals(5, WaypointerCommandHelp.resolvePage("addtemp"));
        assertEquals(6, WaypointerCommandHelp.resolvePage("debug"));
        assertEquals(7, WaypointerCommandHelp.resolvePage("wpch"));
        assertEquals(7, WaypointerCommandHelp.resolvePage("minimap"));
        assertEquals(2, WaypointerCommandHelp.resolvePage("3"));
        assertEquals(-1, WaypointerCommandHelp.resolvePage("all"));
        assertEquals(-1, WaypointerCommandHelp.resolvePage("missing"));
    }

    @Test
    void waypointLabelSuggestionsUseTheEditorNumbers() {
        WaypointGroup route = WaypointGroup.create("Route", "hub");
        route.add(Waypoint.at(0, 0, 0));
        route.add(Waypoint.at(1, 0, 0));
        route.add(Waypoint.at(2, 0, 0));
        route.toggleSubwaypoint(1);
        SuggestionsBuilder builder = new SuggestionsBuilder("wp remove ", "wp remove ".length());

        assertEquals(3, WaypointerCommandSuggestions.suggestWaypointLabels(route, builder));
        assertEquals(List.of("1", "1.1", "2"), builder.build().getList().stream()
                .map(com.mojang.brigadier.suggestion.Suggestion::getText).toList());
    }

    @Test
    void helpOutputHasBlankLinesBeforeAndAfterEveryPage() {
        List<Component> feedback = new ArrayList<>();
        FabricClientCommandSource source = (FabricClientCommandSource) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{FabricClientCommandSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sendFeedback")) {
                        feedback.add((Component) args[0]);
                        return null;
                    }
                    throw new AssertionError("Unexpected command source call: " + method.getName());
                });
        for (String target : List.of("sharing", "all")) {
            feedback.clear();

            assertEquals(1, WaypointerCommandHelp.run(source, "wp", target));
            assertEquals("", feedback.getFirst().getString());
            assertEquals("", feedback.getLast().getString());
        }
    }

    @Test
    void importRetargetingUsesCommandLayerRuleWithoutOverwritingExplicitZones() throws Exception {
        WaypointGroup firstUnknown = WaypointGroup.create("SkyHanni A", Zone.UNKNOWN.id());
        WaypointGroup explicitZone = WaypointGroup.create("Waypointer Native", "dungeon_f7");
        WaypointGroup secondUnknown = WaypointGroup.create("SkyHanni B", Zone.UNKNOWN.id());

        int retargeted = invokeRetargetUnknownGroups(
                List.of(firstUnknown, explicitZone, secondUnknown),
                new Zone("hub", "Hub"));

        assertTrue(retargeted == 2, "two UNKNOWN-zone groups should be retargeted");
        assertTrue("hub".equals(firstUnknown.zoneId()), "first unknown group should move to current zone");
        assertTrue("hub".equals(secondUnknown.zoneId()), "second unknown group should move to current zone");
        assertTrue("dungeon_f7".equals(explicitZone.zoneId()), "explicit source zone should be preserved");
        assertTrue(invokeRetargetUnknownGroups(
                List.of(WaypointGroup.create("No Target", Zone.UNKNOWN.id())), null) == 0);
        assertTrue(invokeRetargetUnknownGroups(
                List.of(WaypointGroup.create("Unknown Target", Zone.UNKNOWN.id())), Zone.UNKNOWN) == 0);
    }

    @Test
    void importRetargetingAssignsUnknownMineshaftOnlyToCurrentLayout() throws Exception {
        WaypointGroup firstInside = WaypointGroup.create("Inside A", "mineshaft_unknown");
        WaypointGroup secondInside = WaypointGroup.create("Inside B", "mineshaft");
        WaypointGroup genericUnknown = WaypointGroup.create("No zone", Zone.UNKNOWN.id());
        WaypointGroup explicitHub = WaypointGroup.create("Hub", "hub");
        WaypointGroup otherLayout = WaypointGroup.create("Other layout", "mineshaft_topaz_1");
        Zone layout = Zone.fromId("mineshaft_ruby_1");

        assertEquals(3, invokeRetargetUnknownGroups(List.of(firstInside, secondInside,
                genericUnknown, explicitHub, otherLayout), layout));
        assertEquals("mineshaft_ruby_1", firstInside.zoneId());
        assertEquals("mineshaft_ruby_1", secondInside.zoneId());
        assertEquals("mineshaft_ruby_1", genericUnknown.zoneId());
        assertEquals("hub", explicitHub.zoneId());
        assertEquals("mineshaft_topaz_1", otherLayout.zoneId());

        Component feedback = WaypointerCommands.importSuccessMessage(
                3, "clipboard", "SKYBLOCKER", 3, layout, "");
        assertTrue(feedback.toString().contains("Mineshaft: Ruby 1"));

        for (Zone outside : List.of(Zone.fromId("hub"), Zone.fromId("dwarven_mines"),
                Zone.fromId("mineshaft_unknown"))) {
            WaypointGroup route = WaypointGroup.create("Outside", "mineshaft_unknown");
            assertEquals(0, invokeRetargetUnknownGroups(List.of(route), outside));
            assertEquals("mineshaft_unknown", route.zoneId());
        }
    }

    private static int invokeRetargetUnknownGroups(List<WaypointGroup> groups, Zone target) throws Exception {
        Method retarget = WaypointerCommands.class.getDeclaredMethod(
                "retargetUnknownGroups", List.class, Zone.class);
        retarget.setAccessible(true);
        return (Integer) retarget.invoke(null, groups, target);
    }

    private static WaypointCodec.Options invokeExportOptionsFromConfig(WaypointerConfig config) throws Exception {
        WaypointerCommands commands = new WaypointerCommands(
                new ActiveGroupManager(),
                null,
                config,
                new ChatImportCache(),
                WaypointerCommandTreeTest::doNothingOpenGui);
        Method exportOptions = WaypointerCommands.class.getDeclaredMethod("exportOptionsFromConfig");
        exportOptions.setAccessible(true);
        return (WaypointCodec.Options) exportOptions.invoke(commands);
    }

    private static CommandDispatcher<FabricClientCommandSource> registeredDispatcher() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        WaypointerCommands commands = new WaypointerCommands(
                new ActiveGroupManager(),
                null,
                new WaypointerConfig(),
                new ChatImportCache(),
                WaypointerCommandTreeTest::doNothingOpenGui);
        Method register = WaypointerCommands.class.getDeclaredMethod(
                "register", CommandDispatcher.class, String.class);
        register.setAccessible(true);
        @SuppressWarnings("unchecked")
        LiteralCommandNode<FabricClientCommandSource> root =
                (LiteralCommandNode<FabricClientCommandSource>) register.invoke(
                        commands, dispatcher, "wp");
        commands.registerAlias(dispatcher, "wptr", root);
        commands.registerAlias(dispatcher, "waypointer", root);
        return dispatcher;
    }

    private static void doNothingOpenGui() {
    }
}
