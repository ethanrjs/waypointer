package com.babbur.waypointer.commands;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;
import com.babbur.waypointer.dungeon.DungeonRoomWaypointPlacement;
import com.babbur.waypointer.dungeon.DungeonWaypointSkipRules;
import com.babbur.waypointer.dungeon.DungeonWaypointType;
import com.babbur.waypointer.input.WaypointAddFlow;
import com.babbur.waypointer.input.WaypointerKeybinds;
import com.babbur.waypointer.placement.PlayerWaypointPlacement;
import com.babbur.waypointer.screen.SettingsScreen;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import static com.babbur.waypointer.commands.CommandHelpers.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

final class WaypointerWaypointCommands {

    enum Flag {
        TINY("tiny", Waypoint.FLAG_SMALL_SUBWAYPOINT, Scope.SUBWAYPOINT,
                "waypointer.screen.group_edit.control.subwaypoint.tiny"),
        FILLED("filled", Waypoint.FLAG_FILLED_SUBWAYPOINT, Scope.SUBWAYPOINT,
                "waypointer.screen.group_edit.control.subwaypoint.filled"),
        HIDE_AFTER_PARENT("hide-after-parent", Waypoint.FLAG_HIDE_SUBWAYPOINT_WHEN_PARENT_REACHED,
                Scope.SUBWAYPOINT, "waypointer.screen.group_edit.control.subwaypoint.hide_after_parent"),
        STAND_TO_SKIP("stand-to-skip", Waypoint.FLAG_SKIP_ON_STAND, Scope.DUNGEON,
                "waypointer.screen.group_edit.control.stand.tooltip"),
        INTERACT_TO_SKIP("interact-to-skip", Waypoint.FLAG_SKIP_ON_INTERACT, Scope.DUNGEON,
                "waypointer.screen.group_edit.control.interact.tooltip"),
        MINE_TO_SKIP("mine-to-skip", Waypoint.FLAG_SKIP_ON_MINE, Scope.DUNGEON,
                "waypointer.screen.group_edit.control.mine.tooltip"),
        LINE_OF_SIGHT("line-of-sight", Waypoint.FLAG_DEPTH_CHECKED, Scope.ANY,
                "waypointer.screen.group_edit.control.los.tooltip");

        enum Scope { ANY, SUBWAYPOINT, DUNGEON }

        final String id;
        final int bit;
        final Scope scope;
        final String labelKey;

        Flag(String id, int bit, Scope scope, String labelKey) {
            this.id = id;
            this.bit = bit;
            this.scope = scope;
            this.labelKey = labelKey;
        }

        static Flag parse(String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            for (Flag flag : values()) {
                if (flag.id.equals(value)) return flag;
            }
            return switch (value) {
                case "small" -> TINY;
                case "hap", "hide" -> HIDE_AFTER_PARENT;
                case "sts", "stand" -> STAND_TO_SKIP;
                case "its", "interact" -> INTERACT_TO_SKIP;
                case "mine" -> MINE_TO_SKIP;
                case "los" -> LINE_OF_SIGHT;
                default -> null;
            };
        }
    }

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointerCommandSuggestions suggestions;
    private final WaypointerRouteCommands routes;
    private final WaypointAddFlow addFlow = new WaypointAddFlow();

    WaypointerWaypointCommands(ActiveGroupManager manager, WaypointerConfig config,
                               WaypointerCommandSuggestions suggestions,
                               WaypointerRouteCommands routes) {
        this.manager = manager;
        this.config = config;
        this.suggestions = suggestions;
        this.routes = routes;
    }


