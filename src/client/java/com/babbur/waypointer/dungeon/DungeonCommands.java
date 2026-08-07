package com.babbur.waypointer.dungeon;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.commands.CommandHelpers;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomDefinition;
import com.babbur.waypointer.dungeon.data.DungeonRoomFingerprint;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRouteImporter;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Client-side commands for the dungeon-waypoints subsystem. Registered as a
 * separate root ({@code /wpd}, {@code /waypointer-dungeon}) rather than as a
 * subtree of {@code /waypointer} on purpose: the main command file is being
 * actively modified by sibling issues #4 and #7 on {@code main}, so adding
 * branches there guarantees a merge conflict. A standalone root keeps the
 * dungeon work merge-safe and lets a follow-up commit fold the verbs into
 * {@code /wp dungeon …} once the sibling work has landed.
 *
 * <p>Verbs:
 *
 * <ul>
 *   <li>{@code /wpd info} -- print current detection state.</li>
 *   <li>{@code /wpd test} -- inject the built-in demo waypoint at the current
 *       room's segment so rendering can be verified end-to-end without curated
 *       data.</li>
 *   <li>{@code /wpd rotate (nw|ne|sw|se|auto)} -- override the detected
 *       {@link Direction} of the current room for this dungeon run, or restore
 *       automatic detection.</li>
 *   <li>{@code /wpd reset} -- forget every runtime-injected demo / custom
 *       waypoint without restarting the game.</li>
 *   <li>{@code /wpd toggle enabled} / {@code debug} -- shortcut toggles for
 *       dungeon room detection and diagnostic logging.</li>
 * </ul>
 */
public final class DungeonCommands {

    private final DungeonStateTracker tracker;
    private final DungeonConfig config;
    private final DungeonRouteSession session;
    private final DungeonRouteDownloader downloader;
    private final ActiveGroupManager manager;

    public DungeonCommands(DungeonStateTracker tracker, DungeonConfig config,
                           DungeonRouteSession session) {
        this(tracker, config, session, null, null);
    }

    public DungeonCommands(DungeonStateTracker tracker, DungeonConfig config,
                           DungeonRouteSession session, DungeonRouteDownloader downloader) {
        this(tracker, config, session, downloader, null);
    }

    public DungeonCommands(DungeonStateTracker tracker, DungeonConfig config,
                           DungeonRouteSession session, DungeonRouteDownloader downloader,
                           ActiveGroupManager manager) {
        this.tracker = tracker;
        this.config = config;
        this.session = session;
        this.downloader = downloader;
        this.manager = manager;
    }

