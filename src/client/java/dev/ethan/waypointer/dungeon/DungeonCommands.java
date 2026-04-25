package dev.ethan.waypointer.dungeon;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

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

    public DungeonCommands(DungeonStateTracker tracker, DungeonConfig config) {
        this.tracker = tracker;
        this.config = config;
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
                .then(literal("rotate")
                        .then(literal("nw").executes(ctx -> runRotate(ctx.getSource(), Direction.NW)))
                        .then(literal("ne").executes(ctx -> runRotate(ctx.getSource(), Direction.NE)))
                        .then(literal("sw").executes(ctx -> runRotate(ctx.getSource(), Direction.SW)))
                        .then(literal("se").executes(ctx -> runRotate(ctx.getSource(), Direction.SE))))
                .then(literal("toggle")
                        .then(literal("enabled").executes(ctx -> runToggle(ctx.getSource(), "enabled")))
                        .then(literal("waypoints").executes(ctx -> runToggle(ctx.getSource(), "waypoints")))
                        .then(literal("highlights").executes(ctx -> runToggle(ctx.getSource(), "highlights")))
                        .then(literal("bounds").executes(ctx -> runToggle(ctx.getSource(), "bounds")))
                        .then(literal("debug").executes(ctx -> runToggle(ctx.getSource(), "debug"))));
        d.register(cmd);
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
        info(src, "Dungeon room: " + room.type() + " " + room.shape()
                + " dir=" + room.direction()
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
            DungeonRoomData.addCustom(room.identityKey(), wp);
        }
        success(src, "Added " + demo.size() + " demo waypoint(s) for shape "
                + room.shape() + " in current room.");
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
