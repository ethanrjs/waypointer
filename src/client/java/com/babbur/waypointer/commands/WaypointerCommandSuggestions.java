package com.babbur.waypointer.commands;

import com.babbur.waypointer.dungeon.DungeonWaypointType;
import com.babbur.waypointer.i18n.Counts;
import com.babbur.waypointer.screen.RouteListPresentation;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.babbur.waypointer.chat.ChatImportCache;
import com.babbur.waypointer.config.Storage;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.RouteIds;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.placement.PlayerWaypointPlacement;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

final class WaypointerCommandSuggestions {

    private final ActiveGroupManager manager;
    private final Storage storage;
    private final WaypointerConfig config;
    private final ChatImportCache chatImportCache;

    WaypointerCommandSuggestions(ActiveGroupManager manager, Storage storage,
                                 WaypointerConfig config, ChatImportCache chatImportCache) {
        this.manager = manager;
        this.storage = storage;
        this.config = config;
        this.chatImportCache = chatImportCache;
    }

    SuggestionProvider<FabricClientCommandSource> suggestRoutes() {
        return (ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            if (RouteIds.looksLikeReference(typed)) {
                suggestIds(manager, builder, false);
                return builder.buildFuture();
            }
            List<WaypointGroup> routes = new RouteTargets(manager).listed();
            for (int i = 0; i < routes.size(); i++) {
                WaypointGroup route = routes.get(i);
                Component tip = Component.literal(route.name() + "  ")
                        .append(Counts.of("waypoint", route.size()));
                String id = RouteIds.routeId(route);
                if (typed.isEmpty() && id != null) builder.suggest(id, tip);
                if (typed.isEmpty() || Integer.toString(i + 1).startsWith(typed)) {
                    if (!typed.isEmpty() || i < 9) builder.suggest(Integer.toString(i + 1), tip);
                }
                if (!typed.isEmpty() && route.name().toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(route.name(), tip);
                }
            }
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestFolders(boolean quoteIfNeeded) {
        return (ctx, builder) -> {
            Zone zone = manager.currentZone();
            if (zone == null) return builder.buildFuture();
            for (var folder : manager.foldersForZone(zone.id())) {
                String value = quoteIfNeeded
                        ? StringArgumentType.escapeIfRequired(folder.name())
                        : folder.name();
                CommandHelpers.suggestText(builder, value, Component.literal(folder.name()));
            }
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestWaypointLabels() {
        return (ctx, builder) -> {
            if (RouteIds.looksLikeReference(builder.getRemaining())) {
                suggestIds(manager, builder, true);
            } else {
                suggestWaypointLabels(manager.targetRoute(), builder);
            }
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestMoveDestination() {
        return (ctx, builder) -> {
            String source = StringArgumentType.getString(ctx, "waypoint");
            WaypointGroup route = manager.targetRoute();
            if (RouteIds.looksLikeReference(source)) {
                var found = RouteTargets.waypointById(manager, source);
                route = found.ok() ? found.value().route() : null;
            }
            if (route == null) return builder.buildFuture();
            String typed = builder.getRemainingLowerCase();
            if (RouteIds.looksLikeReference(typed)) {
                int hash = typed.indexOf('#');
                String prefix = hash < 0 ? null : typed.substring(0, hash);
                if (prefix != null && manager.findRoute(RouteIds.parse(prefix)) != route) {
                    return builder.buildFuture();
                }
                for (int i = 0; i < route.size(); i++) {
                    String id = prefix == null ? RouteIds.waypointId(route, i)
                            : prefix + "#" + RouteTargets.label(route, i);
                    if (id != null && id.startsWith(typed)) {
                        builder.suggest(id, Component.literal(describeWaypoint(route.get(i))));
                    }
                }
            } else {
                suggestWaypointLabels(route, builder);
            }
            return builder.buildFuture();
        };
    }

    /** IDs for the last of several space- or comma-separated route and waypoint IDs. */
    SuggestionProvider<FabricClientCommandSource> suggestRouteIds() {
        return (ctx, builder) -> {
            String remaining = builder.getRemaining();
            int start = Math.max(remaining.lastIndexOf(' '), remaining.lastIndexOf(',')) + 1;
            SuggestionsBuilder last = builder.createOffset(builder.getStart() + start);
            suggestIds(manager, last, false);
            return last.buildFuture();
        };
    }

    /**
     * Route IDs that start with what was typed, or, once a route ID and {@code #} are typed, that
     * route's waypoint IDs. With {@code forWaypoint} set, route IDs end in {@code #} to continue.
     */
    static int suggestIds(ActiveGroupManager manager, SuggestionsBuilder builder, boolean forWaypoint) {
        String typed = builder.getRemainingLowerCase();
        int hash = typed.indexOf('#');
        int count = 0;
        if (hash >= 0) {
            RouteIds.Reference reference = RouteIds.parse(typed.substring(0, hash));
            WaypointGroup route = reference == null ? null : manager.findRoute(reference);
            if (route == null) return 0;
            for (int i = 0; i < route.size(); i++) {
                String id = typed.substring(0, hash) + "#" + RouteTargets.label(route, i);
                if (!id.startsWith(typed)) continue;
                builder.suggest(id, Component.literal(describeWaypoint(route.get(i))));
                count++;
            }
            return count;
        }
        for (WaypointGroup route : manager.allGroupsList()) {
            String id = RouteIds.routeId(route);
            if (id == null || !id.startsWith(typed)) continue;
            builder.suggest(forWaypoint ? id + "#" : id, Component.literal(route.name() + "  ")
                    .append(Counts.of("waypoint", route.size())));
            count++;
        }
        return count;
    }

    static int suggestWaypointLabels(WaypointGroup route, SuggestionsBuilder builder) {
        if (route == null) return 0;
        String typed = builder.getRemainingLowerCase();
        int count = 0;
        for (int i = 0; i < route.size(); i++) {
            String label = RouteTargets.label(route, i);
            if (!label.startsWith(typed)) continue;
            builder.suggest(label, Component.literal(describeWaypoint(route.get(i))));
            count++;
        }
        return count;
    }

    SuggestionProvider<FabricClientCommandSource> suggestWaypointFlags() {
        return (ctx, builder) -> {
            for (WaypointerWaypointCommands.Flag flag : WaypointerWaypointCommands.Flag.values()) {
                CommandHelpers.suggestText(builder, flag.id, Component.translatable(flag.labelKey));
            }
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestDungeonTypes() {
        return (ctx, builder) -> {
            for (var type : DungeonWaypointType.values()) {
                CommandHelpers.suggestText(builder, type.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                        Component.translatable(WaypointerWaypointCommands.typeKey(type)));
            }
            CommandHelpers.suggestText(builder, "none",
                    Component.translatable("waypointer.screen.group_edit.control.types.none"));
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestChatHandles() {
        return (ctx, builder) -> {
            List<String> handles = chatImportCache.handles();
            String token = builder.getRemainingLowerCase();
            for (String h : handles) {
                if (h.toLowerCase(Locale.ROOT).startsWith(token)) {
                    builder.suggest(h, Component.translatable(
                            "waypointer.command.suggestion.cached_import"));
                }
            }
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestPlayerCoord(Axis axis) {
        return (ctx, builder) -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return builder.buildFuture();
            PlayerWaypointPlacement.BlockPosition pos = PlayerWaypointPlacement.fromPlayer(
                    player.getX(), player.getY(), player.getZ(), config);
            int value = switch (axis) {
                case X -> pos.x();
                case Y -> pos.y();
                case Z -> pos.z();
            };
            String raw = Integer.toString(value);
            if (raw.startsWith(builder.getRemaining())) {
                builder.suggest(value, playerCoordSuggestionLabel(axis));
            }
            return builder.buildFuture();
        };
    }

    Component playerCoordSuggestionLabel(Axis axis) {
        if (axis == Axis.Y && config.placeNewWaypointsBelowPlayer()) {
            return Component.translatable(
                    "waypointer.command.suggestion.player_y_below");
        }
        return Component.translatable(
                "waypointer.command.suggestion.player_axis",
                axis.name().toLowerCase(Locale.ROOT));
    }

    SuggestionProvider<FabricClientCommandSource> suggestImportPayloads() {
        return (ctx, builder) -> {
            for (String handle : chatImportCache.handles()) {
                if (handle.toLowerCase(Locale.ROOT)
                        .startsWith(builder.getRemainingLowerCase())) {
                    builder.suggest(handle, Component.translatable(
                            "waypointer.command.suggestion.cached_chat_import"));
                }
            }
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestImportFiles() {
        return (ctx, builder) -> {
            String file = storage.file().toString();
            if (file.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(file, Component.translatable(
                        "waypointer.command.suggestion.storage_file"));
            }
            Path parent = storage.file().getParent();
            if (parent != null
                    && parent.toString().toLowerCase(Locale.ROOT)
                            .startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(parent.toString(), Component.translatable(
                        "waypointer.command.suggestion.config_folder"));
            }
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestNewRouteNames() {
        return (ctx, builder) -> {
            Zone zone = manager.currentZone();
            if (zone != null) {
                CommandHelpers.suggestText(builder, RouteListPresentation
                                .nextRouteName(manager.groupsForZone(zone.id())),
                        Component.translatable("waypointer.command.suggest.route_name"));
            }
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestLoadModes() {
        return (ctx, builder) -> {
            CommandHelpers.suggestText(builder, "sequence", Component.translatable("waypointer.screen.group_edit.mode.sequence"));
            CommandHelpers.suggestText(builder, "static", Component.translatable("waypointer.screen.group_edit.mode.static"));
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestColorModes() {
        return (ctx, builder) -> {
            for (String mode : List.of("color", "gradient", "one", "paint")) {
                CommandHelpers.suggestText(builder, mode, Component.translatable(
                        "waypointer.screen.group_edit.color_mode.tooltip." + mode));
            }
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestToggleStates() {
        return (ctx, builder) -> {
            CommandHelpers.suggestText(builder, "toggle", Component.translatable("waypointer.command.suggest.toggle.toggle"));
            CommandHelpers.suggestText(builder, "on", Component.translatable("waypointer.command.suggest.toggle.on"));
            CommandHelpers.suggestText(builder, "off", Component.translatable("waypointer.command.suggest.toggle.off"));
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestZoneTargets() {
        return (ctx, builder) -> {
            Zone current = manager.currentZone();
            if (current != null) {
                CommandHelpers.suggestText(builder, "current", Component.translatable("waypointer.command.suggest.zone.current", current.displayName()));
                CommandHelpers.suggestText(builder, current.id(), current.displayName());
            }
            for (String zoneId : manager.knownZoneIds()) {
                CommandHelpers.suggestText(builder, zoneId, Zone.fromId(zoneId).displayName());
            }
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestHexColors() {
        return (ctx, builder) -> {
            CommandHelpers.suggestText(builder, "4FE05A", Component.translatable("waypointer.command.suggest.color.green"));
            CommandHelpers.suggestText(builder, "00BFFF", Component.translatable("waypointer.command.suggest.color.cool"));
            CommandHelpers.suggestText(builder, "FF3040", Component.translatable("waypointer.command.suggest.color.hot"));
            return builder.buildFuture();
        };
    }

    static String describeWaypoint(Waypoint w) {
        String coords = w.x() + ", " + w.y() + ", " + w.z();
        return w.hasName() ? w.name() + "  " + coords : coords;
    }

    enum Axis { X, Y, Z }
}
