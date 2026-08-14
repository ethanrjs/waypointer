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
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Locale;

import static com.babbur.waypointer.commands.CommandHelpers.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

final class WaypointerGroupCommands {

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointerCommandSuggestions suggestions;

    WaypointerGroupCommands(ActiveGroupManager manager, WaypointerConfig config,
                            WaypointerCommandSuggestions suggestions) {
        this.manager = manager;
        this.config = config;
        this.suggestions = suggestions;
    }

    LiteralArgumentBuilder<FabricClientCommandSource> areaCommand() {
        return literal("area")
                .then(argument("group", IntegerArgumentType.integer(0))
                        .suggests(suggestions.suggestAllGroupIndices())
                        .then(literal("current")
                                .executes(ctx -> runSetGroupZone(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "group"), "current")))
                        .then(argument("zone", StringArgumentType.greedyString())
                                .suggests(suggestions.suggestZoneTargets())
                                .executes(ctx -> runSetGroupZone(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "group"),
                                        StringArgumentType.getString(ctx, "zone")))));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> groupCommand(String literalName) {
        return literal(literalName)
                .then(literal("create")
                        .then(argument("name", StringArgumentType.greedyString())
                                .suggests(suggestions.suggestGroupNames())
                                .executes(ctx -> runCreateGroup(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(literal("list").executes(ctx -> runListGroups(ctx.getSource())))
                .then(literal("rename")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestions.suggestAllGroupIndices())
                                .then(argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> runRenameGroup(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "name"))))))
                .then(literal("zone")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestions.suggestAllGroupIndices())
                                .then(literal("current")
                                        .executes(ctx -> runSetGroupZone(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"), "current")))
                                .then(argument("zone", StringArgumentType.greedyString())
                                        .suggests(suggestions.suggestZoneTargets())
                                        .executes(ctx -> runSetGroupZone(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "zone"))))))
                .then(literal("area")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestions.suggestAllGroupIndices())
                                .then(literal("current")
                                        .executes(ctx -> runSetGroupZone(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"), "current")))
                                .then(argument("zone", StringArgumentType.greedyString())
                                        .suggests(suggestions.suggestZoneTargets())
                                        .executes(ctx -> runSetGroupZone(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "zone"))))))
                .then(literal("mode")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestions.suggestAllGroupIndices())
                                .then(argument("mode", StringArgumentType.word())
                                        .suggests(suggestions.suggestLoadModes())
                                        .executes(ctx -> runSetGroupMode(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "mode"))))))
                .then(literal("radius")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestions.suggestAllGroupIndices())
                                .then(argument("radius", DoubleArgumentType.doubleArg(
                                        Waypoint.MIN_REACH_RADIUS, Waypoint.MAX_REACH_RADIUS))
                                        .executes(ctx -> runSetGroupRadius(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                DoubleArgumentType.getDouble(ctx, "radius"))))))
                .then(literal("skipahead")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestions.suggestAllGroupIndices())
                                .executes(ctx -> runSetGroupSkipAhead(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "index"), "toggle"))
                                .then(argument("state", StringArgumentType.word())
                                        .suggests(suggestions.suggestToggleStates())
                                        .executes(ctx -> runSetGroupSkipAhead(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "state"))))))
                .then(literal("enable")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestions.suggestAllGroupIndices())
                                .executes(ctx -> runSetGroupEnabled(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "index"), true))))
                .then(literal("disable")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestions.suggestAllGroupIndices())
                                .executes(ctx -> runSetGroupEnabled(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "index"), false))))
                .then(literal("move")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestions.suggestAllGroupIndices())
                                .then(literal("up")
                                        .executes(ctx -> runMoveGroup(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"), -1)))
                                .then(literal("down")
                                        .executes(ctx -> runMoveGroup(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"), 1)))))
                .then(literal("colormode")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestions.suggestAllGroupIndices())
                                .then(argument("mode", StringArgumentType.word())
                                        .suggests(suggestions.suggestColorModes())
                                        .executes(ctx -> runSetGroupColorMode(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "mode"))))))
                .then(literal("color")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestions.suggestAllGroupIndices())
                                .then(argument("hex", StringArgumentType.word())
                                        .suggests(suggestions.suggestHexColors())
                                        .executes(ctx -> runSetGroupStaticColor(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                StringArgumentType.getString(ctx, "hex"))))))
                .then(literal("gradient")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestions.suggestAllGroupIndices())
                                .then(argument("start", StringArgumentType.word())
                                        .suggests(suggestions.suggestHexColors())
                                        .then(argument("end", StringArgumentType.word())
                                                .suggests(suggestions.suggestHexColors())
                                                .executes(ctx -> runSetGroupGradient(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "index"),
                                                        StringArgumentType.getString(ctx, "start"),
                                                        StringArgumentType.getString(ctx, "end")))))))
                .then(literal("delete")
                        .then(argument("index", IntegerArgumentType.integer(0))
                                .suggests(suggestions.suggestAllGroupIndices())
                                .executes(ctx -> runDeleteGroup(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "index"), false))
                                .then(literal("confirm")
                                        .executes(ctx -> runDeleteGroup(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"), true)))));
    }

    int runRenameGroup(FabricClientCommandSource src, int index, String name) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        group.setName(name == null ? "" : name.trim());
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.renamed", index, group.name()));
        return 1;
    }

    int runSetGroupZone(FabricClientCommandSource src, int index, String rawZone) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        Zone zone = resolveCommandZone(src, rawZone);
        if (zone == null) return 0;
        group.setZoneId(zone.id());
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.zone_set",
                index, group.name(), zone.displayName()));
        return 1;
    }

    int runSetGroupMode(FabricClientCommandSource src, int index, String rawMode) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        WaypointGroup.LoadMode mode = WaypointerRouteCommands.parseLoadMode(rawMode);
        if (group == null) return 0;
        if (mode == null) {
            error(src, Component.translatable("waypointer.command.error.mode"));
            return 0;
        }
        group.setLoadMode(mode);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.indexed_mode_set",
                index, mode.name().toLowerCase(Locale.ROOT)));
        return 1;
    }

    int runSetGroupRadius(FabricClientCommandSource src, int index, double radius) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        group.setDefaultRadius(radius);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.indexed_radius_set",
                index, String.format(Locale.ROOT, "%.1f", group.defaultRadius())));
        return 1;
    }

    int runSetGroupSkipAhead(FabricClientCommandSource src, int index, String rawState) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        Boolean state = parseToggleState(rawState, group.skipAheadEnabled());
        if (state == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.toggle_state"));
            return 0;
        }
        group.setSkipAheadEnabled(state);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.skip_ahead_set",
                index,
                Component.translatable(group.skipAheadEnabled()
                        ? "waypointer.common.on"
                        : "waypointer.common.off")));
        return 1;
    }

    int runSetGroupEnabled(FabricClientCommandSource src, int index, boolean enabled) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        group.setEnabled(enabled);
        manager.fireDataChanged();
        success(src, Component.translatable(
                enabled
                        ? "waypointer.command.route.enabled"
                        : "waypointer.command.route.disabled",
                index, group.name()));
        return 1;
    }

    int runMoveGroup(FabricClientCommandSource src, int index, int delta) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        if (group == null) return 0;
        if (!manager.moveGroupBy(group.id(), delta)) {
            error(src, Component.translatable("waypointer.command.route.move_blocked"));
            return 0;
        }
        int newIndex = manager.allGroupsList().indexOf(group);
        success(src, Component.translatable("waypointer.command.route.moved",
                group.name(), newIndex));
        return 1;
    }

    int runSetGroupColorMode(FabricClientCommandSource src, int index, String rawMode) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        WaypointGroup.GradientMode mode = parseGradientMode(rawMode);
        if (group == null) return 0;
        if (mode == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.color_mode"));
            return 0;
        }
        group.setGradientMode(mode);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.color_mode_set",
                index, rawMode.toLowerCase(Locale.ROOT)));
        return 1;
    }

    int runSetGroupStaticColor(FabricClientCommandSource src, int index, String rawColor) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        Integer color = WaypointerRouteCommands.parseRgb(rawColor);
        if (group == null) return 0;
        if (color == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.color_hex"));
            return 0;
        }
        group.setStaticColor(color);
        group.setGradientMode(WaypointGroup.GradientMode.STATIC);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.color_set", index, WaypointerRouteCommands.formatRgb(color)));
        return 1;
    }

    int runSetGroupGradient(FabricClientCommandSource src, int index,
                                    String rawStart, String rawEnd) {
        WaypointGroup group = groupAtIndexOrError(src, index);
        Integer start = WaypointerRouteCommands.parseRgb(rawStart);
        Integer end = WaypointerRouteCommands.parseRgb(rawEnd);
        if (group == null) return 0;
        if (start == null || end == null) {
            error(src, Component.translatable(
                    "waypointer.command.error.gradient_hex"));
            return 0;
        }
        group.setGradientStartColor(start);
        group.setGradientEndColor(end);
        group.setGradientMode(WaypointGroup.GradientMode.AUTO);
        manager.fireDataChanged();
        success(src, Component.translatable(
                "waypointer.command.route.gradient_set",
                index, WaypointerRouteCommands.formatRgb(start), WaypointerRouteCommands.formatRgb(end)));
        return 1;
    }

    WaypointGroup groupAtIndexOrError(FabricClientCommandSource src, int index) {
        List<WaypointGroup> all = manager.allGroupsList();
        if (index < 0 || index >= all.size()) {
            error(src, Component.translatable(
                    "waypointer.command.error.route_index_range",
                    index, all.size() - 1));
            return null;
        }
        return all.get(index);
    }

    Zone resolveCommandZone(FabricClientCommandSource src, String rawZone) {
        String cleaned = WaypointerShareCommands.stripQuotes(rawZone == null ? "" : rawZone).trim();
        if (cleaned.isEmpty()) {
            error(src, Component.translatable(
                    "waypointer.command.error.route_zone_usage"));
            return null;
        }
        if ("current".equalsIgnoreCase(cleaned)) {
            Zone current = manager.currentZone();
            if (current == null) {
                error(src, Component.translatable(
                        "waypointer.command.error.no_current_area"));
                return null;
            }
            return current;
        }
        return cleaned.indexOf(' ') >= 0 || cleaned.indexOf('\'') >= 0
                ? Zone.resolveFromDisplayName(cleaned)
                : Zone.fromId(cleaned);
    }

    static WaypointGroup.GradientMode parseGradientMode(String rawMode) {
        String mode = rawMode == null ? "" : rawMode.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "one", "static", "solid" -> WaypointGroup.GradientMode.STATIC;
            case "gradient", "auto" -> WaypointGroup.GradientMode.AUTO;
            case "manual" -> WaypointGroup.GradientMode.MANUAL;
            default -> null;
        };
    }

    static Boolean parseToggleState(String rawState, boolean current) {
        String state = rawState == null ? "toggle" : rawState.trim().toLowerCase(Locale.ROOT);
        return switch (state) {
            case "", "toggle", "flip" -> !current;
            case "on", "true", "yes", "1" -> true;
            case "off", "false", "no", "0" -> false;
            default -> null;
        };
    }

    int runCreateGroup(FabricClientCommandSource src, String name) {
        Zone zone = manager.currentZone() == null ? Zone.UNKNOWN : manager.currentZone();
        WaypointGroup g = WaypointGroup.create(name, zone.id(), config.skipAheadMechanicEnabled());
        manager.add(g);
        success(src, Component.translatable(
                "waypointer.command.route.created", name, zone.displayName()));
        return 1;
    }

    int runListGroups(FabricClientCommandSource src) {
        List<WaypointGroup> all = manager.allGroupsList();
        if (all.isEmpty()) {
            info(src, Component.translatable(
                    "waypointer.command.route.list_empty"));
            return 0;
        }
        info(src, Component.translatable(
                "waypointer.command.route.list_total", all.size()));
        for (int i = 0; i < all.size(); i++) {
            WaypointGroup g = all.get(i);
            info(src, Component.translatable(
                    g.enabled()
                            ? "waypointer.command.route.list_entry"
                            : "waypointer.command.route.list_entry_disabled",
                    i, g.name(), g.zoneId(), g.size()));
        }
        return all.size();
    }

    int runDeleteGroup(FabricClientCommandSource src, int index, boolean confirmed) {
        List<WaypointGroup> all = manager.allGroupsList();
        if (index < 0 || index >= all.size()) {
            error(src, Component.translatable(
                    "waypointer.command.error.route_index_range",
                    index, all.size() - 1));
            return 0;
        }
        WaypointGroup g = all.get(index);
        if (!confirmed) {
            warn(src, Component.translatable(
                    "waypointer.command.route.delete_confirm",
                    index, g.name(), g.size()));
            return 0;
        }
        manager.remove(g.id());
        success(src, Component.translatable(
                "waypointer.command.route.deleted", g.name()));
        return 1;
    }
}
