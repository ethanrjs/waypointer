package dev.ethan.waypointer.dungeon;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.commands.CommandHelpers;
import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;
import dev.ethan.waypointer.dungeon.data.DungeonRoomFingerprint;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

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
 *   <li>{@code /wpd rotate (nw|ne|sw|se)} -- override the assumed
 *       {@link Direction} of the current room. Until block fingerprinting
 *       lands, this is the player's escape hatch when the canonical-frame
 *       guess is wrong and secrets render mirrored.</li>
 *   <li>{@code /wpd reset} -- forget every runtime-injected demo / custom
 *       waypoint without restarting the game.</li>
 *   <li>{@code /wpd toggle bounds} / {@code highlights} / {@code waypoints}
 *       -- shortcut toggles for the corresponding {@link DungeonConfig}
 *       flags.</li>
 * </ul>
 */
public final class DungeonCommands {

    private final DungeonStateTracker tracker;
    private final DungeonConfig config;
    private final DungeonRouteSession session;

    public DungeonCommands(DungeonStateTracker tracker, DungeonConfig config,
                           DungeonRouteSession session) {
        this.tracker = tracker;
        this.config = config;
        this.session = session;
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
                .then(literal("test").executes(ctx -> runTest(ctx.getSource())))
                .then(literal("reset").executes(ctx -> runReset(ctx.getSource())))
                .then(literal("room")
                        .then(literal("create")
                                .then(argument("id", StringArgumentType.word())
                                        .suggests(suggestRoomIds())
                                        .executes(ctx -> runRoomCreate(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"), ""))
                                        .then(argument("name", StringArgumentType.greedyString())
                                                .suggests(suggestCurrentRoomNames())
                                                .executes(ctx -> runRoomCreate(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "name"))))))
                        .then(literal("rename")
                                .then(argument("name", StringArgumentType.greedyString())
                                        .suggests(suggestCurrentRoomNames())
                                        .executes(ctx -> runRoomRename(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(literal("fingerprint")
                                .then(literal("add").executes(ctx -> runFingerprintAdd(ctx.getSource()))))
                        .then(literal("list").executes(ctx -> runRoomList(ctx.getSource()))))
                .then(literal("waypoint")
                        .then(literal("list").executes(ctx -> runWaypointList(ctx.getSource())))
                        .then(literal("add")
                                .then(argument("category", StringArgumentType.word())
                                        .suggests(suggestCategories())
                                        .executes(ctx -> runWaypointAdd(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "category"), ""))
                                        .then(argument("name", StringArgumentType.greedyString())
                                                .suggests(suggestWaypointNames())
                                                .executes(ctx -> runWaypointAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "category"),
                                                        StringArgumentType.getString(ctx, "name"))))))
                        .then(literal("remove")
                                .then(argument("index", IntegerArgumentType.integer(0))
                                        .suggests(suggestWaypointIndices())
                                        .executes(ctx -> runWaypointRemove(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index")))))
                        .then(literal("move")
                                .then(argument("index", IntegerArgumentType.integer(0))
                                        .suggests(suggestWaypointIndices())
                                        .executes(ctx -> runWaypointMove(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index")))))
                        .then(literal("trigger")
                                .then(argument("index", IntegerArgumentType.integer(0))
                                        .suggests(suggestWaypointIndices())
                                        .then(literal("manual").executes(ctx -> runWaypointTrigger(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                DungeonWaypointTrigger.MANUAL)))
                                        .then(literal("interact").executes(ctx -> runWaypointTrigger(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                DungeonWaypointTrigger.INTERACT_BLOCK)))
                                        .then(literal("chest").executes(ctx -> runWaypointTrigger(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                DungeonWaypointTrigger.OPEN_CHEST)))
                                        .then(literal("lever").executes(ctx -> runWaypointTrigger(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                DungeonWaypointTrigger.FLIP_LEVER)))
                                        .then(literal("superboom").executes(ctx -> runWaypointTrigger(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                DungeonWaypointTrigger.USE_SUPERBOOM)))
                                        .then(literal("pickup").executes(ctx -> runWaypointTrigger(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                DungeonWaypointTrigger.PICKUP_ITEM)))
                                        .then(literal("bat").executes(ctx -> runWaypointTrigger(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                DungeonWaypointTrigger.KILL_BAT)))
                                        .then(literal("break").executes(ctx -> runWaypointTrigger(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                DungeonWaypointTrigger.BREAK_BLOCKS)))
                                        .then(literal("dungeonbreaker").executes(ctx -> runWaypointTrigger(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                DungeonWaypointTrigger.DUNGEONBREAKER)))
                                        .then(literal("chat").executes(ctx -> runWaypointTrigger(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "index"),
                                                DungeonWaypointTrigger.CHAT_MESSAGE))))))
                .then(literal("highlight")
                        .then(literal("list")
                                .then(argument("waypoint", IntegerArgumentType.integer(0))
                                        .suggests(suggestWaypointIndices())
                                        .executes(ctx -> runHighlightList(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "waypoint")))))
                        .then(literal("add")
                                .then(argument("waypoint", IntegerArgumentType.integer(0))
                                        .suggests(suggestWaypointIndices())
                                        .executes(ctx -> runHighlightAdd(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "waypoint"),
                                                DungeonHighlightStyle.OUTLINE))
                                        .then(literal("outline").executes(ctx -> runHighlightAdd(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "waypoint"),
                                                DungeonHighlightStyle.OUTLINE)))
                                        .then(literal("filled").executes(ctx -> runHighlightAdd(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "waypoint"),
                                                DungeonHighlightStyle.FILLED)))
                                        .then(literal("both").executes(ctx -> runHighlightAdd(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "waypoint"),
                                                DungeonHighlightStyle.OUTLINE_FILLED)))))
                        .then(literal("remove")
                                .then(argument("waypoint", IntegerArgumentType.integer(0))
                                        .suggests(suggestWaypointIndices())
                                        .then(argument("highlight", IntegerArgumentType.integer(0))
                                                .suggests(suggestHighlightIndices())
                                                .executes(ctx -> runHighlightRemove(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "waypoint"),
                                                        IntegerArgumentType.getInteger(ctx, "highlight")))))))
                .then(literal("breakbox")
                        .then(literal("add")
                                .then(argument("waypoint", IntegerArgumentType.integer(0))
                                        .suggests(suggestWaypointIndices())
                                        .executes(ctx -> runBreakBoxAdd(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "waypoint"))))))
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
                        .then(literal("se").executes(ctx -> runRotate(ctx.getSource(), Direction.SE))))
                .then(literal("toggle")
                        .then(literal("enabled").executes(ctx -> runToggle(ctx.getSource(), "enabled")))
                        .then(literal("waypoints").executes(ctx -> runToggle(ctx.getSource(), "waypoints")))
                        .then(literal("highlights").executes(ctx -> runToggle(ctx.getSource(), "highlights")))
                        .then(literal("found").executes(ctx -> runToggle(ctx.getSource(), "found")))
                        .then(literal("mode").executes(ctx -> runToggle(ctx.getSource(), "mode")))
                        .then(literal("bounds").executes(ctx -> runToggle(ctx.getSource(), "bounds")))
                        .then(literal("debug").executes(ctx -> runToggle(ctx.getSource(), "debug"))));
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
                if (!seen.add(waypoint.secretIndex())) continue;
                String value = Integer.toString(waypoint.secretIndex());
                if (value.startsWith(prefix)) {
                    builder.suggest(waypoint.secretIndex(),
                            Component.literal("secret #" + waypoint.secretIndex()));
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
        return name + "#" + waypoint.secretIndex() + " "
                + waypoint.category().id + " "
                + waypoint.trigger().name().toLowerCase(Locale.ROOT);
    }

    private static String describeHighlight(DungeonHighlight highlight) {
        return highlight.style() + " "
                + highlight.x() + ", " + highlight.y() + ", " + highlight.z();
    }

    // ---- subcommands -------------------------------------------------------

    private int runInfo(FabricClientCommandSource src) {
        DungeonRoom room = tracker.currentRoom();
        if (!tracker.inDungeon()) {
            info(src, "Not currently inside Catacombs.");
            return 1;
        }
        if (room == null) {
            info(src, "In Catacombs, but no room detected (between rooms? map not yet anchored).");
            return 1;
        }
        info(src, "Dungeon room: " + room.displayName()
                + " (" + room.type() + " " + room.shape() + ")"
                + " dir=" + room.direction()
                + (room.hasRoomId() ? " id=" + room.roomId() : " id=<unmatched>")
                + " corner=(" + room.physicalCornerX() + ", " + room.physicalCornerZ() + ")"
                + " segments=" + room.segments().size());
        return 1;
    }

    /**
     * Inject the built-in demo waypoint set into the current room. Useful for
     * eyeballing whether room-local→world transformation is correct without
     * waiting on curated data.
     */
    private int runTest(FabricClientCommandSource src) {
        DungeonRoom room = tracker.currentRoom();
        if (room == null) {
            error(src, "No room detected. Stand in a Catacombs room and try again.");
            return 0;
        }
        var demo = DungeonRoomData.demoFor(room.shape());
        if (demo.isEmpty()) {
            error(src, "No demo data registered for shape " + room.shape() + ".");
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
        success(src, "Added " + demo.size() + " demo waypoint(s) for shape "
                + room.shape() + " in current room.");
        return 1;
    }

    private int runRoomCreate(FabricClientCommandSource src, String id, String name) {
        DungeonRoom room = requireRoom(src);
        if (room == null) return 0;
        DungeonRoomDefinition definition = DungeonRoomData.defineRoom(id,
                name == null || name.isBlank() ? room.displayName() : name, room);
        tracker.applyCurrentRoomDefinition(definition.id(), definition.displayName());
        success(src, "Dungeon room definition \"" + definition.displayName()
                + "\" created as " + definition.id());
        return 1;
    }

    private int runRoomRename(FabricClientCommandSource src, String name) {
        DungeonRoom room = requireAuthoredRoom(src);
        if (room == null) return 0;
        DungeonRoomDefinition definition = DungeonRoomData.renameRoom(room.roomId(), name);
        tracker.applyCurrentRoomDefinition(definition.id(), definition.displayName());
        success(src, "Renamed room to \"" + definition.displayName() + "\".");
        return 1;
    }

    private int runFingerprintAdd(FabricClientCommandSource src) {
        DungeonRoom room = requireAuthoredRoom(src);
        LocalPlayer player = Minecraft.getInstance().player;
        if (room == null || player == null) {
            if (player == null) error(src, "Not in a world.");
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
        success(src, "Added fingerprint " + blockId + " at relative "
                + relative[0] + ", " + relative[1] + ", " + relative[2]);
        return 1;
    }

    private int runRoomList(FabricClientCommandSource src) {
        int count = 0;
        for (DungeonRoomDefinition definition : DungeonRoomData.customDefinitions()) {
            info(src, definition.id() + " -- " + definition.displayName()
                    + " (" + definition.type() + " " + definition.shape()
                    + ", " + definition.waypoints().size() + " waypoint(s), "
                    + definition.fingerprints().size() + " fingerprint(s))");
            count++;
        }
        if (count == 0) info(src, "No custom dungeon room definitions yet.");
        return count;
    }

    private int runWaypointList(FabricClientCommandSource src) {
        DungeonRoomDefinition definition = requireDefinition(src);
        if (definition == null) return 0;
        List<DungeonWaypoint> waypoints = definition.waypoints();
        if (waypoints.isEmpty()) {
            info(src, "No secret waypoints in " + definition.displayName() + ".");
            return 0;
        }
        for (int i = 0; i < waypoints.size(); i++) {
            DungeonWaypoint waypoint = waypoints.get(i);
            info(src, "[" + i + "] #" + waypoint.secretIndex() + " "
                    + waypoint.category().id + " " + waypoint.x() + ", "
                    + waypoint.y() + ", " + waypoint.z()
                    + " trigger=" + waypoint.trigger().name().toLowerCase(java.util.Locale.ROOT)
                    + (waypoint.hasName() ? " -- " + waypoint.name() : "")
                    + " (" + waypoint.highlights().size() + " highlight(s))");
        }
        return waypoints.size();
    }

    private int runWaypointAdd(FabricClientCommandSource src, String categoryId, String name) {
        DungeonRoom room = requireAuthoredRoom(src);
        LocalPlayer player = Minecraft.getInstance().player;
        if (room == null || player == null) {
            if (player == null) error(src, "Not in a world.");
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
        success(src, "Added secret #" + secretIndex + " to " + room.displayName()
                + " at relative " + relative[0] + ", " + relative[1] + ", " + relative[2]);
        return 1;
    }

    private int runWaypointRemove(FabricClientCommandSource src, int index) {
        DungeonRoom room = requireAuthoredRoom(src);
        DungeonRoomDefinition definition = room == null ? null : DungeonRoomData.definition(room.roomId());
        if (definition == null) return 0;
        if (index >= definition.waypoints().size()) {
            error(src, "Waypoint index out of range (0.." + (definition.waypoints().size() - 1) + ").");
            return 0;
        }
        DungeonRoomData.removeWaypoint(room.roomId(), index);
        session.resetRoom(room);
        success(src, "Removed dungeon waypoint [" + index + "].");
        return 1;
    }

    private int runWaypointMove(FabricClientCommandSource src, int index) {
        DungeonRoom room = requireAuthoredRoom(src);
        LocalPlayer player = Minecraft.getInstance().player;
        DungeonRoomDefinition definition = room == null ? null : DungeonRoomData.definition(room.roomId());
        if (room == null || definition == null || player == null) {
            if (player == null) error(src, "Not in a world.");
            return 0;
        }
        if (index >= definition.waypoints().size()) {
            error(src, "Waypoint index out of range.");
            return 0;
        }
        int[] relative = DungeonMapMath.actualToRelative(
                room.direction(), room.physicalCornerX(), room.physicalCornerZ(),
                (int) Math.floor(player.getX()),
                (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()));
        DungeonRoomData.moveWaypoint(room.roomId(), index, relative[0], relative[1], relative[2]);
        session.resetRoom(room);
        success(src, "Moved waypoint [" + index + "] to relative "
                + relative[0] + ", " + relative[1] + ", " + relative[2]);
        return 1;
    }

    private int runWaypointTrigger(FabricClientCommandSource src, int index,
                                   DungeonWaypointTrigger trigger) {
        DungeonRoom room = requireAuthoredRoom(src);
        DungeonRoomDefinition definition = room == null ? null : DungeonRoomData.definition(room.roomId());
        if (room == null || definition == null) return 0;
        if (index >= definition.waypoints().size()) {
            error(src, "Waypoint index out of range.");
            return 0;
        }
        DungeonRoomData.setWaypointTrigger(room.roomId(), index, trigger);
        session.resetRoom(room);
        success(src, "Waypoint [" + index + "] trigger -> "
                + trigger.name().toLowerCase(java.util.Locale.ROOT));
        return 1;
    }

    private int runHighlightList(FabricClientCommandSource src, int waypointIndex) {
        DungeonWaypoint waypoint = waypointAt(src, waypointIndex);
        if (waypoint == null) return 0;
        for (int i = 0; i < waypoint.highlights().size(); i++) {
            DungeonHighlight highlight = waypoint.highlights().get(i);
            info(src, "[" + i + "] " + highlight.style() + " "
                    + highlight.x() + ", " + highlight.y() + ", " + highlight.z());
        }
        if (waypoint.highlights().isEmpty()) info(src, "No highlights on this waypoint.");
        return waypoint.highlights().size();
    }

    private int runHighlightAdd(FabricClientCommandSource src, int waypointIndex,
                                DungeonHighlightStyle style) {
        DungeonRoom room = requireAuthoredRoom(src);
        LocalPlayer player = Minecraft.getInstance().player;
        if (room == null || player == null) {
            if (player == null) error(src, "Not in a world.");
            return 0;
        }
        DungeonRoomDefinition definition = DungeonRoomData.definition(room.roomId());
        if (definition == null || waypointIndex >= definition.waypoints().size()) {
            error(src, "Waypoint index out of range.");
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
        success(src, "Added " + style + " highlight to waypoint [" + waypointIndex + "].");
        return 1;
    }

    private int runBreakBoxAdd(FabricClientCommandSource src, int waypointIndex) {
        int result = runHighlightAdd(src, waypointIndex, DungeonHighlightStyle.OUTLINE_FILLED);
        if (result == 1) {
            DungeonRoom room = tracker.currentRoom();
            if (room != null) {
                DungeonRoomData.setWaypointTrigger(room.roomId(), waypointIndex,
                        DungeonWaypointTrigger.DUNGEONBREAKER);
                success(src, "Waypoint [" + waypointIndex
                        + "] now uses dungeonbreaker trigger for its break boxes.");
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
            error(src, "Highlight index out of range (0.." + (waypoint.highlights().size() - 1) + ").");
            return 0;
        }
        DungeonRoomData.removeHighlight(room.roomId(), waypointIndex, highlightIndex);
        success(src, "Removed highlight [" + highlightIndex + "] from waypoint [" + waypointIndex + "].");
        return 1;
    }

    private int runRouteNext(FabricClientCommandSource src) {
        DungeonRoom room = requireRoom(src);
        if (room == null) return 0;
        session.advance(room);
        success(src, "Advanced dungeon route to secret #" + session.currentSecretIndex(room) + ".");
        return 1;
    }

    private int runRouteReset(FabricClientCommandSource src) {
        DungeonRoom room = requireRoom(src);
        if (room == null) return 0;
        session.resetRoom(room);
        success(src, "Reset route progress for " + room.displayName() + ".");
        return 1;
    }

    private int runRouteFound(FabricClientCommandSource src, int secretIndex) {
        DungeonRoom room = requireRoom(src);
        if (room == null) return 0;
        session.markFound(room, secretIndex);
        success(src, "Marked secret #" + secretIndex + " found.");
        return 1;
    }

    private int runRotate(FabricClientCommandSource src, Direction dir) {
        tracker.setDirectionOverride(dir);
        success(src, "Rotated current room to " + dir + ". Persisting as default.");
        config.setDefaultDirection(dir.name());
        return 1;
    }

    private int runReset(FabricClientCommandSource src) {
        DungeonRoomData.clearAllCustom();
        success(src, "Cleared all runtime dungeon waypoints.");
        return 1;
    }

    private int runToggle(FabricClientCommandSource src, String which) {
        boolean newValue = switch (which) {
            case "enabled" -> {
                boolean v = !config.enabled();
                config.setEnabled(v);
                yield v;
            }
            case "waypoints" -> {
                boolean v = !config.showSecretWaypoints();
                config.setShowSecretWaypoints(v);
                yield v;
            }
            case "highlights" -> {
                boolean v = !config.showHighlights();
                config.setShowHighlights(v);
                yield v;
            }
            case "bounds" -> {
                boolean v = !config.drawRoomBounds();
                config.setDrawRoomBounds(v);
                yield v;
            }
            case "found" -> {
                boolean v = !config.showFoundSecrets();
                config.setShowFoundSecrets(v);
                yield v;
            }
            case "mode" -> {
                boolean active = !"ACTIVE".equalsIgnoreCase(config.routeRenderMode());
                config.setRouteRenderMode(active ? "ACTIVE" : "ALL");
                yield active;
            }
            case "debug" -> {
                boolean v = !config.debugLogRoomChanges();
                config.setDebugLogRoomChanges(v);
                yield v;
            }
            default -> false;
        };
        success(src, "Dungeon " + which + " -> " + newValue);
        Waypointer.LOGGER.info("Dungeon toggle: {} -> {}", which, newValue);
        return 1;
    }

    private DungeonRoom requireRoom(FabricClientCommandSource src) {
        DungeonRoom room = tracker.currentRoom();
        if (room == null) error(src, "No room detected. Stand in a Catacombs room and try again.");
        return room;
    }

    private DungeonRoom requireAuthoredRoom(FabricClientCommandSource src) {
        DungeonRoom room = requireRoom(src);
        if (room == null) return null;
        if (!room.hasRoomId() || DungeonRoomData.definition(room.roomId()) == null) {
            error(src, "Create a room definition first with /wpd room create <id> [name].");
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
            error(src, "Waypoint index out of range (0.." + (definition.waypoints().size() - 1) + ").");
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

    private static void info(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Component.literal(msg).withStyle(ChatFormatting.GRAY));
    }

    private static void success(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Component.literal(msg).withStyle(ChatFormatting.GREEN));
    }

    private static void error(FabricClientCommandSource src, String msg) {
        src.sendError(Component.literal(msg).withStyle(ChatFormatting.RED));
    }
}
