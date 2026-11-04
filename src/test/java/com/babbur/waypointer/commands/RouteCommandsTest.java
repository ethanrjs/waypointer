package com.babbur.waypointer.commands;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteCommandsTest {

    private final ActiveGroupManager manager = new ActiveGroupManager();
    private final WaypointerConfig config = new WaypointerConfig();
    private final Messages messages = new Messages();
    private final WaypointerRouteCommands routes = new WaypointerRouteCommands(manager, config, null);
    private final WaypointerWaypointCommands waypoints =
            new WaypointerWaypointCommands(manager, config, null, routes);
    private final RouteTargets targets = new RouteTargets(manager);

    private WaypointGroup route(String name, int points) {
        WaypointGroup route = WaypointGroup.create(name, "hub");
        for (int i = 0; i < points; i++) route.add(Waypoint.at(i * 10, 70, i));
        manager.add(route);
        return route;
    }

    @Test
    void targetIsThePinnedRouteThenTheLastEditedThenTheFirstShown() {
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup first = route("First", 2);
        WaypointGroup second = route("Second", 2);
        WaypointGroup third = route("Third", 2);

        assertSame(first, targets.target().route());
        assertEquals(RouteTargets.Reason.FIRST, targets.target().reason());

        manager.noteRouteEdited(second);
        assertSame(second, targets.target().route());
        assertEquals(RouteTargets.Reason.EDITED, targets.target().reason());

        manager.setRecordingTarget(third);
        assertSame(third, targets.target().route());
        assertEquals(RouteTargets.Reason.PINNED, targets.target().reason());

        third.setEnabled(false);
        assertSame(second, manager.targetRoute(), "a hidden pin falls back to the last edited route");
    }

    @Test
    void routesAreNamedByListNumberExactNameOrUniquePrefix() {
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup ruby = route("Ruby route", 1);
        WaypointGroup rubyShort = route("Ruby", 1);
        WaypointGroup topaz = route("Topaz shortcut", 1);
        topaz.setEnabled(false);
        route("Elsewhere", 1).setZoneId("garden");

        assertEquals(List.of(ruby, rubyShort, topaz), targets.listed(), "hidden routes are listed too");
        assertSame(rubyShort, targets.route("2").value());
        assertSame(rubyShort, targets.route("ruby").value(), "an exact name beats a prefix");
        assertSame(topaz, targets.route("\"Topaz\"").value());
        assertFalse(targets.route("Ru").ok(), "two routes start with Ru");
        assertFalse(targets.route("4").ok());
        assertFalse(targets.route("Elsewhere").ok(), "routes in other zones are not listed");
    }

    @Test
    void waypointsAreNamedByTheLabelsTheEditorShows() {
        WaypointGroup route = WaypointGroup.create("Route", "hub");
        for (int i = 0; i < 4; i++) route.add(Waypoint.at(i, 0, 0));
        route.toggleSubwaypoint(2);

        assertEquals(0, RouteTargets.waypoint(route, "1").value());
        assertEquals(1, RouteTargets.waypoint(route, "#2").value());
        assertEquals(2, RouteTargets.waypoint(route, "2.1").value());
        assertEquals(3, RouteTargets.waypoint(route, "3").value(), "#3 is the fourth stored waypoint");
        assertEquals("2.1", RouteTargets.label(route, 2));
        for (String bad : List.of("0", "4", "2.2", "1.1", "x", "", "2.", "1.2.3")) {
            assertFalse(RouteTargets.waypoint(route, bad).ok(), bad);
        }
    }

    @Test
    void routeAndWaypointCommandsChangeOnlyTheTargetRouteAndNameIt() {
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup first = route("First", 3);
        WaypointGroup second = route("Second", 3);

        assertEquals(1, routes.runTarget(messages.source(), "2"));
        assertEquals(1, waypoints.runRemove(messages.source(), "2"));
        assertEquals(1, routes.runSetMode(messages.source(), "all"));
        assertEquals(1, routes.runSetRadius(messages.source(), 4.5));
        assertEquals(1, waypoints.runRename(messages.source(), "1", "Start"));

        assertEquals(3, first.size(), "the first shown route is untouched");
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, first.loadMode());
        assertEquals(2, second.size());
        assertEquals(WaypointGroup.LoadMode.STATIC, second.loadMode());
        assertEquals(4.5, second.defaultRadius());
        assertEquals("Start", second.get(0).name());
        for (Component reply : messages.feedback) {
            assertTrue(reply.getString().startsWith("Second"), "every reply names the route: " + reply.getString());
        }
        assertTrue(messages.errors.isEmpty());
    }

    @Test
    void subwaypointLabelsAddressTheRightWaypoint() {
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup route = route("Route", 4);
        route.toggleSubwaypoint(2);
        int subX = route.get(2).x();
        int thirdX = route.get(3).x();

        assertEquals(1, waypoints.runRemove(messages.source(), "2.1"));
        assertEquals(3, route.size());
        assertTrue(route.waypoints().stream().noneMatch(w -> w.x() == subX));
        assertEquals(thirdX, route.get(2).x(), "removing 2.1 must not touch #3");
    }

    @Test
    void flagsFollowTheEditorsRules() {
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup route = route("Route", 3);
        route.toggleSubwaypoint(1);

        assertEquals(0, waypoints.runSetFlag(messages.source(), "1", "tiny", "on"), "tiny is for subwaypoints");
        assertEquals(1, waypoints.runSetFlag(messages.source(), "1.1", "tiny", "on"));
        assertTrue(route.get(1).hasFlag(Waypoint.FLAG_SMALL_SUBWAYPOINT));
        assertEquals(0, waypoints.runSetFlag(messages.source(), "1", "stand-to-skip", "on"),
                "skip rules only exist in dungeon rooms");
        assertEquals(1, waypoints.runSetFlag(messages.source(), "2", "los", "toggle"));
        assertTrue(route.get(2).hasFlag(Waypoint.FLAG_DEPTH_CHECKED));
        assertEquals(0, waypoints.runSetFlag(messages.source(), "2", "sparkly", "on"));
        assertEquals(3, messages.errors.size());
    }

    @Test
    void hidingTheTargetMovesTheTargetAndShowAcceptsAName() {
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup first = route("First", 1);
        WaypointGroup second = route("Second", 1);

        assertEquals(1, routes.runSetShown(messages.source(), null, false));
        assertFalse(first.enabled());
        assertSame(second, manager.targetRoute());
        assertEquals(1, routes.runSetShown(messages.source(), "First", true));
        assertTrue(first.enabled());
        assertEquals(2, routes.runSetAllShown(messages.source(), false));
        assertNull(manager.targetRoute());
        assertEquals(0, routes.runRename(messages.source(), "Nope"), "no shown route, no target");
    }

    @Test
    void newRoutesBecomeTheTargetAndKeepAnExistingPin() {
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup pinned = route("Pinned", 1);
        manager.setRecordingTarget(pinned);

        assertEquals(1, routes.runCreate(messages.source(), "Fresh"));
        WaypointGroup fresh = targets.route("Fresh").value();
        assertSame(fresh, manager.recordingTarget(), "a pin moves to the new route");
        assertSame(fresh, manager.targetRoute());
        assertEquals(0, routes.runCreate(messages.source(), "  "));
    }

    @Test
    void foldersCanBeMadeFilledRenamedAndRemoved() {
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup route = route("Route", 1);

        assertEquals(1, routes.runCreateFolder(messages.source(), "Gem stuff"));
        assertEquals(1, routes.runSetFolder(messages.source(), "gem"));
        assertEquals("Gem stuff", manager.folderForGroup(route.id()).name());
        assertEquals(1, routes.runRenameFolder(messages.source(), "\"Gem stuff\"", "Gemstones"));
        assertEquals("Gemstones", manager.folderForGroup(route.id()).name());
        assertEquals(1, routes.runSetFolder(messages.source(), null));
        assertNull(manager.folderForGroup(route.id()));
        assertEquals(1, routes.runDeleteFolder(messages.source(), "Gemstones"));
        assertTrue(manager.foldersForZone("hub").isEmpty());
    }

    @Test
    void colorsProgressAndOrderMatchTheEditor() {
        manager.onZoneChanged(new Zone("hub", "Hub"));
        WaypointGroup route = route("Route", 4);

        assertEquals(1, waypoints.runSetColor(messages.source(), "2", "#123456"));
        assertTrue(route.get(1).hasFlag(Waypoint.FLAG_LOCKED_COLOR));
        assertEquals(1, waypoints.runResetColor(messages.source(), "2"));
        assertFalse(route.get(1).hasFlag(Waypoint.FLAG_LOCKED_COLOR));

        assertEquals(1, waypoints.runSetCurrent(messages.source(), "3"));
        assertEquals(2, route.currentIndex());
        assertEquals(1, waypoints.runReset(messages.source()));
        assertEquals(0, route.currentIndex());

        int lastX = route.get(3).x();
        assertEquals(1, waypoints.runStep(messages.source(), "4", -1));
        assertEquals(lastX, route.get(2).x());
        assertEquals(0, waypoints.runStep(messages.source(), "1", -1), "the first waypoint cannot move up");

        assertEquals(1, routes.runSetColorMode(messages.source(), "paint"));
        assertTrue(route.paintEnabled());
        assertEquals(1, routes.runSetColor(messages.source(), "4FE05A"));
        assertFalse(route.paintEnabled());
        assertEquals(WaypointGroup.GradientMode.STATIC, route.gradientMode());
    }

    @Test
    void parsersAcceptTheEditorWordsAndOldSpellings() {
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, WaypointerRouteCommands.parseLoadMode("step"));
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, WaypointerRouteCommands.parseLoadMode("step-by-step"));
        assertEquals(WaypointGroup.LoadMode.SEQUENCE, WaypointerRouteCommands.parseLoadMode("sequence"));
        assertEquals(WaypointGroup.LoadMode.STATIC, WaypointerRouteCommands.parseLoadMode("all"));
        assertEquals(WaypointGroup.LoadMode.STATIC, WaypointerRouteCommands.parseLoadMode("static"));
        assertNull(WaypointerRouteCommands.parseLoadMode("sometimes"));
        assertTrue(WaypointerRouteCommands.parseToggleState(null, false));
        assertFalse(WaypointerRouteCommands.parseToggleState("off", true));
        assertNull(WaypointerRouteCommands.parseToggleState("maybe", true));
        assertEquals(WaypointerWaypointCommands.Flag.HIDE_AFTER_PARENT,
                WaypointerWaypointCommands.Flag.parse("hap"));
        assertEquals(WaypointerWaypointCommands.Flag.LINE_OF_SIGHT,
                WaypointerWaypointCommands.Flag.parse("line_of_sight"));
    }

    private static final class Messages {
        final List<Component> feedback = new ArrayList<>();
        final List<Component> errors = new ArrayList<>();

        FabricClientCommandSource source() {
            return (FabricClientCommandSource) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{FabricClientCommandSource.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "sendFeedback" -> {
                            feedback.add((Component) args[0]);
                            yield null;
                        }
                        case "sendError" -> {
                            errors.add((Component) args[0]);
                            yield null;
                        }
                        case "toString" -> "Messages";
                        default -> throw new AssertionError("Unexpected call: " + method.getName());
                    });
        }
    }
}