    LiteralArgumentBuilder<FabricClientCommandSource> waypointsCommand() {
        return literal("waypoints").executes(ctx -> runListWaypoints(ctx.getSource()));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> addCommand() {
        return literal("add")
                .executes(ctx -> runAdd(ctx.getSource(), ""))
                .then(atCoordinates((ctx, name) -> runAddAt(ctx.getSource(),
                        coordinate(ctx, "x"), coordinate(ctx, "y"), coordinate(ctx, "z"), name)))
                .then(argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> runAdd(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"))));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> insertCommand() {
        return literal("insert")
                .then(label("before")
                        .executes(ctx -> runInsert(ctx.getSource(), labelArg(ctx, "before"), "", null))
                        .then(atCoordinates((ctx, name) -> runInsert(ctx.getSource(),
                                labelArg(ctx, "before"), name,
                                new int[]{coordinate(ctx, "x"), coordinate(ctx, "y"), coordinate(ctx, "z")})))
                        .then(argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> runInsert(ctx.getSource(), labelArg(ctx, "before"),
                                        StringArgumentType.getString(ctx, "name"), null))));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> removeCommand() {
        return literal("remove")
                .then(label("waypoint").executes(ctx -> runRemove(ctx.getSource(),
                        labelArg(ctx, "waypoint"))));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> moveCommand() {
        return literal("move")
                .then(label("waypoint")
                        .then(label("to").executes(ctx -> runMoveTo(ctx.getSource(),
                                labelArg(ctx, "waypoint"), labelArg(ctx, "to")))));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> skipCommand() {
        return literal("skip").executes(ctx -> runSkip(ctx.getSource()));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> unskipCommand() {
        return literal("unskip").executes(ctx -> runUnskip(ctx.getSource()));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> skipToCommand() {
        return literal("skipto")
                .then(label("waypoint").executes(ctx -> runSetCurrent(ctx.getSource(),
                        labelArg(ctx, "waypoint"))));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> resetCommand() {
        return literal("reset").executes(ctx -> runReset(ctx.getSource()));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> waypointCommand() {
        return literal("waypoint").then(label("waypoint")
                .executes(ctx -> runInfo(ctx.getSource(), labelArg(ctx, "waypoint")))
                .then(literal("rename")
                        .executes(ctx -> runRename(ctx.getSource(), labelArg(ctx, "waypoint"), ""))
                        .then(argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> runRename(ctx.getSource(), labelArg(ctx, "waypoint"),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(literal("here").executes(ctx -> runMoveHere(ctx.getSource(),
                        labelArg(ctx, "waypoint"))))
                .then(literal("at")
                        .then(coordinates(z -> z.executes(ctx -> runMoveAt(ctx.getSource(),
                                labelArg(ctx, "waypoint"),
                                coordinate(ctx, "x"), coordinate(ctx, "y"), coordinate(ctx, "z"))))))
                .then(literal("up").executes(ctx -> runStep(ctx.getSource(),
                        labelArg(ctx, "waypoint"), -1)))
                .then(literal("down").executes(ctx -> runStep(ctx.getSource(),
                        labelArg(ctx, "waypoint"), 1)))
                .then(literal("color")
                        .then(literal("reset").executes(ctx -> runResetColor(ctx.getSource(),
                                labelArg(ctx, "waypoint"))))
                        .then(argument("hex", StringArgumentType.word())
                                .suggests(suggestions.suggestHexColors())
                                .executes(ctx -> runSetColor(ctx.getSource(), labelArg(ctx, "waypoint"),
                                        StringArgumentType.getString(ctx, "hex")))))
                .then(literal("radius")
                        .then(argument("blocks", DoubleArgumentType.doubleArg(0.0, Waypoint.MAX_REACH_RADIUS))
                                .executes(ctx -> runSetRadius(ctx.getSource(), labelArg(ctx, "waypoint"),
                                        DoubleArgumentType.getDouble(ctx, "blocks")))))
                .then(literal("current").executes(ctx -> runSetCurrent(ctx.getSource(),
                        labelArg(ctx, "waypoint"))))
                .then(literal("enable").executes(ctx -> runSetDisabled(ctx.getSource(),
                        labelArg(ctx, "waypoint"), false)))
                .then(literal("disable").executes(ctx -> runSetDisabled(ctx.getSource(),
                        labelArg(ctx, "waypoint"), true)))
                .then(literal("sub").executes(ctx -> runToggleSubwaypoint(ctx.getSource(),
                        labelArg(ctx, "waypoint"))))
                .then(literal("addsub")
                        .executes(ctx -> runAddSubwaypoint(ctx.getSource(), labelArg(ctx, "waypoint"), ""))
                        .then(argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> runAddSubwaypoint(ctx.getSource(), labelArg(ctx, "waypoint"),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(literal("flag")
                        .then(argument("flag", StringArgumentType.word())
                                .suggests(suggestions.suggestWaypointFlags())
                                .executes(ctx -> runSetFlag(ctx.getSource(), labelArg(ctx, "waypoint"),
                                        StringArgumentType.getString(ctx, "flag"), "toggle"))
                                .then(argument("state", StringArgumentType.word())
                                        .suggests(suggestions.suggestToggleStates())
                                        .executes(ctx -> runSetFlag(ctx.getSource(), labelArg(ctx, "waypoint"),
                                                StringArgumentType.getString(ctx, "flag"),
                                                StringArgumentType.getString(ctx, "state"))))))
                .then(literal("type")
                        .then(argument("type", StringArgumentType.word())
                                .suggests(suggestions.suggestDungeonTypes())
                                .executes(ctx -> runSetType(ctx.getSource(), labelArg(ctx, "waypoint"),
                                        StringArgumentType.getString(ctx, "type")))))
                .then(literal("appearance").executes(ctx -> runOpenAppearance(ctx.getSource(),
                        labelArg(ctx, "waypoint"))))
                .then(literal("remove").executes(ctx -> runRemove(ctx.getSource(),
                        labelArg(ctx, "waypoint")))));
    }

    private RequiredArgumentBuilder<FabricClientCommandSource, String> label(String name) {
        return argument(name, StringArgumentType.word()).suggests(suggestions.suggestWaypointLabels());
    }

    private static String labelArg(CommandContext<FabricClientCommandSource> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    private static int coordinate(CommandContext<FabricClientCommandSource> ctx, String axis) {
        return IntegerArgumentType.getInteger(ctx, axis);
    }

    @FunctionalInterface
    private interface NamedAction {
        int run(CommandContext<FabricClientCommandSource> ctx, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> atCoordinates(NamedAction action) {
        return literal("at").then(coordinates(z -> z
                .executes(ctx -> action.run(ctx, ""))
                .then(argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> action.run(ctx, StringArgumentType.getString(ctx, "name"))))));
    }

    private RequiredArgumentBuilder<FabricClientCommandSource, Integer> coordinates(
            UnaryOperator<RequiredArgumentBuilder<FabricClientCommandSource, Integer>> finish) {
        RequiredArgumentBuilder<FabricClientCommandSource, Integer> z = finish.apply(
                argument("z", IntegerArgumentType.integer())
                        .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.Z)));
        return argument("x", IntegerArgumentType.integer())
                .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.X))
                .then(argument("y", IntegerArgumentType.integer())
                        .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.Y))
                        .then(z));
    }


    int runListWaypoints(FabricClientCommandSource src) {
        WaypointGroup route = routes.targetOrError(src);
        if (route == null) return 0;
        info(src, Component.empty().append(routeName(route))
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(com.babbur.waypointer.i18n.Counts.of("waypoint", route.size())
                        .copy().withStyle(ChatFormatting.GRAY)));
        int shown = Math.min(route.size(), 20);
        for (int i = 0; i < shown; i++) {
            info(src, waypointLine(route, i));
        }
        if (route.size() > shown) {
            info(src, Component.translatable("waypointer.command.waypoints.more", route.size() - shown)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return route.size();
    }

    private static Component waypointLine(WaypointGroup route, int index) {
        Waypoint waypoint = route.get(index);
        ChatFormatting tone = waypoint.isDisabled() ? ChatFormatting.DARK_GRAY
                : index < route.currentIndex() ? ChatFormatting.GRAY
                : index == route.currentIndex() ? ChatFormatting.YELLOW
                : ChatFormatting.WHITE;
        MutableComponent line = Component.literal((route.isSubwaypoint(index) ? "     " : "  ")
                + route.displayIndexLabel(index) + "  ").withStyle(ChatFormatting.DARK_GRAY);
        line.append(Component.literal(coordinates(waypoint)).withStyle(tone));
        if (waypoint.hasName()) {
            line.append(Component.literal("  " + waypoint.name()).withStyle(ChatFormatting.GRAY));
        }
        if (index == route.currentIndex()) {
            line.append(Component.literal("  ").append(Component.translatable(
                    "waypointer.command.waypoints.current")).withStyle(ChatFormatting.YELLOW));
        }
        return line;
    }

    int runInfo(FabricClientCommandSource src, String label) {
        Found found = find(src, label);
        if (found == null) return 0;
        info(src, Component.empty().append(routeName(found.route()))
                .append(Component.literal(" › ").withStyle(ChatFormatting.DARK_GRAY))
                .append(waypointLine(found.route(), found.index())));
        return 1;
    }


    int runAdd(FabricClientCommandSource src, String name) {
        int[] pos = playerPosition(src);
        return pos == null ? 0 : runAddAt(src, pos[0], pos[1], pos[2], name);
    }

    int runAddAt(FabricClientCommandSource src, int x, int y, int z, String name) {
        if (addPersistentWaypointAt(manager, config, addFlow, x, y, z, name) < 0) {
            error(src, DungeonRoomWaypointPlacement.unavailableMessage());
            return 0;
        }
        return 1;
    }

    static int addPersistentWaypointAt(ActiveGroupManager manager, WaypointerConfig config,
                                       WaypointAddFlow addFlow, int x, int y, int z, String name) {
        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        Waypoint stored = storedCommandWaypoint(target, config, x, y, z, name);
        if (stored == null) return -1;
        target.add(stored);
        int index = target.size() - 1;
        addFlow.afterWaypointAdded(target, index, config.showWaypointChatShareButtons());
        manager.fireDataChanged();
        return index;
    }

    int runInsert(FabricClientCommandSource src, String beforeLabel, String name, int[] at) {
        Found found = find(src, beforeLabel);
        if (found == null) return 0;
        int[] pos = at != null ? at : playerPosition(src);
        if (pos == null) return 0;
        if (insertPersistentWaypointAt(found.route(), config, addFlow, found.index(),
                pos[0], pos[1], pos[2], name) < 0) {
            error(src, DungeonRoomWaypointPlacement.unavailableMessage(found.route()));
            return 0;
        }
        routes.changed(found.route());
        return 1;
    }

    static int insertPersistentWaypointAt(WaypointGroup target, WaypointerConfig config,
                                          WaypointAddFlow addFlow, int index,
                                          int x, int y, int z, String name) {
        if (target == null || index < 0 || index > target.size()) return -1;
        Waypoint stored = storedCommandWaypoint(target, config, x, y, z, name);
        if (stored == null) return -1;
        target.insert(index, stored);
        addFlow.afterWaypointAdded(target, index, config.showWaypointChatShareButtons());
        return index;
    }

    private static Waypoint storedCommandWaypoint(WaypointGroup target, WaypointerConfig config,
                                                  int x, int y, int z, String name) {
        int flags = target != null && target.routeKind() == WaypointGroup.RouteKind.DUNGEON
                ? DungeonWaypointSkipRules.defaultFlagsAt(x, y, z)
                : 0;
        return DungeonRoomWaypointPlacement.toStoredWaypoint(target, new Waypoint(
                x, y, z, name == null ? "" : name.trim(), config.defaultWaypointColor(), flags, 0.0));
    }

    int runAddSubwaypoint(FabricClientCommandSource src, String label, String name) {
        Found found = find(src, label);
        if (found == null) return 0;
        int[] pos = playerPosition(src);
        if (pos == null) return 0;
        WaypointGroup route = found.route();
        int parent = route.isSubwaypoint(found.index()) ? route.parentMainIndex(found.index()) : found.index();
        Waypoint stored = storedCommandWaypoint(route, config, pos[0], pos[1], pos[2], name);
        if (parent < 0 || stored == null) {
            error(src, DungeonRoomWaypointPlacement.unavailableMessage(route));
            return 0;
        }
        int at = route.childEndExclusive(parent);
        route.insert(at, stored.withSubwaypoint(true));
        if (route.skipAheadEnabled()) route.setSkipAheadEnabled(false);
        addFlow.afterWaypointAdded(route, at, config.showWaypointChatShareButtons());
        routes.changed(route);
        return 1;
    }

    int runRemove(FabricClientCommandSource src, String label) {
        Found found = find(src, label);
        if (found == null) return 0;
        WaypointGroup route = found.route();
        String shownLabel = route.displayIndexLabel(found.index());
        Waypoint removed = route.get(found.index());
        route.remove(found.index());
        routes.changed(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.waypoint.removed_at",
                shownLabel, coordinates(removed)));
        return 1;
    }


    int runMoveTo(FabricClientCommandSource src, String label, String toLabel) {
        Found found = find(src, label);
        if (found == null) return 0;
        RouteTargets.Lookup<Integer> to = RouteTargets.waypoint(found.route(), toLabel);
        if (!to.ok()) {
            error(src, to.error());
            return 0;
        }
        WaypointGroup route = found.route();
        String before = route.displayIndexLabel(found.index());
        route.move(found.index(), to.value());
        routes.changed(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.waypoint.moved_to",
                before, route.displayIndexLabel(to.value())));
        return 1;
    }

    int runStep(FabricClientCommandSource src, String label, int delta) {
        Found found = find(src, label);
        if (found == null) return 0;
        WaypointGroup route = found.route();
        String before = route.displayIndexLabel(found.index());
        int now = route.moveBy(found.index(), delta);
        if (now == found.index()) {
            error(src, Component.translatable("waypointer.command.waypoint.move_edge", before));
            return 0;
        }
        routes.changed(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.waypoint.moved_to",
                before, route.displayIndexLabel(now)));
        return 1;
    }

    int runMoveHere(FabricClientCommandSource src, String label) {
        int[] pos = playerPosition(src);
        return pos == null ? 0 : runMoveAt(src, label, pos[0], pos[1], pos[2]);
    }

    int runMoveAt(FabricClientCommandSource src, String label, int x, int y, int z) {
        Found found = find(src, label);
        if (found == null) return 0;
        if (!DungeonRoomWaypointPlacement.moveWaypointToStoredPosition(found.route(), found.index(), x, y, z)) {
            error(src, DungeonRoomWaypointPlacement.unavailableMessage(found.route()));
            return 0;
        }
        routes.changed(found.route());
        routeSuccess(src, found.route(), Component.translatable("waypointer.command.waypoint.repositioned",
                found.route().displayIndexLabel(found.index()), x + ", " + y + ", " + z));
        return 1;
    }


    int runRename(FabricClientCommandSource src, String label, String rawName) {
        Found found = find(src, label);
        if (found == null) return 0;
        String name = rawName == null ? "" : rawName.trim();
        WaypointGroup route = found.route();
        route.set(found.index(), route.get(found.index()).withName(name));
        routes.changed(route);
        routeSuccess(src, route, name.isEmpty()
                ? Component.translatable("waypointer.command.waypoint.name_cleared",
                        route.displayIndexLabel(found.index()))
                : Component.translatable("waypointer.command.waypoint.renamed_to",
                        route.displayIndexLabel(found.index()), name));
        return 1;
    }

    int runSetColor(FabricClientCommandSource src, String label, String rawColor) {
        Found found = find(src, label);
        if (found == null) return 0;
        Integer color = WaypointerRouteCommands.parseRgb(rawColor);
        if (color == null) {
            error(src, Component.translatable("waypointer.command.error.hex_value", rawColor));
            return 0;
        }
        WaypointGroup route = found.route();
        route.lockWaypointColor(found.index(), color);
        route.setPaintEnabled(false);
        routes.changed(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.waypoint.color",
                route.displayIndexLabel(found.index()), WaypointerRouteCommands.formatRgb(color)));
        return 1;
    }

    int runResetColor(FabricClientCommandSource src, String label) {
        Found found = find(src, label);
        if (found == null) return 0;
        WaypointGroup route = found.route();
        route.unlockWaypointColor(found.index());
        routes.changed(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.waypoint.color_reset",
                route.displayIndexLabel(found.index())));
        return 1;
    }

    int runSetRadius(FabricClientCommandSource src, String label, double radius) {
        Found found = find(src, label);
        if (found == null) return 0;
        WaypointGroup route = found.route();
        route.set(found.index(), route.get(found.index()).withRadius(radius));
        routes.changed(route);
        String shown = route.displayIndexLabel(found.index());
        routeSuccess(src, route, radius <= 0
                ? Component.translatable("waypointer.command.waypoint.radius_default", shown)
                : Component.translatable("waypointer.command.waypoint.radius", shown,
                        WaypointerRouteCommands.formatBlocks(radius)));
        return 1;
    }

    int runSetDisabled(FabricClientCommandSource src, String label, boolean disabled) {
        Found found = find(src, label);
        if (found == null) return 0;
        WaypointGroup route = found.route();
        route.setWaypointDisabled(found.index(), disabled);
        routes.changed(route);
        routeSuccess(src, route, Component.translatable(disabled
                        ? "waypointer.command.waypoint.disabled"
                        : "waypointer.command.waypoint.enabled",
                route.displayIndexLabel(found.index())));
        return 1;
    }

    int runToggleSubwaypoint(FabricClientCommandSource src, String label) {
        Found found = find(src, label);
        if (found == null) return 0;
        WaypointGroup route = found.route();
        if (!route.toggleSubwaypoint(found.index())) {
            error(src, Component.translatable(found.index() == 0
                    ? "waypointer.screen.group_edit.menu.subwaypoint_first"
                    : "waypointer.screen.group_edit.menu.subwaypoint_no_main"));
            return 0;
        }
        int now = found.index();
        boolean subwaypoint = route.isSubwaypoint(now);
        if (subwaypoint && route.skipAheadEnabled()) route.setSkipAheadEnabled(false);
        routes.changed(route);
        routeSuccess(src, route, Component.translatable(subwaypoint
                        ? "waypointer.command.waypoint.subwaypoint"
                        : "waypointer.command.waypoint.main",
                route.displayIndexLabel(now)));
        return 1;
    }

    int runSetFlag(FabricClientCommandSource src, String label, String rawFlag, String rawState) {
        Found found = find(src, label);
        if (found == null) return 0;
        Flag flag = Flag.parse(rawFlag);
        if (flag == null) {
            error(src, Component.translatable("waypointer.command.error.flag", rawFlag));
            return 0;
        }
        WaypointGroup route = found.route();
        if (flag.scope == Flag.Scope.SUBWAYPOINT && !route.isSubwaypoint(found.index())) {
            error(src, Component.translatable("waypointer.command.error.flag_subwaypoint",
                    Component.translatable(flag.labelKey), route.displayIndexLabel(found.index())));
            return 0;
        }
        if (flag.scope == Flag.Scope.DUNGEON && route.routeKind() != WaypointGroup.RouteKind.DUNGEON) {
            error(src, Component.translatable("waypointer.command.error.flag_dungeon",
                    Component.translatable(flag.labelKey)));
            return 0;
        }
        Waypoint waypoint = route.get(found.index());
        Boolean on = WaypointerRouteCommands.parseToggleState(rawState, waypoint.hasFlag(flag.bit));
        if (on == null) {
            error(src, Component.translatable("waypointer.command.error.state_value", rawState));
            return 0;
        }
        route.set(found.index(), waypoint.withFlags(on ? waypoint.flags() | flag.bit : waypoint.flags() & ~flag.bit));
        routes.changed(route);
        routeSuccess(src, route, Component.translatable(on
                        ? "waypointer.command.waypoint.flag_on"
                        : "waypointer.command.waypoint.flag_off",
                route.displayIndexLabel(found.index()), Component.translatable(flag.labelKey)));
        return 1;
    }

    int runSetType(FabricClientCommandSource src, String label, String rawType) {
        Found found = find(src, label);
        if (found == null) return 0;
        WaypointGroup route = found.route();
        if (route.routeKind() != WaypointGroup.RouteKind.DUNGEON) {
            error(src, Component.translatable("waypointer.command.error.flag_dungeon",
                    Component.translatable("waypointer.screen.group_edit.control.types.title")));
            return 0;
        }
        String value = rawType == null ? "" : rawType.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        Waypoint waypoint = route.get(found.index());
        if (value.equals("none")) {
            route.set(found.index(), waypoint.withFlags(waypoint.flags() & ~Waypoint.DUNGEON_METADATA_FLAGS));
            routes.changed(route);
            routeSuccess(src, route, Component.translatable("waypointer.command.waypoint.type_cleared",
                    route.displayIndexLabel(found.index())));
            return 1;
        }
        DungeonWaypointType type = dungeonType(value);
        if (type == null) {
            error(src, Component.translatable("waypointer.command.error.type", rawType));
            return 0;
        }
        route.set(found.index(), waypoint.withFlags(type.applyExclusive(waypoint.flags())));
        routes.changed(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.waypoint.type_set",
                route.displayIndexLabel(found.index()), Component.translatable(typeKey(type))));
        return 1;
    }

    static DungeonWaypointType dungeonType(String value) {
        for (DungeonWaypointType type : DungeonWaypointType.values()) {
            if (type.name().toLowerCase(Locale.ROOT).equals(value)) return type;
        }
        return null;
    }

    static String typeKey(DungeonWaypointType type) {
        return "waypointer.dungeon.waypoint_type." + type.name().toLowerCase(Locale.ROOT);
    }

    int runOpenAppearance(FabricClientCommandSource src, String label) {
        Found found = find(src, label);
        if (found == null) return 0;
        if (!SettingsScreen.openWaypointAppearance(manager, config, found.route(), found.index())) {
            error(src, Component.translatable("waypointer.screen.group_edit.menu.appearance.room_copy"));
            return 0;
        }
        return 1;
    }


    int runSetCurrent(FabricClientCommandSource src, String label) {
        Found found = find(src, label);
        if (found == null) return 0;
        WaypointGroup route = found.route();
        int index = found.index();
        DungeonRoomRouteLibrary.applyProgress(manager, route, group -> group.setCurrentTargetIndex(index));
        manager.fireDataChanged();
        routeSuccess(src, route, Component.translatable("waypointer.command.waypoint.current",
                route.displayIndexLabel(Math.min(route.currentIndex(), route.size() - 1))));
        return 1;
    }

    int runReset(FabricClientCommandSource src) {
        WaypointGroup route = routes.targetOrError(src);
        if (route == null) return 0;
        DungeonRoomRouteLibrary.applyProgress(manager, route, WaypointGroup::resetProgress);
        manager.fireDataChanged();
        routeSuccess(src, route, Component.translatable("waypointer.command.route.reset"));
        return 1;
    }

    int runSkip(FabricClientCommandSource src) {
        List<WaypointGroup> moving = new ArrayList<>();
        for (WaypointGroup group : manager.activeGroups()) {
            if (!group.isComplete() && !group.isEmpty()) moving.add(group);
        }
        if (WaypointerKeybinds.skipCurrentWaypointTargets(manager, config) == 0) {
            error(src, Component.translatable("waypointer.command.skip.none"));
            return 0;
        }
        reportProgress(src, moving, "waypointer.command.skip.done");
        return moving.size();
    }

    int runUnskip(FabricClientCommandSource src) {
        List<WaypointGroup> before = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        List<WaypointGroup> candidates = new ArrayList<>(manager.activeGroups());
        candidates.addAll(manager.completedDungeonRoomGroupsInCurrentZone());
        for (WaypointGroup group : candidates) {
            before.add(group);
            indices.add(group.currentIndex());
        }
        if (WaypointerKeybinds.unskipCurrentWaypointTargets(manager) == 0) {
            error(src, Component.translatable("waypointer.command.unskip.none"));
            return 0;
        }
        List<WaypointGroup> moved = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i).currentIndex() != indices.get(i)) moved.add(before.get(i));
        }
        reportProgress(src, moved, "waypointer.command.unskip.done");
        return moved.size();
    }

    private static void reportProgress(FabricClientCommandSource src, List<WaypointGroup> routes, String key) {
        for (WaypointGroup route : routes) {
            if (route.isEmpty()) continue;
            routeSuccess(src, route, route.isComplete()
                    ? Component.translatable("waypointer.command.progress.complete")
                    : Component.translatable(key, route.displayIndexLabel(route.currentIndex())));
        }
    }


    private record Found(WaypointGroup route, int index) {}

    private Found find(FabricClientCommandSource src, String label) {
        WaypointGroup route = routes.targetOrError(src);
        if (route == null) return null;
        RouteTargets.Lookup<Integer> index = RouteTargets.waypoint(route, label);
        if (!index.ok()) {
            error(src, index.error());
            return null;
        }
        return new Found(route, index.value());
    }

    private int[] playerPosition(FabricClientCommandSource src) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            error(src, Component.translatable("waypointer.command.error.not_in_world"));
            return null;
        }
        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                player.getX(), player.getY(), player.getZ(), config);
        return new int[]{pos.x(), pos.y(), pos.z()};
    }

    private static String coordinates(Waypoint waypoint) {
        return waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z();
    }
}
