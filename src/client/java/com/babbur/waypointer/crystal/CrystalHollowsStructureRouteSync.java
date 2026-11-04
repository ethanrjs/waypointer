package com.babbur.waypointer.crystal;

import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CrystalHollowsStructureRouteSync {

    public static final String GENERATED_PREFIX = "crystal_hollows:route:";

    private final ActiveGroupManager manager;
    private final CrystalHollowsTracker tracker;
    private final Map<String, String> signatures = new HashMap<>();
    private boolean syncing;

    public CrystalHollowsStructureRouteSync(ActiveGroupManager manager, CrystalHollowsTracker tracker) {
        this.manager = manager;
        this.tracker = tracker;
    }

    public void install() {
        manager.addDataListener(this::sync);
        manager.addZoneListener(zone -> sync());
        tracker.addDetectionListener(this::sync);
        sync();
    }

    public static String generatedId(String sourceGroupId) {
        return GENERATED_PREFIX + sourceGroupId;
    }

    public static boolean isGeneratedGroup(WaypointGroup group) {
        return group != null && group.runtimeOnly() && group.id().startsWith(GENERATED_PREFIX);
    }

    public void sync() {
        if (syncing) return;
        boolean inLobby = tracker.active() && tracker.lobby() != null;
        if (!inLobby && signatures.isEmpty()) return;
        syncing = true;
        try {
            Map<String, WaypointGroup> replacements = new LinkedHashMap<>();
            Map<String, String> nextSignatures = new HashMap<>();
            Set<String> kept = new HashSet<>();
            if (inLobby) {
                for (WaypointGroup source : manager.allGroups()) {
                    if (!CrystalHollowsStructureRoutes.isStructureRoute(source)
                            || !source.enabled() || source.isEmpty()) continue;
                    CrystalHollowsPosition pivot = CrystalHollowsStructureRoutes.pivotFor(source);
                    if (pivot == null) continue;
                    String id = generatedId(source.id());
                    String signature = signature(source, pivot);
                    WaypointGroup existing = manager.get(id);
                    if (existing != null && !isGeneratedGroup(existing)) continue;
                    if (existing != null && signature.equals(signatures.get(id))) {
                        kept.add(id);
                        nextSignatures.put(id, signature);
                        continue;
                    }
                    replacements.put(id, project(source, pivot, existing));
                    nextSignatures.put(id, signature);
                }
            }
            List<String> removals = new ArrayList<>();
            for (WaypointGroup group : manager.allGroups()) {
                if (!isGeneratedGroup(group) || kept.contains(group.id())) continue;
                removals.add(group.id());
            }
            signatures.clear();
            signatures.putAll(nextSignatures);
            if (!removals.isEmpty() || !replacements.isEmpty()) {
                manager.replaceGroupsAtomically(removals, replacements.values());
            }
        } finally {
            syncing = false;
        }
    }

    static WaypointGroup project(WaypointGroup source, CrystalHollowsPosition pivot, WaypointGroup previous) {
        WaypointGroup group = new WaypointGroup(generatedId(source.id()), source.name(),
                CrystalHollowsStructureFolder.ZONE_ID);
        group.setRuntimeOnly(true);
        group.setRuntimeSourceGroupId(source.id());
        group.setRouteKind(WaypointGroup.RouteKind.REGULAR);
        group.setLoadMode(source.loadMode());
        group.setGradientMode(WaypointGroup.GradientMode.MANUAL);
        group.setDefaultRadius(source.defaultRadius());
        group.setSkipAheadEnabled(source.skipAheadEnabled());
        group.setPaint(source.paint());
        group.setPaintEnabled(source.paintEnabled());
        group.setStylePresetId(source.stylePresetId());
        group.setStyleOverride(source.styleOverride());
        List<Waypoint> waypoints = new ArrayList<>(source.size());
        for (Waypoint stored : source.waypoints()) {
            waypoints.add(CrystalHollowsStructureZones.toWorld(stored, pivot));
        }
        group.addAll(waypoints);
        int progress = previous != null && previous.size() == group.size()
                ? previous.currentIndex() : source.currentIndex();
        group.setCurrentIndex(Math.max(0, Math.min(progress, group.size())));
        return group;
    }

    static String signature(WaypointGroup source, CrystalHollowsPosition pivot) {
        StringBuilder out = new StringBuilder(source.id()).append('|').append(source.name())
                .append('|').append(pivot.x()).append(',').append(pivot.y()).append(',').append(pivot.z())
                .append('|').append(source.loadMode()).append('|').append(source.defaultRadius())
                .append('|').append(source.skipAheadEnabled()).append('|').append(source.stylePresetId())
                .append('|').append(source.styleOverride().values().hashCode())
                .append('|').append(source.paintEnabled()).append('|').append(System.identityHashCode(source.paint()));
        for (Waypoint waypoint : source.waypoints()) {
            out.append(';').append(waypoint.preciseX()).append(',').append(waypoint.preciseY())
                    .append(',').append(waypoint.preciseZ()).append(',').append(waypoint.name())
                    .append(',').append(waypoint.color()).append(',').append(waypoint.flags())
                    .append(',').append(waypoint.customRadius())
                    .append(',').append(waypoint.styleOverride().values().hashCode());
        }
        return out.toString();
    }
}
