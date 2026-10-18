package com.babbur.waypointer.commands;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteFolder;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.DungeonRoomRouteLibrary;
import com.babbur.waypointer.screen.GroupEditScreen;
import com.babbur.waypointer.screen.RoutePublishScreen;
import com.babbur.waypointer.screen.SettingsScreen;
import com.babbur.waypointer.screen.WaypointerScreen;
import com.babbur.waypointer.screen.WaypointerZoneCatalog;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.List;
import java.util.Locale;

import static com.babbur.waypointer.commands.CommandHelpers.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

final class WaypointerRouteCommands {

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final WaypointerCommandSuggestions suggestions;
    private final RouteTargets targets;

    WaypointerRouteCommands(ActiveGroupManager manager, WaypointerConfig config,
                            WaypointerCommandSuggestions suggestions) {
        this.manager = manager;
        this.config = config;
        this.suggestions = suggestions;
        this.targets = new RouteTargets(manager);
    }


    LiteralArgumentBuilder<FabricClientCommandSource> listCommand() {
        return literal("list").executes(ctx -> runList(ctx.getSource()));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> targetCommand() {
        return literal("target")
                .executes(ctx -> runShowTarget(ctx.getSource()))
                .then(literal("auto").executes(ctx -> runTargetAuto(ctx.getSource())))
                .then(argument("route", StringArgumentType.greedyString())
                        .suggests(suggestions.suggestRoutes())
                        .executes(ctx -> runTarget(ctx.getSource(),
                                StringArgumentType.getString(ctx, "route"))));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> visibilityCommand(String name, boolean shown) {
        LiteralArgumentBuilder<FabricClientCommandSource> command = literal(name)
                .then(literal("all").executes(ctx -> runSetAllShown(ctx.getSource(), shown)))
                .then(argument("route", StringArgumentType.greedyString())
                        .suggests(suggestions.suggestRoutes())
                        .executes(ctx -> runSetShown(ctx.getSource(),
                                StringArgumentType.getString(ctx, "route"), shown)));
        return shown ? command : command.executes(ctx -> runSetShown(ctx.getSource(), null, false));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> routeCommand() {
        return literal("route")
                .executes(ctx -> runShowTarget(ctx.getSource()))
                .then(literal("new")
                        .then(argument("name", StringArgumentType.greedyString())
                                .suggests(suggestions.suggestNewRouteNames())
                                .executes(ctx -> runCreate(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(literal("rename")
                        .then(argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> runRename(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(literal("delete")
                        .executes(ctx -> runDelete(ctx.getSource(), false))
                        .then(literal("confirm").executes(ctx -> runDelete(ctx.getSource(), true))))
                .then(literal("mode")
                        .then(argument("mode", StringArgumentType.word())
                                .suggests(suggestions.suggestLoadModes())
                                .executes(ctx -> runSetMode(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "mode")))))
                .then(literal("radius")
                        .then(argument("blocks", DoubleArgumentType.doubleArg(
                                Waypoint.MIN_REACH_RADIUS, Waypoint.MAX_REACH_RADIUS))
                                .executes(ctx -> runSetRadius(ctx.getSource(),
                                        DoubleArgumentType.getDouble(ctx, "blocks")))))
                .then(literal("icon")
                        .then(literal("none").executes(ctx -> runSetIcon(ctx.getSource(), "", false)))
                        .then(argument("item", StringArgumentType.word())
                                .suggests(WaypointerRouteCommands::suggestItems)
                                .executes(ctx -> runSetIcon(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item"), false))
                                .then(literal("glint").executes(ctx -> runSetIcon(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item"), true)))))
                .then(literal("skipahead")
                        .executes(ctx -> runSetSkipAhead(ctx.getSource(), "toggle"))
                        .then(argument("state", StringArgumentType.word())
                                .suggests(suggestions.suggestToggleStates())
                                .executes(ctx -> runSetSkipAhead(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "state")))))
                .then(literal("colormode")
                        .then(argument("mode", StringArgumentType.word())
                                .suggests(suggestions.suggestColorModes())
                                .executes(ctx -> runSetColorMode(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "mode")))))
                .then(literal("color")
                        .then(argument("hex", StringArgumentType.word())
                                .suggests(suggestions.suggestHexColors())
                                .executes(ctx -> runSetColor(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "hex")))))
                .then(literal("gradient")
                        .then(argument("start", StringArgumentType.word())
                                .suggests(suggestions.suggestHexColors())
                                .then(argument("end", StringArgumentType.word())
                                        .suggests(suggestions.suggestHexColors())
                                        .executes(ctx -> runSetGradient(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "start"),
                                                StringArgumentType.getString(ctx, "end"))))))
                .then(literal("zone")
                        .then(argument("zone", StringArgumentType.greedyString())
                                .suggests(suggestions.suggestZoneTargets())
                                .executes(ctx -> runSetZone(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "zone")))))
                .then(literal("move")
                        .then(literal("up").executes(ctx -> runMove(ctx.getSource(), -1)))
                        .then(literal("down").executes(ctx -> runMove(ctx.getSource(), 1))))
                .then(literal("folder")
                        .then(literal("none").executes(ctx -> runSetFolder(ctx.getSource(), null)))
                        .then(argument("folder", StringArgumentType.greedyString())
                                .suggests(suggestions.suggestFolders(false))
                                .executes(ctx -> runSetFolder(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "folder")))));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> folderCommand() {
        return literal("folder")
                .then(literal("new")
                        .then(argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> runCreateFolder(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(literal("rename")
                        .then(argument("folder", StringArgumentType.string())
                                .suggests(suggestions.suggestFolders(true))
                                .then(argument("name", StringArgumentType.greedyString())
                                        .executes(ctx -> runRenameFolder(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "folder"),
                                                StringArgumentType.getString(ctx, "name"))))))
                .then(literal("color")
                        .then(argument("folder", StringArgumentType.string())
                                .suggests(suggestions.suggestFolders(true))
                                .then(argument("hex", StringArgumentType.word())
                                        .suggests(suggestions.suggestHexColors())
                                        .executes(ctx -> runFolderColor(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "folder"),
                                                StringArgumentType.getString(ctx, "hex"))))))
                .then(literal("delete")
                        .then(argument("folder", StringArgumentType.greedyString())
                                .suggests(suggestions.suggestFolders(false))
                                .executes(ctx -> runDeleteFolder(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "folder")))));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> editCommand() {
        return literal("edit").executes(ctx -> runOpenEditor(ctx.getSource()));
    }

    LiteralArgumentBuilder<FabricClientCommandSource> appearanceCommand(WaypointerWaypointCommands waypoints) {
        return literal("appearance")
                .executes(ctx -> runOpenAppearance(ctx.getSource()))
                .then(literal("route")
                        .then(argument("route", StringArgumentType.greedyString())
                                .suggests(suggestions.suggestRoutes())
                                .executes(ctx -> runOpenRouteAppearance(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "route")))))
                .then(literal("waypoint")
                        .then(argument("waypoint", StringArgumentType.word())
                                .suggests(suggestions.suggestWaypointLabels())
                                .executes(ctx -> waypoints.runOpenAppearance(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "waypoint")))))
                .then(literal("zone")
                        .executes(ctx -> runOpenZoneAppearance(ctx.getSource(), null))
                        .then(argument("zone", StringArgumentType.greedyString())
                                .suggests(this::suggestAppearanceZones)
                                .executes(ctx -> runOpenZoneAppearance(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "zone")))))
                .then(literal("everywhere").executes(ctx -> {
                    SettingsScreen.openGlobalAppearance(config);
                    return 1;
                }));
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestAppearanceZones(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String key : SettingsScreen.appearanceZoneKeys(manager, config)) {
            if (key.startsWith(typed)) {
                CommandHelpers.suggestText(builder, key, SettingsScreen.appearanceZoneLabel(key));
            }
        }
        return builder.buildFuture();
    }

    LiteralArgumentBuilder<FabricClientCommandSource> publishCommand() {
        return literal("publish").executes(ctx -> runOpenPublish(ctx.getSource()));
    }


    int runList(FabricClientCommandSource src) {
        List<WaypointGroup> routes = targets.listed();
        if (routes.isEmpty()) {
            info(src, Component.translatable("waypointer.command.list.none", targets.zoneName()));
            return 0;
        }
        WaypointGroup target = manager.targetRoute();
        info(src, Component.translatable("waypointer.command.list.header", targets.zoneName())
                .withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < routes.size(); i++) {
            info(src, listRow(i + 1, routes.get(i), routes.get(i) == target));
        }
        return routes.size();
    }

    private Component listRow(int number, WaypointGroup route, boolean target) {
        MutableComponent row = Component.literal(target ? " ▶ " : "   ")
                .withStyle(ChatFormatting.YELLOW);
        row.append(Component.literal(number + ". ").withStyle(ChatFormatting.DARK_GRAY));
        Style nameStyle = Style.EMPTY
                .withColor(route.enabled() ? ChatFormatting.WHITE : ChatFormatting.GRAY)
                .withClickEvent(new ClickEvent.RunCommand("/wp target " + number))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable(target
                        ? "waypointer.command.list.target_hover"
                        : "waypointer.command.list.pick_hover")));
        row.append(Component.literal(route.name()).withStyle(nameStyle));
        row.append(Component.literal("  ").append(com.babbur.waypointer.i18n.Counts.of(
                "waypoint", route.size())).withStyle(ChatFormatting.DARK_GRAY));
        if (!route.enabled()) {
            row.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("waypointer.command.list.hidden")
                            .withStyle(ChatFormatting.DARK_GRAY));
        }
        return row;
    }

    int runShowTarget(FabricClientCommandSource src) {
        RouteTargets.Target target = targets.target();
        if (target == null) {
            info(src, Component.translatable("waypointer.command.target.none", targets.zoneName()));
            return 0;
        }
        String key = switch (target.reason()) {
            case PINNED -> "waypointer.command.target.pinned";
            case EDITED -> "waypointer.command.target.edited";
            case FIRST -> "waypointer.command.target.first";
        };
        info(src, Component.translatable(key, routeName(target.route())));
        return 1;
    }

    int runTarget(FabricClientCommandSource src, String reference) {
        RouteTargets.Lookup<WaypointGroup> found = targets.route(reference);
        if (!found.ok()) {
            error(src, found.error());
            return 0;
        }
        WaypointGroup route = found.value();
        if (!route.enabled()) {
            error(src, Component.translatable("waypointer.command.target.hidden", route.name()));
            return 0;
        }
        manager.setRecordingTarget(route);
        if (manager.recordingTarget() != route) {
            error(src, Component.translatable("waypointer.command.target.unavailable", route.name()));
            return 0;
        }
        routeSuccess(src, route, Component.translatable("waypointer.command.target.set"));
        return 1;
    }

    int runTargetAuto(FabricClientCommandSource src) {
        manager.setRecordingTarget(null);
        WaypointGroup now = manager.targetRoute();
        if (now == null) {
            success(src, Component.translatable("waypointer.command.target.auto"));
        } else {
            routeSuccess(src, now, Component.translatable("waypointer.command.target.auto_now"));
        }
        return 1;
    }


    int runCreate(FabricClientCommandSource src, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            error(src, Component.translatable("waypointer.command.error.name_missing"));
            return 0;
        }
        Zone zone = manager.currentZone() == null ? Zone.UNKNOWN : manager.currentZone();
        WaypointGroup route = WaypointGroup.create(name, zone.id(), config.skipAheadMechanicEnabled());
        boolean pinned = manager.recordingTarget() != null;
        manager.add(route);
        if (pinned) manager.setRecordingTarget(route);
        manager.noteRouteEdited(route);
        routeSuccess(src, route, Component.translatable(
                "waypointer.command.route.created_here", zone.displayName()));
        return 1;
    }

    int runRename(FabricClientCommandSource src, String rawName) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            error(src, Component.translatable("waypointer.command.error.name_missing"));
            return 0;
        }
        String before = route.name();
        route.setName(name);
        changed(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.route.renamed_from", before));
        return 1;
    }

    int runDelete(FabricClientCommandSource src, boolean confirmed) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        if (!confirmed) {
            warn(src, Component.translatable("waypointer.command.route.delete_ask",
                    routeName(route), com.babbur.waypointer.i18n.Counts.of("waypoint", route.size())));
            return 0;
        }
        manager.remove(route.id());
        success(src, Component.translatable("waypointer.command.route.deleted", route.name()));
        return 1;
    }

    int runSetShown(FabricClientCommandSource src, String reference, boolean shown) {
        WaypointGroup route;
        if (reference == null) {
            route = targetOrError(src);
        } else {
            RouteTargets.Lookup<WaypointGroup> found = targets.route(reference);
            if (!found.ok()) error(src, found.error());
            route = found.value();
        }
        if (route == null) return 0;
        setShown(route, shown);
        manager.fireDataChanged();
        routeSuccess(src, route, Component.translatable(shown
                ? "waypointer.command.route.shown"
                : "waypointer.command.route.hidden"));
        return 1;
    }

    int runSetAllShown(FabricClientCommandSource src, boolean shown) {
        int count = 0;
        for (WaypointGroup route : targets.listed()) {
            if (route.enabled() == shown) continue;
            setShown(route, shown);
            count++;
        }
        if (count > 0) manager.fireDataChanged();
        success(src, Component.translatable(shown
                        ? "waypointer.command.route.shown_all"
                        : "waypointer.command.route.hidden_all",
                com.babbur.waypointer.i18n.Counts.of("route", count), targets.zoneName()));
        return count;
    }

    private void setShown(WaypointGroup route, boolean shown) {
        if (route.routeKind() == WaypointGroup.RouteKind.DUNGEON) {
            DungeonRoomRouteLibrary.setRouteEnabled(manager, WaypointerClient.dungeonConfig(), route, shown);
        } else {
            route.setEnabled(shown);
        }
    }

    int runSetMode(FabricClientCommandSource src, String rawMode) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        WaypointGroup.LoadMode mode = parseLoadMode(rawMode);
        if (mode == null) {
            error(src, Component.translatable("waypointer.command.error.mode_value", rawMode));
            return 0;
        }
        route.setLoadMode(mode);
        changed(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.route.mode",
                Component.translatable(mode == WaypointGroup.LoadMode.STATIC
                        ? "waypointer.screen.group_edit.mode.static"
                        : "waypointer.screen.group_edit.mode.sequence")));
        return 1;
    }

    int runSetRadius(FabricClientCommandSource src, double radius) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        route.setDefaultRadius(radius);
        changed(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.route.radius",
                formatBlocks(route.defaultRadius())));
        return 1;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestItems(
            com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (net.minecraft.resources.Identifier id : net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()) {
            String shown = id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
            if (!shown.equals("air") && (shown.startsWith(typed) || id.toString().startsWith(typed))) {
                builder.suggest(shown);
            }
        }
        return builder.buildFuture();
    }

