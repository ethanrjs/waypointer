package com.babbur.waypointer.crystal;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Waypoint;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.crystal.CrystalHollowsChatParser.CrystalUpdate;
import com.babbur.waypointer.crystal.CrystalHollowsChatParser.NpcDialogue;
import com.babbur.waypointer.crystal.CrystalHollowsChatParser.PlayerChat;
import com.babbur.waypointer.crystal.CrystalHollowsChatParser.SharedCoordinate;
import com.babbur.waypointer.crystal.compass.Crystal;
import com.babbur.waypointer.crystal.compass.CrystalState;
import com.babbur.waypointer.location.SidebarTexts;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Tracks lobby structures from visible game signals; never scans blocks or chunks. */
public final class CrystalHollowsTracker {

    private static final String SESSION_SERVER_ID = "session-only";
    private static final int TELEPORT_DELAY_TICKS = 50;
    private static final CrystalHollowsStructure[] STRUCTURES = CrystalHollowsStructure.values();

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final CrystalHollowsStore store;
    private final CrystalHollowsProjection projection;
    private final List<Runnable> detectionListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<Integer> processedEntityIds = new HashSet<>();
    private final EnumMap<Crystal, CrystalState> tabCrystalStates = new EnumMap<>(Crystal.class);
    private CrystalHollowsLobbyState lobby;
    private String serverId;
    private boolean active;
    private int ticks;
    private int delayTicks;
    private boolean hasKingsScent;
    private int currentDay = -1;
    private CrystalHollowsStructure sidebarStructure;
    private CrystalHollowsAreaSession areaSession;
    private CrystalHollowsPosition lastRoughPosition;
    private boolean lastStructureWaypoints;
    private boolean lastHideStructuresFolder;
    private boolean lastShowRough;
    private boolean lastNucleusWaypoints;
    private boolean lastCompassAutoFocus;
    private int lastDetectionMask;
    private DebugSnapshot lastDebugSnapshot = DebugSnapshot.inactive();
    private WishingCompassController compassController;
    private WaypointGroup compassTargetGroup;
    private Waypoint compassTarget;
    private StructureSighting compassTargetSighting;
    private StructureSighting compassLocalTargetSighting;
    private CrystalHollowsZone compassOriginRegion;
    private CrystalHollowsZone compassCurrentRegion;
    private boolean compassCurrentRegionKnown;
    private final Map<String, StructureSighting> compassShares = new java.util.HashMap<>();
    private long nextCompassShareId;
    private String compassShareReference;
    private boolean batchingDetections;
    private boolean detectionsChanged;
    private long caveInDeadline;
    private final Map<StructureSighting, Integer> incorrectStructureTicks = new java.util.HashMap<>();
    private String verificationArea;

    public CrystalHollowsTracker(
            ActiveGroupManager manager, WaypointerConfig config, CrystalHollowsStore store) {
        this.manager = manager;
        this.config = config;
        this.lastCompassAutoFocus = config.crystalHollowsCompassAutoFocus();
        this.store = store;
        this.projection = new CrystalHollowsProjection(manager, config);
    }

