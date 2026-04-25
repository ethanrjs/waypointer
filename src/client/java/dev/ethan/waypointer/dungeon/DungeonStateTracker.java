package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Detects what room the player is standing in inside Catacombs and exposes
 * that as a {@link DungeonRoom} read by the renderer.
 *
 * <p>Pipeline (re-implemented from Skyblocker's {@code DungeonManager.update};
 * LGPL-3.0):
 *
 * <ol>
 *   <li>Subscribe to {@link ActiveGroupManager}'s zone listener; only do work
 *       when the resolved zone id starts with {@code "dungeon_"}.</li>
 *   <li>Per tick (throttled): find Mort the entrance NPC's armor-stand to
 *       anchor the dungeon's origin in world coordinates.</li>
 *   <li>Read the dungeon map item from the player's last hotbar slot to get
 *       the entrance pixel + per-room pixel size.</li>
 *   <li>Snap the player's position to a 32-block grid to find the NW corner
 *       of the segment they're standing in.</li>
 *   <li>Look up that segment's color on the map to classify the room type;
 *       flood-fill same-color segments to derive the room's shape.</li>
 *   <li>Compute the canonical-frame physical corner from the segment bounds
 *       + the configured (or runtime-rotated) {@link Direction}.</li>
 * </ol>
 *
 * <p>State mutates on the client tick thread; the public read accessors are
 * safe to call from the render thread because the {@code currentRoom}
 * reference is volatile and {@link DungeonRoom} is immutable.
 *
 * <p><b>Not implemented yet (see issue #9 follow-ups):</b>
 *
 * <ul>
 *   <li>Block-fingerprint room identification -- tracker reports "this is a
 *       1x2 ROOM" but not "this is the Lava Ravine room". Direction is
 *       therefore guessed from {@link DungeonConfig#defaultDirection()} until
 *       fingerprinting is available.</li>
 *   <li>Per-secret found tracking driven by chat / interaction events.</li>
 * </ul>
 */
public final class DungeonStateTracker {

    /**
     * Throttle the per-tick scan. Most state changes only matter when the
     * player crosses a 32-block grid boundary, which can't happen faster
     * than once every several ticks even at a sprint, so 5-tick (4Hz)
     * cadence is more than enough.
     */
    private static final int SCAN_INTERVAL_TICKS = 5;

    private final ActiveGroupManager manager;
    private final DungeonConfig config;
    private final List<Consumer<DungeonRoom>> listeners = new ArrayList<>();

    private volatile boolean inDungeon;
    private volatile DungeonRoom currentRoom;

    /** World position of the entrance room's NW corner, snapped to the 32-grid. */
    private volatile int physicalEntranceX;
    private volatile int physicalEntranceZ;
    private volatile boolean entranceAnchored;

    /** Map-pixel position of the entrance room's NW pixel + the per-room pixel size. */
    private volatile int mapEntranceX;
    private volatile int mapEntranceZ;
    private volatile int mapRoomSize;
    private volatile boolean mapAnchored;

    private volatile Direction directionOverride;

    private int tickCounter;

    public DungeonStateTracker(ActiveGroupManager manager, DungeonConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void install() {
        manager.addZoneListener(this::onZoneChanged);
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    /** Listener fired whenever the detected current room changes (including to {@code null}). */
    public void addRoomListener(Consumer<DungeonRoom> l) { listeners.add(l); }

    public boolean inDungeon()             { return inDungeon; }
    public DungeonRoom currentRoom()       { return currentRoom; }
    public Direction directionOverride()   { return directionOverride; }

    /** Cycle the active room's assumed direction. Resets to config default when null. */
    public void setDirectionOverride(Direction dir) {
        this.directionOverride = dir;
        // Drop the cached room so the next tick picks the new direction up.
        DungeonRoom prev = currentRoom;
        if (prev != null) {
            DungeonRoom rotated = new DungeonRoom(
                    prev.type(),
                    prev.shape(),
                    dir == null ? defaultDirection() : dir,
                    prev.physicalCornerX(),
                    prev.physicalCornerZ(),
                    prev.segments());
            currentRoom = rotated;
            fireRoomChanged(rotated);
        }
    }

    // ---- zone -> dungeon state -----------------------------------------

    private void onZoneChanged(Zone zone) {
        boolean nowDungeon = zone != null && zone.id() != null && zone.id().startsWith("dungeon_");
        if (nowDungeon == inDungeon) return;
        inDungeon = nowDungeon;
        // Reset every cached anchor so a re-entry picks them up fresh -- the
        // physical entrance moves to a new instance each run.
        entranceAnchored = false;
        mapAnchored = false;
        DungeonRoom prev = currentRoom;
        if (prev != null) {
            currentRoom = null;
            fireRoomChanged(null);
        }
        if (nowDungeon) {
            Waypointer.LOGGER.info("Dungeon detected: {} -- searching for Mort + dungeon map", zone.id());
        }
    }

    // ---- per-tick scan -------------------------------------------------

    private void onClientTick(Minecraft client) {
        if (!config.enabled() || !inDungeon) return;
        if (++tickCounter < SCAN_INTERVAL_TICKS) return;
        tickCounter = 0;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) return;

        if (!entranceAnchored && !tryAnchorEntrance(level)) return;

        MapItemSavedData map = getDungeonMap(client);
        if (map == null) return;

        if (!mapAnchored && !tryAnchorMap(map)) return;

        // Player's current 32-block segment (NW corner) in world coords.
        int[] physRoomCorner = DungeonMapMath.physicalSegmentCorner(player.getX(), player.getZ());
        int[] mapPixel = DungeonMapMath.physicalToMap(
                physicalEntranceX, physicalEntranceZ,
                mapEntranceX, mapEntranceZ,
                mapRoomSize,
                physRoomCorner[0], physRoomCorner[1]);

        byte color = DungeonMapMath.getColor(map, mapPixel[0], mapPixel[1]);
        DungeonRoomType type = DungeonRoomType.fromMapColor(color);
        if (type == null) {
            // Player is between rooms (a corridor) or off the map.
            if (currentRoom != null) {
                currentRoom = null;
                fireRoomChanged(null);
            }
            return;
        }

        // Flood-fill segments to find the room's full footprint, then build
        // the immutable DungeonRoom record.
        List<int[]> mapSegments = DungeonMapMath.floodSegments(map, mapPixel[0], mapPixel[1], mapRoomSize, color);
        DungeonRoom built = buildRoom(type, mapSegments);
        if (built == null) return;

        DungeonRoom prev = currentRoom;
        if (prev == null || !prev.identityKey().equals(built.identityKey())) {
            currentRoom = built;
            fireRoomChanged(built);
        }
    }

    private DungeonRoom buildRoom(DungeonRoomType type, List<int[]> mapSegments) {
        if (mapSegments.isEmpty()) return null;

        // Convert each map-pixel segment to its physical NW corner so shape
        // classification can compare integer-grid spans cleanly.
        int minSegX = Integer.MAX_VALUE, minSegZ = Integer.MAX_VALUE;
        int maxSegX = Integer.MIN_VALUE, maxSegZ = Integer.MIN_VALUE;
        java.util.Set<Integer> distinctX = new java.util.HashSet<>();
        java.util.Set<Integer> distinctZ = new java.util.HashSet<>();
        List<Long> packed = new ArrayList<>(mapSegments.size());
        for (int[] mp : mapSegments) {
            int[] phys = DungeonMapMath.mapToPhysical(
                    mapEntranceX, mapEntranceZ, mapRoomSize,
                    physicalEntranceX, physicalEntranceZ,
                    mp[0], mp[1]);
            packed.add(DungeonRoom.packSegment(phys[0], phys[1]));
            if (phys[0] < minSegX) minSegX = phys[0];
            if (phys[1] < minSegZ) minSegZ = phys[1];
            if (phys[0] > maxSegX) maxSegX = phys[0];
            if (phys[1] > maxSegZ) maxSegZ = phys[1];
            distinctX.add(phys[0]);
            distinctZ.add(phys[1]);
        }

        DungeonRoomShape shape = (type == DungeonRoomType.ROOM)
                ? DungeonRoomShape.classify(packed.size(), distinctX.size(), distinctZ.size())
                : DungeonRoomShape.ONE_BY_ONE;

        Direction dir = directionOverride != null ? directionOverride : defaultDirection();
        int[] corner = DungeonMapMath.physicalCorner(dir, minSegX, minSegZ, maxSegX, maxSegZ);
        return new DungeonRoom(type, shape, dir, corner[0], corner[1], packed);
    }

    // ---- anchors -------------------------------------------------------

    private boolean tryAnchorEntrance(ClientLevel level) {
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand)) continue;
            Component name = stand.getCustomName();
            if (name == null) continue;
            if (!name.getString().contains("Mort")) continue;
            int[] corner = DungeonMapMath.physicalSegmentCorner(stand.getX(), stand.getZ());
            physicalEntranceX = corner[0];
            physicalEntranceZ = corner[1];
            entranceAnchored = true;
            Waypointer.LOGGER.info("Dungeon entrance anchored at world ({}, {}) via Mort", corner[0], corner[1]);
            return true;
        }
        return false;
    }

    private boolean tryAnchorMap(MapItemSavedData map) {
        int[] entrance = DungeonMapMath.findEntranceAndRoomSize(map);
        if (entrance == null || entrance[2] <= 0) return false;
        mapEntranceX = entrance[0];
        mapEntranceZ = entrance[1];
        mapRoomSize = entrance[2];
        mapAnchored = true;
        Waypointer.LOGGER.info("Dungeon map anchored at pixel ({}, {}) with room size {}",
                entrance[0], entrance[1], entrance[2]);
        return true;
    }

    private static MapItemSavedData getDungeonMap(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) return null;
        // Slot 8 is the rightmost hotbar slot, where Hypixel auto-equips the
        // dungeon map item on entry. Reading directly from there matches
        // Skyblocker's approach and avoids scanning the full inventory.
        ItemStack stack = player.getInventory().getItem(8);
        if (stack.isEmpty()) return null;
        MapId id = stack.get(DataComponents.MAP_ID);
        if (id == null) return null;
        return MapItem.getSavedData(id, level);
    }

    // ---- helpers -------------------------------------------------------

    private Direction defaultDirection() {
        try {
            return Direction.valueOf(config.defaultDirection().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Direction.NW;
        }
    }

    private void fireRoomChanged(DungeonRoom room) {
        if (config.debugLogRoomChanges()) {
            Waypointer.LOGGER.info("Dungeon room changed -> {}", room == null ? "<none>" : room.identityKey());
        }
        for (Consumer<DungeonRoom> l : listeners) l.accept(room);
    }
}
