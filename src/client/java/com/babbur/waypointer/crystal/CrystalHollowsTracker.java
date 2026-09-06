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
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Tracks lobby structures from visible game signals; never scans blocks or chunks. */
public final class CrystalHollowsTracker {

    private static final String SESSION_SERVER_ID = "session-only";
    private static final int TELEPORT_DELAY_TICKS = 50;

    private final ActiveGroupManager manager;
    private final WaypointerConfig config;
    private final CrystalHollowsStore store;
    private final CrystalHollowsProjection projection;
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
    private DebugSnapshot lastDebugSnapshot = DebugSnapshot.inactive();
    private WishingCompassController compassController;
    private WaypointGroup compassTargetGroup;
    private Waypoint compassTarget;
    private StructureSighting compassTargetSighting;
    private StructureSighting compassLocalTargetSighting;
    private final Map<String, StructureSighting> compassShares = new java.util.HashMap<>();
    private long nextCompassShareId;
    private String compassShareReference;
    private boolean batchingDetections;
    private boolean detectionsChanged;

    public CrystalHollowsTracker(
            ActiveGroupManager manager, WaypointerConfig config, CrystalHollowsStore store) {
        this.manager = manager;
        this.config = config;
        this.store = store;
        this.projection = new CrystalHollowsProjection(manager, config);
    }

    public void install() {
        manager.addZoneListener(this::onZoneChanged);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> leaveLobby());
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

    public Map<Crystal, CrystalState> crystalStatesForCompass() {
        return CrystalHollowsTabList.preferredStates(
                tabCrystalStates, lobby == null ? Map.of() : lobby.crystals());
    }

    public void attachCompassController(WishingCompassController controller) {
        compassController = controller;
    }

    public CrystalHollowsLobbyState.MergeResult merge(StructureSighting sighting) {
        return merge(sighting, true);
    }

    public CrystalHollowsLobbyState.MergeResult merge(StructureSighting sighting, boolean announce) {
        if (!active || lobby == null) {
            Waypointer.LOGGER.debug("Crystal Hollows merge ignored while inactive: {}", sighting);
            return CrystalHollowsLobbyState.MergeResult.IGNORED;
        }
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

    private void flushDetections() {
        if (!detectionsChanged) return;
        detectionsChanged = false;
        projection.rebuild(lobby);
        persist();
    }

    void focusCompassTarget(StructureSighting sighting) {
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
        compassShareReference = "compass:" + (++nextCompassShareId);
        compassShares.put(compassShareReference, sighting);
        compassTargetGroup.setEnabled(true);
        manager.focusTempWaypoint(compassTargetGroup, index);
        manager.fireTransientDataChanged();
    }

    public StructureSighting compassTargetSighting() { return compassTargetSighting; }
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
            boolean arrived = CompassMarkerState.arrived(compassTarget);
            compassTargetGroup.set(index, compassTarget.withPos(sighting.x(), sighting.y(), sighting.z())
                    .withName(Component.translatable("waypointer.crystal.structure."
                            + sighting.structure().id()).getString())
                    .withColor(sighting.structure().rgb()));
            compassTarget = compassTargetGroup.get(index);
            if (arrived) CompassMarkerState.markArrived(compassTarget);
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

    private void finishCompassNavigation() {
        if (compassTargetGroup == null || compassTarget == null) return;
        for (int index = 0; index < compassTargetGroup.size(); index++) {
            if (compassTargetGroup.get(index) != compassTarget) continue;
            if (CompassMarkerState.arrived(compassTarget)) return;
            if (compassTargetGroup.focusedVisibleIndex() == index) manager.clearTempWaypointFocus();
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
    }

    public void remove(CrystalHollowsSightingSelector.Selection selection) {
        if (lobby != null && lobby.removeSighting(selection)) {
            projection.rebuild(lobby);
            persist();
        }
    }

    public void clearSightings() {
        if (lobby == null) return;
        lobby.clearSightings();
        projection.rebuild(lobby);
        persist();
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
    }

    private void onJoin() {
        leaveLobby();
        processedEntityIds.clear();
        delayTicks = TELEPORT_DELAY_TICKS;
        onZoneChanged(manager.currentZone());
    }

    private void leaveLobby() {
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
        if (compassController != null) compassController.reset();
        else clearCompassTarget();
        lastDebugSnapshot = DebugSnapshot.inactive();
    }

    private void onTick(Minecraft client) {
        if (!config.crystalHollowsEnabled()) {
            if (active) leaveLobby();
            return;
        }
        if (!active) onZoneChanged(manager.currentZone());
        if (!active || client.level == null || client.player == null) return;
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
        boolean structureWaypoints = config.crystalHollowsStructureWaypoints();
        boolean hideStructuresFolder = config.crystalHollowsHideStructuresFolder();
        boolean showRough = config.crystalHollowsShowRoughMarkers();
        boolean nucleusWaypoints = config.crystalHollowsNucleusWaypoints();
        if (structureWaypoints == lastStructureWaypoints
                && hideStructuresFolder == lastHideStructuresFolder
                && showRough == lastShowRough
                && nucleusWaypoints == lastNucleusWaypoints) {
            return;
        }
        lastStructureWaypoints = structureWaypoints;
        lastHideStructuresFolder = hideStructuresFolder;
        lastShowRough = showRough;
        lastNucleusWaypoints = nucleusWaypoints;
        projection.rebuild(lobby);
    }

    private void updateSidebar(Minecraft client) {
        String area = CrystalHollowsSidebar.areaName(SidebarTexts.collectColorStripped(client));
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

    private void sampleRoughArea(Minecraft client) {
        if (delayTicks > 0 || areaSession == null) return;
        CrystalHollowsPosition position = areaSession.sample(
                client.player.getX(), client.player.getY(), client.player.getZ());
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

    private void onGameMessage(Component message, boolean overlay) {
        if (overlay || message == null || !active || lobby == null) return;
        if (WaypointerChatFeedback.consumeIfSuppressed(message)) return;
        String text = message.getString();
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
        return client.player != null
                && client.player.getGameProfile().name().equalsIgnoreCase(sender);
    }

    private void mergeDialogue(NpcDialogue dialogue) {
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
        String resolved = CrystalHollowsLobbyIdentity.currentServerId(client);
        int day = CrystalHollowsLobbyIdentity.currentDay(client.level);
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
        int restoredSightings = restored.map(state -> state.sightings().size()).orElse(0);
        if (restoredSightings > 0) {
            Waypointer.LOGGER.info("Restored {} Crystal Hollows location(s) for lobby {}",
                    restoredSightings, resolved);
            send(Component.translatable("waypointer.crystal.message.restored",
                    restoredSightings, resolved).withStyle(ChatFormatting.GRAY));
        }
    }

    private void resetLobbyTransients() {
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
        String reference = CrystalHollowsSightingSelector.referenceFor(lobby.sightings(), sighting);
        if (reference == null) reference = sighting.structure().id();
        MutableComponent message = Component.translatable("waypointer.crystal.message.detected",
                Component.translatable("waypointer.crystal.structure." + sighting.structure().id())
                        .withStyle(Style.EMPTY.withColor(sighting.structure().rgb())),
                coordinate(sighting.x()), coordinate(sighting.y()), coordinate(sighting.z()))
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" "))
                .append(Component.translatable("waypointer.crystal.action.share")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand(
                                        "/wpch share " + reference))));
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
                    .withStyle(ChatFormatting.GREEN).append(message)));
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
