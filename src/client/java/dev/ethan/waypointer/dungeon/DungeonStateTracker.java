package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Detects what room the player is standing in inside Catacombs and exposes
 * that as a {@link DungeonRoom} read by the renderer.
 *
 * <p>Pipeline:
 *
 * <ol>
 *   <li>Subscribe to {@link ActiveGroupManager}'s zone listener; only do work
 *       when the resolved zone id starts with {@code "dungeon_"}.</li>
 *   <li>Snap the player's position to a 32-block grid to find the NW corner
 *       of the segment they're standing in.</li>
 *   <li>Hash that segment's room-core column and resolve it through
 *       {@link DungeonRoomData}'s core catalog, then recursively add adjacent
 *       32-block components whose cores belong to the same room.</li>
 *   <li>Cache every detected component so revisits and nearby loaded rooms are
 *       instant.</li>
 *   <li>Opportunistically read the dungeon map item and Mort anchor to scan
 *       visible map cells in the background. Map topology is a hint; core
 *       hashes are the identity source.</li>
 * </ol>
 *
 * <p>State mutates on the client tick thread; the public read accessors are
 * safe to call from the render thread because the {@code currentRoom}
 * reference is volatile and {@link DungeonRoom} is immutable.
 *
 * <p><b>Remaining follow-ups:</b>
 *
 * <ul>
 *   <li>Rotation discovery from room markers instead of relying only on
 *       {@link DungeonConfig#defaultDirection()} or a manual override.</li>
 *   <li>Per-secret found tracking driven by chat / interaction events.</li>
 * </ul>
 */
public final class DungeonStateTracker {

    /**
     * Run the cheap current-room cache/core check every tick so room changes
     * feel immediate. Expensive work is bounded by segment caches and only
     * happens when a segment has not been identified yet.
     */
    private static final int SCAN_INTERVAL_TICKS = 1;
    private static final int MAX_ROOM_COMPONENTS = 4;

    private final ActiveGroupManager manager;
    private final DungeonConfig config;
    private final List<Consumer<DungeonRoom>> listeners = new ArrayList<>();
    private final Map<Long, DungeonRoom> knownRoomsBySegment = new HashMap<>();

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
        Zone currentZone = manager.currentZone();
        if (currentZone != null) onZoneChanged(currentZone);
    }

    /** Listener fired whenever the detected current room changes (including to {@code null}). */
    public void addRoomListener(Consumer<DungeonRoom> l) { listeners.add(l); }

    public boolean inDungeon()             { return inDungeon; }
    public DungeonRoom currentRoom()       { return currentRoom; }
    public Direction directionOverride()   { return directionOverride; }

    /*[[AI-FN-DOC
Function:
DungeonStateTracker.applyCurrentRoomDefinition
Purpose:
Replace the currently detected room's catalog identity after the user manually applies or renames a room definition.
Why this exists:
Manual dungeon-room editing needs to update the live room immediately so the zone bridge, renderer, and route lookup see the corrected id without waiting for another scan.
When to use:
Call from UI or command code that intentionally applies a known room id/name to the current detected room; do not call when no room is active.
Inputs:
id is the normalized or normalizable room definition id to attach; name is the display label to show for the room.
Outputs:
No return value; currentRoom is updated when a room exists.
Side effects:
Mutates currentRoom, refreshes knownRoomsBySegment entries for the same physical components, and notifies room listeners.
Failure modes:
If there is no current room the method returns without changing state; invalid ids are not validated here because catalog editing owns that boundary.
Important invariants:
The physical room geometry must remain unchanged, only the catalog identity changes; the segment cache must not keep the stale identity for those components.
Internal logic:
Read currentRoom, return if null, create a copy with the requested definition, replace currentRoom, cache the updated room by all of its segments, and fire the room-change event.
Pseudocode:
prev = currentRoom
if prev is null return
updated = prev.withDefinition(id, name)
currentRoom = updated
cacheRoom(updated)
fireRoomChanged(updated)
Implementation notes:
Updating the cache avoids the next tick restoring the previous auto-detected identity from knownRoomsBySegment.
AI self-check:
Verify geometry is preserved, listeners still fire once, and the segment cache is refreshed after manual identity changes.
]]*/
    public void applyCurrentRoomDefinition(String id, String name) {
        DungeonRoom prev = currentRoom;
        if (prev == null) return;
        DungeonRoom updated = prev.withDefinition(id, name);
        currentRoom = updated;
        cacheRoom(updated);
        fireRoomChanged(updated);
    }

    /*[[AI-FN-DOC
Function:
DungeonStateTracker.setDirectionOverride
Purpose:
Apply a runtime dungeon-room direction override and rotate the current room state to that direction.
Why this exists:
Some room-local waypoint projections depend on orientation, and the user can cycle the assumed direction when automatic rotation is not sufficient.
When to use:
Call from direction-cycling UI or command code with a concrete Direction, or null to return to the configured default direction.
Inputs:
dir is the desired Direction override, or null to clear the override and use config.defaultDirection.
Outputs:
No return value; currentRoom may be replaced with an equivalent room using the new direction.
Side effects:
Mutates directionOverride, clears the detected-room segment cache because cached rooms embed direction, updates currentRoom when present, and notifies room listeners.
Failure modes:
If no current room exists, only the override and cache state change; invalid config defaults are handled by defaultDirection.
Important invariants:
Room type, shape, physical corner, segments, id, and name stay unchanged when only direction changes.
Internal logic:
Store the override, clear cached rooms, copy the current room with the effective direction if one exists, cache that rotated room, and emit one room-change event.
Pseudocode:
directionOverride = dir
knownRoomsBySegment.clear()
prev = currentRoom
if prev is null return
rotated = new DungeonRoom(prev fields, effective direction)
currentRoom = rotated
cacheRoom(rotated)
fireRoomChanged(rotated)
Implementation notes:
Clearing the cache prevents a later tick from reusing rooms built with the previous direction.
AI self-check:
Verify clearing cache cannot lose currentRoom, effective direction matches null/default behavior, and listeners are not fired when there is no current room.
]]*/
    public void setDirectionOverride(Direction dir) {
        this.directionOverride = dir;
        knownRoomsBySegment.clear();
        // Drop the cached room so the next tick picks the new direction up.
        DungeonRoom prev = currentRoom;
        if (prev != null) {
            DungeonRoom rotated = new DungeonRoom(
                    prev.type(),
                    prev.shape(),
                    dir == null ? defaultDirection() : dir,
                    prev.physicalCornerX(),
                    prev.physicalCornerZ(),
                    prev.segments(),
                    prev.roomId(),
                    prev.roomName());
            currentRoom = rotated;
            cacheRoom(rotated);
            fireRoomChanged(rotated);
        }
    }

    // ---- zone -> dungeon state -----------------------------------------

    /*[[AI-FN-DOC
Function:
DungeonStateTracker.onZoneChanged
Purpose:
Enter or leave dungeon-tracking mode when the active Waypointer zone changes.
Why this exists:
Dungeon room detection should only scan while the player is in Catacombs or an already-applied room zone, and all instance-local caches must reset between runs.
When to use:
Registered as the ActiveGroupManager zone listener during install; callers should not invoke it directly except during initial state synchronization.
Inputs:
zone is the newly resolved Waypointer Zone and may be null.
Outputs:
No return value.
Side effects:
Mutates inDungeon, clears anchors and detected-room caches on dungeon state changes, may clear currentRoom and notify listeners, and may write a debug log entry.
Failure modes:
Null or non-dungeon zones simply leave dungeon mode and clear instance-local state.
Important invariants:
A new dungeon run must not reuse a previous run's room cache, map anchor, or entrance anchor; room listeners must be notified if the active room disappears.
Internal logic:
Classify the zone as broad dungeon or room-zone, return if the mode did not change, reset cached state, clear the current room if needed, and log when entering dungeon mode.
Pseudocode:
nowDungeon = isBroadDungeonZone(zone) or isRoomZone(zone)
if nowDungeon equals inDungeon return
inDungeon = nowDungeon
reset anchors and known room cache
if currentRoom exists clear it and notify listeners
if nowDungeon log detection start
Implementation notes:
Room zones count as dungeon context so the room bridge can apply a named room without immediately disabling the tracker.
AI self-check:
Verify all per-instance state is cleared on both enter and leave, and null zones are handled safely.
]]*/
    private void onZoneChanged(Zone zone) {
        boolean nowDungeon = DungeonRoomZoneBridge.isBroadDungeonZone(zone)
                || DungeonRoomZoneBridge.isRoomZone(zone);
        if (nowDungeon == inDungeon) return;
        inDungeon = nowDungeon;
        // Reset every cached anchor so a re-entry picks them up fresh -- the
        // physical entrance moves to a new instance each run.
        entranceAnchored = false;
        mapAnchored = false;
        knownRoomsBySegment.clear();
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

    /*[[AI-FN-DOC
Function:
DungeonStateTracker.onClientTick
Purpose:
Update the current dungeon room and background room cache from client world/map state.
Why this exists:
Waypointer needs room zones to become active as soon as possible inside Catacombs, and Odin-style core scanning is fast enough to run every tick when cached.
When to use:
Registered with Fabric END_CLIENT_TICK during install; it should not be called manually outside tests or event dispatch.
Inputs:
client is the active Minecraft client instance supplied by Fabric and may have null player/level during loading screens.
Outputs:
No return value; currentRoom and knownRoomsBySegment are updated as detection succeeds or fails.
Side effects:
Reads player position, client chunks, map item data, and nearby armor stands; mutates map/entrance anchors, detected room cache, currentRoom, and listener notifications.
Failure modes:
Missing config, non-dungeon state, null player/level, unavailable chunks, missing map, or unknown core hashes cause a safe no-room/null update instead of throwing.
Important invariants:
Current segment core identity is attempted before map-shape fallback; map anchors are optional for instant current-room detection; map-visible backfill must only add cache entries, not override the current room incorrectly.
Internal logic:
Throttle by SCAN_INTERVAL_TICKS, derive the player's physical segment, reuse or scan a core-matched room for that segment, opportunistically update map anchors and scan visible map cells, fall back to the old map-footprint path if core matching fails, then publish the current-room change.
Pseudocode:
if disabled or not in dungeon return
apply tick throttle
get player and level; return if missing
playerSegment = physicalSegmentCorner(player)
built = cached room for playerSegment or scanAndCacheRoom(playerSegment)
map = getDungeonMap(client)
if map exists:
  try map and entrance anchors when missing
  if both anchors exist:
    scanVisibleMapRooms(map, level)
    if built is null build map fallback room for player
setCurrentRoom(built)
Implementation notes:
The core path does not wait for Mort or the map, which removes the early-run detection delay seen in the performance snapshot.
AI self-check:
Verify the current-room path remains cheap for unchanged rooms, no map work is required for a core match, and null updates are intentional.
]]*/
    private void onClientTick(Minecraft client) {
        if (!config.enabled() || !inDungeon) return;
        if (++tickCounter < SCAN_INTERVAL_TICKS) return;
        tickCounter = 0;

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) return;

        int[] physRoomCorner = DungeonMapMath.physicalSegmentCorner(player.getX(), player.getZ());
        long playerSegment = DungeonRoom.packSegment(physRoomCorner[0], physRoomCorner[1]);
        DungeonRoomCoreScanner coreScanner = new DungeonRoomCoreScanner(level);
        DungeonRoom built = knownRoomsBySegment.get(playerSegment);
        if (built == null) {
            built = scanAndCacheRoom(playerSegment, coreScanner);
        }

        MapItemSavedData map = getDungeonMap(client);
        if (map != null) {
            if (!mapAnchored) tryAnchorMap(map);
            if (!entranceAnchored) tryAnchorEntrance(level);
            if (mapAnchored && entranceAnchored) {
                scanVisibleMapRooms(map, coreScanner);
                if (built == null) {
                    built = buildMapFallbackRoom(player, map, level);
                }
            }
        }

        setCurrentRoom(built);
    }

    /*[[AI-FN-DOC
Function:
DungeonStateTracker.buildMapFallbackRoom
Purpose:
Build a room from the legacy dungeon-map flood-fill path when direct core matching cannot identify the current segment.
Why this exists:
Map-derived topology can still provide useful generic room state for special rooms, corridors near room boundaries, or temporary catalog misses while the core catalog is incomplete.
When to use:
Use only after map and entrance anchors are available and scanAndCacheRoom returned null for the player's current segment.
Inputs:
player is the local player whose position seeds the lookup; map is the current dungeon map data; level is the current client level used for fingerprint/core fallback.
Outputs:
Returns a DungeonRoom built from map topology and optional catalog matching, or null when the player is off-room or map data is insufficient.
Side effects:
Reads map pixels and client world block data through DungeonRoomBlockLookup/DungeonRoomCoreScanner; caches any successfully built room.
Failure modes:
Invalid map pixels, unknown map colors, empty flood-fill results, or unmatched definitions return null.
Important invariants:
This is a fallback below core matching, so it must not run before the direct current-segment core path has had a chance to identify the room.
Internal logic:
Convert the player's physical segment to a map pixel, classify map color, flood same-color map segments, build a room from those segments, cache it if present, and return it.
Pseudocode:
physRoomCorner = physicalSegmentCorner(player)
mapPixel = physicalToMap(anchors, physRoomCorner)
color = getColor(map, mapPixel)
type = DungeonRoomType.fromMapColor(color)
if type is null return null
mapSegments = floodSegments(map, mapPixel, mapRoomSize, color)
room = buildRoom(type, mapSegments, level)
if room is not null cacheRoom(room)
return room
Implementation notes:
Keeping this path preserves previous behavior for generic shape rooms while core-based detection becomes the primary named-room path.
AI self-check:
Verify null map-color handling still clears the current room through setCurrentRoom(null), and successful fallback rooms enter the cache.
]]*/
    private DungeonRoom buildMapFallbackRoom(LocalPlayer player, MapItemSavedData map, ClientLevel level) {
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
            return null;
        }

        // Flood-fill segments to find the room's full footprint, then build
        // the immutable DungeonRoom record.
        List<int[]> mapSegments = DungeonMapMath.floodSegments(map, mapPixel[0], mapPixel[1], mapRoomSize, color);
        DungeonRoom built = buildRoom(type, mapSegments, level);
        if (built != null) cacheRoom(built);
        return built;
    }

    /*[[AI-FN-DOC
Function:
DungeonStateTracker.buildRoom
Purpose:
Create a DungeonRoom from map-flooded segment pixels and attempt to attach an authored room definition.
Why this exists:
The older map-topology detector is still useful as a fallback and for special room types, and it needs a single helper that converts map coordinates into world geometry.
When to use:
Use after map flood-fill has produced one or more map segment pixels for a room; prefer scanAndCacheRoom for named current-room detection when a core hash is available.
Inputs:
type is the map-derived DungeonRoomType; mapSegments is a non-null list of map pixel coordinate pairs; level is the client world used for block/core matching.
Outputs:
Returns a DungeonRoom with geometry and optional definition, or null when no map segments were supplied.
Side effects:
Reads client world block state through DungeonRoomBlockLookup and DungeonRoomCoreScanner when matching definitions; does not mutate tracker state directly.
Failure modes:
Empty segment lists return null; incomplete world data can leave the room generic/unmatched.
Important invariants:
Map-to-physical conversion must use the current map and entrance anchors; the room direction must respect directionOverride when present.
Internal logic:
Convert map pixels to packed physical segments, track min/max/distinct spans, classify shape, compute canonical physical corner, construct a generic DungeonRoom, and pass it through DungeonRoomData matching.
Pseudocode:
if mapSegments empty return null
for each map segment:
  convert to physical segment
  append packed segment
  update min/max and distinct coordinate sets
shape = classify for normal rooms else one-by-one
dir = override or default
corner = physicalCorner(dir, min/max)
room = new DungeonRoom(type, shape, dir, corner, packed)
return DungeonRoomData.withMatchedDefinition(room, block lookup, core scanner)
Implementation notes:
This method intentionally retains the map-flood behavior for fallback compatibility even though core recursion is now the preferred path.
AI self-check:
Verify the method does not cache or publish by itself, and still handles special room types as one-by-one.
]]*/
    private DungeonRoom buildRoom(DungeonRoomType type, List<int[]> mapSegments, ClientLevel level) {
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
        DungeonRoom room = new DungeonRoom(type, shape, dir, corner[0], corner[1], packed);
        return DungeonRoomData.withMatchedDefinition(
                room,
                new DungeonRoomBlockLookup(level),
                new DungeonRoomCoreScanner(level));
    }

    /*[[AI-FN-DOC
Function:
DungeonStateTracker.scanVisibleMapRooms
Purpose:
Opportunistically identify every map-visible dungeon room segment whose chunk/core data is already available.
Why this exists:
Odin and Devonian feel instant because they keep scanning the dungeon grid in the background, so rooms can be named before the player physically enters them when the client has loaded their chunks.
When to use:
Call after both map and entrance anchors are known during a dungeon tick; do not call before mapRoomSize and entrance anchors are initialized.
Inputs:
map is the current dungeon map data; coreScanner hashes physical room centers in the current client level.
Outputs:
No return value; successfully identified rooms are inserted into knownRoomsBySegment.
Side effects:
Reads map pixels and client chunk/block data, and mutates the room segment cache.
Failure modes:
Unknown map colors, out-of-bounds map cells, unloaded chunks, and unknown core hashes are skipped and retried on later ticks.
Important invariants:
Background scanning must never clear or replace currentRoom directly; it only warms the cache that current-room lookup can later reuse.
Internal logic:
Walk the room-cell lattice implied by the entrance map pixel and map room size, skip cells that are not room colors, convert each cell to a physical segment, and direct core-scan/cache any segment not already known.
Pseudocode:
step = mapRoomSize + map room gap
find first lattice x/z by stepping backward from entrance while inside map
for each lattice x/z inside map:
  if map color is not a room color continue
  convert map cell to physical segment
  if segment already cached or attempted this pass continue
  scanAndCacheRoom(segment, coreScanner)
Implementation notes:
The pass is bounded by the 128x128 map and normally checks at most the 6x6 dungeon room grid, so running it every tick is acceptable while misses wait for chunks to load.
AI self-check:
Verify the method has no currentRoom side effects, respects map bounds, and deduplicates per pass.
]]*/
    private void scanVisibleMapRooms(MapItemSavedData map, DungeonRoomCoreScanner coreScanner) {
        if (mapRoomSize <= 0) return;
        int step = mapRoomSize + DungeonMapMath.MAP_ROOM_GAP_PX;
        int firstMapX = mapEntranceX;
        int firstMapZ = mapEntranceZ;
        while (firstMapX - step >= 0) firstMapX -= step;
        while (firstMapZ - step >= 0) firstMapZ -= step;

        Set<Long> attempted = new HashSet<>();
        for (int mapX = firstMapX; mapX < 128; mapX += step) {
            for (int mapZ = firstMapZ; mapZ < 128; mapZ += step) {
                byte color = DungeonMapMath.getColor(map, mapX, mapZ);
                if (DungeonRoomType.fromMapColor(color) == null) continue;

                int[] physical = DungeonMapMath.mapToPhysical(
                        mapEntranceX, mapEntranceZ, mapRoomSize,
                        physicalEntranceX, physicalEntranceZ,
                        mapX, mapZ);
                long segment = DungeonRoom.packSegment(physical[0], physical[1]);
                if (knownRoomsBySegment.containsKey(segment)) continue;
                if (!attempted.add(segment)) continue;
                scanAndCacheRoom(segment, coreScanner);
            }
        }
    }

    /*[[AI-FN-DOC
Function:
DungeonStateTracker.scanAndCacheRoom
Purpose:
Identify a dungeon room from one physical segment's core hash and cache every same-room component discovered from that seed.
Why this exists:
The fastest reliable room identity path is to hash the segment center and look it up in the core catalog, then recursively include adjacent components whose cores belong to the same room definition.
When to use:
Use for the player's current segment and for background map-grid seeds; do not use when no client level/chunk data is available.
Inputs:
seedSegment is a packed physical segment corner to scan; coreScanner is bound to the current client level and must be non-null.
Outputs:
Returns the identified DungeonRoom, or null when the seed core is unknown or components cannot be collected.
Side effects:
Reads client chunk/block state through coreScanner and mutates knownRoomsBySegment when a room is identified.
Failure modes:
Unknown seed hashes, ambiguous catalog hashes, unloaded chunks, or empty component sets return null and leave the cache unchanged.
Important invariants:
The seed segment's core decides the room definition before map shape is considered; cached rooms must include all collected components under the same DungeonRoom instance.
Internal logic:
Check the cache first, hash the seed, resolve its definition, collect adjacent matching-core components, build a room from those components and definition metadata, cache it by segment, and return it.
Pseudocode:
cached = knownRoomsBySegment[seedSegment]
if cached exists return it
seedCore = coreScanner.coreHashForSegment(seedSegment)
definition = DungeonRoomData.definitionForCoreHash(seedCore)
if definition is null return null
components = collectCoreComponents(seedSegment, definition.coreHashes, coreScanner)
if components empty return null
room = buildCoreRoom(definition, components)
cacheRoom(room)
return room
Implementation notes:
This mirrors Odin's core-first detector while retaining Waypointer's own catalog and Java implementation.
AI self-check:
Verify no map-shape gate remains in this path and cache hits avoid repeated block scans.
]]*/
    private DungeonRoom scanAndCacheRoom(long seedSegment, DungeonRoomCoreScanner coreScanner) {
        DungeonRoom cached = knownRoomsBySegment.get(seedSegment);
        if (cached != null) return cached;

        int seedCore = coreScanner.coreHashForSegment(seedSegment);
        DungeonRoomDefinition definition = DungeonRoomData.definitionForCoreHash(seedCore);
        if (definition == null) return null;

        Set<Long> components = collectCoreComponents(seedSegment, definition.coreHashes(), coreScanner);
        if (components.isEmpty()) return null;

        DungeonRoom room = buildCoreRoom(definition, components);
        cacheRoom(room);
        return room;
    }

    /*[[AI-FN-DOC
Function:
DungeonStateTracker.collectCoreComponents
Purpose:
Collect all adjacent 32-block dungeon components whose center cores belong to the same room definition as the seed.
Why this exists:
Multi-segment rooms need correct physical geometry for room-relative waypoint projection, and map color flood-fill can over-merge unrelated adjacent rooms.
When to use:
Use after the seed segment's core has already resolved to a specific room definition and its allowed core hash list.
Inputs:
seedSegment is the packed starting physical segment; roomCoreHashes is the definition's allowed core hash list; coreScanner hashes candidate segments.
Outputs:
Returns a set of packed physical segments that are part of the same room according to core hashes.
Side effects:
Reads client chunk/block state through coreScanner; does not mutate tracker fields.
Failure modes:
Unloaded or non-matching neighbor chunks simply do not join the component set.
Important invariants:
The search is bounded to MAX_ROOM_COMPONENTS because Catacombs rooms contain at most four 32-block components; only matching core hashes join the room.
Internal logic:
Breadth-first search from the seed, skip visited segments, hash each candidate, accept it only if the core belongs to the definition, then enqueue its four cardinal neighbors while under the component cap.
Pseudocode:
visited = empty set
components = empty set
pending = queue(seed)
while pending not empty and components size < max:
  segment = pending pop
  if already visited continue
  core = hash segment
  if core not in roomCoreHashes continue
  add segment to components
  enqueue north/south/east/west neighbor segments
return components
Implementation notes:
Using BFS rather than recursion avoids accidental stack growth and makes the component cap obvious.
AI self-check:
Verify non-matching neighbors are not added, four-way adjacency uses DungeonMapMath.SEGMENT_BLOCKS, and the search cannot run unbounded.
]]*/
    private Set<Long> collectCoreComponents(long seedSegment, List<Integer> roomCoreHashes,
                                            DungeonRoomCoreScanner coreScanner) {
        Set<Long> visited = new HashSet<>();
        Set<Long> components = new HashSet<>();
        Deque<Long> pending = new ArrayDeque<>();
        pending.add(seedSegment);

        while (!pending.isEmpty() && components.size() < MAX_ROOM_COMPONENTS) {
            long segment = pending.removeFirst();
            if (!visited.add(segment)) continue;

            int core = coreScanner.coreHashForSegment(segment);
            if (!roomCoreHashes.contains(core)) continue;

            components.add(segment);
            addNeighborSegments(segment, pending);
        }

        return components;
    }

    /*[[AI-FN-DOC
Function:
DungeonStateTracker.addNeighborSegments
Purpose:
Append the four cardinal 32-block neighbor segment positions around a dungeon segment to a pending scan queue.
Why this exists:
Core-component collection needs a single explicit place for the Catacombs room-grid adjacency rule.
When to use:
Use only inside collectCoreComponents when expanding an accepted room component.
Inputs:
segment is a packed physical segment corner; pending is the mutable queue that will receive neighboring packed segments.
Outputs:
No return value; four packed neighbor segments are appended to pending.
Side effects:
Mutates the pending queue.
Failure modes:
No expected failures; invalid input coordinates simply produce corresponding invalid neighbors that later fail core matching.
Important invariants:
Neighbor offsets must be exactly DungeonMapMath.SEGMENT_BLOCKS along one axis at a time.
Internal logic:
Unpack x/z, compute +/-32 neighbors on X and Z, pack each neighbor, and append them to the queue.
Pseudocode:
x = segmentX(segment)
z = segmentZ(segment)
pending add pack(x + segmentBlocks, z)
pending add pack(x - segmentBlocks, z)
pending add pack(x, z + segmentBlocks)
pending add pack(x, z - segmentBlocks)
Implementation notes:
This tiny helper avoids repeating packed-coordinate arithmetic in the BFS loop.
AI self-check:
Verify exactly four cardinal neighbors are added and no diagonal positions are produced.
]]*/
    private static void addNeighborSegments(long segment, Deque<Long> pending) {
        int x = DungeonRoom.segmentX(segment);
        int z = DungeonRoom.segmentZ(segment);
        pending.add(DungeonRoom.packSegment(x + DungeonMapMath.SEGMENT_BLOCKS, z));
        pending.add(DungeonRoom.packSegment(x - DungeonMapMath.SEGMENT_BLOCKS, z));
        pending.add(DungeonRoom.packSegment(x, z + DungeonMapMath.SEGMENT_BLOCKS));
        pending.add(DungeonRoom.packSegment(x, z - DungeonMapMath.SEGMENT_BLOCKS));
    }

    /*[[AI-FN-DOC
Function:
DungeonStateTracker.buildCoreRoom
Purpose:
Construct a live DungeonRoom snapshot from a matched catalog definition and core-collected physical components.
Why this exists:
Room identity and geometry need to be combined after core scanning so renderers can project room-relative waypoints from the correct physical corner.
When to use:
Use after collectCoreComponents has produced at least one segment for a uniquely matched DungeonRoomDefinition.
Inputs:
definition is the matched room definition and must be non-null; components is a non-empty set of packed physical segments.
Outputs:
Returns a DungeonRoom carrying definition type, definition shape, effective direction, physical corner, sorted components, id, and display name.
Side effects:
None.
Failure modes:
If components is empty the min/max values would be invalid; callers are responsible for checking before calling.
Important invariants:
The room shape comes from the matched definition rather than map flood-fill; the physical corner is computed from the actual collected components and effective direction.
Internal logic:
Copy and sort components, compute min/max segment bounds, resolve effective direction, compute canonical corner through DungeonMapMath.physicalCorner, and create a defined DungeonRoom.
Pseudocode:
packed = sorted copy of components
initialize min/max
for each packed segment update min/max x/z
dir = directionOverride or defaultDirection
corner = physicalCorner(dir, min/max)
return new DungeonRoom(definition fields, dir, corner, packed, definition id/name)
Implementation notes:
Sorting stabilizes identity/debug output and cache behavior without changing geometry.
AI self-check:
Verify definition shape is used, all components are preserved, and direction override behavior matches map fallback.
]]*/
    private DungeonRoom buildCoreRoom(DungeonRoomDefinition definition, Set<Long> components) {
        List<Long> packed = new ArrayList<>(components);
        Collections.sort(packed);

        int minSegX = Integer.MAX_VALUE;
        int minSegZ = Integer.MAX_VALUE;
        int maxSegX = Integer.MIN_VALUE;
        int maxSegZ = Integer.MIN_VALUE;
        for (long segment : packed) {
            int x = DungeonRoom.segmentX(segment);
            int z = DungeonRoom.segmentZ(segment);
            if (x < minSegX) minSegX = x;
            if (z < minSegZ) minSegZ = z;
            if (x > maxSegX) maxSegX = x;
            if (z > maxSegZ) maxSegZ = z;
        }

        Direction dir = directionOverride != null ? directionOverride : defaultDirection();
        int[] corner = DungeonMapMath.physicalCorner(dir, minSegX, minSegZ, maxSegX, maxSegZ);
        return new DungeonRoom(
                definition.type(),
                definition.shape(),
                dir,
                corner[0],
                corner[1],
                packed,
                definition.id(),
                definition.displayName());
    }

    /*[[AI-FN-DOC
Function:
DungeonStateTracker.cacheRoom
Purpose:
Store one detected room under every physical segment that belongs to it.
Why this exists:
Instant re-entry and current-room lookup should reuse a previously scanned room without rehashing block columns.
When to use:
Use after constructing or manually updating a DungeonRoom whose segments are known.
Inputs:
room is the detected DungeonRoom to cache and may be null.
Outputs:
No return value.
Side effects:
Mutates knownRoomsBySegment.
Failure modes:
Null rooms are ignored; rooms with no segments add no cache entries.
Important invariants:
Every segment in room.segments maps to the same DungeonRoom instance so multi-component rooms remain coherent.
Internal logic:
Return on null, otherwise iterate room.segments and put room into the cache for each packed segment.
Pseudocode:
if room is null return
for each segment in room.segments:
  knownRoomsBySegment[segment] = room
Implementation notes:
The method intentionally overwrites previous entries for the same segments so manual definition/direction changes refresh the cache.
AI self-check:
Verify null is safe and multi-component rooms cache all components.
]]*/
    private void cacheRoom(DungeonRoom room) {
        if (room == null) return;
        for (Long segment : room.segments()) {
            knownRoomsBySegment.put(segment, room);
        }
    }

    /*[[AI-FN-DOC
Function:
DungeonStateTracker.setCurrentRoom
Purpose:
Publish a current-room change only when the detected room state actually differs from the previous state.
Why this exists:
Room listeners drive zone switching and rendering updates, so repeated identical tick detections should not spam them.
When to use:
Use at the end of each detector tick with the newly detected room or null.
Inputs:
next is the room detected for the current tick, or null when the player is not in an identifiable room.
Outputs:
No return value.
Side effects:
May mutate currentRoom and notify room listeners through fireRoomChanged.
Failure modes:
No expected failures; listener failures are not caught here and follow existing event behavior.
Important invariants:
Identity comparison includes geometry plus room id/name, because a manual or core-based name change matters even when geometry is unchanged.
Internal logic:
Read previous room, return if sameRoomState says nothing changed, otherwise assign currentRoom and fire the change event.
Pseudocode:
prev = currentRoom
if sameRoomState(prev, next) return
currentRoom = next
fireRoomChanged(next)
Implementation notes:
Centralizing this comparison avoids subtle differences between the core path and map fallback path.
AI self-check:
Verify null-to-null does not fire, null-to-room and room-to-null do fire, and id/name changes fire.
]]*/
    private void setCurrentRoom(DungeonRoom next) {
        DungeonRoom prev = currentRoom;
        if (sameRoomState(prev, next)) return;
        currentRoom = next;
        fireRoomChanged(next);
    }

    /*[[AI-FN-DOC
Function:
DungeonStateTracker.sameRoomState
Purpose:
Compare two DungeonRoom snapshots for listener-visible equality.
Why this exists:
DungeonRoom.identityKey intentionally focuses on geometry, but listeners also need to observe changes in matched room id or display name.
When to use:
Use before publishing a detector result to decide whether currentRoom changed enough to notify listeners.
Inputs:
left and right are DungeonRoom snapshots and may each be null.
Outputs:
Returns true when both snapshots are null or when geometry identity, room id, and room name all match.
Side effects:
None.
Failure modes:
No expected failures; DungeonRoom normalizes room id/name to non-null strings.
Important invariants:
Room id and room name are part of listener-visible state even when type/shape/corner/segments are identical.
Internal logic:
Handle object identity and null cases first, then compare identityKey, roomId, and roomName.
Pseudocode:
if left and right are the same object return true
if either is null return false
return left.identityKey equals right.identityKey and ids equal and names equal
Implementation notes:
Using identityKey keeps the comparison aligned with the existing room-change semantics while fixing the prior omission of catalog identity.
AI self-check:
Verify the method is pure and treats id/name changes as room-state changes.
]]*/
    private static boolean sameRoomState(DungeonRoom left, DungeonRoom right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.identityKey().equals(right.identityKey())
                && left.roomId().equals(right.roomId())
                && left.roomName().equals(right.roomName());
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
