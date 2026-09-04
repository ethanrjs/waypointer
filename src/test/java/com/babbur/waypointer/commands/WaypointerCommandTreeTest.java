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
    void registerAddsHighValueBranchesAndRedirectsAliases() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher();
        List<String> branches = List.of(
                "gui",
                "help",
                "list",
                "routes",
                "skip",
                "unskip",
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
                "editmode",
                "edit",
                "importchat",
                "waypoint",
                "area",
                "group");

        CommandNode<FabricClientCommandSource> rootNode = dispatcher.getRoot().getChild("wp");
        assertNotNull(rootNode, "missing canonical command root wp");
        for (String branch : branches) {
            assertNotNull(rootNode.getChild(branch), "missing /wp " + branch + " command branch");
        }
        for (String alias : List.of("waypointer", "wptr")) {
            CommandNode<FabricClientCommandSource> aliasNode = dispatcher.getRoot().getChild(alias);
            assertNotNull(aliasNode, "missing command alias " + alias);
            assertSame(rootNode, aliasNode.getRedirect(), "/" + alias + " should redirect to /wp");
            assertNotNull(aliasNode.getCommand(), "/" + alias + " should retain the root action");
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
                Map.entry("wptr help", "short alias redirect"),
                Map.entry("waypointer list", "long alias redirect"),
                Map.entry("wp routes", "in-game public route catalog"),
                Map.entry("wp skip", "advance active routes by one waypoint"),
                Map.entry("wp unskip", "move routes back one waypoint"),
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
                Map.entry("wp move 1 1", "active route reorder command"),
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
                Map.entry("wp export bare", "coordinate-only route export"),
                Map.entry("wp import WP:abc", "inline import payload"),
                Map.entry("wp importfile C:\\routes\\waypoints.json", "file import payload"),
                Map.entry("wp importchat A1b2", "cached chat import"),
                Map.entry("wp importchat config A1b2", "typed cached config import"),
                Map.entry("wp importchat dungeon A1b2", "typed cached dungeon import"),
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
                Map.entry("wp export routes", "explicit route export command"),
                Map.entry("wp export config", "config export command"),
                Map.entry("wp export dungeon", "dungeon export command"),
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
                "wp waypoint radius 1 100.1",
                "wp group radius 0 100.1")) {
            ParseResults<FabricClientCommandSource> parsed = dispatcher.parse(command, null);
            assertFalse(parsed.getExceptions().isEmpty(), command + " should reject unsafe reach radii");
        }
    }

    @Test
    void cliExportGroupsUseOnlyTheFirstActiveRoute() {
        ActiveGroupManager manager = new ActiveGroupManager();
        WaypointGroup hub = WaypointGroup.create("Hub", "hub");
        hub.add(Waypoint.at(1, 2, 3));
        WaypointGroup dungeon = WaypointGroup.create("Dungeon", "dungeon_f7");
        manager.add(hub);
        manager.add(dungeon);

        assertTrue(WaypointerCommands.cliExportGroups(manager).isEmpty());

        manager.onZoneChanged(new Zone("hub", "Hub"));

        assertEquals(List.of(hub), WaypointerCommands.cliExportGroups(manager));
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

        assertEquals("Waypoint 2 (#1.1) 1, 0, 0",
                WaypointerCommands.activeGroupIndexTooltip(group, 1));
    }

    @Test
    void waypointNumbersConvertToZeroBasedStorageOnlyAtTheCommandBoundary() {
        assertEquals(0, WaypointerCommands.waypointIndexFromNumber(1));
        assertEquals(49, WaypointerCommands.waypointIndexFromNumber(50));
    }

    @Test
    void waypointCommandsRejectZeroButRouteIndicesRemainZeroBased() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher();

        for (String command : List.of(
                "wp remove 0",
                "wp insert 0",
                "wp move 0 1",
                "wp waypoint move 0 here",
                "wp waypoint rename 0 name",
                "wp sub 0")) {
            assertFalse(dispatcher.parse(command, null).getExceptions().isEmpty(), command);
        }
        assertTrue(dispatcher.parse("wp group rename 0 route", null).getExceptions().isEmpty());
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
    void helpTargetsResolveCommandAliasesToUsefulPages() {
        assertEquals(0, WaypointerCommandHelp.resolvePage(null), "blank help should open basics");
        assertEquals(0, WaypointerCommandHelp.resolvePage("gui"), "gui command should resolve to basics");
        assertEquals(0, WaypointerCommandHelp.resolvePage("list"), "list command should resolve to basics");
        assertEquals(1, WaypointerCommandHelp.resolvePage("add"), "add command should resolve to route editing");
        assertEquals(1, WaypointerCommandHelp.resolvePage("edit"), "nested edit alias should resolve to route editing");
        assertEquals(1, WaypointerCommandHelp.resolvePage("editmode"), "editmode command should resolve to route editing");
        assertEquals(1, WaypointerCommandHelp.resolvePage("insert"), "insert command should resolve to route editing");
        assertEquals(2, WaypointerCommandHelp.resolvePage("sub"), "short sub command should resolve to subwaypoint help");
        assertEquals(2, WaypointerCommandHelp.resolvePage("tiny"), "tiny command should resolve to subwaypoint help");
        assertEquals(2, WaypointerCommandHelp.resolvePage("filled"), "filled command should resolve to subwaypoint help");
        assertEquals(2, WaypointerCommandHelp.resolvePage("hap"), "hap command should resolve to subwaypoint help");
        assertEquals(2, WaypointerCommandHelp.resolvePage("sts"), "sts command should resolve to subwaypoint help");
        assertEquals(2, WaypointerCommandHelp.resolvePage("its"), "its command should resolve to subwaypoint help");
        assertEquals(2, WaypointerCommandHelp.resolvePage("los"), "los command should resolve to subwaypoint help");
        assertEquals(3, WaypointerCommandHelp.resolvePage("waypoint"), "waypoint command should resolve to details");
        assertEquals(4, WaypointerCommandHelp.resolvePage("group"), "group command should resolve to groups");
        assertEquals(4, WaypointerCommandHelp.resolvePage("area"), "area command should resolve to groups");
        assertEquals(5, WaypointerCommandHelp.resolvePage("import"), "import command should resolve to sharing");
        assertEquals(5, WaypointerCommandHelp.resolvePage("export"), "export command should resolve to sharing");
        assertEquals(5, WaypointerCommandHelp.resolvePage("importfile"), "importfile command should resolve to sharing");
        assertEquals(5, WaypointerCommandHelp.resolvePage("importchat"), "importchat command should resolve to sharing");
        assertEquals(6, WaypointerCommandHelp.resolvePage("chat"), "chat section should resolve directly");
        assertEquals(6, WaypointerCommandHelp.resolvePage("addtemp"), "addtemp command should resolve to chat/temp help");
        assertEquals(6, WaypointerCommandHelp.resolvePage("chattemp"), "chattemp command should resolve to chat/temp help");
        assertEquals(6, WaypointerCommandHelp.resolvePage("blacklist"), "blacklist command should resolve to chat/temp help");
        assertEquals(7, WaypointerCommandHelp.resolvePage("debug"), "debug command should resolve to debug");
        assertEquals(8, WaypointerCommandHelp.resolvePage("crystal"), "crystal should resolve directly");
        assertEquals(8, WaypointerCommandHelp.resolvePage("wpch"), "wpch should resolve to crystal help");
        assertEquals(-1, WaypointerCommandHelp.resolvePage("all"), "all is handled before page resolution");
        assertEquals(-1, WaypointerCommandHelp.resolvePage("missing"), "unknown target should still be rejected");
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
