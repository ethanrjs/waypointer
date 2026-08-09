package com.babbur.waypointer.commands;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.chat.ChatImportCache;
import com.babbur.waypointer.config.Storage;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.placement.PlayerWaypointPlacement;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    SuggestionProvider<FabricClientCommandSource> suggestActiveGroupIndices() {
        return (ctx, builder) -> {
            WaypointGroup g = manager.firstActiveGroup();
            if (g == null) return builder.buildFuture();
            String prefix = builder.getRemaining();
            for (int index = 0; index < g.size(); index++) {
                int number = index + 1;
                if (Integer.toString(number).startsWith(prefix)) {
                    builder.suggest(number, Component.literal(activeGroupIndexTooltip(g, index)));
                }
            }
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestSkipTargets() {
        return (ctx, builder) -> {
            suggestSkipTargets(manager.activeGroups(), builder);
            return builder.buildFuture();
        };
    }

    static int suggestSkipTargets(List<WaypointGroup> groups, SuggestionsBuilder builder) {
        String prefix = builder.getRemainingLowerCase();
        Set<String> suggested = new LinkedHashSet<>();
        for (WaypointGroup group : groups) {
            if (group == null) continue;
            for (int i = 0; i < group.size(); i++) {
                String label = WaypointerRouteCommands.skipTargetLabel(group, i);
                if (label.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    if (suggested.add(label)) {
                        builder.suggest(label, Component.literal(describeWaypoint(group.get(i))));
                    }
                }
            }
        }
        return suggested.size();
    }

    SuggestionProvider<FabricClientCommandSource> suggestInsertSlots() {
        return (ctx, builder) -> {
            WaypointGroup g = manager.firstActiveGroup();
            if (g == null) return builder.buildFuture();
            int size = g.size();
            String prefix = builder.getRemaining();
            for (int index = 0; index <= size; index++) {
                int number = index + 1;
                String s = Integer.toString(number);
                if (!s.startsWith(prefix)) continue;
                String tip = index == size
                        ? "Waypoint " + number + " appends"
                        : "Waypoint " + number + ", before "
                                + g.displayIndexLabel(index) + " " + describeWaypoint(g.get(index));
                builder.suggest(number, Component.literal(tip));
            }
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestAllGroupIndices() {
        return (ctx, builder) -> {
            List<WaypointGroup> all = manager.allGroupsList();
            return CommandHelpers.suggestIndexed(builder, all.size(),
                    i -> "index " + i + ": " + all.get(i).name()
                            + " (" + all.get(i).size() + " pts)");
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

    SuggestionProvider<FabricClientCommandSource> suggestGroupNames() {
        return (ctx, builder) -> {
            Zone zone = manager.currentZone();
            if (zone != null) {
                CommandHelpers.suggestText(builder, "Route -- " + zone.displayName().toLowerCase(Locale.ROOT),
                        "default route name for current zone");
            }
            CommandHelpers.suggestText(builder, "Route", "generic route name");
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestLoadModes() {
        return (ctx, builder) -> {
            CommandHelpers.suggestText(builder, "sequence", "show previous/current/next route points");
            CommandHelpers.suggestText(builder, "static", "show every waypoint at once");
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestColorModes() {
        return (ctx, builder) -> {
            CommandHelpers.suggestText(builder, "gradient", "auto color waypoints between endpoints");
            CommandHelpers.suggestText(builder, "manual", "keep per-waypoint colors");
            CommandHelpers.suggestText(builder, "one", "use one route color");
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestToggleStates() {
        return (ctx, builder) -> {
            CommandHelpers.suggestText(builder, "toggle", "flip current state");
            CommandHelpers.suggestText(builder, "on", "turn on");
            CommandHelpers.suggestText(builder, "off", "turn off");
            return builder.buildFuture();
        };
    }

    SuggestionProvider<FabricClientCommandSource> suggestZoneTargets() {
        return (ctx, builder) -> {
            Zone current = manager.currentZone();
            if (current != null) {
                CommandHelpers.suggestText(builder, "current", "current area: " + current.displayName());
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
            CommandHelpers.suggestText(builder, "4FE05A", "Waypointer green");
            CommandHelpers.suggestText(builder, "00BFFF", "cool route start");
            CommandHelpers.suggestText(builder, "FF3040", "hot route end");
            return builder.buildFuture();
        };
    }

    static String describeWaypoint(Waypoint w) {
        String coords = w.x() + ", " + w.y() + ", " + w.z();
        return w.hasName() ? w.name() + "  " + coords : coords;
    }

    static String activeGroupIndexTooltip(WaypointGroup group, int index) {
        return "Waypoint " + (index + 1) + " (" + group.displayIndexLabel(index) + ") "
                + describeWaypoint(group.get(index));
    }

    enum Axis { X, Y, Z }
}
