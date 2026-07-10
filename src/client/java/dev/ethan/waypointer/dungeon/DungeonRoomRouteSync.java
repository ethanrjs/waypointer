package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Waypoint;
import dev.ethan.waypointer.core.WaypointGroup;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Mirrors authored dungeon-room secrets into normal runtime route groups.
 *
 * <p>Dungeon room data is stored in canonical room-local coordinates, while the
 * existing waypoint renderer/progression pipeline expects world coordinates.
 * This adapter does the one translation step when a physical room is detected,
 * then leaves rendering, labels, and progress to the ordinary route system.
 *
 * <p>The mirror is progress-aware: secrets the {@link DungeonRouteSession}
 * already saw collected are left out of the rebuilt group, and a fully
 * completed room drops its group entirely (when
 * {@link DungeonConfig#hideCompletedRooms()} is on), so trigger detection and
 * the map's green checkmark visibly clean up the world as the run progresses.
 */
public final class DungeonRoomRouteSync {

    static final String GENERATED_GROUP_ID_PREFIX = "dungeon:auto:";

    /**
     * Uniform route colors: every progress secret shares one color and every
     * support marker (highlights, non-progress markers) another, so an
     * imported route reads as one route instead of a per-category confetti.
     */
    public static final int SECRET_WAYPOINT_COLOR = 0x2EE0FF;
    public static final int SUPPORT_WAYPOINT_COLOR = 0xFFB300;

    private final ActiveGroupManager manager;
    private final DungeonStateTracker tracker;
    private final DungeonRouteSession session;
    private final DungeonConfig config;
    private final Consumer<DungeonRoom> roomListener = room -> syncCurrentRoom();
    private final Consumer<Zone> zoneListener = this::onZoneChanged;
    private final Runnable syncListener = this::syncCurrentRoom;
    private boolean syncing;

    public DungeonRoomRouteSync(ActiveGroupManager manager, DungeonStateTracker tracker) {
        this(manager, tracker, null, null);
    }

    public DungeonRoomRouteSync(ActiveGroupManager manager, DungeonStateTracker tracker,
                                DungeonRouteSession session, DungeonConfig config) {
        this.manager = manager;
        this.tracker = tracker;
        this.session = session;
        this.config = config;
    }

    public void install() {
        tracker.addRoomListener(roomListener);
        manager.addZoneListener(zoneListener);
        manager.addDataListener(syncListener);
        DungeonRoomData.addChangeListener(syncListener);
        if (session != null) session.addChangeListener(syncListener);
        syncCurrentRoom();
    }

    public void uninstall() {
        tracker.removeRoomListener(roomListener);
        manager.removeZoneListener(zoneListener);
        manager.removeDataListener(syncListener);
        DungeonRoomData.removeChangeListener(syncListener);
        if (session != null) session.removeChangeListener(syncListener);
    }

    private void onZoneChanged(Zone zone) {
        if (!DungeonRoomZoneBridge.isBroadDungeonZone(zone)
                && !DungeonRoomZoneBridge.isRoomZone(zone)) {
            removeGeneratedGroups();
            return;
        }
        syncCurrentRoom();
    }

    private void syncCurrentRoom() {
        if (syncing) return;
        DungeonRoom room = tracker.currentRoom();
        if (room == null || !room.hasRoomId()) return;

        syncing = true;
        try {
            syncRoom(room);
        } finally {
            syncing = false;
        }
    }

    private void syncRoom(DungeonRoom room) {
        String roomId = room.roomId();
        String generatedId = generatedGroupId(roomId);

        // A user-authored route wins over downloaded secrets. Stored room
        // routes are room-local, so the mirror is what projects them into this
        // run's room placement; the stored group itself never renders (see
        // ActiveGroupManager#activeGroups).
        WaypointGroup userRoute = firstUserRouteGroup(roomId);
        if (userRoute != null) {
            if (!userRoute.enabled()) {
                removeGeneratedGroup(generatedId);
                return;
            }
            replaceGeneratedGroup(generatedId,
                    transformedRouteGroupForRoom(room, userRoute, session));
            return;
        }

        DungeonRoomDefinition definition = DungeonRoomData.customDefinition(roomId);
        if (definition == null
                || definition.waypoints().isEmpty()
                || isCompletedAndHidden(room)) {
            removeGeneratedGroup(generatedId);
            return;
        }

        WaypointGroup group = routeGroupForRoom(room, definition, session);
        if (group.isEmpty()) {
            removeGeneratedGroup(generatedId);
            return;
        }
        replaceGeneratedGroup(generatedId, group);
    }

    /**
     * Swap in a rebuilt mirror without losing view progress: rebuilds happen on
     * every data/session change, and a SEQUENCE group that snapped back to
     * waypoint #1 each time would fight the player's advancement.
     */
    private void replaceGeneratedGroup(String generatedId, WaypointGroup next) {
        carryOverProgress(manager.get(generatedId), next);
        manager.add(next);
    }

    static void carryOverProgress(WaypointGroup previous, WaypointGroup next) {
        if (previous == null || next == null) return;
        int previousCurrent = previous.currentIndex();
        if (previousCurrent <= 0 || previousCurrent >= previous.size()) return;
        Waypoint current = previous.get(previousCurrent);
        for (int i = 0; i < next.size(); i++) {
            Waypoint candidate = next.get(i);
            if (candidate.preciseX() == current.preciseX()
                    && candidate.preciseY() == current.preciseY()
                    && candidate.preciseZ() == current.preciseZ()
                    && candidate.isSubwaypoint() == current.isSubwaypoint()) {
                next.setCurrentIndex(i);
                return;
            }
        }
    }

    private boolean isCompletedAndHidden(DungeonRoom room) {
        return session != null
                && (config == null || config.hideCompletedRooms())
                && session.isRoomComplete(room);
    }

    /**
     * The user-authored route that suppresses downloaded community secrets —
     * but only when it actually contains waypoints. Empty leftovers (a
     * clicked-away "New Route", an aborted import) must not silently suppress
     * an installed secret route. Disabled routes still suppress (the user hid
     * their route deliberately; resurrecting the community secrets would undo
     * that), which is why this returns the group instead of a boolean — the
     * caller also needs its enabled state.
     */
    private WaypointGroup firstUserRouteGroup(String roomId) {
        return storedRouteForRoom(manager, roomId);
    }

    /** The stored (persisted, room-local) route for a room, or null. */
    public static WaypointGroup storedRouteForRoom(ActiveGroupManager manager, String roomId) {
        if (manager == null || roomId == null) return null;
        for (WaypointGroup group : manager.groupsForZone(roomId)) {
            if (!group.runtimeOnly() && !group.isEmpty()) return group;
        }
        return null;
    }

    /**
     * The stored group a runtime mirror was projected from, or null when the
     * mirror reflects downloaded secrets (definition-backed) rather than a
     * user route. Mirror and source share waypoint order, so an index into one
     * addresses the same waypoint in the other.
     */
    public static WaypointGroup storedSourceForMirror(ActiveGroupManager manager,
                                                      WaypointGroup mirror) {
        if (!isGeneratedGroup(mirror)) return null;
        return storedRouteForRoom(manager, mirror.zoneId());
    }

    /**
     * Resolve the group that an edit must mutate to survive the next dungeon
     * route rebuild. Normal and stored groups are already durable. Generated
     * mirrors must write through to their stored room-local source; a null
     * result means the mirror only reflects downloaded secrets and must be
     * explicitly converted before editing.
     */
    public static WaypointGroup durableEditTarget(ActiveGroupManager manager,
                                                   WaypointGroup visibleGroup) {
        if (!isGeneratedGroup(visibleGroup)) return visibleGroup;
        return storedSourceForMirror(manager, visibleGroup);
    }

    /**
     * True when in-world edits in this room should be refused because the room
     * shows downloaded secrets that the user has not converted into their own
     * route yet — editing the throwaway mirror would silently discard changes.
     */
    public static boolean secretsRequireConversion(ActiveGroupManager manager, String zoneId) {
        if (manager == null || zoneId == null) return false;
        DungeonRoomDefinition definition = DungeonRoomData.customDefinition(zoneId);
        if (definition == null || definition.waypoints().isEmpty()) return false;
        return storedRouteForRoom(manager, zoneId) == null;
    }

    private void removeGeneratedGroups() {
        List<String> ids = new ArrayList<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (isGeneratedGroup(group)) ids.add(group.id());
        }
        for (String id : ids) {
            manager.remove(id);
        }
    }

    private void removeGeneratedGroup(String id) {
        WaypointGroup existing = manager.get(id);
        if (isGeneratedGroup(existing)) {
            manager.remove(id);
        }
    }

    static WaypointGroup routeGroupForRoom(DungeonRoom room, DungeonRoomDefinition definition) {
        return routeGroupForRoom(room, definition, null);
    }

    /**
     * Project a user-authored room route (waypoints stored room-local, see
     * {@link DungeonRoomWaypointPlacement}) into actual world positions for the
     * given room placement. Precise sub-block offsets survive the projection.
     * The mirror carries the generated id so zone-exit cleanup catches it; the
     * stored group keeps its own id and never renders directly. The session
     * parameter mirrors {@link #routeGroupForRoom}'s filtering hook; user
     * routes carry no secret indices yet, so no waypoints are filtered.
     */
    static WaypointGroup transformedRouteGroupForRoom(DungeonRoom room, WaypointGroup source,
                                                      DungeonRouteSession session) {
        WaypointGroup group = new WaypointGroup(
                generatedGroupId(source.zoneId()), source.name(), source.zoneId());
        group.setRuntimeOnly(true);
        group.setLoadMode(source.loadMode());
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setDefaultRadius(source.defaultRadius());
        group.setSkipAheadEnabled(source.skipAheadEnabled());

        List<Waypoint> waypoints = new ArrayList<>(source.size());
        for (Waypoint stored : source.waypoints()) {
            waypoints.add(DungeonRoomWaypointPlacement.toActualWaypoint(room, stored));
        }
        group.addAll(waypoints);
        // Seed from the stored group's progress (persisted, or moved by the
        // next/previous keybinds); a previous mirror's carry-over then wins on
        // rebuilds within the session.
        group.setCurrentIndex(source.currentIndex());
        return group;
    }

    /**
     * Convert an installed secret-route definition into a normal, persisted,
     * user-editable route group. Coordinates stay room-local (the definition's
     * own frame) — the sync mirror projects them into each run's room
     * placement, so the converted route keeps working across runs. Once added,
     * the user route suppresses the definition-generated group; deleting it
     * brings the installed secrets back.
     */
    public static WaypointGroup editableRouteFromDefinition(DungeonRoomDefinition definition) {
        WaypointGroup group = WaypointGroup.create("Secret Route", definition.id());
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);

        List<Waypoint> waypoints = new ArrayList<>();
        for (DungeonWaypoint dungeonWaypoint : definition.waypoints()) {
            if (dungeonWaypoint.secretIndex() <= 0) {
                waypoints.add(new Waypoint(
                        dungeonWaypoint.x(), dungeonWaypoint.y(), dungeonWaypoint.z(),
                        dungeonWaypoint.name(), SUPPORT_WAYPOINT_COLOR,
                        Waypoint.FLAG_SUBWAYPOINT, 0.0));
                continue;
            }
            waypoints.add(new Waypoint(
                    dungeonWaypoint.x(), dungeonWaypoint.y(), dungeonWaypoint.z(),
                    dungeonWaypoint.name(), SECRET_WAYPOINT_COLOR,
                    DungeonWaypointSkipRules.flagsForTrigger(dungeonWaypoint.trigger()), 0.0));
            for (DungeonHighlight highlight : dungeonWaypoint.highlights()) {
                waypoints.add(new Waypoint(
                        highlight.x(), highlight.y(), highlight.z(),
                        "", SUPPORT_WAYPOINT_COLOR,
                        Waypoint.FLAG_SUBWAYPOINT | highlightFlags(highlight.style()), 0.0));
            }
        }
        group.addAll(waypoints);
        return group;
    }

    static WaypointGroup routeGroupForRoom(DungeonRoom room, DungeonRoomDefinition definition,
                                           DungeonRouteSession session) {
        WaypointGroup group = new WaypointGroup(
                generatedGroupId(definition.id()),
                "Dungeon Secrets -- " + definition.displayName(),
                definition.id());
        group.setRuntimeOnly(true);
        // SEQUENCE so the route navigates one secret at a time: the current
        // secret gets the tracer/entry path and prev/next render as context,
        // instead of every secret in the room shouting at once.
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);

        List<Waypoint> waypoints = new ArrayList<>();
        for (DungeonWaypoint dungeonWaypoint : definition.waypoints()) {
            if (isAlreadyFound(session, room, dungeonWaypoint)) continue;
            addWaypoint(room, dungeonWaypoint, waypoints);
        }
        group.addAll(waypoints);
        return group;
    }

    private static boolean isAlreadyFound(DungeonRouteSession session, DungeonRoom room,
                                          DungeonWaypoint waypoint) {
        return session != null
                && session.peekStatus(room, waypoint) == DungeonRouteSession.Status.FOUND;
    }

    private static void addWaypoint(DungeonRoom room, DungeonWaypoint dungeonWaypoint,
                                    List<Waypoint> out) {
        int[] actual = DungeonMapMath.relativeToActual(
                room.direction(),
                room.physicalCornerX(),
                room.physicalCornerZ(),
                dungeonWaypoint.x(),
                dungeonWaypoint.y(),
                dungeonWaypoint.z());

        if (dungeonWaypoint.secretIndex() <= 0) {
            // Persistent marker (e.g. imported Odin NORMAL waypoints): renders
            // like a highlight and never participates in route progression.
            out.add(new Waypoint(
                    actual[0],
                    actual[1],
                    actual[2],
                    dungeonWaypoint.name(),
                    SUPPORT_WAYPOINT_COLOR,
                    Waypoint.FLAG_SUBWAYPOINT,
                    0.0));
            return;
        }

        out.add(new Waypoint(
                actual[0],
                actual[1],
                actual[2],
                dungeonWaypoint.name(),
                SECRET_WAYPOINT_COLOR,
                DungeonWaypointSkipRules.flagsForTriggerAt(
                        dungeonWaypoint.trigger(), actual[0], actual[1], actual[2]),
                0.0));

        for (DungeonHighlight highlight : dungeonWaypoint.highlights()) {
            int[] highlightActual = DungeonMapMath.relativeToActual(
                    room.direction(),
                    room.physicalCornerX(),
                    room.physicalCornerZ(),
                    highlight.x(),
                    highlight.y(),
                    highlight.z());
            out.add(new Waypoint(
                    highlightActual[0],
                    highlightActual[1],
                    highlightActual[2],
                    "",
                    SUPPORT_WAYPOINT_COLOR,
                    Waypoint.FLAG_SUBWAYPOINT | highlightFlags(highlight.style()),
                    0.0));
        }
    }

    private static int highlightFlags(DungeonHighlightStyle style) {
        return style == DungeonHighlightStyle.FILLED
                || style == DungeonHighlightStyle.OUTLINE_FILLED
                ? Waypoint.FLAG_FILLED_SUBWAYPOINT
                : 0;
    }

    public static boolean isGeneratedGroup(WaypointGroup group) {
        return group != null
                && group.runtimeOnly()
                && group.id().startsWith(GENERATED_GROUP_ID_PREFIX);
    }

    public static String generatedGroupId(String roomId) {
        return GENERATED_GROUP_ID_PREFIX + DungeonRoomDefinition.normalizeId(roomId);
    }
}
