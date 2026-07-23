package com.babbur.waypointer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
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
import com.babbur.waypointer.dungeon.DungeonSecretCategory;
import com.babbur.waypointer.dungeon.DungeonStateTracker;
import com.babbur.waypointer.dungeon.DungeonWaypoint;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomDefinition;
import com.babbur.waypointer.input.WaypointAddFlow;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointerCommandTreeTest {

    @Test
    void registerAddsHighValueBranchesToEveryRootAlias() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher();
        List<String> roots = List.of("waypointer", "wptr", "wp");
        List<String> branches = List.of(
                "gui",
                "help",
                "list",
                "skip",
                "skipto",
                "sub",
                "tiny",
                "filled",
                "hap",
                "sts",
                "its",
                "los",
                "reset",
                "mode",
                "radius",
                "move",
                "add",
                "addtemp",
                "chattemp",
                "blacklist",
                "remove",
                "insert",
                "clear",
                "export",
                "import",
                "importfile",
                "debug",
                "devmode",
                "editmode",
                "edit",
                "importchat",
                "waypoint",
                "area",
                "group");

        for (String root : roots) {
            CommandNode<FabricClientCommandSource> rootNode = dispatcher.getRoot().getChild(root);
            assertNotNull(rootNode, "missing command root " + root);
            for (String branch : branches) {
                assertNotNull(rootNode.getChild(branch),
                        "missing /" + root + " " + branch + " command branch");
            }
        }
    }

    @Test
    void criticalWorkflowCommandsParseWithoutSyntaxErrors() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher();
        Map<String, String> commands = Map.ofEntries(
                Map.entry("wp help import", "help section lookup"),
                Map.entry("wp help chattemp", "chat-temp help lookup"),
                Map.entry("wp help all", "all-command help lookup"),
                Map.entry("wp help sub", "short subwaypoint help lookup"),
                Map.entry("wp help debug", "debug help lookup"),
                Map.entry("wp help devmode", "developer mode help lookup"),
                Map.entry("wp skip", "advance active routes by one waypoint"),
                Map.entry("wp devmode", "developer mode toggle"),
                Map.entry("wp devmode on", "developer mode enable"),
                Map.entry("wp devmode off", "developer mode disable"),
                Map.entry("wp devmode status", "developer mode status"),
                Map.entry("wp devmode report", "developer mode report"),
                Map.entry("wp add at 10 64 -20 Secret Lever", "coordinate route insertion"),
                Map.entry("wp insert 1 at 10 64 -20 Secret Lever", "coordinate route insertion at slot"),
                Map.entry("wp sub 1", "subwaypoint toggle by index"),
                Map.entry("wp tiny 1", "tiny subwaypoint toggle"),
                Map.entry("wp filled 1", "filled subwaypoint toggle"),
                Map.entry("wp hap 1", "hide-after-parent subwaypoint toggle"),
                Map.entry("wp sts 1", "stand-to-skip waypoint toggle"),
                Map.entry("wp its 1", "interact-to-skip waypoint toggle"),
                Map.entry("wp los 1", "line-of-sight waypoint toggle"),
                Map.entry("wp reset", "active route progress reset"),
                Map.entry("wp mode static", "active route mode command"),
                Map.entry("wp radius 4.5", "active route radius command"),
                Map.entry("wp move 1 0", "active route reorder command"),
                Map.entry("wp waypoint move 1 here", "move waypoint to player"),
                Map.entry("wp waypoint move 1 at 10 64 -20", "move waypoint to coordinates"),
                Map.entry("wp waypoint rename 1 Secret Lever", "rename waypoint"),
                Map.entry("wp waypoint color 1 58C878", "waypoint color command"),
                Map.entry("wp waypoint radius 1 2.5", "waypoint radius command"),
                Map.entry("wp waypoint sub 1", "long subwaypoint toggle command"),
                Map.entry("wp addtemp at -5 70 9 Party Chat", "temporary coordinate waypoint"),
                Map.entry("wp chattemp 1 2 3 Babbur party", "chat coordinate click command"),
                Map.entry("wp blacklist add Babbur", "chat coordinate blacklist mutation"),
                Map.entry("wp clear confirm", "confirmed clear command"),
                Map.entry("wp export names", "named route export"),
                Map.entry("wp export nonames", "anonymous route export"),
                Map.entry("wp import WP:abc", "inline import payload"),
                Map.entry("wp importfile C:\\routes\\waypoints.json", "file import payload"),
                Map.entry("wp importchat A1b2", "cached chat import"),
                Map.entry("wp edit mode", "edit mode nested alias"),
                Map.entry("wp area 0 current", "short group area attachment"),
                Map.entry("wp group create Dungeon Route", "group creation"),
                Map.entry("wp group rename 0 Dungeon Route", "group rename"),
                Map.entry("wp group zone 0 current", "group current-zone attachment"),
                Map.entry("wp group zone 0 the_park", "group explicit-zone attachment"),
                Map.entry("wp group area 0 current", "group area alias"),
                Map.entry("wp group mode 0 sequence", "group mode command"),
                Map.entry("wp group radius 0 3.5", "group radius command"),
                Map.entry("wp group skipahead 0 off", "group skip-ahead command"),
                Map.entry("wp group enable 0", "group enable command"),
                Map.entry("wp group disable 0", "group disable command"),
                Map.entry("wp group colormode 0 gradient", "group color-mode command"),
                Map.entry("wp group color 0 4FE05A", "group static-color command"),
                Map.entry("wp group gradient 0 00BFFF FF3040", "group gradient command"),
                Map.entry("wp group delete 0", "group deletion warning"),
                Map.entry("wp group delete 0 confirm", "confirmed group deletion"));

        for (Map.Entry<String, String> command : commands.entrySet()) {
            ParseResults<FabricClientCommandSource> parsed = dispatcher.parse(command.getKey(), null);
            assertTrue(parsed.getExceptions().isEmpty(),
                    command.getValue() + " should parse without syntax errors: " + parsed.getExceptions());
            assertTrue(!parsed.getContext().getNodes().isEmpty(),
                    command.getValue() + " should produce parsed command nodes");
        }
    }

    @Test
    void reachRadiusCommandsRejectValuesAboveTheCanonicalMaximum() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher();

        for (String command : List.of(
                "wp radius 100.1",
                "wp waypoint radius 0 100.1",
                "wp group radius 0 100.1")) {
            ParseResults<FabricClientCommandSource> parsed = dispatcher.parse(command, null);
            assertFalse(parsed.getExceptions().isEmpty(), command + " should reject unsafe reach radii");
        }
    }

    @Test
    void cliExportGroupsUseCurrentZoneOrAllGroupsWhenNoZoneIsKnown() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup hub = WaypointGroup.create("Hub", "hub");
        WaypointGroup dungeon = WaypointGroup.create("Dungeon", "dungeon_f7");
        manager.add(hub);
        manager.add(dungeon);

        assertEquals(List.of(hub, dungeon), WaypointerCommands.cliExportGroups(manager));

        manager.onZoneChanged(new Zone("hub", "Hub"));

        assertEquals(List.of(hub), WaypointerCommands.cliExportGroups(manager));
    }

    @Test
    void cliExportDefaultOptionsMirrorConfigDefaultsWithoutImplicitLabel() throws Exception {
        WaypointCodec.Options options = invokeExportOptionsFromConfig(new WaypointerConfig());

        assertTrue(options.includeNames);
        assertTrue(options.includeColors);
        assertTrue(options.includeRadii);
        assertTrue(options.includeWaypointFlags);
        assertTrue(options.includeGroupMeta);
        assertEquals("", options.label);
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
        DungeonRoomData.clearAllCustom();
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
            DungeonRoomData.defineRoom("command-room", "Command Room", room);
            trackerField.set(null, tracker);
            setCurrentRoom.invoke(tracker, room);
            manager.onZoneChanged(new Zone("command-room", "Command Room"));

            int index = WaypointerCommands.addPersistentWaypointAt(manager, config,
                    new WaypointAddFlow(), -95, 68, -121, "beam");

            Waypoint waypoint = manager.groupsForZone("command-room").get(0).get(index);
            assertEquals(21, waypoint.x());
            assertEquals(68, waypoint.y());
            assertEquals(-17, waypoint.z());
            assertEquals("beam", waypoint.name());
        } finally {
            trackerField.set(null, previousTracker);
            DungeonRoomData.clearAllCustom();
        }
    }

    @Test
    void addPersistentWaypointRefusesDefinitionOnlyDungeonSecrets() {
        DungeonRoomData.clearAllCustom();
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointerConfig config = new WaypointerConfig();
        DungeonRoom room = new DungeonRoom(
                DungeonRoomType.PUZZLE,
                DungeonRoomShape.ONE_BY_ONE,
                Direction.NW,
                -74,
                -138,
                List.of(DungeonRoom.packSegment(-104, -168)),
                "downloaded-room",
                "Downloaded Room",
                DungeonDetectionConfidence.CORE_CONFIRMED);

        try {
            DungeonRoomDefinition definition = DungeonRoomData.defineRoom(
                    "downloaded-room", "Downloaded Room", room);
            DungeonRoomData.addWaypoint(definition.id(), DungeonWaypoint.plain(
                    "secret", DungeonSecretCategory.CHEST, 4, 70, 7, ""));
            manager.onZoneChanged(new Zone("downloaded-room", "Downloaded Room"));

            assertTrue(WaypointerCommands.definitionOnlyRouteRequiresConversion(manager));
            assertEquals(-1, WaypointerCommands.addPersistentWaypointAt(
                    manager, config, new WaypointAddFlow(), 10, 64, -20, "blocked"));
            assertTrue(manager.allGroups().isEmpty());
        } finally {
            DungeonRoomData.clearAllCustom();
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
    void removeWaypointAtDeletesZeroBasedIndexOnly() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(Waypoint.at(0, 64, 0));
        group.add(new Waypoint(1, 65, 1, "Remove me", Waypoint.DEFAULT_COLOR, 0, 0.0));
        group.add(Waypoint.at(2, 66, 2));

        assertNull(WaypointerCommands.removeWaypointAt(group, -1));
        assertNull(WaypointerCommands.removeWaypointAt(group, 3));
        assertEquals(3, group.size());

        Waypoint removed = WaypointerCommands.removeWaypointAt(group, 1);

        assertNotNull(removed);
        assertEquals("Remove me", removed.name());
        assertEquals(2, group.size());
        assertEquals(0, group.get(0).x());
        assertEquals(2, group.get(1).x());
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
    void skipToMovesEveryActiveRouteWithMatchingDisplayedTarget() {
        WaypointGroup first = WaypointGroup.create("First", "hub");
        first.add(Waypoint.at(0, 0, 0));
        first.add(Waypoint.at(1, 0, 0));
        first.add(Waypoint.at(2, 0, 0));
        WaypointGroup second = WaypointGroup.create("Second", "hub");
        second.add(Waypoint.at(10, 0, 0));
        second.add(Waypoint.at(11, 0, 0));
        second.add(Waypoint.at(12, 0, 0));
        WaypointGroup tooShort = WaypointGroup.create("Too Short", "hub");
        tooShort.add(Waypoint.at(20, 0, 0));

        WaypointerCommands.SkipToOutcome outcome = WaypointerCommands.skipActiveGroupsToTarget(
                List.of(first, second, tooShort), "3");

        assertTrue(outcome.moved() == 2, "two active routes should move to label 3");
        assertTrue(outcome.error() != null, "the non-matching active route should leave diagnostic context");
        assertTrue("3".equals(outcome.firstMovedLabel()), "success label should stay user-facing");
        assertTrue(first.currentIndex() == 2, "first route should jump to displayed label 3");
        assertTrue(second.currentIndex() == 2, "second route should jump to displayed label 3");
        assertTrue(tooShort.currentIndex() == 0, "route without label 3 should not move");
    }

    @Test
    void skipToReportsErrorWhenNoActiveRouteHasTarget() {
        WaypointGroup first = WaypointGroup.create("First", "hub");
        first.add(Waypoint.at(0, 0, 0));
        WaypointGroup empty = WaypointGroup.create("Empty", "hub");

        WaypointerCommands.SkipToOutcome outcome = WaypointerCommands.skipActiveGroupsToTarget(
                List.of(first, empty), "2");

        assertTrue(outcome.moved() == 0, "no route has displayed label 2");
        assertTrue(outcome.error() != null && outcome.error().contains("out of range"));
        assertTrue(first.currentIndex() == 0, "route should remain unchanged on no-match");
    }

    @Test
    void skipTargetSuggestionsIncludeLabelsFromLaterActiveGroups() {
        WaypointGroup first = WaypointGroup.create("First", "hub");
        first.add(Waypoint.at(0, 0, 0));
        first.add(Waypoint.at(1, 0, 0));
        WaypointGroup later = WaypointGroup.create("Later", "hub");
        later.add(Waypoint.at(10, 0, 0));
        later.add(Waypoint.at(11, 0, 0));
        later.add(Waypoint.at(12, 0, 0));
        SuggestionsBuilder builder = new SuggestionsBuilder("wp skipto ", "wp skipto ".length());

        int suggested = WaypointerCommands.suggestSkipTargets(List.of(first, later), builder);

        assertTrue(suggested == 3, "two labels come from both routes and label 3 comes from the later route");
        assertTrue(builder.build().getList().stream().anyMatch(s -> "3".equals(s.getText())),
                "later active route label should autocomplete: " + builder.build().getList());
    }

    @Test
    void activeGroupIndexTooltipShowsSubwaypointLabelWithSingleHash() {
        WaypointGroup group = WaypointGroup.create("Route", "hub");
        group.add(Waypoint.at(0, 0, 0));
        group.add(Waypoint.at(1, 0, 0));
        group.toggleSubwaypoint(1);

        assertEquals("index 1 (#1.1) 1, 0, 0",
                WaypointerCommands.activeGroupIndexTooltip(group, 1));
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
    void helpTargetsResolveCommandAliasesToUsefulPages() throws Exception {
        assertEquals(0, invokeResolveHelpPage(null), "blank help should open basics");
        assertEquals(0, invokeResolveHelpPage("gui"), "gui command should resolve to basics");
        assertEquals(0, invokeResolveHelpPage("list"), "list command should resolve to basics");
        assertEquals(1, invokeResolveHelpPage("add"), "add command should resolve to route editing");
        assertEquals(1, invokeResolveHelpPage("edit"), "nested edit alias should resolve to route editing");
        assertEquals(1, invokeResolveHelpPage("editmode"), "editmode command should resolve to route editing");
        assertEquals(1, invokeResolveHelpPage("insert"), "insert command should resolve to route editing");
        assertEquals(2, invokeResolveHelpPage("sub"), "short sub command should resolve to subwaypoint help");
        assertEquals(2, invokeResolveHelpPage("tiny"), "tiny command should resolve to subwaypoint help");
        assertEquals(2, invokeResolveHelpPage("filled"), "filled command should resolve to subwaypoint help");
        assertEquals(2, invokeResolveHelpPage("hap"), "hap command should resolve to subwaypoint help");
        assertEquals(2, invokeResolveHelpPage("sts"), "sts command should resolve to subwaypoint help");
        assertEquals(2, invokeResolveHelpPage("its"), "its command should resolve to subwaypoint help");
        assertEquals(2, invokeResolveHelpPage("los"), "los command should resolve to subwaypoint help");
        assertEquals(3, invokeResolveHelpPage("waypoint"), "waypoint command should resolve to details");
        assertEquals(4, invokeResolveHelpPage("group"), "group command should resolve to groups");
        assertEquals(4, invokeResolveHelpPage("area"), "area command should resolve to groups");
        assertEquals(5, invokeResolveHelpPage("import"), "import command should resolve to sharing");
        assertEquals(5, invokeResolveHelpPage("export"), "export command should resolve to sharing");
        assertEquals(5, invokeResolveHelpPage("importfile"), "importfile command should resolve to sharing");
        assertEquals(5, invokeResolveHelpPage("importchat"), "importchat command should resolve to sharing");
        assertEquals(6, invokeResolveHelpPage("chat"), "chat section should resolve directly");
        assertEquals(6, invokeResolveHelpPage("addtemp"), "addtemp command should resolve to chat/temp help");
        assertEquals(6, invokeResolveHelpPage("chattemp"), "chattemp command should resolve to chat/temp help");
        assertEquals(6, invokeResolveHelpPage("blacklist"), "blacklist command should resolve to chat/temp help");
        assertEquals(7, invokeResolveHelpPage("debug"), "debug command should resolve to debug");
        assertEquals(-1, invokeResolveHelpPage("all"), "all is handled by runHelp, not page resolution");
        assertEquals(-1, invokeResolveHelpPage("missing"), "unknown target should still be rejected");
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

    private static int invokeRetargetUnknownGroups(List<WaypointGroup> groups, Zone target) throws Exception {
        Method retarget = WaypointerCommands.class.getDeclaredMethod(
                "retargetUnknownGroups", List.class, Zone.class);
        retarget.setAccessible(true);
        return (Integer) retarget.invoke(null, groups, target);
    }

    private static int invokeResolveHelpPage(String target) throws Exception {
        Method resolve = WaypointerCommands.class.getDeclaredMethod("resolveHelpPage", String.class);
        resolve.setAccessible(true);
        return (Integer) resolve.invoke(null, new Object[]{target});
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
        for (String root : List.of("waypointer", "wptr", "wp")) {
            register.invoke(commands, dispatcher, root);
        }
        return dispatcher;
    }

    private static void doNothingOpenGui() {
    }
}
