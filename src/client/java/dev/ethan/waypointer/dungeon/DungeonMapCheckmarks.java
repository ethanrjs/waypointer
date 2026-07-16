package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.List;

/**
 * Reads room checkmarks off the in-game dungeon map and feeds them into the
 * route session.
 *
 * <p>Hypixel paints a green checkmark over a room's map tile once the room is
 * cleared with <i>all</i> secrets collected. That signal is authoritative in a
 * way client-side trigger detection can never be: it covers secrets a teammate
 * collected in a room this client never entered. On green, the room's route is
 * marked complete, which (via the session listener in
 * {@link DungeonRoomRouteSync}) drops its waypoints from the world.
 *
 * <p>Reads the map data already synced to the client -- one byte-array lookup
 * per known room every half second; no packets, no mixins.
 */
public final class DungeonMapCheckmarks {

    private static final int POLL_INTERVAL_TICKS = 10;
    /** Hypixel keeps the magical map in the 9th hotbar slot. */
    private static final int DUNGEON_MAP_HOTBAR_SLOT = 8;
    /** {@code MapColor} id of the green checkmark (same id the entrance tile uses). */
    private static final byte GREEN_CHECKMARK_COLOR = 30;

    private final DungeonStateTracker tracker;
    private final DungeonRouteSession session;
    private final DungeonConfig config;

    private int tickCounter;
    private ClientLevel anchorLevel;
    private int[] mapEntranceAndRoomSize;
    private long physicalEntranceSegment;

    public DungeonMapCheckmarks(DungeonStateTracker tracker, DungeonRouteSession session,
                                DungeonConfig config) {
        this.tracker = tracker;
        this.session = session;
        this.config = config;
    }

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft client) {
        if (++tickCounter < POLL_INTERVAL_TICKS) return;
        tickCounter = 0;

        if (!config.enabled() || !config.autoCompleteRoomsOnGreenCheckmark()
                || !tracker.inDungeon()) {
            resetAnchors();
            return;
        }
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) return;
        if (level != anchorLevel) resetAnchors();

        MapItemSavedData map = heldDungeonMap(player, level);
        if (map == null) return;
        if (!resolveAnchors(level, map)) return;

        applyGreenCheckmarks(map);
    }

    private void resetAnchors() {
        anchorLevel = null;
        mapEntranceAndRoomSize = null;
    }

    private static MapItemSavedData heldDungeonMap(LocalPlayer player, ClientLevel level) {
        ItemStack stack = player.getInventory().getItem(DUNGEON_MAP_HOTBAR_SLOT);
        if (!stack.is(Items.FILLED_MAP)) return null;
        MapId mapId = stack.get(DataComponents.MAP_ID);
        return mapId == null ? null : level.getMapData(mapId);
    }

    /**
     * The entrance room is the only fixed anchor shared by the physical grid
     * and the map image. Both sides resolve lazily: the physical side needs
     * the entrance chunk scanned, the map side needs the map to have rendered
     * the entrance tile (it always has by the time the player can move).
     */
    private boolean resolveAnchors(ClientLevel level, MapItemSavedData map) {
        if (mapEntranceAndRoomSize != null) return true;

        DungeonRoom entrance = null;
        for (DungeonRoom room : tracker.knownRooms()) {
            if (room.type() == DungeonRoomType.ENTRANCE && !room.segments().isEmpty()) {
                entrance = room;
                break;
            }
        }
        if (entrance == null) return false;

        int[] entranceAndSize = DungeonMapMath.findEntranceAndRoomSize(map);
        if (entranceAndSize == null) return false;

        anchorLevel = level;
        mapEntranceAndRoomSize = entranceAndSize;
        physicalEntranceSegment = entrance.segments().get(0);
        return true;
    }

    private void applyGreenCheckmarks(MapItemSavedData map) {
        int mapEntranceX = mapEntranceAndRoomSize[0];
        int mapEntranceZ = mapEntranceAndRoomSize[1];
        int roomSize = mapEntranceAndRoomSize[2];
        int physEntranceX = DungeonRoom.segmentX(physicalEntranceSegment);
        int physEntranceZ = DungeonRoom.segmentZ(physicalEntranceSegment);

        for (DungeonRoom room : tracker.knownRooms()) {
            if (room.type() != DungeonRoomType.ROOM || !room.hasRoomId()) continue;
            if (session.isRoomComplete(room)) continue;
            if (hasGreenCheckmark(map, room, physEntranceX, physEntranceZ,
                    mapEntranceX, mapEntranceZ, roomSize)) {
                session.markRoomComplete(room);
            }
        }
    }

    /**
     * The checkmark is painted over the centre of one of the room's segments
     * (multi-segment rooms get it on one tile only), so every segment centre
     * is checked.
     */
    private static boolean hasGreenCheckmark(MapItemSavedData map, DungeonRoom room,
                                             int physEntranceX, int physEntranceZ,
                                             int mapEntranceX, int mapEntranceZ, int roomSize) {
        List<Long> segments = room.segments();
        int half = roomSize / 2;
        for (long segment : segments) {
            int[] pixel = DungeonMapMath.physicalToMap(
                    physEntranceX, physEntranceZ,
                    mapEntranceX, mapEntranceZ,
                    roomSize,
                    DungeonRoom.segmentX(segment), DungeonRoom.segmentZ(segment));
            byte center = DungeonMapMath.getColor(map, pixel[0] + half, pixel[1] + half);
            if (center == GREEN_CHECKMARK_COLOR) return true;
        }
        return false;
    }
}
