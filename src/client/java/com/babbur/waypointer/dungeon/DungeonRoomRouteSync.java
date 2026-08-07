package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
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

    /** Fallback colors for legacy/default secrets and non-progress markers. */
    public static final int SECRET_WAYPOINT_COLOR = 0x2EE0FF;
    public static final int SUPPORT_WAYPOINT_COLOR = 0xFFB300;

    private final ActiveGroupManager manager;
    private final DungeonStateTracker tracker;
    private final DungeonRouteSession session;
    private final DungeonConfig config;
    private final Consumer<DungeonRoom> roomListener = this::onRoomChanged;
    private final Consumer<Zone> zoneListener = this::onZoneChanged;
    private final Runnable syncListener = this::syncCurrentRoom;
    private boolean syncing;

    /**
     * Bulk-mutation gate. Every installed sync listens to the manager, the
     * dungeon config, and the room database, so a loop that flips N routes used
     * to trigger N full room resyncs -- quadratic once a player has a hundred
     * routes installed, and the reason hide-all and closing the route list
     * stalled the client. Bulk callers wrap their loop in {@link #batched} and
     * pay for exactly one resync at the end.
     */
    private static final List<DungeonRoomRouteSync> INSTALLED = new ArrayList<>();
    private static int batchDepth;
    private static boolean batchSyncPending;

    /**
     * Run {@code work} with room resyncing suppressed, then resync once if
     * anything asked for it. Re-entrant: only the outermost call resyncs.
     */
    public static void batched(Runnable work) {
        if (work == null) return;
        batchDepth++;
        try {
            work.run();
        } finally {
            batchDepth--;
            if (batchDepth == 0 && batchSyncPending) {
                batchSyncPending = false;
                for (DungeonRoomRouteSync sync : List.copyOf(INSTALLED)) {
                    sync.syncCurrentRoom();
                }
            }
        }
    }
    private String selectedPhysicalRoomKey;
    private String selectedSourceGroupId;

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
        if (config != null) config.addChangeListener(syncListener);
        if (!INSTALLED.contains(this)) INSTALLED.add(this);
        syncCurrentRoom();
    }

    public void uninstall() {
        INSTALLED.remove(this);
        tracker.removeRoomListener(roomListener);
        manager.removeZoneListener(zoneListener);
        manager.removeDataListener(syncListener);
        DungeonRoomData.removeChangeListener(syncListener);
        if (session != null) session.removeChangeListener(syncListener);
        if (config != null) config.removeChangeListener(syncListener);
    }

    private void onZoneChanged(Zone zone) {
        if (!DungeonRoomZoneBridge.isBroadDungeonZone(zone)
                && !DungeonRoomZoneBridge.isRoomZone(zone)) {
            removeGeneratedGroups();
            return;
        }
        syncCurrentRoom();
    }

    private void onRoomChanged(DungeonRoom room) {
        String physicalKey = room == null ? null : room.identityKey();
        if (!java.util.Objects.equals(selectedPhysicalRoomKey, physicalKey)) {
            selectedPhysicalRoomKey = physicalKey;
            selectedSourceGroupId = null;
        }
        syncCurrentRoom();
    }

    private void syncCurrentRoom() {
        if (batchDepth > 0) {
            batchSyncPending = true;
            return;
        }
        if (syncing) return;
        syncing = true;
        try {
            if (config != null && !config.enabled()) {
                removeGeneratedGroups();
                return;
            }
            DungeonRoom room = tracker.currentRoom();
            if (room == null || !room.hasRoomId()) return;
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
        WaypointGroup userRoute = firstUserRouteGroup(room);
        if (userRoute != null) {
            if (!userRoute.enabled()) {
                removeGeneratedGroup(generatedId);
                return;
            }
            replaceGeneratedGroup(generatedId,
                    transformedRouteGroupForRoom(
                            room, userRoute, session, visibleSecretStages()));
            return;
        }

        DungeonRoomDefinition definition = DungeonRoomData.customDefinition(roomId);
        if (definition == null
                || definition.waypoints().isEmpty()
                || isCompletedAndHidden(room)) {
            removeGeneratedGroup(generatedId);
            return;
        }

        WaypointGroup group = routeGroupForRoom(
                room, definition, session, config);
        group.setVisibleMainSteps(visibleSecretStages());
        if (group.isEmpty()) {
            removeGeneratedGroup(generatedId);
            return;
        }
        group.setEnabled(config == null || config.roomRouteEnabled(roomId));
        replaceGeneratedGroup(generatedId, group);
    }

    /**
     * Swap in a rebuilt mirror without losing view progress: rebuilds happen on
     * every data/session change, and a SEQUENCE group that snapped back to
     * waypoint #1 each time would fight the player's advancement.
     */
    private void replaceGeneratedGroup(String generatedId, WaypointGroup next) {
        WaypointGroup previous = manager.get(generatedId);
        if (previous == null
                || java.util.Objects.equals(
                previous.runtimeSourceGroupId(), next.runtimeSourceGroupId())) {
            carryOverProgress(previous, next);
        }
        manager.add(next);
    }

    static void carryOverProgress(WaypointGroup previous, WaypointGroup next) {
        if (previous == null || next == null) return;
        if (next.activeSubwaypointParentIndex() >= 0 || next.isComplete()) return;
        if (previous.isComplete() && sameWaypointSequence(previous, next)) {
            next.setCurrentTargetIndex(next.size());
            return;
        }

        int previousActiveParent = previous.activeSubwaypointParentIndex();
        if (previousActiveParent >= 0) {
            int mappedParent = matchingWaypointIndex(
                    next, previous.get(previousActiveParent), previousActiveParent);
            if (mappedParent >= 0 && next.childEndExclusive(mappedParent) > mappedParent + 1) {
                next.advancePast(mappedParent);
                return;
            }
        }

        int previousCurrent = previous.currentIndex();
        if (previousCurrent <= 0 || previousCurrent >= previous.size()) return;
        Waypoint current = previous.get(previousCurrent);
        int mappedCurrent = matchingWaypointIndex(next, current, previousCurrent);
        if (mappedCurrent >= 0) next.setCurrentTargetIndex(mappedCurrent);
    }

    private static int matchingWaypointIndex(WaypointGroup group,
                                             Waypoint reference,
                                             int preferredIndex) {
        if (preferredIndex >= 0 && preferredIndex < group.size()
                && sameWaypointPosition(group.get(preferredIndex), reference)) {
            return preferredIndex;
        }
        for (int i = 0; i < group.size(); i++) {
            Waypoint candidate = group.get(i);
            if (sameWaypointPosition(candidate, reference)) return i;
        }
        return -1;
    }

    private static boolean sameWaypointPosition(Waypoint candidate, Waypoint reference) {
        return candidate.preciseX() == reference.preciseX()
                && candidate.preciseY() == reference.preciseY()
                && candidate.preciseZ() == reference.preciseZ()
                && candidate.isSubwaypoint() == reference.isSubwaypoint();
    }

    private static boolean sameWaypointSequence(WaypointGroup first, WaypointGroup second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            if (!sameWaypointPosition(first.get(i), second.get(i))) return false;
        }
        return true;
    }

    private boolean isCompletedAndHidden(DungeonRoom room) {
        return session != null
                && (config == null || config.hideCompletedRooms())
                && session.isRoomComplete(room);
    }

    /**
     * The user-authored route that suppresses downloaded community secrets --
     * but only when it actually contains waypoints. Empty leftovers (a
     * clicked-away "New Route", an aborted import) must not silently suppress
     * an installed secret route. Disabled routes still suppress (the user hid
     * their route deliberately; resurrecting the community secrets would undo
     * that), which is why this returns the group instead of a boolean -- the
     * caller also needs its enabled state.
     */
    private WaypointGroup firstUserRouteGroup(DungeonRoom room) {
        if (room == null) return null;
        List<WaypointGroup> enabled = new ArrayList<>();
        WaypointGroup disabled = null;
        for (WaypointGroup group : manager.groupsForZone(room.roomId())) {
            if (group.temp() || group.runtimeOnly() || group.isEmpty()) continue;
            if (!group.enabled()) {
                if (disabled == null) disabled = group;
                continue;
            }
            enabled.add(group);
        }
        if (enabled.isEmpty()) {
            selectedSourceGroupId = disabled == null ? null : disabled.id();
            return disabled;
        }
        if (selectedSourceGroupId != null) {
            for (WaypointGroup group : enabled) {
                if (selectedSourceGroupId.equals(group.id())) return group;
            }
        }

        Minecraft minecraft = Minecraft.getInstance();
        Vec3 playerPos = minecraft == null || minecraft.player == null
                ? null
                : minecraft.player.position();
        WaypointGroup selected = enabled.getFirst();
        if (playerPos != null) {
            double best = startDistanceSq(room, selected, playerPos);
            for (int i = 1; i < enabled.size(); i++) {
                WaypointGroup candidate = enabled.get(i);
                double distance = startDistanceSq(room, candidate, playerPos);
                if (distance < best) {
                    selected = candidate;
                    best = distance;
                }
            }
        }
        selectedSourceGroupId = selected.id();
        return selected;
    }

    static double startDistanceSq(DungeonRoom room, WaypointGroup group, Vec3 playerPos) {
        if (room == null || group == null || group.isEmpty() || playerPos == null) {
            return Double.POSITIVE_INFINITY;
        }
        Waypoint actual = DungeonRoomWaypointPlacement.toActualWaypoint(room, group.get(0));
        double dx = actual.centerX() - playerPos.x;
        double dy = actual.centerY() - playerPos.y;
        double dz = actual.centerZ() - playerPos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private int visibleSecretStages() {
        return config == null ? 1 : config.visibleSecretStages();
    }

    /** The stored (persisted, room-local) route for a room, or null. */
    public static WaypointGroup storedRouteForRoom(ActiveGroupManager manager, String roomId) {
        if (manager == null || roomId == null) return null;
        WaypointGroup disabled = null;
        for (WaypointGroup group : manager.groupsForZone(roomId)) {
            if (group.temp() || group.runtimeOnly() || group.isEmpty()) continue;
            if (group.enabled()) return group;
            if (disabled == null) disabled = group;
        }
        return disabled;
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
        if (mirror.runtimeSourceGroupId() != null) {
            WaypointGroup exact = manager.get(mirror.runtimeSourceGroupId());
            if (exact != null && !exact.runtimeOnly()) return exact;
        }
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
     * Apply an explicit editor progress change to both halves of a projected
     * dungeon route. The stored group owns the UI state, while its runtime
     * mirror owns rendering; mutating only one lets the next mirror rebuild
     * restore stale progress from the other.
     */
    public static void setManualCurrentIndex(ActiveGroupManager manager,
                                             WaypointGroup visibleGroup,
                                             int index) {
        applyManualProgress(manager, visibleGroup, group -> group.setCurrentIndex(index));
    }

    public static void resetManualProgress(ActiveGroupManager manager,
                                           WaypointGroup visibleGroup) {
        applyManualProgress(manager, visibleGroup, WaypointGroup::resetProgress);
    }

    private static void applyManualProgress(ActiveGroupManager manager,
                                            WaypointGroup visibleGroup,
                                            Consumer<WaypointGroup> mutation) {
        if (visibleGroup == null || mutation == null) return;
        mutation.accept(visibleGroup);
        if (manager == null) return;

        WaypointGroup stored = storedSourceForMirror(manager, visibleGroup);
        if (stored != null) mutation.accept(stored);
        WaypointGroup source = stored == null && !isGeneratedGroup(visibleGroup)
                ? visibleGroup
                : stored;
        if (source == null) return;

        WaypointGroup mirror = manager.get(generatedGroupId(source.zoneId()));
        if (mirror != null && mirror != visibleGroup) mutation.accept(mirror);
    }

    /** Persist a route-list visibility change at the owner of a dungeon mirror. */
    public static void setRouteEnabled(ActiveGroupManager manager,
                                       DungeonConfig config,
                                       WaypointGroup visibleGroup,
                                       boolean enabled) {
        if (visibleGroup == null) return;
        WaypointGroup stored = storedSourceForMirror(manager, visibleGroup);
        if (stored != null) {
            stored.setEnabled(enabled);
        } else if (isGeneratedGroup(visibleGroup) && config != null) {
            config.setRoomRouteEnabled(visibleGroup.zoneId(), enabled);
        }
        visibleGroup.setEnabled(enabled);

        WaypointGroup source = stored == null && !isGeneratedGroup(visibleGroup)
                ? visibleGroup
                : stored;
        if (manager != null && source != null) {
            WaypointGroup mirror = manager.get(generatedGroupId(source.zoneId()));
            if (mirror != null) mirror.setEnabled(enabled);
        }
    }

    public static void setDefinitionRouteEnabled(ActiveGroupManager manager,
                                                 DungeonConfig config,
                                                 String roomZoneId,
                                                 boolean enabled) {
        if (config == null || roomZoneId == null || roomZoneId.isBlank()) return;
        config.setRoomRouteEnabled(roomZoneId, enabled);
        if (manager == null) return;
        WaypointGroup mirror = manager.get(generatedGroupId(roomZoneId));
        if (mirror != null) mirror.setEnabled(enabled);
    }

    /**
     * True when in-world edits in this room should be refused because the room
     * shows downloaded secrets that the user has not converted into their own
     * route yet -- editing the throwaway mirror would silently discard changes.
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
        manager.removeAll(ids);
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
        return transformedRouteGroupForRoom(room, source, session, 0);
    }

    static WaypointGroup transformedRouteGroupForRoom(DungeonRoom room, WaypointGroup source,
                                                      DungeonRouteSession session,
                                                      int visibleSecretStages) {
        WaypointGroup group = new WaypointGroup(
                generatedGroupId(source.zoneId()), source.name(), source.zoneId());
        group.setRuntimeOnly(true);
        group.setRuntimeSourceGroupId(source.id());
        group.setVisibleMainSteps(visibleSecretStages);
        group.setLoadMode(source.loadMode());
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setDefaultRadius(source.defaultRadius());
        group.setSkipAheadEnabled(source.skipAheadEnabled());
        group.setPaint(source.paint());
        group.setPaintEnabled(source.paintEnabled());

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
     * own frame) -- the sync mirror projects them into each run's room
     * placement, so the converted route keeps working across runs. Once added,
     * the user route suppresses the definition-generated group; deleting it
     * brings the installed secrets back.
     */
    public static WaypointGroup editableRouteFromDefinition(DungeonRoomDefinition definition) {
        return editableRouteFromDefinition(definition, null);
    }

    static WaypointGroup editableRouteFromDefinition(DungeonRoomDefinition definition,
                                                       DungeonConfig config) {
        WaypointGroup group = WaypointGroup.create("Secret Route", definition.id());
        group.setLoadMode(WaypointGroup.LoadMode.SEQUENCE);
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setSkipAheadEnabled(false);

        List<Waypoint> waypoints = new ArrayList<>();
        List<Waypoint> leadingSupportWaypoints = new ArrayList<>();
        boolean hasProgressWaypoint = false;
        int currentStage = Integer.MIN_VALUE;
        boolean stageHasMain = false;
        for (DungeonWaypoint dungeonWaypoint : definition.waypoints()) {
            if (dungeonWaypoint.secretIndex() <= 0) {
                Waypoint supportWaypoint = new Waypoint(
                        dungeonWaypoint.x(), dungeonWaypoint.y(), dungeonWaypoint.z(),
                        dungeonWaypoint.name(),
                        dungeonWaypoint.hasOwnColor()
                                ? dungeonWaypoint.color()
                                : SUPPORT_WAYPOINT_COLOR,
                        Waypoint.FLAG_SUBWAYPOINT, 0.0);
                if (hasProgressWaypoint) {
                    waypoints.add(supportWaypoint);
                } else {
                    leadingSupportWaypoints.add(supportWaypoint);
                }
                continue;
            }

            if (dungeonWaypoint.secretIndex() != currentStage) {
                currentStage = dungeonWaypoint.secretIndex();
                stageHasMain = false;
            }
            int flags = DungeonWaypointSkipRules.flagsForTrigger(dungeonWaypoint.trigger());
            if (stageHasMain) flags |= Waypoint.FLAG_SUBWAYPOINT;
            if (dungeonWaypoint.completesSecret()) flags |= Waypoint.FLAG_DUNGEON_SECRET;
            if (actionUsesLineOfSight(dungeonWaypoint.trigger())) {
                flags |= Waypoint.FLAG_DEPTH_CHECKED;
            }
            waypoints.add(new Waypoint(
                    dungeonWaypoint.x(), dungeonWaypoint.y(), dungeonWaypoint.z(),
                    dungeonWaypoint.name(),
                    dungeonWaypoint.hasOwnColor()
                            ? dungeonWaypoint.color()
                            : defaultActionColor(dungeonWaypoint, config),
                    flags, 0.0));
            if (!hasProgressWaypoint) {
                hasProgressWaypoint = true;
                waypoints.addAll(leadingSupportWaypoints);
                leadingSupportWaypoints.clear();
            }
            stageHasMain = true;
            for (DungeonHighlight highlight : dungeonWaypoint.highlights()) {
                int highlightFlags = Waypoint.FLAG_SUBWAYPOINT | highlightFlags(highlight.style());
                if (dungeonWaypoint.trigger() == DungeonWaypointTrigger.THROW_PEARL) {
                    highlightFlags |= Waypoint.FLAG_DUNGEON_PEARL_TARGET
                            | Waypoint.FLAG_HIDE_BEACON
                            | Waypoint.FLAG_HIDE_NAME;
                } else if (dungeonWaypoint.trigger() == DungeonWaypointTrigger.BREAK_BLOCKS
                        || dungeonWaypoint.trigger() == DungeonWaypointTrigger.DUNGEONBREAKER) {
                    highlightFlags |= DungeonWaypointSkipRules.flagsForTrigger(
                            dungeonWaypoint.trigger());
                    if (actionUsesLineOfSight(dungeonWaypoint.trigger())) {
                        highlightFlags |= Waypoint.FLAG_DEPTH_CHECKED;
                    }
                }
                waypoints.add(new Waypoint(
                        highlight.x(), highlight.y(), highlight.z(),
                        "", highlight.hasOwnColor()
                        ? highlight.color()
                        : defaultActionColor(dungeonWaypoint, config),
                        highlightFlags, 0.0));
            }
        }
        if (!hasProgressWaypoint) {
            group.setLoadMode(WaypointGroup.LoadMode.STATIC);
            for (Waypoint supportWaypoint : leadingSupportWaypoints) {
                waypoints.add(supportWaypoint.withSubwaypoint(false));
            }
        }
        group.addAll(waypoints);
        return group;
    }

    private static int defaultActionColor(DungeonWaypoint waypoint, DungeonConfig config) {
        if (waypoint == null) return SUPPORT_WAYPOINT_COLOR;
        if (waypoint.hasOwnColor()) return waypoint.color();
        return switch (waypoint.trigger()) {
            case INTERACT_BLOCK, FLIP_LEVER -> config == null
                    ? DungeonSecretCategory.LEVER.defaultColor : config.automaticInteractColor();
            case OPEN_CHEST, CHAT_MESSAGE, ANY_SECRET -> config == null
                    ? SECRET_WAYPOINT_COLOR : config.automaticSecretColor();
            case USE_SUPERBOOM -> config == null
                    ? DungeonSecretCategory.SUPERBOOM.defaultColor : config.automaticSuperboomColor();
            case PICKUP_ITEM -> config == null
                    ? DungeonSecretCategory.ITEM.defaultColor : config.automaticItemColor();
            case KILL_BAT -> config == null
                    ? DungeonSecretCategory.BAT.defaultColor : config.automaticBatColor();
            case BREAK_BLOCKS -> config == null
                    ? DungeonSecretCategory.STONK.defaultColor : config.automaticBreakBlocksColor();
            case DUNGEONBREAKER -> config == null
                    ? DungeonSecretCategory.DUNGEONBREAKER.defaultColor
                    : config.automaticDungeonbreakerColor();
            case ETHERWARP -> config == null
                    ? DungeonSecretCategory.ETHERWARP.defaultColor : config.automaticEtherwarpColor();
            case THROW_PEARL -> config == null
                    ? DungeonSecretCategory.PEARL.defaultColor : config.automaticPearlColor();
            case MANUAL -> waypoint.category().defaultColor;
        };
    }

    private static boolean actionUsesLineOfSight(DungeonWaypointTrigger trigger) {
        return trigger == DungeonWaypointTrigger.ETHERWARP
                || trigger == DungeonWaypointTrigger.DUNGEONBREAKER;
    }

    /**
     * Install imported room definitions as ordinary persisted routes. Existing
     * dungeon routes are kept but disabled, making the newly imported set the
     * active choice without deleting the player's prior work.
     */
    public static List<WaypointGroup> installEditableRoutes(
            ActiveGroupManager manager, DungeonConfig config,
            Collection<DungeonRoomDefinition> definitions) {
        return installEditableRoutes(manager, config, definitions, true);
    }

    /**
     * @param supersedePreviousRoutes when {@code true} (the import and download
     *     paths) every existing dungeon room route is hidden so the incoming set
     *     reads on its own; nothing is deleted, so any route can be switched
     *     back on individually. Backfill paths that merely top up missing
     *     routes pass {@code false} -- hiding the player's library because the
     *     mod noticed a gap at startup would be a nasty surprise.
     */
    public static List<WaypointGroup> installEditableRoutes(
            ActiveGroupManager manager, DungeonConfig config,
            Collection<DungeonRoomDefinition> definitions,
            boolean supersedePreviousRoutes) {
        if (manager == null || definitions == null || definitions.isEmpty()) {
            return List.of();
        }

        List<WaypointGroup> routes = new ArrayList<>();
        for (DungeonRoomDefinition definition : definitions) {
            if (definition != null && !definition.waypoints().isEmpty()) {
                WaypointGroup route = editableRouteFromDefinition(definition, config);
                route.setName(definition.displayName() + " secrets");
                routes.add(route);
            }
        }
        if (routes.isEmpty()) return List.of();

        List<String> installedRoomIds = new ArrayList<>();
        for (DungeonRoomDefinition definition : definitions) {
            if (definition != null && !definition.waypoints().isEmpty()) {
                installedRoomIds.add(definition.id());
            }
        }

        // A fresh import supersedes what was there: hide every existing dungeon
        // room route, not just the rooms this import happens to overlap. Someone
        // installing a new route set does not want their old one drawing over it
        // in the rooms the new set does not cover.
        List<String> supersededRoomIds = new ArrayList<>(installedRoomIds);
        if (supersedePreviousRoutes) {
            for (DungeonRoomDefinition known : DungeonRoomData.allDefinitions()) {
                if (known != null && !supersededRoomIds.contains(known.id())) {
                    supersededRoomIds.add(known.id());
                }
            }
        }
        batched(() -> {
            for (WaypointGroup existing : manager.allGroups()) {
                if (existing.temp() || existing.runtimeOnly()) continue;
                boolean supersededRoom = supersedePreviousRoutes
                        ? DungeonRoomData.definition(existing.zoneId()) != null
                        : installedRoomIds.contains(existing.zoneId());
                if (supersededRoom) existing.setEnabled(false);
            }
            if (config != null) {
                config.disableRoomRoutes(supersededRoomIds);
            }
            manager.addAll(routes);
        });
        return List.copyOf(routes);
    }

    /** Installs persisted secret definitions which predate ordinary-route imports. */
    public static List<WaypointGroup> installMissingEditableRoutes(
            ActiveGroupManager manager, DungeonConfig config,
            Collection<DungeonRoomDefinition> definitions) {
        if (manager == null || definitions == null || definitions.isEmpty()) return List.of();

        List<DungeonRoomDefinition> missing = new ArrayList<>();
        for (DungeonRoomDefinition definition : definitions) {
            if (definition != null && !definition.waypoints().isEmpty()
                    && storedRouteForRoom(manager, definition.id()) == null) {
                missing.add(definition);
            }
        }
        return installEditableRoutes(manager, config, missing, false);
    }

    static WaypointGroup routeGroupForRoom(DungeonRoom room, DungeonRoomDefinition definition,
                                           DungeonRouteSession session) {
        return routeGroupForRoom(room, definition, session, null);
    }

    static WaypointGroup routeGroupForRoom(DungeonRoom room, DungeonRoomDefinition definition,
                                           DungeonRouteSession session, DungeonConfig config) {
        List<DungeonWaypoint> visible = new ArrayList<>();
        boolean complete = session != null && session.isRoomComplete(room);
        int lastStage = definition.waypoints().stream()
                .mapToInt(DungeonWaypoint::secretIndex)
                .max()
                .orElse(0);
        for (DungeonWaypoint waypoint : definition.waypoints()) {
            if (!isAlreadyFound(session, room, waypoint)
                    || complete && waypoint.secretIndex() == lastStage) {
                visible.add(waypoint);
            }
        }
        WaypointGroup local = editableRouteFromDefinition(
                definition.withWaypoints(visible), config);
        local.setName("Dungeon Secrets -- " + definition.displayName());
        WaypointGroup group = transformedRouteGroupForRoom(room, local, session, 0);
        group.setRuntimeSourceGroupId(null);
        if (complete) group.setCurrentTargetIndex(group.size());
        return group;
    }

    private static boolean isAlreadyFound(DungeonRouteSession session, DungeonRoom room,
                                          DungeonWaypoint waypoint) {
        return session != null
                && session.peekStatus(room, waypoint) == DungeonRouteSession.Status.FOUND;
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

    /**
     * Delete every dungeon room route: the installed route groups, the imported
     * room definitions behind them, and the packs that grouped them.
     *
     * <p>Bundled definitions survive -- they ship with the mod and reappear as
     * soon as the player enters a matching room, which is the intended floor
     * rather than something this action should try to erase.
     *
     * @return number of route groups removed
     */
    public static int deleteAllDungeonRoutes(ActiveGroupManager manager, DungeonConfig config) {
        if (manager == null) return 0;
        List<String> removeIds = new ArrayList<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (group.temp() || group.runtimeOnly()) continue;
            if (DungeonRoomData.definition(group.zoneId()) != null) removeIds.add(group.id());
        }
        batched(() -> {
            manager.removeAll(removeIds);
            DungeonRoomData.clearAllCustom();
            if (config != null) config.disableRoomRoutes(allRoomIds());
        });
        manager.fireDataChanged();
        return removeIds.size();
    }

    private static List<String> allRoomIds() {
        List<String> out = new ArrayList<>();
        for (DungeonRoomDefinition definition : DungeonRoomData.allDefinitions()) {
            if (definition != null) out.add(definition.id());
        }
        return out;
    }
}
