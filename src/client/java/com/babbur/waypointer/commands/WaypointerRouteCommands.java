package com.babbur.waypointer.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.DungeonRoomWaypointPlacement;
import com.babbur.waypointer.dungeon.DungeonWaypointSkipRules;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;
import com.babbur.waypointer.input.WaypointAddFlow;
import com.babbur.waypointer.input.WaypointerKeybinds;
import com.babbur.waypointer.placement.PlayerWaypointPlacement;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Locale;

import static com.babbur.waypointer.commands.CommandHelpers.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

final class WaypointerRouteCommands {

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointAddFlow addFlow = new WaypointAddFlow();
    private final WaypointerCommandSuggestions suggestions;

    WaypointerRouteCommands(ActiveGroupManager manager, WaypointerConfig config,
                            WaypointerCommandSuggestions suggestions) {
        this.manager = manager;
        this.config = config;
        this.suggestions = suggestions;
    }

    LiteralArgumentBuilder<FabricClientCommandSource> currentSubwayCommand(String name) {
        return literal(name)
                .executes(ctx -> runToggleSubwaypoint(ctx.getSource(), null))
                .then(argument("index", IntegerArgumentType.integer(1))
                        .suggests(suggestions.suggestActiveGroupIndices())
                        .executes(ctx -> runToggleSubwaypoint(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "index"))));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> currentFlagCommand(String name, int flag,
                                                                                String labelKey,
                                                                                boolean subwaypointOnly) {
        return literal(name)
                .executes(ctx -> runToggleWaypointFlag(
                        ctx.getSource(), null, flag, labelKey, subwaypointOnly))
                .then(argument("index", IntegerArgumentType.integer(1))
                        .suggests(suggestions.suggestActiveGroupIndices())
                        .executes(ctx -> runToggleWaypointFlag(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "index"),
                                flag, labelKey, subwaypointOnly)));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> insertCommand() {
        return literal("insert")
                .then(argument("index", IntegerArgumentType.integer(1))
                        .suggests(suggestions.suggestInsertSlots())
                        .executes(ctx -> runInsert(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "index"), ""))
                        .then(literal("at")
                                .then(argument("x", IntegerArgumentType.integer())
                                        .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.X))
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.Y))
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.Z))
                                                        .executes(ctx -> runInsertAt(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                ""))
                                                        .then(argument("name", StringArgumentType.greedyString())
                                                                .executes(ctx -> runInsertAt(ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "index"),
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                        IntegerArgumentType.getInteger(ctx, "z"),
                                                                        StringArgumentType.getString(ctx, "name"))))))))
                        .then(argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> runInsert(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "index"),
                                        StringArgumentType.getString(ctx, "name")))));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> waypointCommand() {
        return literal("waypoint")
                .then(literal("move")
                        .then(argument("index", IntegerArgumentType.integer(1))
                                .suggests(suggestions.suggestActiveGroupIndices())
                                .then(literal("here")
                                        .executes(ctx -> runMoveWaypointHere(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"))))
                                .then(literal("at")
                                        .then(argument("x", IntegerArgumentType.integer())
                                                .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.X))
                                                .then(argument("y", IntegerArgumentType.integer())
                                                        .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.Y))
                                                        .then(argument("z", IntegerArgumentType.integer())
                                                                .suggests(suggestions.suggestPlayerCoord(WaypointerCommandSuggestions.Axis.Z))
                                                                .executes(ctx -> runMoveWaypointAt(ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "index"),
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                        IntegerArgumentType.getInteger(ctx, "z")))))))))
                .then(literal("rename")
                        .then(argument("index", IntegerArgumentType.integer(1))
                                .suggests(suggestions.suggestActiveGroupIndices())
                                .then(argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> runRenameWaypoint(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "name"))))))
                .then(literal("color")
                        .then(argument("index", IntegerArgumentType.integer(1))
                                .suggests(suggestions.suggestActiveGroupIndices())
                                .then(argument("hex", StringArgumentType.word())
                                        .suggests(suggestions.suggestHexColors())
                                        .executes(ctx -> runSetWaypointColor(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "hex"))))))
                .then(literal("radius")
                        .then(argument("index", IntegerArgumentType.integer(1))
                                .suggests(suggestions.suggestActiveGroupIndices())
                                .then(argument("radius", DoubleArgumentType.doubleArg(
                                        0.0, Waypoint.MAX_REACH_RADIUS))
                                        .executes(ctx -> runSetWaypointRadius(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                DoubleArgumentType.getDouble(ctx, "radius"))))))
                .then(literal("sub")
                        .then(argument("index", IntegerArgumentType.integer(1))
                                .suggests(suggestions.suggestActiveGroupIndices())
                                .executes(ctx -> runToggleSubwaypoint(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "index")))));
    }

    int runList(FabricClientCommandSource src) {
        List<WaypointGroup> active = manager.activeGroups();
        if (active.isEmpty()) {
            Zone zone = manager.currentZone();
            info(src, zone == null
                    ? Component.translatable("waypointer.command.list.empty")
                    : Component.translatable(
                            "waypointer.command.list.empty_in_zone", zone.displayName()));
            return 0;
        }
        for (WaypointGroup g : active) {
            String currentIndexText = g.isComplete()
                    ? "complete"
                    : Integer.toString(g.currentIndex() + 1);
            if (g.currentIndex() >= 0 && g.currentIndex() < g.size()) {
                currentIndexText += " (" + g.displayIndexLabel(g.currentIndex()) + ")";
            }
            info(src, Component.translatable(
                    "waypointer.command.list.route_numbered",
                    Component.literal(g.name()).withStyle(ChatFormatting.AQUA),
                    g.size(), currentIndexText));
            int shown = Math.min(g.size(), 16);
            for (int i = 0; i < shown; i++) {
                Waypoint w = g.get(i);
                ChatFormatting color = i < g.currentIndex() ? ChatFormatting.DARK_GRAY
                        : i == g.currentIndex() ? ChatFormatting.YELLOW
                        : ChatFormatting.WHITE;
                info(src, Component.translatable(
                        "waypointer.command.list.waypoint_numbered",
                        g.displayIndexLabel(i), i + 1,
                        Component.literal(w.x() + ", " + w.y() + ", " + w.z())
                                .withStyle(color),
                        w.hasName()
                                ? Component.literal(w.name()).withStyle(ChatFormatting.GRAY)
                                : Component.empty()));
            }
            if (g.size() > shown) {
                info(src, Component.translatable(
                        "waypointer.command.list.more", g.size() - shown));
            }
        }
        return active.size();
    }

    int runSkipCurrentWaypoint(FabricClientCommandSource src) {
        int moved = WaypointerKeybinds.skipCurrentWaypointTargets(manager, config);
        if (moved == 0) {
            error(src, Component.translatable("waypointer.command.skip.no_route"));
            return 0;
        }
        success(src, Component.translatable(
                moved == 1
                        ? "waypointer.command.skip.success.one"
                        : "waypointer.command.skip.success.many",
                moved));
        return moved;
    }

    int runUnskipCurrentWaypoint(FabricClientCommandSource src) {
        int moved = WaypointerKeybinds.unskipCurrentWaypointTargets(manager);
        if (moved == 0) {
            error(src, Component.translatable(
                    "waypointer.command.unskip.no_route"));
            return 0;
        }
        success(src, Component.translatable(
                moved == 1
                        ? "waypointer.command.unskip.success.one"
                        : "waypointer.command.unskip.success.many",
                moved));
        return moved;
    }

    int runSkipTo(FabricClientCommandSource src, String target) {
        List<WaypointGroup> activeGroups = manager.activeGroups();
        if (activeGroups.isEmpty()) {
            error(src, Component.translatable("waypointer.command.skip.no_route"));
            return 0;
        }
        WaypointerCommands.SkipToOutcome outcome = skipActiveGroupsToTarget(activeGroups, target);
        if (outcome.moved() == 0) {
            error(src, outcome.error() == null
                    ? Component.translatable(
                            "waypointer.command.skipto.no_target", target)
                    : Component.literal(outcome.error()));
            return 0;
        }
        manager.fireDataChanged();

        success(src, Component.translatable(
                outcome.moved() == 1
                        ? "waypointer.command.skipto.success.one"
                        : "waypointer.command.skipto.success.many",
                outcome.moved(), outcome.firstMovedLabel()));
        return outcome.moved();
    }

    static WaypointerCommands.SkipToOutcome skipActiveGroupsToTarget(List<WaypointGroup> activeGroups, String target) {
        int moved = 0;
        String firstError = null;
        String firstMovedLabel = null;
        for (WaypointGroup group : activeGroups) {
            if (group == null || group.isEmpty()) continue;
            SkipTarget resolved = resolveSkipTargetIndex(group, target);
            if (resolved.error() != null) {
                if (firstError == null) firstError = resolved.error();
                continue;
            }
            group.setCurrentTargetIndex(resolved.index());
            if (firstMovedLabel == null) {
                firstMovedLabel = skipTargetLabel(group, resolved.index());
            }
            moved++;
        }
        return new WaypointerCommands.SkipToOutcome(moved, firstMovedLabel, firstError);
    }

    static SkipTarget resolveSkipTargetIndex(WaypointGroup group, String rawTarget) {
        String target = rawTarget == null ? "" : rawTarget.trim();
        if (target.startsWith("#")) target = target.substring(1);
        if (target.isEmpty()) {
            return SkipTarget.error("Usage: /wp skipto <number>, for example 2 or 2.2");
        }

        String[] parts = target.split("\\.", -1);
        if (parts.length < 1 || parts.length > 2 || parts[0].isBlank()) {
            return SkipTarget.error("Invalid skip target '" + rawTarget + "'. Use 2 or 2.2.");
        }

        int mainOrdinal = parsePositiveOrdinal(parts[0]);
        if (mainOrdinal <= 0) {
            return SkipTarget.error("Main waypoint number must be 1 or higher.");
        }

        int mainIndex = indexForMainOrdinal(group, mainOrdinal);
        if (mainIndex < 0) {
            return SkipTarget.error("Main waypoint " + mainOrdinal
                    + " out of range (1.." + group.mainWaypointCount() + ")");
        }
        if (parts.length == 1) {
            return SkipTarget.index(mainIndex);
        }
        if (parts[1].isBlank()) {
            return SkipTarget.error("Subwaypoint number is missing after the decimal.");
        }

        int childOrdinal = parsePositiveOrdinal(parts[1]);
        if (childOrdinal <= 0) {
            return SkipTarget.error("Subwaypoint number must be 1 or higher.");
        }

        int childIndex = indexForChildOrdinal(group, mainIndex, childOrdinal);
        if (childIndex < 0) {
            return SkipTarget.error("Waypoint " + mainOrdinal + " only has "
                    + childCount(group, mainIndex) + " subwaypoint(s).");
        }
        return SkipTarget.index(childIndex);
    }

    static int parsePositiveOrdinal(String raw) {
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed < 1 ? -1 : parsed;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int indexForMainOrdinal(WaypointGroup group, int ordinal) {
        int count = 0;
        for (int i = 0; i < group.size(); i++) {
            if (group.isSubwaypoint(i)) continue;
            count++;
            if (count == ordinal) return i;
        }
        return -1;
    }

    static int indexForChildOrdinal(WaypointGroup group, int mainIndex, int childOrdinal) {
        if (mainIndex < 0 || mainIndex >= group.size() || group.isSubwaypoint(mainIndex)) {
            return -1;
        }
        int count = 0;
        for (int i = mainIndex + 1; i < group.size() && group.isSubwaypoint(i); i++) {
            count++;
            if (count == childOrdinal) return i;
        }
        return -1;
    }

    static int childCount(WaypointGroup group, int mainIndex) {
        if (mainIndex < 0 || mainIndex >= group.size() || group.isSubwaypoint(mainIndex)) {
            return 0;
        }
        int count = 0;
        for (int i = mainIndex + 1; i < group.size() && group.isSubwaypoint(i); i++) {
            count++;
        }
        return count;
    }

    static String skipTargetLabel(WaypointGroup group, int index) {
        String label = group.displayIndexLabel(index);
        return label.startsWith("#") ? label.substring(1) : label;
    }

    record SkipTarget(int index, String error) {
        static SkipTarget index(int index) {
            return new SkipTarget(index, null);
        }

        static SkipTarget error(String message) {
            return new SkipTarget(-1, message == null ? "Invalid skip target" : message);
        }
    }

    int runResetActiveGroup(FabricClientCommandSource src) {
        WaypointGroup group = manager.firstActiveGroup();
        if (group == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.no_active_route"));
            return 0;
        }
        group.resetProgress();
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.reset.success", group.name()));
        return 1;
    }

    int runSetActiveGroupMode(FabricClientCommandSource src, String rawMode) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null) return 0;
        WaypointGroup.LoadMode mode = parseLoadMode(rawMode);
        if (mode == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.mode"));
            return 0;
        }
        group.setLoadMode(mode);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.mode_set",
                group.name(), mode.name().toLowerCase(Locale.ROOT)));
        return 1;
    }

    int runSetActiveGroupRadius(FabricClientCommandSource src, double radius) {
        WaypointGroup group = activeGroupOrError(src);
        if (group == null) return 0;
        group.setDefaultRadius(radius);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.radius_set",
                group.name(), String.format(Locale.ROOT, "%.1f", group.defaultRadius())));
        return 1;
    }

    int runMoveWaypointToSlot(FabricClientCommandSource src, int index, int slot) {
        index = waypointIndexFromNumber(index);
        slot = waypointIndexFromNumber(slot);
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        if (slot < 0 || slot >= group.size()) {
            error(src, Component.translatable(
                    "waypointer.command.error.move_waypoint_number_range",
                    slot + 1, group.size()));
            return 0;
        }
        group.move(index, slot);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.waypoint.reordered_numbered", index + 1, slot + 1));
        return 1;
    }

    int runToggleSubwaypoint(FabricClientCommandSource src, Integer requestedIndex) {
        WaypointGroup group = activeGroupOrError(src);
        int index = resolveActiveWaypointIndex(src, group, requestedIndex);
        if (index < 0) return 0;

        boolean wasSubwaypoint = group.isSubwaypoint(index);
        if (!group.toggleSubwaypoint(index)) {
            error(src, index == 0
                    ? Component.translatable(
                            "waypointer.command.waypoint.first_not_subwaypoint")
                    : Component.translatable(
                            "waypointer.command.waypoint.not_subwaypoint_numbered", index + 1));
            return 0;
        }
        if (!wasSubwaypoint && group.isSubwaypoint(index)) {
            group.setSkipAheadEnabled(false);
        }
        manager.fireDataChanged();
        success(src, Component.translatable(
                group.isSubwaypoint(index)
                        ? "waypointer.command.waypoint.subwaypoint"
                        : "waypointer.command.waypoint.main",
                group.displayIndexLabel(index)));
        return 1;
    }

    int runToggleWaypointFlag(FabricClientCommandSource src, Integer requestedIndex,
                                      int flag, String labelKey, boolean subwaypointOnly) {
        WaypointGroup group = activeGroupOrError(src);
        int index = resolveActiveWaypointIndex(src, group, requestedIndex);
        if (index < 0) return 0;
        if (subwaypointOnly && !group.isSubwaypoint(index)) {
            error(src, Component.translatable(
                    "waypointer.command.waypoint.flag_requires_subwaypoint",
                    group.displayIndexLabel(index), index + 1));
            return 0;
        }

        Waypoint waypoint = group.get(index);
        int nextFlags = waypoint.flags() ^ flag;
        group.set(index, waypoint.withFlags(nextFlags));
        manager.fireDataChanged();
        success(src, Component.translatable(
                ((nextFlags & flag) != 0)
                        ? "waypointer.command.waypoint.flag_enabled"
                        : "waypointer.command.waypoint.flag_disabled",
                Component.translatable(labelKey), group.displayIndexLabel(index)));
        return 1;
    }

    int runMoveWaypointHere(FabricClientCommandSource src, int index) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            error(src, Component.translatable("waypointer.command.error.not_in_world"));
            return 0;
        }
        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                player.getX(), player.getY(), player.getZ(), config);
        return runMoveWaypointAt(src, index, pos.x(), pos.y(), pos.z());
    }

    int runMoveWaypointAt(FabricClientCommandSource src, int index, int x, int y, int z) {
        index = waypointIndexFromNumber(index);
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        DungeonRoomWaypointPlacement.moveWaypointToStoredPosition(group, index, x, y, z);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.waypoint.moved",
                group.displayIndexLabel(index), x, y, z));
        return 1;
    }

    int runRenameWaypoint(FabricClientCommandSource src, int index, String name) {
        index = waypointIndexFromNumber(index);
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        group.set(index, group.get(index).withName(name == null ? "" : name.trim()));
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.waypoint.renamed",
                group.displayIndexLabel(index)));
        return 1;
    }

    int runSetWaypointColor(FabricClientCommandSource src, int index, String rawColor) {
        index = waypointIndexFromNumber(index);
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        Integer color = parseRgb(rawColor);
        if (color == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.color_hex"));
            return 0;
        }

        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        Waypoint waypoint = group.get(index);
        group.set(index, waypoint.withColor(color).withFlags(waypoint.flags() | Waypoint.FLAG_LOCKED_COLOR));
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.waypoint.color_set",
                group.displayIndexLabel(index), formatRgb(color)));
        return 1;
    }

    int runSetWaypointRadius(FabricClientCommandSource src, int index, double radius) {
        index = waypointIndexFromNumber(index);
        WaypointGroup group = activeGroupOrError(src);
        if (group == null || !validateWaypointIndex(src, group, index)) return 0;
        group.set(index, group.get(index).withRadius(radius));
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.waypoint.radius_set",
                group.displayIndexLabel(index),
                String.format(Locale.ROOT, "%.1f", radius)));
        return 1;
    }

    WaypointGroup activeGroupOrError(FabricClientCommandSource src) {
        WaypointGroup visibleGroup = manager.firstActiveGroup();
        if (visibleGroup == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.no_active_route"));
            return null;
        }
        WaypointGroup editTarget = DungeonRoomRouteLibrary.durableEditTarget(manager, visibleGroup);
        if (editTarget == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.convert_first"));
        }
        return editTarget;
    }

    int resolveActiveWaypointIndex(FabricClientCommandSource src, WaypointGroup group,
                                           Integer requestedIndex) {
        if (group == null) return -1;
        int index = requestedIndex == null
                ? group.currentIndex()
                : waypointIndexFromNumber(requestedIndex);
        if (requestedIndex == null && (index < 0 || index >= group.size())) {
            error(src, Component.translatable(
                    "waypointer.command.error.no_current_waypoint"));
            return -1;
        }
        if (!validateWaypointIndex(src, group, index)) {
            return -1;
        }
        return index;
    }

    boolean validateWaypointIndex(FabricClientCommandSource src, WaypointGroup group, int index) {
        if (group != null && index >= 0 && index < group.size()) return true;
        if (group != null && group.isEmpty()) {
            error(src, Component.translatable("waypointer.command.error.no_current_waypoint"));
            return false;
        }
        int max = group == null ? 0 : group.size();
        error(src, Component.translatable(
                "waypointer.command.error.waypoint_number_range", index + 1, max));
        return false;
    }

    static int waypointIndexFromNumber(int number) {
        return number - 1;
    }

    static WaypointGroup.LoadMode parseLoadMode(String rawMode) {
        String mode = rawMode == null ? "" : rawMode.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "static", "all" -> WaypointGroup.LoadMode.STATIC;
            case "sequence", "sequenced", "seq" -> WaypointGroup.LoadMode.SEQUENCE;
            default -> null;
        };
    }

    static Integer parseRgb(String rawColor) {
        if (rawColor == null) return null;
        String cleaned = rawColor.trim();
        if (cleaned.startsWith("#")) cleaned = cleaned.substring(1);
        if (cleaned.startsWith("0x") || cleaned.startsWith("0X")) cleaned = cleaned.substring(2);
        if (cleaned.length() != 6) return null;
        try {
            return Integer.parseInt(cleaned, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String formatRgb(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    int runAdd(FabricClientCommandSource src, String name) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            error(src, Component.translatable("waypointer.command.error.not_in_world"));
            return 0;
        }
        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                player.getX(), player.getY(), player.getZ(), config);

        return runAddAt(src, pos.x(), pos.y(), pos.z(), name);
    }

    int runAddAt(FabricClientCommandSource src, int x, int y, int z, String name) {
        int index = addPersistentWaypointAt(manager, config, addFlow, x, y, z, name);
        if (index < 0) {
            error(src, Component.translatable(
                    "waypointer.command.error.convert_first"));
            return 0;
        }
        return 1;
    }

    static int addPersistentWaypointAt(ActiveGroupManager manager, WaypointerConfig config,
                                       WaypointAddFlow addFlow, int x, int y, int z,
                                       String name) {
        if (definitionOnlyRouteRequiresConversion(manager)) return -1;
        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        target.add(storedCommandWaypoint(target, config, x, y, z, name));
        int index = target.size() - 1;
        addFlow.afterWaypointAdded(target, index, config.showWaypointChatShareButtons());
        manager.fireDataChanged();
        return index;
    }

    static int insertPersistentWaypointAt(WaypointGroup target, WaypointerConfig config,
                                          WaypointAddFlow addFlow, int index,
                                          int x, int y, int z, String name) {
        if (target == null || index < 0 || index > target.size()) return -1;
        target.insert(index, storedCommandWaypoint(target, config, x, y, z, name));
        addFlow.afterWaypointAdded(target, index, config.showWaypointChatShareButtons());
        return index;
    }

    static Waypoint storedCommandWaypoint(WaypointGroup target, WaypointerConfig config,
                                                  int x, int y, int z, String name) {
        return DungeonRoomWaypointPlacement.toStoredWaypoint(
                target, commandWaypoint(target, config, x, y, z, name));
    }

    static Waypoint commandWaypoint(WaypointGroup target, WaypointerConfig config,
                                            int x, int y, int z, String name) {
        int flags = target != null && target.routeKind() == WaypointGroup.RouteKind.DUNGEON
                ? DungeonWaypointSkipRules.defaultFlagsAt(x, y, z)
                : 0;
        return new Waypoint(x, y, z, name == null ? "" : name,
                config.defaultWaypointColor(), flags, 0.0);
    }

    int runInsert(FabricClientCommandSource src, int index, String name) {
        index = waypointIndexFromNumber(index);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            error(src, Component.translatable("waypointer.command.error.not_in_world"));
            return 0;
        }
        if (definitionOnlyRouteRequiresConversion(manager)) {
            error(src, Component.translatable(
                    "waypointer.command.error.convert_first"));
            return 0;
        }

        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        if (index < 0 || index > target.size()) {
            error(src, Component.translatable(
                    "waypointer.command.error.insert_waypoint_number_range",
                    index + 1, target.size() + 1));
            return 0;
        }

        PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                player.getX(), player.getY(), player.getZ(), config);
        insertPersistentWaypointAt(target, config, addFlow, index, pos.x(), pos.y(), pos.z(), name);
        manager.fireDataChanged();

        return 1;
    }

    int runInsertAt(FabricClientCommandSource src, int index,
                            int x, int y, int z, String name) {
        index = waypointIndexFromNumber(index);
        if (definitionOnlyRouteRequiresConversion(manager)) {
            error(src, Component.translatable(
                    "waypointer.command.error.convert_first"));
            return 0;
        }
        WaypointGroup target = manager.getOrCreateActiveGroup(config.skipAheadMechanicEnabled());
        if (index < 0 || index > target.size()) {
            error(src, Component.translatable(
                    "waypointer.command.error.insert_waypoint_number_range",
                    index + 1, target.size() + 1));
            return 0;
        }

        insertPersistentWaypointAt(target, config, addFlow, index, x, y, z, name);
        manager.fireDataChanged();
        return 1;
    }

    int runRemove(FabricClientCommandSource src, int index) {
        index = waypointIndexFromNumber(index);
        WaypointGroup target = activeGroupOrError(src);
        if (target == null) return 0;
        if (!validateWaypointIndex(src, target, index)) return 0;
        String displayLabel = target.displayIndexLabel(index);
        Waypoint removed = removeWaypointAt(target, index);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.waypoint.removed_numbered",
                index + 1, displayLabel, removed.x(), removed.y(), removed.z()));
        return 1;
    }

    static boolean definitionOnlyRouteRequiresConversion(ActiveGroupManager manager) {
        return false;
    }

    static Waypoint removeWaypointAt(WaypointGroup target, int index) {
        if (target == null || index < 0 || index >= target.size()) return null;
        Waypoint removed = target.get(index);
        target.remove(index);
        return removed;
    }

    int runClearZone(FabricClientCommandSource src, boolean confirmed) {
        Zone zone = manager.currentZone();
        if (zone == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.no_active_zone"));
            return 0;
        }
        List<WaypointGroup> here = manager.groupsForZone(zone.id());
        if (here.isEmpty()) {
            info(src, Component.translatable(
                    "waypointer.command.clear.empty", zone.displayName()));
            return 0;
        }
        if (!confirmed) {
            warn(src, Component.translatable(
                    "waypointer.command.clear.confirm",
                    here.size(), zone.displayName()));
            return 0;
        }
        int cleared = clearCurrentZoneGroups(manager, true);
        success(src, Component.translatable(
                "waypointer.command.clear.success",
                here.size(), zone.displayName()));
        return cleared;
    }

    static int clearCurrentZoneGroups(ActiveGroupManager manager, boolean confirmed) {
        Zone zone = manager.currentZone();
        if (!confirmed || zone == null) return 0;
        List<WaypointGroup> here = List.copyOf(manager.groupsForZone(zone.id()));
        for (WaypointGroup group : here) {
            manager.remove(group.id());
        }
        return here.size();
    }
}