    int runSetIcon(FabricClientCommandSource src, String rawItem, boolean glint) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        if (rawItem.isEmpty()) {
            route.setIcon(com.babbur.waypointer.core.RouteIcon.NONE);
            changed(route);
            routeSuccess(src, route, Component.translatable("waypointer.command.route.icon_none"));
            return 1;
        }
        String spec = com.babbur.waypointer.core.RouteIcon.of(rawItem, glint);
        var item = spec.isEmpty() ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getOptional(net.minecraft.resources.Identifier.tryParse(
                        com.babbur.waypointer.core.RouteIcon.itemId(spec))).orElse(null);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            error(src, Component.translatable("waypointer.command.error.icon_value", rawItem));
            return 0;
        }
        route.setIcon(spec);
        changed(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.route.icon",
                new net.minecraft.world.item.ItemStack(item).getHoverName()));
        return 1;
    }

    int runSetSkipAhead(FabricClientCommandSource src, String rawState) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        Boolean state = parseToggleState(rawState, route.skipAheadEnabled());
        if (state == null) {
            error(src, Component.translatable("waypointer.command.error.state_value", rawState));
            return 0;
        }
        route.setSkipAheadEnabled(state);
        changed(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.route.skip_ahead",
                Component.translatable(state ? "options.on" : "options.off")));
        return 1;
    }

    int runSetColorMode(FabricClientCommandSource src, String rawMode) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        String mode = rawMode == null ? "" : rawMode.trim().toLowerCase(Locale.ROOT);
        String labelKey;
        switch (mode) {
            case "color", "manual" -> {
                route.setPaintEnabled(false);
                route.setGradientMode(WaypointGroup.GradientMode.MANUAL);
                labelKey = "waypointer.screen.group_edit.color_mode.color";
            }
            case "gradient", "auto" -> {
                route.setPaintEnabled(false);
                route.setGradientMode(WaypointGroup.GradientMode.AUTO);
                labelKey = "waypointer.screen.group_edit.color_mode.gradient";
            }
            case "one", "static", "solid" -> {
                route.setPaintEnabled(false);
                route.setGradientMode(WaypointGroup.GradientMode.STATIC);
                labelKey = "waypointer.screen.group_edit.color_mode.one";
            }
            case "paint" -> {
                route.setPaintEnabled(true);
                labelKey = "waypointer.screen.group_edit.color_mode.paint";
            }
            default -> {
                error(src, Component.translatable("waypointer.command.error.color_mode_value", rawMode));
                return 0;
            }
        }
        changed(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.route.color_mode",
                Component.translatable(labelKey)));
        return 1;
    }

    int runSetColor(FabricClientCommandSource src, String rawColor) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        Integer color = parseRgb(rawColor);
        if (color == null) {
            error(src, Component.translatable("waypointer.command.error.hex_value", rawColor));
            return 0;
        }
        route.setPaintEnabled(false);
        route.setStaticColor(color);
        route.setGradientMode(WaypointGroup.GradientMode.STATIC);
        changed(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.route.one_color",
                formatRgb(color)));
        return 1;
    }

    int runSetGradient(FabricClientCommandSource src, String rawStart, String rawEnd) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        Integer start = parseRgb(rawStart);
        Integer end = parseRgb(rawEnd);
        if (start == null || end == null) {
            error(src, Component.translatable("waypointer.command.error.hex_value",
                    start == null ? rawStart : rawEnd));
            return 0;
        }
        route.setPaintEnabled(false);
        route.setGradientStartColor(start);
        route.setGradientEndColor(end);
        route.setGradientMode(WaypointGroup.GradientMode.AUTO);
        changed(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.route.gradient",
                formatRgb(start), formatRgb(end)));
        return 1;
    }

    int runSetZone(FabricClientCommandSource src, String rawZone) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        Zone zone = resolveZone(src, rawZone);
        if (zone == null) return 0;
        if (!WaypointerZoneCatalog.retargetRoute(route, zone.id())) {
            error(src, Component.translatable("waypointer.command.error.zone_move", route.name(),
                    zone.displayName()));
            return 0;
        }
        manager.fireDataChangedFor(route);
        routeSuccess(src, route, Component.translatable("waypointer.command.route.zone",
                WaypointerZoneCatalog.displayZoneLabel(route.zoneId())));
        return 1;
    }

    int runMove(FabricClientCommandSource src, int delta) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        if (!manager.moveGroupBy(route.id(), delta)) {
            error(src, Component.translatable("waypointer.command.route.move_blocked"));
            return 0;
        }
        manager.fireDataChanged();
        routeSuccess(src, route, Component.translatable("waypointer.command.route.position",
                targets.number(route)));
        return 1;
    }

    int runSetFolder(FabricClientCommandSource src, String reference) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        if (reference == null) {
            if (!manager.removeGroupFromFolder(route.id())) {
                error(src, Component.translatable("waypointer.command.route.no_folder"));
                return 0;
            }
            manager.fireDataChanged();
            routeSuccess(src, route, Component.translatable("waypointer.command.route.folder_cleared"));
            return 1;
        }
        RouteTargets.Lookup<RouteFolder> folder = targets.folder(reference);
        if (!folder.ok()) {
            error(src, folder.error());
            return 0;
        }
        if (!manager.assignGroupToFolder(route.id(), folder.value().id())) {
            error(src, Component.translatable("waypointer.command.error.folder_route", route.name()));
            return 0;
        }
        manager.fireDataChanged();
        routeSuccess(src, route, Component.translatable("waypointer.command.route.folder_set",
                folder.value().name()));
        return 1;
    }

    LiteralArgumentBuilder<FabricClientCommandSource> clearCommand() {
        return literal("clear")
                .executes(ctx -> runClearZone(ctx.getSource(), false))
                .then(literal("confirm").executes(ctx -> runClearZone(ctx.getSource(), true)));
    }

    int runClearZone(FabricClientCommandSource src, boolean confirmed) {
        Zone zone = manager.currentZone();
        if (zone == null) {
            error(src, Component.translatable("waypointer.command.error.no_zone"));
            return 0;
        }
        List<WaypointGroup> here = manager.groupsForZone(zone.id());
        if (here.isEmpty()) {
            info(src, Component.translatable("waypointer.command.clear.empty", zone.displayName()));
            return 0;
        }
        if (!confirmed) {
            warn(src, Component.translatable("waypointer.command.clear.ask",
                    com.babbur.waypointer.i18n.Counts.of("route", here.size()), zone.displayName()));
            return 0;
        }
        int cleared = clearCurrentZoneGroups(manager, true);
        success(src, Component.translatable("waypointer.command.clear.done",
                com.babbur.waypointer.i18n.Counts.of("route", cleared), zone.displayName()));
        return cleared;
    }

    static int clearCurrentZoneGroups(ActiveGroupManager manager, boolean confirmed) {
        Zone zone = manager.currentZone();
        if (!confirmed || zone == null) return 0;
        List<WaypointGroup> here = List.copyOf(manager.groupsForZone(zone.id()));
        for (WaypointGroup group : here) manager.remove(group.id());
        return here.size();
    }


    int runCreateFolder(FabricClientCommandSource src, String rawName) {
        String name = RouteTargets.unquote(rawName);
        Zone zone = manager.currentZone();
        if (zone == null || name.isEmpty()) {
            error(src, Component.translatable(zone == null
                    ? "waypointer.command.error.no_zone"
                    : "waypointer.command.error.name_missing"));
            return 0;
        }
        RouteFolder folder = manager.createFolder(name, zone.id(), List.of());
        if (folder == null) {
            error(src, Component.translatable("waypointer.command.error.folder_create", name));
            return 0;
        }
        manager.fireDataChanged();
        success(src, Component.translatable("waypointer.command.folder.created", folder.name(),
                zone.displayName()));
        return 1;
    }

    int runRenameFolder(FabricClientCommandSource src, String reference, String rawName) {
        RouteTargets.Lookup<RouteFolder> folder = targets.folder(reference);
        if (!folder.ok()) {
            error(src, folder.error());
            return 0;
        }
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || !manager.renameFolder(folder.value().id(), name)) {
            error(src, Component.translatable("waypointer.command.error.name_missing"));
            return 0;
        }
        manager.fireDataChanged();
        success(src, Component.translatable("waypointer.command.folder.renamed",
                folder.value().name(), name));
        return 1;
    }

    int runFolderColor(FabricClientCommandSource src, String reference, String rawColor) {
        RouteTargets.Lookup<RouteFolder> folder = targets.folder(reference);
        if (!folder.ok()) {
            error(src, folder.error());
            return 0;
        }
        Integer color = parseRgb(rawColor);
        if (color == null) {
            error(src, Component.translatable("waypointer.command.error.hex_value", rawColor));
            return 0;
        }
        manager.setFolderColor(folder.value().id(), color);
        manager.fireDataChanged();
        success(src, Component.translatable("waypointer.command.folder.color",
                folder.value().name(), formatRgb(color)));
        return 1;
    }

    int runDeleteFolder(FabricClientCommandSource src, String reference) {
        RouteTargets.Lookup<RouteFolder> folder = targets.folder(reference);
        if (!folder.ok()) {
            error(src, folder.error());
            return 0;
        }
        if (!manager.deleteFolder(folder.value().id())) {
            error(src, Component.translatable("waypointer.command.error.folder_delete",
                    folder.value().name()));
            return 0;
        }
        manager.fireDataChanged();
        success(src, Component.translatable("waypointer.command.folder.deleted", folder.value().name()));
        return 1;
    }


    int runOpenEditor(FabricClientCommandSource src) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> MinecraftCompat.setScreen(client, new GroupEditScreen(
                new WaypointerScreen(manager, config), manager, config, route)));
        return 1;
    }

    int runOpenAppearance(FabricClientCommandSource src) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        if (!SettingsScreen.openRouteAppearance(manager, config, route)) {
            error(src, Component.translatable("waypointer.screen.group_edit.menu.appearance.room_copy"));
            return 0;
        }
        return 1;
    }

    int runOpenRouteAppearance(FabricClientCommandSource src, String reference) {
        RouteTargets.Lookup<WaypointGroup> found = targets.route(reference);
        if (!found.ok()) {
            error(src, found.error());
            return 0;
        }
        if (!SettingsScreen.openRouteAppearance(manager, config, found.value())) {
            error(src, Component.translatable("waypointer.screen.group_edit.menu.appearance.room_copy"));
            return 0;
        }
        return 1;
    }

    int runOpenZoneAppearance(FabricClientCommandSource src, String reference) {
        List<String> keys = SettingsScreen.appearanceZoneKeys(manager, config);
        String key = null;
        if (reference == null || reference.isBlank()) {
            Zone zone = manager.currentZone();
            List<String> own = zone == null ? List.of()
                    : com.babbur.waypointer.config.WaypointStyles.zoneKeys(zone.id());
            if (!own.isEmpty() && keys.contains(own.getLast())) key = own.getLast();
            else if (!own.isEmpty() && keys.contains(own.getFirst())) key = own.getFirst();
        } else {
            String wanted = reference.strip().toLowerCase(Locale.ROOT);
            for (String candidate : keys) {
                if (candidate.equals(wanted) || candidate.equals(wanted.replace(' ', '_'))
                        || SettingsScreen.appearanceZoneLabel(candidate).getString().toLowerCase(Locale.ROOT).equals(wanted)) {
                    key = candidate;
                    break;
                }
            }
        }
        if (key == null) {
            error(src, Component.translatable("waypointer.command.error.appearance_zone",
                    reference == null || reference.isBlank() ? targets.zoneName() : Component.literal(reference)));
            return 0;
        }
        SettingsScreen.openZoneAppearance(config, key);
        return 1;
    }

    int runOpenPublish(FabricClientCommandSource src) {
        WaypointGroup route = targetOrError(src);
        if (route == null) return 0;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> RoutePublishScreen.open(null, config, route));
        return 1;
    }


    WaypointGroup targetOrError(FabricClientCommandSource src) {
        RouteTargets.Lookup<WaypointGroup> target = targets.targetOrError();
        if (!target.ok()) error(src, target.error());
        return target.value();
    }

    void changed(WaypointGroup route) {
        manager.noteRouteEdited(route);
        manager.fireDataChanged();
    }

    private Zone resolveZone(FabricClientCommandSource src, String rawZone) {
        String cleaned = RouteTargets.unquote(rawZone);
        if (cleaned.isEmpty() || "current".equalsIgnoreCase(cleaned)) {
            Zone current = manager.currentZone();
            if (current == null) error(src, Component.translatable("waypointer.command.error.no_zone"));
            return current;
        }
        Zone zone = cleaned.indexOf(' ') >= 0 || cleaned.indexOf('\'') >= 0
                ? Zone.resolveFromDisplayName(cleaned)
                : Zone.fromId(cleaned);
        if (zone == null || Zone.UNKNOWN.equals(zone)) {
            error(src, Component.translatable("waypointer.command.error.zone_unknown", cleaned));
            return null;
        }
        return zone;
    }

    static String formatBlocks(double blocks) {
        return String.format(Locale.ROOT, "%.1f", blocks);
    }

    static WaypointGroup.LoadMode parseLoadMode(String rawMode) {
        String mode = rawMode == null ? "" : rawMode.trim().toLowerCase(Locale.ROOT).replace("-", "");
        return switch (mode) {
            case "all", "showall", "static" -> WaypointGroup.LoadMode.STATIC;
            case "step", "stepbystep", "sequence", "sequenced", "seq" -> WaypointGroup.LoadMode.SEQUENCE;
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
}