    public void install() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            register(dispatcher, "wpd");
            register(dispatcher, "waypointer-dungeon");
        });
    }

    private void register(CommandDispatcher<FabricClientCommandSource> d, String root) {
        LiteralArgumentBuilder<FabricClientCommandSource> cmd = literal(root)
                .executes(ctx -> runInfo(ctx.getSource()))
                .then(literal("info").executes(ctx -> runInfo(ctx.getSource())))
                .then(literal("room")
                        .then(literal("list").executes(ctx -> runRoomList(ctx.getSource()))))
                .then(literal("import")
                        .then(argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> runImport(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "file")))))
                .then(literal("routes")
                        .then(literal("download").executes(ctx -> runRoutesDownload(ctx.getSource())))
                        .then(literal("dismiss").executes(ctx -> runRoutesDismiss(ctx.getSource()))))
                .then(literal("route")
                        .then(literal("next").executes(ctx -> runRouteNext(ctx.getSource())))
                        .then(literal("reset").executes(ctx -> runRouteReset(ctx.getSource())))
                        .then(literal("found")
                                .then(argument("secretIndex", IntegerArgumentType.integer(1))
                                        .suggests(suggestSecretIndices())
                                        .executes(ctx -> runRouteFound(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "secretIndex"))))))
                .then(literal("rotate")
                        .then(literal("nw").executes(ctx -> runRotate(ctx.getSource(), Direction.NW)))
                        .then(literal("ne").executes(ctx -> runRotate(ctx.getSource(), Direction.NE)))
                        .then(literal("sw").executes(ctx -> runRotate(ctx.getSource(), Direction.SW)))
                        .then(literal("se").executes(ctx -> runRotate(ctx.getSource(), Direction.SE)))
                        .then(literal("auto").executes(ctx -> runRotate(ctx.getSource(), null))))
                .then(literal("toggle")
                        .then(literal("enabled").executes(ctx -> runToggle(ctx.getSource(), "enabled")))
                        .then(literal("debug").executes(ctx -> runToggle(ctx.getSource(), "debug")))
                        .then(literal("greencheck").executes(ctx -> runToggle(ctx.getSource(), "greencheck")))
                        .then(literal("hidecompleted").executes(ctx -> runToggle(ctx.getSource(), "hidecompleted"))));
        d.register(cmd);
    }

    // ---- tab-complete suggestion providers -------------------------------

    private SuggestionProvider<FabricClientCommandSource> suggestRoomIds() {
        return (ctx, builder) -> {
            DungeonRoom room = tracker.currentRoom();
            if (room != null) {
                String generated = room.hasRoomId()
                        ? room.roomId()
                        : fallbackRoomId("room", room);
                CommandHelpers.suggestText(builder, generated, "current detected room");
            }
            for (DungeonRoomDefinition definition : DungeonRoomData.customDefinitions()) {
                CommandHelpers.suggestText(builder, definition.id(), definition.displayName());
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestCategories() {
        return (ctx, builder) -> {
            for (DungeonSecretCategory category : DungeonSecretCategory.values()) {
                CommandHelpers.suggestText(builder, category.id, "secret category");
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestCurrentRoomNames() {
        return (ctx, builder) -> {
            DungeonRoom room = tracker.currentRoom();
            if (room != null) CommandHelpers.suggestText(builder, room.displayName(), "current room display name");
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestWaypointNames() {
        return (ctx, builder) -> {
            String categoryId;
            try {
                categoryId = StringArgumentType.getString(ctx, "category");
            } catch (IllegalArgumentException ignored) {
                categoryId = "secret";
            }
            DungeonSecretCategory category = DungeonSecretCategory.fromId(categoryId);
            CommandHelpers.suggestText(builder, category.id + " secret", "category-based name");
            if (category == DungeonSecretCategory.SUPERBOOM) {
                CommandHelpers.suggestText(builder, "Superboom wall", "common superboom marker");
            } else if (category == DungeonSecretCategory.DUNGEONBREAKER) {
                CommandHelpers.suggestText(builder, "Break tunnel", "common dungeonbreaker marker");
            } else if (category == DungeonSecretCategory.LEVER) {
                CommandHelpers.suggestText(builder, "Lever", "common lever marker");
            } else if (category == DungeonSecretCategory.CHEST) {
                CommandHelpers.suggestText(builder, "Chest", "common chest marker");
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestWaypointIndices() {
        return (ctx, builder) -> {
            DungeonRoomDefinition definition = currentDefinition();
            if (definition == null) return builder.buildFuture();
            return CommandHelpers.suggestIndexed(builder, definition.waypoints().size(),
                    i -> describeWaypoint(definition.waypoints().get(i)));
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestHighlightIndices() {
        return (ctx, builder) -> {
            DungeonRoomDefinition definition = currentDefinition();
            if (definition == null) return builder.buildFuture();
            int waypointIndex;
            try {
                waypointIndex = IntegerArgumentType.getInteger(ctx, "waypoint");
            } catch (IllegalArgumentException ignored) {
                return builder.buildFuture();
            }
            if (waypointIndex < 0 || waypointIndex >= definition.waypoints().size()) {
                return builder.buildFuture();
            }
            List<DungeonHighlight> highlights = definition.waypoints().get(waypointIndex).highlights();
            return CommandHelpers.suggestIndexed(builder, highlights.size(),
                    i -> describeHighlight(highlights.get(i)));
        };
    }

    private SuggestionProvider<FabricClientCommandSource> suggestSecretIndices() {
        return (ctx, builder) -> {
            DungeonRoomDefinition definition = currentDefinition();
            if (definition == null) return builder.buildFuture();
            String prefix = builder.getRemaining();
            java.util.Set<Integer> seen = new java.util.TreeSet<>();
            for (DungeonWaypoint waypoint : definition.waypoints()) {
                if (!isProgressSecretWaypoint(waypoint)) continue;
                if (!seen.add(waypoint.secretIndex())) continue;
                String value = Integer.toString(waypoint.secretIndex());
                if (value.startsWith(prefix)) {
                    builder.suggest(waypoint.secretIndex(),
                            Component.translatable(
                                    "waypointer.dungeon.command.suggestion.secret",
                                    waypoint.secretIndex()));
                }
            }
            return builder.buildFuture();
        };
    }

    private DungeonRoomDefinition currentDefinition() {
        DungeonRoom room = tracker.currentRoom();
        if (room == null || !room.hasRoomId()) return null;
        return DungeonRoomData.definition(room.roomId());
    }

    private static String describeWaypoint(DungeonWaypoint waypoint) {
        String name = waypoint.hasName() ? waypoint.name() + " " : "";
        return name + secretIndexDescriptor(waypoint) + " "
                + waypoint.category().id + " "
                + waypoint.trigger().name().toLowerCase(Locale.ROOT);
    }

    static boolean isProgressSecretWaypoint(DungeonWaypoint waypoint) {
        return waypoint != null && waypoint.secretIndex() > 0;
    }

    static String secretIndexDescriptor(DungeonWaypoint waypoint) {
        return isProgressSecretWaypoint(waypoint) ? "#" + waypoint.secretIndex() : "support";
    }

    private static String describeHighlight(DungeonHighlight highlight) {
        return highlight.style() + " "
                + highlight.x() + ", " + highlight.y() + ", " + highlight.z();
    }

    // ---- subcommands -------------------------------------------------------

    private int runInfo(FabricClientCommandSource src) {
        DungeonRoom room = tracker.currentRoom();
        if (!tracker.inDungeon()) {
            info(src, Component.translatable("waypointer.dungeon.command.info.not_in_catacombs"));
            return 1;
        }
        if (room == null) {
            info(src, Component.translatable("waypointer.dungeon.command.info.no_room"));
            return 1;
        }
        info(src, Component.translatable(
                "waypointer.dungeon.command.info.room",
                room.displayName(), room.type(), room.shape(), room.direction(),
                room.hasRoomId() ? room.roomId() : "<unmatched>",
                room.physicalCornerX(), room.physicalCornerZ(),
                room.segments().size(), roomCountsSuffix(room)));
        return 1;
    }

    private static String roomCountsSuffix(DungeonRoom room) {
        if (!room.hasRoomId()) return "";
        DungeonRoomDefinition definition = DungeonRoomData.definition(room.roomId());
        if (definition == null) return "";
        StringBuilder counts = new StringBuilder();
        if (definition.hasSecretCount()) counts.append(" secrets=").append(definition.secretCount());
        if (definition.hasCryptCount()) counts.append(" crypts=").append(definition.cryptCount());
        if (definition.hasTrappedChestCount()) {
            counts.append(" trappedChests=").append(definition.trappedChestCount());
        }
        return counts.toString();
    }

    /**
     * Inject the built-in demo waypoint set into the current room. Useful for
     * eyeballing whether room-local→world transformation is correct without
     * waiting on curated data.
     */
    private int runTest(FabricClientCommandSource src) {
        DungeonRoom room = tracker.currentRoom();
        if (room == null) {
            error(src, Component.translatable("waypointer.dungeon.command.error.no_room"));
            return 0;
        }
        var demo = DungeonRoomData.demoFor(room.shape());
        if (demo.isEmpty()) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.test.no_demo", room.shape()));
            return 0;
        }
        for (DungeonWaypoint wp : demo) {
            String id = room.hasRoomId() && DungeonRoomData.isCustomDefinition(room.roomId())
                    ? room.roomId()
                    : fallbackRoomId("test", room);
            if (!room.hasRoomId() || !DungeonRoomData.isCustomDefinition(room.roomId())) {
                DungeonRoomDefinition def = DungeonRoomData.defineRoom(id, room.displayName(), room);
                tracker.applyCurrentRoomDefinition(def.id(), def.displayName());
                room = tracker.currentRoom();
            }
            DungeonRoomData.addWaypoint(id, wp);
        }
        success(src, Component.translatable(
                "waypointer.dungeon.command.test.added", demo.size(), room.shape()));
        return 1;
    }

    private int runRoomCreate(FabricClientCommandSource src, String id, String name) {
        DungeonRoom room = requireRoom(src);
        if (room == null) return 0;
        DungeonRoomDefinition definition;
        try {
            definition = DungeonRoomData.defineIdentifiedRoom(
                    id,
                    name == null || name.isBlank() ? room.displayName() : name,
                    room,
                    new DungeonRoomCoreScanner(Minecraft.getInstance().level));
        } catch (IllegalStateException unavailableIdentity) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.room.identity_unavailable"));
            return 0;
        }
        tracker.applyCurrentRoomDefinition(definition.id(), definition.displayName());
        success(src, Component.translatable(
                "waypointer.dungeon.command.room.created",
                definition.displayName(), definition.id()));
        return 1;
    }

    private int runRoomRename(FabricClientCommandSource src, String name) {
        DungeonRoom room = requireAuthoredRoom(src);
        if (room == null) return 0;
        DungeonRoomDefinition definition = DungeonRoomData.renameRoom(room.roomId(), name);
        tracker.applyCurrentRoomDefinition(definition.id(), definition.displayName());
        success(src, Component.translatable(
                "waypointer.dungeon.command.room.renamed", definition.displayName()));
        return 1;
    }

    private int runFingerprintAdd(FabricClientCommandSource src) {
        DungeonRoom room = requireAuthoredRoom(src);
        LocalPlayer player = Minecraft.getInstance().player;
        if (room == null || player == null) {
            if (player == null) {
                error(src, Component.translatable("waypointer.command.error.not_in_world"));
            }
            return 0;
        }
        int wx = (int) Math.floor(player.getX());
        int wy = (int) Math.floor(player.getY());
        int wz = (int) Math.floor(player.getZ());
        int[] relative = DungeonMapMath.actualToRelative(
                room.direction(), room.physicalCornerX(), room.physicalCornerZ(), wx, wy, wz);
        String blockId = BuiltInRegistries.BLOCK
                .getKey(player.level().getBlockState(new BlockPos(wx, wy, wz)).getBlock())
                .toString();
        DungeonRoomData.addFingerprint(room.roomId(),
                new DungeonRoomFingerprint(relative[0], relative[1], relative[2], blockId));
        success(src, Component.translatable(
                "waypointer.dungeon.command.fingerprint.added",
                blockId, relative[0], relative[1], relative[2]));
        return 1;
    }

    private int runRoomList(FabricClientCommandSource src) {
        int count = 0;
        for (DungeonRoomDefinition definition : DungeonRoomData.customDefinitions()) {
            info(src, Component.translatable(
                    "waypointer.dungeon.command.room.list_entry",
                    definition.id(), definition.displayName(),
                    definition.type(), definition.shape(),
                    definition.waypoints().size(), definition.fingerprints().size()));
            count++;
        }
        if (count == 0) {
            info(src, Component.translatable("waypointer.dungeon.command.room.list_empty"));
        }
        return count;
    }

    private int runWaypointList(FabricClientCommandSource src) {
        DungeonRoomDefinition definition = requireDefinition(src);
        if (definition == null) return 0;
        List<DungeonWaypoint> waypoints = definition.waypoints();
        if (waypoints.isEmpty()) {
            info(src, Component.translatable(
                    "waypointer.dungeon.command.waypoint.list_empty",
                    definition.displayName()));
            return 0;
        }
        for (int i = 0; i < waypoints.size(); i++) {
            DungeonWaypoint waypoint = waypoints.get(i);
            info(src, Component.translatable(
                    "waypointer.dungeon.command.waypoint.list_entry",
                    i, secretIndexDescriptor(waypoint), waypoint.category().id,
                    waypoint.x(), waypoint.y(), waypoint.z(),
                    waypoint.trigger().name().toLowerCase(java.util.Locale.ROOT),
                    waypoint.hasName() ? " -- " + waypoint.name() : "",
                    waypoint.highlights().size()));
        }
        return waypoints.size();
    }

    private int runWaypointAdd(FabricClientCommandSource src, String categoryId, String name) {
        DungeonRoom room = requireAuthoredRoom(src);
        LocalPlayer player = Minecraft.getInstance().player;
        if (room == null || player == null) {
            if (player == null) {
                error(src, Component.translatable("waypointer.command.error.not_in_world"));
            }
            return 0;
        }
        DungeonRoomDefinition definition = DungeonRoomData.definition(room.roomId());
        int[] relative = DungeonMapMath.actualToRelative(
                room.direction(), room.physicalCornerX(), room.physicalCornerZ(),
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()));
        int secretIndex = nextSecretIndex(definition.waypoints());
        DungeonSecretCategory category = DungeonSecretCategory.fromId(categoryId);
        DungeonWaypoint waypoint = new DungeonWaypoint(
                room.roomId() + ":" + secretIndex,
                secretIndex,
                category,
                DungeonWaypoint.defaultTrigger(category),
                relative[0], relative[1], relative[2],
                name,
                List.of());
        DungeonRoomData.addWaypoint(room.roomId(), waypoint);
        session.resetRoom(room);
        success(src, Component.translatable(
                "waypointer.dungeon.command.waypoint.added",
                secretIndex, room.displayName(),
                relative[0], relative[1], relative[2]));
        return 1;
    }

    private int runWaypointRemove(FabricClientCommandSource src, int index) {
        DungeonRoom room = requireAuthoredRoom(src);
        DungeonRoomDefinition definition = room == null ? null : DungeonRoomData.definition(room.roomId());
        if (definition == null) return 0;
        if (index >= definition.waypoints().size()) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.error.waypoint_index_range",
                    definition.waypoints().size() - 1));
            return 0;
        }
        DungeonRoomData.removeWaypoint(room.roomId(), index);
        session.resetRoom(room);
        success(src, Component.translatable(
                "waypointer.dungeon.command.waypoint.removed", index));
        return 1;
    }

    private int runWaypointMove(FabricClientCommandSource src, int index) {
        DungeonRoom room = requireAuthoredRoom(src);
        LocalPlayer player = Minecraft.getInstance().player;
        DungeonRoomDefinition definition = room == null ? null : DungeonRoomData.definition(room.roomId());
        if (room == null || definition == null || player == null) {
            if (player == null) {
                error(src, Component.translatable("waypointer.command.error.not_in_world"));
            }
            return 0;
        }
        if (index >= definition.waypoints().size()) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.error.waypoint_index"));
            return 0;
        }
        int[] relative = DungeonMapMath.actualToRelative(
                room.direction(), room.physicalCornerX(), room.physicalCornerZ(),
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()));
        DungeonRoomData.moveWaypoint(room.roomId(), index, relative[0], relative[1], relative[2]);
        session.resetRoom(room);
        success(src, Component.translatable(
                "waypointer.dungeon.command.waypoint.moved",
                index, relative[0], relative[1], relative[2]));
        return 1;
    }

    private int runWaypointTrigger(FabricClientCommandSource src, int index,
                                   DungeonWaypointTrigger trigger) {
        DungeonRoom room = requireAuthoredRoom(src);
        DungeonRoomDefinition definition = room == null ? null : DungeonRoomData.definition(room.roomId());
        if (room == null || definition == null) return 0;
        if (index >= definition.waypoints().size()) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.error.waypoint_index"));
            return 0;
        }
        DungeonRoomData.setWaypointTrigger(room.roomId(), index, trigger);
        session.resetRoom(room);
        success(src, Component.translatable(
                "waypointer.dungeon.command.waypoint.trigger",
                index, trigger.name().toLowerCase(java.util.Locale.ROOT)));
        return 1;
    }

    private int runHighlightList(FabricClientCommandSource src, int waypointIndex) {
        DungeonWaypoint waypoint = waypointAt(src, waypointIndex);
        if (waypoint == null) return 0;
        for (int i = 0; i < waypoint.highlights().size(); i++) {
            DungeonHighlight highlight = waypoint.highlights().get(i);
            info(src, Component.translatable(
                    "waypointer.dungeon.command.highlight.list_entry",
                    i, highlight.style(),
                    highlight.x(), highlight.y(), highlight.z()));
        }
        if (waypoint.highlights().isEmpty()) {
            info(src, Component.translatable(
                    "waypointer.dungeon.command.highlight.list_empty"));
        }
        return waypoint.highlights().size();
    }

    private int runHighlightAdd(FabricClientCommandSource src, int waypointIndex,
                                DungeonHighlightStyle style) {
        DungeonRoom room = requireAuthoredRoom(src);
        LocalPlayer player = Minecraft.getInstance().player;
        if (room == null || player == null) {
            if (player == null) {
                error(src, Component.translatable("waypointer.command.error.not_in_world"));
            }
            return 0;
        }
        DungeonRoomDefinition definition = DungeonRoomData.definition(room.roomId());
        if (definition == null || waypointIndex >= definition.waypoints().size()) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.error.waypoint_index"));
            return 0;
        }
        int[] relative = DungeonMapMath.actualToRelative(
                room.direction(), room.physicalCornerX(), room.physicalCornerZ(),
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()));
        DungeonRoomData.addHighlight(room.roomId(), waypointIndex,
                new DungeonHighlight(relative[0], relative[1], relative[2],
                        style, DungeonHighlight.INHERIT_COLOR));
        success(src, Component.translatable(
                "waypointer.dungeon.command.highlight.added", style, waypointIndex));
        return 1;
    }

    private int runBreakBoxAdd(FabricClientCommandSource src, int waypointIndex) {
        int result = runHighlightAdd(src, waypointIndex, DungeonHighlightStyle.OUTLINE_FILLED);
        if (result == 1) {
            DungeonRoom room = tracker.currentRoom();
            if (room != null) {
                DungeonRoomData.setWaypointTrigger(room.roomId(), waypointIndex,
                        DungeonWaypointTrigger.DUNGEONBREAKER);
                success(src, Component.translatable(
                        "waypointer.dungeon.command.breakbox.trigger", waypointIndex));
            }
        }
        return result;
    }

    private int runHighlightRemove(FabricClientCommandSource src, int waypointIndex,
                                   int highlightIndex) {
        DungeonWaypoint waypoint = waypointAt(src, waypointIndex);
        DungeonRoom room = tracker.currentRoom();
        if (waypoint == null || room == null) return 0;
        if (highlightIndex >= waypoint.highlights().size()) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.error.highlight_index_range",
                    waypoint.highlights().size() - 1));
            return 0;
        }
        DungeonRoomData.removeHighlight(room.roomId(), waypointIndex, highlightIndex);
        success(src, Component.translatable(
                "waypointer.dungeon.command.highlight.removed",
                highlightIndex, waypointIndex));
        return 1;
    }

    private int runRoutesDownload(FabricClientCommandSource src) {
        if (downloader == null) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.routes.unavailable"));
            return 0;
        }
        downloader.download(component -> src.sendFeedback(component));
        return 1;
    }

    private int runRoutesDismiss(FabricClientCommandSource src) {
        if (downloader == null) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.routes.unavailable"));
            return 0;
        }
        downloader.dismissPrompt();
        success(src, Component.translatable(
                "waypointer.dungeon.command.routes.dismissed"));
        return 1;
    }

    /**
     * Import third-party route data ({@code /wpd import <file>}). Accepts
     * SecretRoutes {@code routes.json}, Odin waypoint packs (file or shared
     * Base64 string in a file), and Waypointer's own formats; the format is
     * sniffed, never declared.
     */
    private int runImport(FabricClientCommandSource src, String rawPath) {
        Path file = resolveImportPath(rawPath);
        if (file == null) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.import.file_not_found", rawPath));
            return 0;
        }

        String payload;
        try {
            payload = Files.readString(file);
        } catch (IOException e) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.import.read_failed",
                    file, e.getMessage()));
            return 0;
        }

        DungeonRouteImporter.Result result;
        try {
            result = DungeonRouteImporter.parse(payload);
        } catch (IllegalArgumentException e) {
            error(src, Component.translatable(
                    "waypointer.command.import.failed", e.getMessage()));
            return 0;
        }

        DungeonRoomData.importCustomDefinitions(result.definitions());
        List<WaypointGroup> routes =
                DungeonRoomRouteSync.installEditableRoutes(manager, config, result.definitions());
        if (routes.isEmpty()) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.import.no_usable_routes"));
            return 0;
        }
        success(src, Component.translatable(
                "waypointer.dungeon.command.import.success",
                result.waypointCount(), routes.size(),
                importFormatLabel(result.format())));
        info(src, Component.translatable(
                "waypointer.dungeon.routes.existing_disabled"));
        if (result.skippedVariants() > 0) {
            info(src, Component.translatable(
                    "waypointer.dungeon.command.import.skipped_variants",
                    result.skippedVariants()));
        }
        if (!result.unmatchedRooms().isEmpty()) {
            info(src, Component.translatable(
                    "waypointer.dungeon.command.import.unmatched_rooms",
                    result.unmatchedRooms().size(),
                    summarizeNames(result.unmatchedRooms())));
        }
        return 1;
    }

    private static Path resolveImportPath(String rawPath) {
        String expanded = rawPath.trim();
        if (expanded.startsWith("~/")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        Path direct = Path.of(expanded);
        if (Files.isRegularFile(direct)) return direct;
        if (!direct.isAbsolute()) {
            Path inGameDir = Minecraft.getInstance().gameDirectory.toPath().resolve(expanded);
            if (Files.isRegularFile(inGameDir)) return inGameDir;
            Path inConfigDir = FabricLoader.getInstance().getConfigDir().resolve(expanded);
            if (Files.isRegularFile(inConfigDir)) return inConfigDir;
        }
        return null;
    }

    private static Component importFormatLabel(DungeonRouteImporter.Format format) {
        return switch (format) {
            case WAYPOINTER -> Component.translatable(
                    "waypointer.dungeon.command.import.format.waypointer");
            case SECRET_ROUTES -> Component.translatable(
                    "waypointer.dungeon.command.import.format.secret_routes");
            case ODIN_PACK -> Component.translatable(
                    "waypointer.dungeon.command.import.format.odin");
        };
    }

    private static String summarizeNames(List<String> names) {
        int shown = Math.min(names.size(), 8);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) out.append(", ");
            out.append(names.get(i));
        }
        if (names.size() > shown) out.append(", +").append(names.size() - shown).append(" more");
        return out.toString();
    }

    private int runRouteNext(FabricClientCommandSource src) {
        DungeonRoom room = requireRoom(src);
        if (room == null) return 0;
        session.advance(room);
        int currentSecretIndex = session.currentSecretIndex(room);
        if (currentSecretIndex == 0) {
            success(src, Component.translatable(
                    "waypointer.dungeon.command.route.completed", room.displayName()));
        } else {
            success(src, Component.translatable(
                    "waypointer.dungeon.command.route.advanced", currentSecretIndex));
        }
        return 1;
    }

    private int runRouteReset(FabricClientCommandSource src) {
        DungeonRoom room = requireRoom(src);
        if (room == null) return 0;
        session.resetRoom(room);
        success(src, Component.translatable(
                "waypointer.dungeon.command.route.reset", room.displayName()));
        return 1;
    }

    private int runRouteFound(FabricClientCommandSource src, int secretIndex) {
        DungeonRoom room = requireRoom(src);
        if (room == null) return 0;
        if (!isAuthoredSecretIndex(room, secretIndex)) {
            String available = availableAuthoredSecretIndexes(room);
            error(src, available.isEmpty()
                    ? Component.translatable(
                            "waypointer.dungeon.command.route.secret_not_authored_empty",
                            secretIndex, room.displayName())
                    : Component.translatable(
                            "waypointer.dungeon.command.route.secret_not_authored",
                            secretIndex, room.displayName(), available));
            return 0;
        }
        session.markFound(room, secretIndex);
        success(src, Component.translatable(
                "waypointer.dungeon.command.route.secret_found", secretIndex));
        return 1;
    }

    static boolean isAuthoredSecretIndex(DungeonRoom room, int secretIndex) {
        if (room == null || secretIndex <= 0) return false;
        for (DungeonWaypoint waypoint : DungeonRoomData.waypointsFor(room)) {
            if (waypoint.secretIndex() == secretIndex) return true;
        }
        return false;
    }

    static String availableAuthoredSecretIndexes(DungeonRoom room) {
        java.util.Set<Integer> indexes = new java.util.LinkedHashSet<>();
        for (DungeonWaypoint waypoint : DungeonRoomData.waypointsFor(room)) {
            int index = waypoint.secretIndex();
            if (index > 0) indexes.add(index);
        }

        StringBuilder available = new StringBuilder();
        for (Integer index : indexes) {
            if (!available.isEmpty()) available.append(", ");
            available.append("#").append(index);
        }
        return available.toString();
    }

    private int runRotate(FabricClientCommandSource src, Direction dir) {
        if (!tracker.setDirectionOverride(dir)) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.rotate.no_room"));
            return 0;
        }
        if (dir == null) {
            success(src, Component.translatable(
                    "waypointer.dungeon.command.rotate.automatic"));
        } else {
            success(src, Component.translatable(
                    "waypointer.dungeon.command.rotate.set", dir));
        }
        return 1;
    }

    private int runReset(FabricClientCommandSource src, boolean confirmed) {
        if (!confirmed) {
            warn(src, Component.translatable(
                    "waypointer.dungeon.command.reset.confirm"));
            return 0;
        }
        DungeonRoomData.clearAllCustom();
        success(src, Component.translatable(
                "waypointer.dungeon.command.reset.cleared"));
        return 1;
    }

    private int runToggle(FabricClientCommandSource src, String which) {
        boolean newValue = switch (which) {
            case "enabled" -> {
                boolean v = !config.enabled();
                config.setEnabled(v);
                yield v;
            }
            case "debug" -> {
                boolean v = !config.debugLogRoomChanges();
                config.setDebugLogRoomChanges(v);
                yield v;
            }
            case "greencheck" -> {
                boolean v = !config.autoCompleteRoomsOnGreenCheckmark();
                config.setAutoCompleteRoomsOnGreenCheckmark(v);
                yield v;
            }
            case "hidecompleted" -> {
                boolean v = !config.hideCompletedRooms();
                config.setHideCompletedRooms(v);
                yield v;
            }
            default -> false;
        };
        success(src, Component.translatable(
                "waypointer.dungeon.command.toggle", which, newValue));
        Waypointer.LOGGER.info("Dungeon toggle: {} -> {}", which, newValue);
        return 1;
    }

    private DungeonRoom requireRoom(FabricClientCommandSource src) {
        DungeonRoom room = tracker.currentRoom();
        if (room == null) {
            error(src, Component.translatable("waypointer.dungeon.command.error.no_room"));
        }
        return room;
    }

    private DungeonRoom requireAuthoredRoom(FabricClientCommandSource src) {
        DungeonRoom room = requireRoom(src);
        if (room == null) return null;
        if (!room.hasRoomId() || DungeonRoomData.definition(room.roomId()) == null) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.error.create_room_first"));
            return null;
        }
        return room;
    }

    private DungeonRoomDefinition requireDefinition(FabricClientCommandSource src) {
        DungeonRoom room = requireAuthoredRoom(src);
        return room == null ? null : DungeonRoomData.definition(room.roomId());
    }

    private DungeonWaypoint waypointAt(FabricClientCommandSource src, int waypointIndex) {
        DungeonRoomDefinition definition = requireDefinition(src);
        if (definition == null) return null;
        if (waypointIndex >= definition.waypoints().size()) {
            error(src, Component.translatable(
                    "waypointer.dungeon.command.error.waypoint_index_range",
                    definition.waypoints().size() - 1));
            return null;
        }
        return definition.waypoints().get(waypointIndex);
    }

    private static int nextSecretIndex(List<DungeonWaypoint> waypoints) {
        int max = 0;
        for (DungeonWaypoint waypoint : waypoints) {
            if (waypoint.secretIndex() > max) max = waypoint.secretIndex();
        }
        return max + 1;
    }

    private static String fallbackRoomId(String prefix, DungeonRoom room) {
        return prefix + "-" + Integer.toUnsignedLong(room.identityKey().hashCode());
    }

    // ---- styled feedback (matches WaypointerCommands' palette) -------------

    private static void info(FabricClientCommandSource src, Component msg) {
        src.sendFeedback(WaypointerChatFeedback.suppress(
                msg.copy().withStyle(ChatFormatting.GRAY)));
    }

    private static void success(FabricClientCommandSource src, Component msg) {
        src.sendFeedback(WaypointerChatFeedback.suppress(
                msg.copy().withStyle(ChatFormatting.GREEN)));
    }

    private static void warn(FabricClientCommandSource src, Component msg) {
        src.sendFeedback(WaypointerChatFeedback.suppress(
                msg.copy().withStyle(ChatFormatting.YELLOW)));
    }

    private static void error(FabricClientCommandSource src, Component msg) {
        src.sendError(WaypointerChatFeedback.suppress(
                msg.copy().withStyle(ChatFormatting.RED)));
    }
}