    public void install() {
        manager.addZoneListener(this::onZoneChanged);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> leaveLobby());
        ClientReceiveMessageEvents.MODIFY_GAME.register(this::addCoordinateShareActions);
        ClientReceiveMessageEvents.GAME.register(this::onGameMessage);
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        onZoneChanged(manager.currentZone());
    }

    public boolean active() { return active; }
    public CrystalHollowsLobbyState lobby() { return lobby; }
    public String serverId() { return serverId; }
    public boolean hasKingsScent() { return hasKingsScent; }
    public CrystalHollowsStructure sidebarStructure() { return sidebarStructure; }
    public DebugSnapshot debugSnapshot() { return lastDebugSnapshot; }

    public boolean detectionEnabled(StructureSighting sighting) {
        return CrystalHollowsDetectionPolicy.allows(config, sighting);
    }

    int closingInSeconds(String sidebar, long now) {
        int countdown = CrystalHollowsSidebar.closingInSeconds(sidebar);
        if (countdown >= 0) return countdown;
        return caveInDeadline == 0 ? -1 : (int) Math.max(0, (caveInDeadline - now + 999) / 1000);
    }

    void observeCaveInWarning(String text, long now) {
        if (!active || lobby == null || !CrystalHollowsChatParser.isCaveInWarning(text)) return;
        // Warning timing is approximate. Later warnings must not extend the first estimate.
        long deadline = now + 60_000;
        caveInDeadline = caveInDeadline == 0 ? deadline : Math.min(caveInDeadline, deadline);
    }

    public Map<Crystal, CrystalState> crystalStatesForCompass() {
        return CrystalHollowsTabList.preferredStates(
                tabCrystalStates, lobby == null ? Map.of() : lobby.crystals());
    }

    public void attachCompassController(WishingCompassController controller) {
        compassController = controller;
    }

    /** Runs after sightings change, the projection rebuilds, or a lobby starts or ends. */
    public void addDetectionListener(Runnable listener) {
        if (listener != null) detectionListeners.add(listener);
    }

    private void notifyDetectionListeners() {
        for (Runnable listener : detectionListeners) listener.run();
    }

    public CrystalHollowsLobbyState.MergeResult merge(StructureSighting sighting) {
        return merge(sighting, true);
    }

    public CrystalHollowsLobbyState.MergeResult merge(StructureSighting sighting, boolean announce) {
        if (!active || lobby == null) {
            Waypointer.LOGGER.debug("Crystal Hollows merge ignored while inactive: {}", sighting);
            return CrystalHollowsLobbyState.MergeResult.IGNORED;
        }
        if (!detectionEnabled(sighting)) return CrystalHollowsLobbyState.MergeResult.IGNORED;
        CrystalHollowsLobbyState.MergeResult result = lobby.merge(sighting);
        Waypointer.LOGGER.debug("Crystal Hollows merge {}: {}", result, sighting);
        boolean localArrival = sighting.confidence() == SightingConfidence.ENTITY
                || sighting.confidence() == SightingConfidence.NPC_CHAT
                || sighting.confidence() == SightingConfidence.ROUGH_AREA;
        if (localArrival) {
            detectionsChanged |= projection.markArrived(lobby, sighting);
            refineCompassTarget(sighting);
            if (compassTargetSighting != null && compassTargetSighting.structure() == sighting.structure()
                    && (!sighting.structure().multiInstance()
                        || compassTargetSighting.position().distanceSquared(sighting.position()) <= 60.0 * 60.0)) {
                finishCompassNavigation();
            }
        }
        if (result != CrystalHollowsLobbyState.MergeResult.IGNORED) {
            if (compassTargetSighting != null) {
                refineCompassTarget(resolveCompassTarget(compassTargetSighting));
            }
            detectionsChanged = true;
            if (!batchingDetections) flushDetections();
            if (announce && config.crystalHollowsAnnounceDetections()
                    && sighting.confidence() != SightingConfidence.ROUGH_AREA
                    && (result == CrystalHollowsLobbyState.MergeResult.ADDED
                            || result == CrystalHollowsLobbyState.MergeResult.UPGRADED)) {
                announceDetection(sighting);
            }
        }
        if (!batchingDetections) flushDetections();
        return result;
    }

    void batchDetections(Runnable detections) {
        boolean wasBatching = batchingDetections;
        batchingDetections = true;
        try {
            detections.run();
        } finally {
            batchingDetections = wasBatching;
            if (!batchingDetections) flushDetections();
        }
    }

    void applyRelayUpdate(StructureRelayProtocol.Update update) {
        if (update == null || !active || lobby == null) return;
        batchDetections(() -> {
            if (update.snapshot()) detectionsChanged |= lobby.clearRemoteSightings("relay");
            for (StructureSighting sighting : update.sightings()) merge(sighting, false);
        });
    }

    private void flushDetections() {
        if (!detectionsChanged) return;
        detectionsChanged = false;
        rebuildProjection();
        persist();
        notifyDetectionListeners();
    }

    void focusCompassTarget(StructureSighting sighting) {
        focusCompassTarget(sighting, null);
    }

    void focusCompassTarget(StructureSighting sighting, CrystalHollowsZone originRegion) {
        compassLocalTargetSighting = sighting.confidence() == SightingConfidence.SHARED_REMOTE ? null : sighting;
        sighting = resolveCompassTarget(sighting);
        finishCompassNavigation();
        compassTargetGroup = manager.addTempWaypoint(sighting.x(), sighting.y(), sighting.z(),
                Component.translatable("waypointer.crystal.structure." + sighting.structure().id())
                        .getString(),
                Waypoint.TEMP_UNTIL_LEAVE, 0L, sighting.structure().rgb());
        int index = compassTargetGroup.size() - 1;
        compassTarget = compassTargetGroup.get(index)
                .withFlags(Waypoint.FLAG_THROUGH_WALL | Waypoint.FLAG_LOCKED_COLOR);
        compassTargetGroup.set(index, compassTarget);
        compassTarget = compassTargetGroup.get(index);
        compassTargetSighting = sighting;
        compassOriginRegion = originRegion;
        compassShareReference = "compass:" + (++nextCompassShareId);
        compassShares.put(compassShareReference, sighting);
        compassTargetGroup.setEnabled(true);
        CompassMarkerState.markTarget(compassTarget);
        if (compassCurrentRegionKnown && originRegion != null && originRegion != compassCurrentRegion) {
            CompassMarkerState.markRegionExited(compassTarget);
        } else if (config.crystalHollowsCompassAutoFocus()) {
            manager.focusTempWaypoint(compassTargetGroup, index);
        }
        manager.fireTransientDataChanged();
    }

    /** Reentry never resumes navigation. Only a new compass solution starts it again. */
    void observeCompassPosition(double x, double y, double z) {
        CrystalHollowsZone nextRegion = CrystalHollowsGeometry.zoneAt(x, y, z);
        if (compassCurrentRegionKnown && nextRegion != compassCurrentRegion && compassController != null) {
            compassController.onRegionChanged(compassCurrentRegion, nextRegion);
        }
        compassCurrentRegion = nextRegion;
        compassCurrentRegionKnown = true;
        if (compassOriginRegion == null || compassOriginRegion == compassCurrentRegion
                || !compassMarkerRetained() || CompassMarkerState.navigationStopped(compassTarget)) return;
        if (compassOwnsFocus()) manager.clearTempWaypointFocus();
        CompassMarkerState.markRegionExited(compassTarget);
        manager.fireTransientDataChanged();
    }

    private boolean compassMarkerRetained() {
        return compassTargetGroup != null && manager.get(compassTargetGroup.id()) == compassTargetGroup
                && compassTarget != null
                && compassTargetGroup.waypoints().stream().anyMatch(waypoint -> waypoint == compassTarget);
    }

    private boolean compassOwnsFocus() {
        if (!compassMarkerRetained() || !manager.tempWaypointFocusActive()) return false;
        int index = compassTargetGroup.focusedVisibleIndex();
        return index >= 0 && index < compassTargetGroup.size() && compassTargetGroup.get(index) == compassTarget;
    }

    public enum CompassNavigationState { NONE, ACTIVE, ARRIVED, REGION_EXITED, DEFOCUSED, MARKER_REMOVED }

    /** Region values are known enums; null currentRegion with currentRegionKnown means outside the bounds.
     * Navigation enabled is lifecycle state, independent of the user's tracer and auto-focus settings. */
    public record CompassNavigationSnapshot(CrystalHollowsZone originRegion,
            CrystalHollowsZone currentRegion, boolean currentRegionKnown,
            CompassNavigationState state, boolean navigationEnabled,
            boolean markerRetained, boolean ownsFocus) {}

    public CompassNavigationSnapshot compassNavigationSnapshot() {
        boolean retained = compassMarkerRetained();
        CompassNavigationState state = compassTarget == null ? CompassNavigationState.NONE
                : !retained ? CompassNavigationState.MARKER_REMOVED
                : CompassMarkerState.regionExited(compassTarget) ? CompassNavigationState.REGION_EXITED
                : CompassMarkerState.arrived(compassTarget) ? CompassNavigationState.ARRIVED
                : CompassMarkerState.defocused(compassTarget) ? CompassNavigationState.DEFOCUSED
                : CompassNavigationState.ACTIVE;
        return new CompassNavigationSnapshot(compassOriginRegion, compassCurrentRegion,
                compassCurrentRegionKnown, state, state == CompassNavigationState.ACTIVE,
                retained, compassOwnsFocus());
    }

    public StructureSighting compassTargetSighting() { return compassTargetSighting; }
    public StructureSighting compassNavigationTarget() {
        return compassMarkerRetained() && !CompassMarkerState.navigationStopped(compassTarget)
                ? compassTargetSighting : null;
    }
    public String compassShareReference() { return compassShareReference; }
    public StructureSighting compassShare(String reference) { return compassShares.get(reference); }

    private StructureSighting resolveCompassTarget(StructureSighting target) {
        if (lobby == null) return target;
        StructureSighting resolved = target;
        for (StructureSighting candidate : lobby.sightings()) {
            if (!refinesCompassTarget(target, candidate)) continue;
            // Do not choose arbitrarily between nearby structures or separate instances.
            if (resolved != target) {
                return target;
            }
            resolved = candidate;
        }
        return resolved;
    }

    private static boolean refinesCompassTarget(StructureSighting target, StructureSighting sighting) {
        double distance = target.position().distanceSquared(sighting.position());
        boolean ambiguous = target.structure() == CrystalHollowsStructure.WISHING_TARGET
                && (target.candidates().isEmpty() || target.candidates().contains(sighting.structure()))
                && sighting.structure() != CrystalHollowsStructure.WISHING_TARGET
                && distance <= 80 * 80;
        if (!ambiguous && (target.structure() != sighting.structure()
                || sighting.structure().multiInstance() && distance > 60 * 60)) return false;
        int incomingStrength = (sighting.remoteEvidence() == null ? sighting.confidence()
                : sighting.remoteEvidence()).ordinal();
        int currentStrength = (target.remoteEvidence() == null ? target.confidence()
                : target.remoteEvidence()).ordinal();
        return ambiguous && sighting.confidence() != SightingConfidence.SHARED_REMOTE
                || incomingStrength > currentStrength
                || incomingStrength == currentStrength && (ambiguous
                    || target.confidence() == SightingConfidence.SHARED_REMOTE
                        && sighting.confidence() != SightingConfidence.SHARED_REMOTE);
    }

    void refineCompassTarget(StructureSighting sighting) {
        if (compassTargetSighting == null || compassTargetGroup == null
                || manager.get(compassTargetGroup.id()) != compassTargetGroup) return;
        if (!refinesCompassTarget(compassTargetSighting, sighting)) return;
        updateCompassMarker(sighting);
    }

    private void updateCompassMarker(StructureSighting sighting) {
        for (int index = 0; index < compassTargetGroup.size(); index++) {
            if (compassTargetGroup.get(index) != compassTarget) continue;
            boolean defocused = CompassMarkerState.defocused(compassTarget);
            boolean arrived = CompassMarkerState.arrived(compassTarget);
            boolean regionExited = CompassMarkerState.regionExited(compassTarget);
            compassTargetGroup.set(index, compassTarget.withPos(sighting.x(), sighting.y(), sighting.z())
                    .withName(Component.translatable("waypointer.crystal.structure."
                            + sighting.structure().id()).getString())
                    .withColor(sighting.structure().rgb()));
            compassTarget = compassTargetGroup.get(index);
            CompassMarkerState.markTarget(compassTarget);
            if (defocused) CompassMarkerState.markDefocused(compassTarget);
            if (arrived) CompassMarkerState.markArrived(compassTarget);
            if (regionExited) CompassMarkerState.markRegionExited(compassTarget);
            compassTargetSighting = sighting;
            if (sighting.confidence() != SightingConfidence.SHARED_REMOTE) compassLocalTargetSighting = sighting;
            compassShares.put(compassShareReference, sighting);
            manager.fireTransientDataChanged();
            return;
        }
    }

    void checkCompassArrival(CrystalHollowsStructure area, double x, double y, double z) {
        if (compassTargetSighting == null || compassTarget == null) return;
        CrystalHollowsStructure target = compassTargetSighting.structure();
        boolean zoneArrival = area != null && (area == target
                || target == CrystalHollowsStructure.WISHING_TARGET
                    && (compassTargetSighting.candidates().isEmpty()
                        || compassTargetSighting.candidates().contains(area)));
        if (zoneArrival && target == CrystalHollowsStructure.WISHING_TARGET) {
            StructureSighting resolved = new StructureSighting(area, compassTargetSighting.x(),
                    compassTargetSighting.y(), compassTargetSighting.z(), SightingConfidence.COMPASS,
                    "compass:sidebar", System.currentTimeMillis());
            updateCompassMarker(resolved);
            merge(resolved, false);
        }
        double dx = x - compassTarget.x(), dy = y - compassTarget.y(), dz = z - compassTarget.z();
        if (zoneArrival || target.sidebarName() == null && dx * dx + dy * dy + dz * dz <= 100) {
            finishCompassNavigation();
        }
    }

    void refreshCompassFocusSetting() {
        boolean autoFocus = config.crystalHollowsCompassAutoFocus();
        if (lastCompassAutoFocus && !autoFocus && compassTargetGroup != null
                && manager.get(compassTargetGroup.id()) == compassTargetGroup) {
            int focusedIndex = compassTargetGroup.focusedVisibleIndex();
            if (focusedIndex >= 0 && focusedIndex < compassTargetGroup.size()
                    && compassTargetGroup.get(focusedIndex) == compassTarget) {
                manager.clearTempWaypointFocus();
                manager.fireTransientDataChanged();
            }
        }
        lastCompassAutoFocus = autoFocus;
    }

    public void defocus() {
        for (WaypointGroup group : manager.activeGroups()) {
            int index = group.focusedVisibleIndex();
            if (group.temp() && index >= 0 && index < group.size()) {
                CompassMarkerState.markDefocused(group.get(index));
            }
        }
        CompassMarkerState.markDefocused(compassTarget);
        manager.clearTempWaypointFocus();
        manager.fireTransientDataChanged();
    }

    private void finishCompassNavigation() {
        if (compassTargetGroup == null || compassTarget == null) return;
        for (int index = 0; index < compassTargetGroup.size(); index++) {
            if (compassTargetGroup.get(index) != compassTarget) continue;
            if (CompassMarkerState.arrived(compassTarget)) return;
            if (compassOwnsFocus()) manager.clearTempWaypointFocus();
            CompassMarkerState.markArrived(compassTarget);
            manager.fireTransientDataChanged();
            return;
        }
    }

    void clearCompassTarget() {
        compassShares.clear();
        compassShareReference = null;
        if (compassTargetGroup != null && manager.get(compassTargetGroup.id()) == compassTargetGroup) {
            for (int index = 0; index < compassTargetGroup.size(); index++) {
                if (compassTargetGroup.get(index) == compassTarget) {
                    compassTargetGroup.remove(index);
                    manager.fireTransientDataChanged();
                    break;
                }
            }
        }
        compassTargetGroup = null;
        compassTarget = null;
        compassTargetSighting = null;
        compassLocalTargetSighting = null;
        compassOriginRegion = null;
        compassCurrentRegion = null;
        compassCurrentRegionKnown = false;
    }

    public void remove(CrystalHollowsSightingSelector.Selection selection) {
        if (lobby != null && lobby.removeSighting(selection)) {
            projection.rebuild(lobby);
            persist();
            notifyDetectionListeners();
        }
    }

    public void clearSightings() {
        if (lobby == null) return;
        lobby.clearSightings();
        projection.rebuild(lobby);
        persist();
        notifyDetectionListeners();
    }

    public void rebuildProjection() {
        if (!active) return;
        if (compassTargetSighting != null
                && compassTargetSighting.confidence() == SightingConfidence.SHARED_REMOTE
                && (lobby == null || lobby.sightings().stream().noneMatch(sighting ->
                    sighting.confidence() == SightingConfidence.SHARED_REMOTE
                            && sighting.structure() == compassTargetSighting.structure()
                            && sighting.position().equals(compassTargetSighting.position())
                            && sighting.remoteEvidence() == compassTargetSighting.remoteEvidence()))) {
            if (compassLocalTargetSighting == null) clearCompassTarget();
            else updateCompassMarker(resolveCompassTarget(compassLocalTargetSighting));
        }
        projection.rebuild(lobby);
    }

    public void configurationChanged() {
        refreshCompassFocusSetting();
        if (!config.crystalHollowsEnabled()) {
            if (active) leaveLobby();
            return;
        }
        onZoneChanged(manager.currentZone());
        if (active) projection.rebuild(lobby);
    }

    public void flush() {
        if (lobby != null && isPersistable()) store.put(lobby);
        store.flush();
    }

    private void onZoneChanged(Zone zone) {
        boolean shouldActivate = zone != null
                && CrystalHollowsStructureFolder.ZONE_ID.equals(zone.id())
                && config.crystalHollowsEnabled();
        if (!shouldActivate) {
            if (active) leaveLobby();
            return;
        }
        if (active) return;
        active = true;
        delayTicks = TELEPORT_DELAY_TICKS;
        projection.ensureFolder();
        resolveIdentity(Minecraft.getInstance());
        if (lobby == null) createSessionLobby();
        projection.rebuild(lobby);
        notifyDetectionListeners();
    }

    private void onJoin() {
        leaveLobby();
        processedEntityIds.clear();
        delayTicks = TELEPORT_DELAY_TICKS;
        onZoneChanged(manager.currentZone());
    }

    private void leaveLobby() {
        incorrectStructureTicks.clear();
        verificationArea = null;
        if (lobby != null && isPersistable()) store.put(lobby);
        projection.endSession();
        active = false;
        lobby = null;
        serverId = null;
        sidebarStructure = null;
        areaSession = null;
        lastRoughPosition = null;
        processedEntityIds.clear();
        tabCrystalStates.clear();
        hasKingsScent = false;
        currentDay = -1;
        caveInDeadline = 0;
        if (compassController != null) compassController.reset();
        else clearCompassTarget();
        lastDebugSnapshot = DebugSnapshot.inactive();
        notifyDetectionListeners();
    }

    private void onTick(Minecraft client) {
        if (!config.crystalHollowsEnabled()) {
            if (active) leaveLobby();
            return;
        }
        if (!active) onZoneChanged(manager.currentZone());
        if (!active || client.level == null || client.player == null) return;
        observeCompassPosition(client.player.getX(), client.player.getY(), client.player.getZ());
        if (projection.ensureFolder()) projection.rebuild(lobby);
        refreshProjectionSettings();
        ticks++;
        if (delayTicks > 0) delayTicks--;
        batchDetections(() -> {
            if (ticks % 2 == 0) updateSidebar(client);
            if (ticks % 10 == 0) {
                sampleRoughArea(client);
                scanEntities(client);
            }
            if (ticks % 20 == 0) {
                updateTabList(client.getConnection());
                resolveIdentity(client);
            }
            if (delayTicks == 0) checkCompassArrival(sidebarStructure, client.player.getX(),
                    client.player.getY(), client.player.getZ());
            if (compassController != null) compassController.tick(System.currentTimeMillis());
        });
        lastDebugSnapshot = new DebugSnapshot(true, serverId, currentDay,
                lobby == null ? 0 : lobby.sightings().size(),
                sidebarStructure, delayTicks, processedEntityIds.size(), hasKingsScent,
                tabCrystalStates.size());
    }

    private void refreshProjectionSettings() {
        refreshCompassFocusSetting();
        boolean structureWaypoints = config.crystalHollowsStructureWaypoints();
        boolean hideStructuresFolder = config.crystalHollowsHideStructuresFolder();
        boolean showRough = config.crystalHollowsShowRoughMarkers();
        boolean nucleusWaypoints = config.crystalHollowsNucleusWaypoints();
        int detectionMask = 0;
        for (CrystalHollowsStructure structure : STRUCTURES) {
            if (config.crystalHollowsDetectionEnabled(structure.id())) {
                detectionMask |= 1 << structure.ordinal();
            }
        }
        if (structureWaypoints == lastStructureWaypoints
                && hideStructuresFolder == lastHideStructuresFolder
                && showRough == lastShowRough
                && nucleusWaypoints == lastNucleusWaypoints
                && detectionMask == lastDetectionMask) {
            return;
        }
        lastStructureWaypoints = structureWaypoints;
        lastHideStructuresFolder = hideStructuresFolder;
        lastShowRough = showRough;
        lastNucleusWaypoints = nucleusWaypoints;
        if (detectionMask != lastDetectionMask) {
            processedEntityIds.clear();
            lastRoughPosition = null;
            lastDetectionMask = detectionMask;
        }
        projection.rebuild(lobby);
    }

    private void updateSidebar(Minecraft client) {
        String area = CrystalHollowsSidebar.areaName(SidebarTexts.collectColorStripped(client));
        BlockPos position = client.player.blockPosition();
        int radius = CrystalHollowsDetectionPolicy.ARRIVAL_RADIUS;
        boolean loaded = client.level.hasChunk((position.getX() - radius) >> 4, (position.getZ() - radius) >> 4)
                && client.level.hasChunk((position.getX() - radius) >> 4, (position.getZ() + radius) >> 4)
                && client.level.hasChunk((position.getX() + radius) >> 4, (position.getZ() - radius) >> 4)
                && client.level.hasChunk((position.getX() + radius) >> 4, (position.getZ() + radius) >> 4);
        for (CrystalHollowsStructure structure : checkIncorrectStructures(area, client.player.getX(),
                client.player.getY(), client.player.getZ(), loaded && delayTicks == 0)) {
            send(Component.translatableWithFallback("waypointer.crystal.message.incorrect_skyblocker",
                    "Skyblocker websocket provided incorrect %s coordinates.",
                    Component.translatable("waypointer.crystal.structure." + structure.id()))
                    .withStyle(ChatFormatting.GRAY));
        }
        CrystalHollowsStructure next = CrystalHollowsSidebar.structureForArea(area);
        sidebarStructure = next;
        if (delayTicks > 0 || next == null || !CrystalHollowsGeometry.insideHollows(
                client.player.getX(), client.player.getY(), client.player.getZ())) {
            areaSession = null;
            lastRoughPosition = null;
            return;
        }
        if (areaSession == null || areaSession.structure() != next) {
            areaSession = new CrystalHollowsAreaSession(next,
                    client.player.getX(), client.player.getY(), client.player.getZ());
            lastRoughPosition = null;
        }
    }

    /** Called every two ticks; require three continuous seconds of contradictory arrival evidence. */
    List<CrystalHollowsStructure> checkIncorrectStructures(String area, double x, double y, double z, boolean ready) {
        if (!ready || !java.util.Objects.equals(verificationArea, area)) incorrectStructureTicks.clear();
        verificationArea = area;
        if (!ready || lobby == null) return List.of();
        incorrectStructureTicks.keySet().removeIf(sighting -> !lobby.sightings().contains(sighting)
                || !CrystalHollowsDetectionPolicy.contradictsSkyblocker(sighting, area, x, y, z));
        List<CrystalHollowsStructure> rejected = new ArrayList<>();
        for (StructureSighting sighting : List.copyOf(lobby.sightings())) {
            if (!CrystalHollowsDetectionPolicy.contradictsSkyblocker(sighting, area, x, y, z)) continue;
            if (incorrectStructureTicks.merge(sighting, 2, Integer::sum) < 60) continue;
            if (lobby.rejectSkyblocker(sighting)) {
                rejected.add(sighting.structure());
                if (compassTargetSighting != null
                        && SkyblockerStructureProtocol.SOURCE.equals(compassTargetSighting.source())
                        && compassTargetSighting.structure() == sighting.structure()
                        && compassTargetSighting.position().equals(sighting.position())) {
                    if (compassLocalTargetSighting == null) clearCompassTarget();
                    else updateCompassMarker(resolveCompassTarget(compassLocalTargetSighting));
                }
            }
            incorrectStructureTicks.remove(sighting);
        }
        if (!rejected.isEmpty()) {
            detectionsChanged = true;
            if (!batchingDetections) flushDetections();
        }
        return rejected;
    }

    private void sampleRoughArea(Minecraft client) {
        sampleRoughArea(client.player.getX(), client.player.getY(), client.player.getZ());
    }

    void sampleRoughArea(double x, double y, double z) {
        if (delayTicks > 0 || areaSession == null
                || !config.crystalHollowsDetectionEnabled(areaSession.structure().id())) return;
        CrystalHollowsPosition position = areaSession.sample(x, y, z);
        if (lastRoughPosition != null && lastRoughPosition.distanceSquared(position) < 4.0) return;
        lastRoughPosition = position;
        merge(new StructureSighting(areaSession.structure(), position.x(), position.y(), position.z(),
                SightingConfidence.ROUGH_AREA, "sidebar", System.currentTimeMillis()));
    }

    private void scanEntities(Minecraft client) {
        if (!CrystalHollowsDetectionPolicy.shouldScanEntities(
                config.crystalHollowsEntityDetection(), delayTicks)) return;
        for (CrystalHollowsEntityScanner.Detection detection :
                CrystalHollowsEntityScanner.scan(client, sidebarStructure, processedEntityIds)) {
            processedEntityIds.add(detection.entityId());
            if (!config.crystalHollowsDetectionEnabled(detection.structure().id())) continue;
            CrystalHollowsPosition position = detection.position();
            if (detection.divanKeeper() && lobby != null) lobby.setDivanCentre(position);
            merge(new StructureSighting(detection.structure(),
                    position.x(), position.y(), position.z(), SightingConfidence.ENTITY,
                    "entity:" + detection.sourceName(), System.currentTimeMillis()));
        }
    }

    private void updateTabList(ClientPacketListener connection) {
        if (connection == null || lobby == null) return;
        List<String> lines = connection.getListedOnlinePlayers().stream()
                .map(CrystalHollowsTracker::tabText)
                .toList();
        Map<Crystal, CrystalState> parsed = CrystalHollowsTabList.parseCrystalStates(lines);
        hasKingsScent = CrystalHollowsTabList.hasKingsScent(lines);
        if (!parsed.equals(tabCrystalStates)) {
            tabCrystalStates.clear();
            tabCrystalStates.putAll(parsed);
        }
    }

    Component addCoordinateShareActions(Component message, boolean overlay) {
        if (overlay || message == null || !active || lobby == null || !config.coordinateShareHelper()) return message;
        var chat = CrystalHollowsChatParser.playerChat(message.getString());
        if (chat.isEmpty() || isLocalPlayer(chat.get().sender())) return message;
        MutableComponent result = null;
        for (CrystalHollowsStructure structure : CrystalHollowsChatParser.requestedStructures(chat.get().body())) {
            List<StructureSighting> known = lobby.sightings().stream()
                    .filter(sighting -> sighting.structure() == structure).toList();
            if (known.isEmpty() && structure == CrystalHollowsStructure.CRYSTAL_NUCLEUS) {
                known = List.of(CrystalHollowsShare.resolve(List.of(), structure.id(), null));
            }
            for (int index = 0; index < known.size(); index++) {
                if (result == null) result = message.copy();
                Component name = Component.translatable("waypointer.crystal.structure." + structure.id())
                        .append(known.size() > 1 ? " " + (index + 1) : "");
                result.append(" ").append(Component.translatableWithFallback(
                        "waypointer.crystal.action.share_location", "[Share %s]", name)
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                                .withClickEvent(CrystalHollowsShare.share(known.get(index)))));
            }
        }
        return result == null ? message : result;
    }

    private void onGameMessage(Component message, boolean overlay) {
        if (overlay || message == null || !active || lobby == null) return;
        if (WaypointerChatFeedback.consumeIfSuppressed(message)) return;
        String text = message.getString();
        observeCaveInWarning(text, System.currentTimeMillis());
        Optional<PlayerChat> playerChat = CrystalHollowsChatParser.playerChat(text);
        if (CrystalHollowsChatParser.isDelayTrigger(text)) delayTicks = TELEPORT_DELAY_TICKS;
        CrystalHollowsChatParser.parseCompassServerMessage(text).ifPresent(compassMessage -> {
            if (compassController != null) compassController.onServerMessage(compassMessage);
        });

        Optional<CrystalUpdate> crystalUpdate = CrystalHollowsChatParser.parseCrystalState(text);
        if (crystalUpdate.isPresent()) {
            CrystalUpdate update = crystalUpdate.orElseThrow();
            if (update.resetAll()) lobby.resetCrystals();
            else lobby.setCrystal(update.crystal(), update.state());
            persist();
        }

        Optional<NpcDialogue> dialogue = CrystalHollowsChatParser.parseNpcDialogue(text);
        if (dialogue.isPresent()) mergeDialogue(dialogue.orElseThrow());

        if (config.crystalHollowsChatDetection()) {
            if (playerChat.isPresent() && !isLocalPlayer(playerChat.orElseThrow().sender())) {
                PlayerChat chat = playerChat.orElseThrow();
                for (SharedCoordinate coordinate :
                        CrystalHollowsChatParser.parseSharedCoordinates(chat.body())) {
                    if (coordinate.structure() == null) continue;
                    merge(new StructureSighting(coordinate.structure(), coordinate.x(), coordinate.y(),
                            coordinate.z(), SightingConfidence.SHARED_CHAT,
                            "chat:" + chat.sender(), System.currentTimeMillis()));
                }
            }
        }
    }

    private static boolean isLocalPlayer(String sender) {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.player != null
                && client.player.getGameProfile().name().equalsIgnoreCase(sender);
    }

    private void mergeDialogue(NpcDialogue dialogue) {
        if (!config.crystalHollowsDetectionEnabled(dialogue.structure().id())) return;
        Minecraft client = Minecraft.getInstance();
        Optional<CrystalHollowsPosition> entity = CrystalHollowsEntityScanner.nearestDialogueAnchor(
                client, dialogue.structure());
        CrystalHollowsPosition position;
        SightingConfidence confidence;
        String source;
        if (entity.isPresent()) {
            position = entity.orElseThrow();
            confidence = SightingConfidence.ENTITY;
            source = "entity:npc-dialogue";
        } else if (client.player != null) {
            position = new CrystalHollowsPosition(
                    (int) Math.floor(client.player.getX()),
                    (int) Math.floor(client.player.getY()),
                    (int) Math.floor(client.player.getZ()));
            confidence = SightingConfidence.NPC_CHAT;
            source = "npc-chat";
        } else {
            return;
        }
        merge(new StructureSighting(dialogue.structure(), position.x(), position.y(), position.z(),
                confidence, source, System.currentTimeMillis()));
    }

    private void resolveIdentity(Minecraft client) {
        resolveIdentity(CrystalHollowsLobbyIdentity.currentServerId(client),
                CrystalHollowsLobbyIdentity.currentDay(client.level));
    }

    void resolveIdentity(String resolved, int day) {
        currentDay = day;
        if (resolved == null || resolved.isBlank()) {
            if (lobby == null) createSessionLobby();
            return;
        }
        if (resolved.equals(serverId) && lobby != null) {
            lobby.touch(System.currentTimeMillis(), day);
            return;
        }
        CrystalHollowsLobbyTransition.Kind transition =
                CrystalHollowsLobbyTransition.classify(serverId, resolved);
        CrystalHollowsLobbyState session = lobby != null
                        && SESSION_SERVER_ID.equals(lobby.serverId())
                ? lobby
                : null;
        if (lobby != null && isPersistable()) store.put(lobby);
        if (transition == CrystalHollowsLobbyTransition.Kind.DIFFERENT_LOBBY) {
            projection.endSession();
            resetLobbyTransients();
        } else {
            projection.clear();
        }
        serverId = resolved;
        Optional<CrystalHollowsLobbyState> restored = store.restore(resolved, day);
        long now = System.currentTimeMillis();
        lobby = CrystalHollowsLobbyState.identify(
                resolved, day, now, restored.orElse(null), session);
        store.put(lobby);
        projection.ensureFolder();
        projection.rebuild(lobby);
        notifyDetectionListeners();
        int restoredSightings = restored.map(state -> state.sightings().size()).orElse(0);
        if (restoredSightings > 0) {
            Waypointer.LOGGER.info("Restored {} Crystal Hollows location(s) for lobby {}",
                    restoredSightings, resolved);
            send(Component.translatable("waypointer.crystal.message.restored",
                    restoredSightings, resolved).withStyle(ChatFormatting.GRAY));
        }
    }

    private void resetLobbyTransients() {
        incorrectStructureTicks.clear();
        verificationArea = null;
        caveInDeadline = 0;
        processedEntityIds.clear();
        tabCrystalStates.clear();
        hasKingsScent = false;
        sidebarStructure = null;
        areaSession = null;
        lastRoughPosition = null;
        delayTicks = TELEPORT_DELAY_TICKS;
        if (compassController != null) compassController.reset();
    }

    private void createSessionLobby() {
        serverId = null;
        lobby = new CrystalHollowsLobbyState(
                SESSION_SERVER_ID, System.currentTimeMillis(), -1);
    }

    private void announceDetection(StructureSighting sighting) {
        MutableComponent message = Component.translatable("waypointer.crystal.message.detected",
                Component.translatable("waypointer.crystal.structure." + sighting.structure().id())
                        .withStyle(Style.EMPTY.withColor(sighting.structure().rgb())),
                coordinate(sighting.x()), coordinate(sighting.y()), coordinate(sighting.z()))
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" "))
                .append(Component.translatable("waypointer.crystal.action.share")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(CrystalHollowsShare.share(sighting))))
                .append(CrystalHollowsShare.defocus());
        send(message);
    }

    private static Component coordinate(int value) {
        return Component.literal(Integer.toString(value)).withStyle(ChatFormatting.WHITE);
    }

    private void persist() {
        if (lobby != null && isPersistable()) store.put(lobby);
    }

    private boolean isPersistable() {
        return serverId != null && lobby != null && serverId.equals(lobby.serverId());
    }

    private static String tabText(PlayerInfo info) {
        Component display = info.getTabListDisplayName();
        return display == null ? info.getProfile().name() : display.getString();
    }

    private static void send(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(WaypointerChatFeedback.suppress(Component.literal("[WP] ")
                    .withStyle(ChatFormatting.AQUA).append(message)));
        }
    }

    public record DebugSnapshot(
            boolean active,
            String serverId,
            int day,
            int sightings,
            CrystalHollowsStructure sidebarStructure,
            int delayTicks,
            int processedEntities,
            boolean kingsScent,
            int tabCrystalStates) {
        static DebugSnapshot inactive() {
            return new DebugSnapshot(false, null, -1, 0, null, 0, 0, false, 0);
        }
    }
}
