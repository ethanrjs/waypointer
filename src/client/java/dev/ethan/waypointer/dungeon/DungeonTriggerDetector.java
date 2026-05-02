package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts normal client-observable dungeon actions into secret progress.
 *
 * <p>This deliberately stays client-side and best-effort. It never sends
 * packets or automates actions; it only watches the player interact with
 * authored targets and advances the local route when the expected evidence is
 * visible to the client.
 */
public final class DungeonTriggerDetector {

    private static final int SCAN_INTERVAL_TICKS = 4;
    private static final double ENTITY_TRIGGER_RANGE_SQ = 36.0;

    private final DungeonStateTracker tracker;
    private final DungeonRouteSession session;
    private final Map<Integer, Vec3> nearbyItemLastPositions = new HashMap<>();
    private final Map<Integer, Vec3> nearbyBatLastPositions = new HashMap<>();

    private int tickCounter;

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
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide()) onAttackBlock(pos);
            return InteractionResult.PASS;
        });
        ClientReceiveMessageEvents.GAME.register(this::onChatMessage);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onUseBlock(BlockPos pos, ItemStack held) {
        DungeonRoom room = tracker.currentRoom();
        if (room == null) return;

        ClientLevel level = Minecraft.getInstance().level;
        BlockState state = level == null ? null : level.getBlockState(pos);
        for (DungeonWaypoint waypoint : DungeonRoomData.waypointsFor(room)) {
            if (!matchesAnyTarget(room, waypoint, pos)) continue;
            if (useMatchesTrigger(waypoint, held, state)) {
                session.markFound(room, waypoint.secretIndex());
            }
        }
    }

    private void onAttackBlock(BlockPos pos) {
        DungeonRoom room = tracker.currentRoom();
        if (room == null) return;
        for (DungeonWaypoint waypoint : DungeonRoomData.waypointsFor(room)) {
            if ((waypoint.trigger() == DungeonWaypointTrigger.BREAK_BLOCKS
                    || waypoint.trigger() == DungeonWaypointTrigger.DUNGEONBREAKER)
                    && matchesAnyTarget(room, waypoint, pos)) {
                session.markFound(room, waypoint.secretIndex());
            }
        }
    }

    private void onChatMessage(Component message, boolean overlay) {
        DungeonRoom room = tracker.currentRoom();
        if (room == null) return;

        String text = message.getString().toLowerCase(Locale.ROOT);
        for (DungeonWaypoint waypoint : DungeonRoomData.waypointsFor(room)) {
            if (waypoint.trigger() != DungeonWaypointTrigger.CHAT_MESSAGE) continue;
            String needle = waypoint.hasName() ? waypoint.name() : waypoint.category().id;
            if (!needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) {
                session.markFound(room, waypoint.secretIndex());
            }
        }
    }

    private void onTick(Minecraft client) {
        if (++tickCounter < SCAN_INTERVAL_TICKS) return;
        tickCounter = 0;

        DungeonRoom room = tracker.currentRoom();
        ClientLevel level = client.level;
        if (room == null || level == null) {
            nearbyItemLastPositions.clear();
            nearbyBatLastPositions.clear();
            return;
        }

        checkBreakTargets(level, room);
        checkEntityDisappearance(level, room);
    }

    private void checkBreakTargets(ClientLevel level, DungeonRoom room) {
        for (DungeonWaypoint waypoint : DungeonRoomData.waypointsFor(room)) {
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

    private void checkEntityDisappearance(ClientLevel level, DungeonRoom room) {
        Map<Integer, Vec3> itemsNow = new HashMap<>();
        Map<Integer, Vec3> batsNow = new HashMap<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof ItemEntity && nearAnyEntityTrigger(room, entity, DungeonWaypointTrigger.PICKUP_ITEM)) {
                itemsNow.put(entity.getId(), entity.position());
            } else if (entity instanceof AmbientCreature
                    && nearAnyEntityTrigger(room, entity, DungeonWaypointTrigger.KILL_BAT)) {
                batsNow.put(entity.getId(), entity.position());
            }
        }

        for (Integer id : nearbyItemLastPositions.keySet()) {
            if (!itemsNow.containsKey(id)) {
                Vec3 lastPos = nearbyItemLastPositions.get(id);
                markNearestWaypointForEntityTrigger(room, DungeonWaypointTrigger.PICKUP_ITEM, lastPos);
            }
        }
        for (Integer id : nearbyBatLastPositions.keySet()) {
            if (!batsNow.containsKey(id)) {
                Vec3 lastPos = nearbyBatLastPositions.get(id);
                markNearestWaypointForEntityTrigger(room, DungeonWaypointTrigger.KILL_BAT, lastPos);
            }
        }
        nearbyItemLastPositions.clear();
        nearbyItemLastPositions.putAll(itemsNow);
        nearbyBatLastPositions.clear();
        nearbyBatLastPositions.putAll(batsNow);
    }

    private boolean useMatchesTrigger(DungeonWaypoint waypoint, ItemStack held, BlockState state) {
        return switch (waypoint.trigger()) {
            case INTERACT_BLOCK -> true;
            case OPEN_CHEST -> state != null && (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST));
            case FLIP_LEVER -> state != null && state.is(Blocks.LEVER);
            case USE_SUPERBOOM -> itemNameContains(held, "superboom");
            default -> false;
        };
    }

    private static boolean itemNameContains(ItemStack stack, String needle) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean nearAnyEntityTrigger(DungeonRoom room, Entity entity,
                                                DungeonWaypointTrigger trigger) {
        for (DungeonWaypoint waypoint : DungeonRoomData.waypointsFor(room)) {
            if (waypoint.trigger() == trigger && distanceToWaypoint(room, waypoint, entity) <= ENTITY_TRIGGER_RANGE_SQ) {
                return true;
            }
        }
        return false;
    }

    private void markNearestWaypointForEntityTrigger(DungeonRoom room, DungeonWaypointTrigger trigger,
                                                     Vec3 entityPos) {
        if (entityPos == null) return;
        DungeonWaypoint best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (DungeonWaypoint waypoint : DungeonRoomData.waypointsFor(room)) {
            if (waypoint.trigger() != trigger) continue;
            double distSq = distanceToWaypoint(room, waypoint, entityPos);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = waypoint;
            }
        }
        if (best != null && bestDistSq <= ENTITY_TRIGGER_RANGE_SQ) {
            session.markFound(room, best.secretIndex());
        }
    }

    private static double distanceToWaypoint(DungeonRoom room, DungeonWaypoint waypoint, Entity entity) {
        return distanceToWaypoint(room, waypoint, entity.position());
    }

    private static double distanceToWaypoint(DungeonRoom room, DungeonWaypoint waypoint, Vec3 entityPos) {
        BlockPos pos = worldPos(room, waypoint.x(), waypoint.y(), waypoint.z());
        double dx = entityPos.x - (pos.getX() + 0.5);
        double dy = entityPos.y - (pos.getY() + 0.5);
        double dz = entityPos.z - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean matchesAnyTarget(DungeonRoom room, DungeonWaypoint waypoint, BlockPos pos) {
        for (BlockPos target : worldTargets(room, waypoint)) {
            if (target.equals(pos)) return true;
        }
        return false;
    }

    private static List<BlockPos> worldTargets(DungeonRoom room, DungeonWaypoint waypoint) {
        if (!waypoint.highlights().isEmpty()) {
            return waypoint.highlights().stream()
                    .map(h -> worldPos(room, h.x(), h.y(), h.z()))
                    .toList();
        }
        return List.of(worldPos(room, waypoint.x(), waypoint.y(), waypoint.z()));
    }

    private static BlockPos worldPos(DungeonRoom room, int x, int y, int z) {
        int[] world = DungeonMapMath.relativeToActual(
                room.direction(), room.physicalCornerX(), room.physicalCornerZ(), x, y, z);
        return new BlockPos(world[0], world[1], world[2]);
    }
}
