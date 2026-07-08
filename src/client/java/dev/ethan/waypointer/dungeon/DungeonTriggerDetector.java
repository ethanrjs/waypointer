package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Converts normal client-observable dungeon actions into secret progress.
 *
 * <p>This deliberately stays client-side and best-effort. It never sends
 * packets or automates actions; it only watches the player interact with
 * authored targets and advances the local route when the expected evidence is
 * visible to the client.
 *
 * <p>Detection is event-driven rather than polled wherever the client exposes
 * an event:
 *
 * <ul>
 *   <li><b>Interacts</b> (chests, levers, essence skulls) --
 *       {@link UseBlockCallback}.</li>
 *   <li><b>Item pickups</b> -- {@link ClientEntityEvents#ENTITY_UNLOAD} on
 *       item entities. Polling every N ticks misses items collected within the
 *       polling window, which is the common case when the player stands on the
 *       spawn point. The unload event fires for every removal, so instant
 *       pickups are seen too; despawn false-positives are filtered by the
 *       known secret-drop names and the player-proximity requirement.</li>
 *   <li><b>Bats</b> -- Hypixel plays {@code BAT_HURT}/{@code BAT_DEATH} at the
 *       telltale volume {@code 0.1} when a secret bat dies. The sound carries
 *       the death position, unlike entity unloads which also fire when a bat
 *       merely flies out of tracking range.</li>
 *   <li><b>Etherwarps</b> -- armed by a sneak + right-click with an Aspect of
 *       the Void/End, then confirmed by the instant position jump the server
 *       teleport produces within the next second.</li>
 * </ul>
 */
public final class DungeonTriggerDetector {

    private static final int SCAN_INTERVAL_TICKS = 4;
    private static final double ENTITY_TRIGGER_RANGE_SQ = 36.0;
    private static final double ITEM_PICKUP_PLAYER_RANGE_SQ = 36.0;
    private static final double ETHERWARP_TRIGGER_RANGE_SQ = 9.0;
    /** Position delta per tick that distinguishes a teleport from running/jumping. */
    private static final double ETHERWARP_JUMP_DISTANCE_SQ = 4.0;
    private static final long ETHERWARP_ARM_WINDOW_MS = 1_500L;
    private static final float SECRET_BAT_SOUND_VOLUME = 0.1f;

    /**
     * Item names Hypixel uses for secret drops (matched case-insensitively as
     * substrings). Same curated list Odin uses; anything else that vanishes is
     * a despawn or an unrelated drop.
     */
    private static final List<String> SECRET_ITEM_NAMES = List.of(
            "health potion", "healing potion", "healing viii", "healing 8",
            "decoy", "inflatable jerry", "spirit leap", "trap", "training weights",
            "defuse kit", "dungeon chest key", "treasure talisman", "revive stone",
            "architect's first draft", "secret dye", "candycomb");

    private static final List<String> ETHERWARP_ITEM_NAMES =
            List.of("aspect of the void", "aspect of the end");

    private static final Set<DungeonWaypointTrigger> ITEM_TRIGGERS =
            EnumSet.of(DungeonWaypointTrigger.PICKUP_ITEM, DungeonWaypointTrigger.ANY_SECRET);
    private static final Set<DungeonWaypointTrigger> BAT_TRIGGERS =
            EnumSet.of(DungeonWaypointTrigger.KILL_BAT, DungeonWaypointTrigger.ANY_SECRET);
    private static final Set<DungeonWaypointTrigger> ETHERWARP_TRIGGERS =
            EnumSet.of(DungeonWaypointTrigger.ETHERWARP, DungeonWaypointTrigger.ANY_SECRET);

    private final DungeonStateTracker tracker;
    private final DungeonRouteSession session;

    private int tickCounter;
    private long etherwarpArmedAtMillis;
    private Vec3 lastTickPosition;

    public DungeonTriggerDetector(DungeonStateTracker tracker, DungeonRouteSession session) {
        this.tracker = tracker;
        this.session = session;
    }

    public void install() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide()) {
                onUseBlock(hit.getBlockPos(), player.getItemInHand(hand));
            }
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide()) {
                onUseItem(player.getItemInHand(hand), player.isShiftKeyDown());
            }
            return InteractionResult.PASS;
        });
        ClientEntityEvents.ENTITY_UNLOAD.register(this::onEntityUnload);
        ClientReceiveMessageEvents.GAME.register(this::onChatMessage);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        DungeonSoundHook.setListener(this::onSoundPacket);
    }

    // ---- interacts (chests, levers, essence skulls) ----------------------

    private void onUseBlock(BlockPos pos, ItemStack held) {
        DungeonRoom room = tracker.currentRoom();
        if (room == null) return;
        List<DungeonWaypoint> waypoints = DungeonRoomData.waypointsFor(room);

        ClientLevel level = Minecraft.getInstance().level;
        BlockState state = level == null ? null : level.getBlockState(pos);
        for (DungeonWaypoint waypoint : waypoints) {
            if (!matchesAnyTarget(room, waypoint, pos)) continue;
            if (useMatchesTrigger(waypoint, held, state)) {
                session.markFound(room, waypoint.secretIndex());
            }
        }
    }

    // ---- etherwarp --------------------------------------------------------

    private void onUseItem(ItemStack held, boolean sneaking) {
        if (!sneaking || held == null || held.isEmpty()) return;
        if (tracker.currentRoom() == null) return;
        String name = held.getHoverName().getString().toLowerCase(Locale.ROOT);
        for (String etherwarpItem : ETHERWARP_ITEM_NAMES) {
            if (name.contains(etherwarpItem)) {
                etherwarpArmedAtMillis = System.currentTimeMillis();
                return;
            }
        }
    }

    private void checkEtherwarpLanding(LocalPlayer player) {
        Vec3 previous = lastTickPosition;
        Vec3 current = player.position();
        lastTickPosition = current;

        if (previous == null || etherwarpArmedAtMillis == 0L) return;
        if (System.currentTimeMillis() - etherwarpArmedAtMillis > ETHERWARP_ARM_WINDOW_MS) {
            etherwarpArmedAtMillis = 0L;
            return;
        }
        if (previous.distanceToSqr(current) < ETHERWARP_JUMP_DISTANCE_SQ) return;

        etherwarpArmedAtMillis = 0L;
        DungeonRoom room = tracker.currentRoom();
        if (room == null) return;
        // The teleport lands the player standing on the warped-to block, so
        // the landing position itself is the authored waypoint position.
        DungeonWaypoint waypoint = DungeonTriggerSelection.nearestEntityTrigger(
                room, DungeonRoomData.waypointsFor(room), ETHERWARP_TRIGGERS,
                current.x, current.y, current.z, ETHERWARP_TRIGGER_RANGE_SQ);
        if (waypoint != null) session.markFound(room, waypoint.secretIndex());
    }

    // ---- item pickups ------------------------------------------------------

    private void onEntityUnload(Entity entity, ClientLevel level) {
        if (!(entity instanceof ItemEntity item)) return;
        DungeonRoom room = tracker.currentRoom();
        if (room == null) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null
                || player.distanceToSqr(entity) > ITEM_PICKUP_PLAYER_RANGE_SQ
                || !isSecretItemName(item.getItem().getHoverName().getString())) {
            return;
        }

        DungeonWaypoint waypoint = DungeonTriggerSelection.nearestEntityTrigger(
                room, DungeonRoomData.waypointsFor(room), ITEM_TRIGGERS,
                entity.getX(), entity.getY(), entity.getZ(), ENTITY_TRIGGER_RANGE_SQ);
        if (waypoint != null) session.markFound(room, waypoint.secretIndex());
    }

    private static boolean isSecretItemName(String rawName) {
        if (rawName == null || rawName.isEmpty()) return false;
        String name = rawName.toLowerCase(Locale.ROOT);
        for (String secretName : SECRET_ITEM_NAMES) {
            if (name.contains(secretName)) return true;
        }
        return false;
    }

    // ---- bats ---------------------------------------------------------------

    private void onSoundPacket(SoundEvent sound, float volume, double x, double y, double z) {
        if (volume != SECRET_BAT_SOUND_VOLUME) return;
        if (sound != SoundEvents.BAT_HURT && sound != SoundEvents.BAT_DEATH) return;
        DungeonRoom room = tracker.currentRoom();
        if (room == null) return;

        DungeonWaypoint waypoint = DungeonTriggerSelection.nearestEntityTrigger(
                room, DungeonRoomData.waypointsFor(room), BAT_TRIGGERS,
                x, y, z, ENTITY_TRIGGER_RANGE_SQ);
        if (waypoint != null) session.markFound(room, waypoint.secretIndex());
    }

    // ---- chat + break polling ------------------------------------------------

    private void onChatMessage(Component message, boolean overlay) {
        DungeonRoom room = tracker.currentRoom();
        if (room == null) return;

        String text = message.getString();
        List<DungeonWaypoint> waypoints = DungeonRoomData.waypointsFor(room);
        for (DungeonWaypoint waypoint : waypoints) {
            if (waypoint.trigger() != DungeonWaypointTrigger.CHAT_MESSAGE) continue;
            if (DungeonTriggerSelection.chatMessageMatchesWaypoint(text, waypoint)) {
                session.markFound(room, waypoint.secretIndex());
            }
        }
    }

    private void onTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            lastTickPosition = null;
            etherwarpArmedAtMillis = 0L;
        } else {
            checkEtherwarpLanding(player);
        }

        if (++tickCounter < SCAN_INTERVAL_TICKS) return;
        tickCounter = 0;

        DungeonRoom room = tracker.currentRoom();
        ClientLevel level = client.level;
        if (room == null || level == null) return;

        checkBreakTargets(level, room, DungeonRoomData.waypointsFor(room));
    }

    private void checkBreakTargets(ClientLevel level, DungeonRoom room,
                                   List<DungeonWaypoint> waypoints) {
        for (DungeonWaypoint waypoint : waypoints) {
            if (waypoint.trigger() != DungeonWaypointTrigger.BREAK_BLOCKS
                    && waypoint.trigger() != DungeonWaypointTrigger.DUNGEONBREAKER
                    && waypoint.trigger() != DungeonWaypointTrigger.USE_SUPERBOOM) {
                continue;
            }
            List<BlockPos> targets = worldTargets(room, waypoint);
            if (targets.isEmpty()) continue;
            boolean allCleared = true;
            for (BlockPos target : targets) {
                if (!level.getBlockState(target).isAir()) {
                    allCleared = false;
                    break;
                }
            }
            if (allCleared) session.markFound(room, waypoint.secretIndex());
        }
    }

    private boolean useMatchesTrigger(DungeonWaypoint waypoint, ItemStack held, BlockState state) {
        return switch (waypoint.trigger()) {
            case INTERACT_BLOCK -> state != null && !state.isAir();
            // Wither/redstone essence secrets are skull blocks the player clicks,
            // so "open" covers both containers and essence heads.
            case OPEN_CHEST -> state != null
                    && (state.is(Blocks.CHEST)
                    || state.is(Blocks.TRAPPED_CHEST)
                    || state.getBlock() instanceof AbstractSkullBlock);
            case FLIP_LEVER -> state != null && state.is(Blocks.LEVER);
            case USE_SUPERBOOM -> held != null
                    && held.is(Items.TNT)
                    && DungeonTriggerSelection.itemNameMatchesSuperboom(
                    held.getHoverName().getString());
            case ANY_SECRET -> state != null && isSecretInteractBlock(state);
            default -> false;
        };
    }

    /** Block kinds Hypixel uses for click-to-collect secrets. */
    static boolean isSecretInteractBlock(BlockState state) {
        return state.is(Blocks.CHEST)
                || state.is(Blocks.TRAPPED_CHEST)
                || state.is(Blocks.LEVER)
                || state.getBlock() instanceof AbstractSkullBlock;
    }

    private static boolean matchesAnyTarget(DungeonRoom room, DungeonWaypoint waypoint, BlockPos pos) {
        for (BlockPos target : worldTargets(room, waypoint)) {
            if (target.equals(pos)) return true;
        }
        return false;
    }

    private static List<BlockPos> worldTargets(DungeonRoom room, DungeonWaypoint waypoint) {
        List<BlockPos> targets = new ArrayList<>(1 + waypoint.highlights().size());
        targets.add(worldPos(room, waypoint.x(), waypoint.y(), waypoint.z()));
        for (DungeonHighlight highlight : waypoint.highlights()) {
            targets.add(worldPos(room, highlight.x(), highlight.y(), highlight.z()));
        }
        return List.copyOf(targets);
    }

    private static BlockPos worldPos(DungeonRoom room, int x, int y, int z) {
        int[] world = DungeonMapMath.relativeToActual(
                room.direction(), room.physicalCornerX(), room.physicalCornerZ(), x, y, z);
        return new BlockPos(world[0], world[1], world[2]);
    }
}
