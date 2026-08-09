package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Keeps the rendered route matched to the current room. Saved routes win. */
public final class DungeonRoomRouteSync {

    private final ActiveGroupManager manager;
    private final DungeonStateTracker tracker;
    private final DungeonConfig config;
    private final Consumer<DungeonRoom> roomListener = this::onRoomChanged;
    private final Consumer<Zone> zoneListener = this::onZoneChanged;
    private final Runnable syncListener = this::syncCurrentRoom;
    private boolean syncing;
    private String selectedPhysicalRoomKey;
    private String selectedSourceGroupId;

    private static final List<DungeonRoomRouteSync> INSTALLED = new ArrayList<>();
    private static int batchDepth;
    private static boolean batchSyncPending;

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

    public DungeonRoomRouteSync(ActiveGroupManager manager, DungeonStateTracker tracker) {
        this(manager, tracker, null);
    }

    public DungeonRoomRouteSync(ActiveGroupManager manager, DungeonStateTracker tracker,
                                DungeonConfig config) {
        this.manager = manager;
        this.tracker = tracker;
        this.config = config;
    }

    public void install() {
        tracker.addRoomListener(roomListener);
        manager.addZoneListener(zoneListener);
        manager.addDataListener(syncListener);
        if (config != null) config.addChangeListener(syncListener);
        if (!INSTALLED.contains(this)) INSTALLED.add(this);
        syncCurrentRoom();
    }

    public void uninstall() {
        INSTALLED.remove(this);
        tracker.removeRoomListener(roomListener);
        manager.removeZoneListener(zoneListener);
        manager.removeDataListener(syncListener);
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
        String generatedId = DungeonRoomRouteProjection.generatedGroupId(roomId);

        WaypointGroup userRoute = firstUserRouteGroup(room);
        if (userRoute != null) {
            if (!userRoute.enabled()) {
                removeGeneratedGroup(generatedId);
                return;
            }
            replaceGeneratedGroup(generatedId,
                    DungeonRoomRouteProjection.transformedRouteGroupForRoom(
                            room, userRoute, visibleSecretStages()));
            return;
        }

        removeGeneratedGroup(generatedId);
    }

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

    private WaypointGroup firstUserRouteGroup(DungeonRoom room) {
        if (room == null) return null;
        List<WaypointGroup> enabled = new ArrayList<>();
        WaypointGroup disabled = null;
        for (WaypointGroup group : manager.groupsForZone(room.roomId())) {
            if (group.routeKind() != WaypointGroup.RouteKind.DUNGEON
                    || group.temp() || group.runtimeOnly() || group.isEmpty()) continue;
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

    private void removeGeneratedGroups() {
        List<String> ids = new ArrayList<>();
        for (WaypointGroup group : manager.allGroups()) {
            if (DungeonRoomRouteProjection.isGeneratedGroup(group)) ids.add(group.id());
        }
        manager.removeAll(ids);
    }

    private void removeGeneratedGroup(String id) {
        WaypointGroup existing = manager.get(id);
        if (DungeonRoomRouteProjection.isGeneratedGroup(existing)) {
            manager.remove(id);
        }
    }
}
