package com.babbur.waypointer.crystal;

import com.babbur.waypointer.chat.WaypointerChatFeedback;
import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.core.ActiveGroupManager;
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

/**
 * Lobby-scoped Crystal Hollows detection orchestrator.
 *
 * <p>The fair-play boundary is deliberate: this tracker uses only information the game already
 * shows the player (sidebar, nearby visible entities, received chat, tab widgets, and personal
 * Wishing Compass particles). It never scans chunks or blocks for hidden structures.
 */
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
    private CrystalHollowsStructure sidebarStructure;
    private CrystalHollowsAreaSession areaSession;
    private CrystalHollowsPosition lastRoughPosition;
    private boolean lastStructureWaypoints;
    private boolean lastShowRough;
    private boolean lastNucleusWaypoints;
    private DebugSnapshot lastDebugSnapshot = DebugSnapshot.inactive();
    private WishingCompassController compassController;

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

    public void attachCompassController(WishingCompassController controller) {
        compassController = controller;
    }

    public CrystalHollowsLobbyState.MergeResult merge(StructureSighting sighting) {
        if (!active || lobby == null) return CrystalHollowsLobbyState.MergeResult.IGNORED;
        CrystalHollowsLobbyState.MergeResult result = lobby.merge(sighting);
        if (result != CrystalHollowsLobbyState.MergeResult.IGNORED) {
            projection.rebuild(lobby);
            persist();
            if (config.crystalHollowsAnnounceDetections()
                    && (result == CrystalHollowsLobbyState.MergeResult.ADDED
                            || result == CrystalHollowsLobbyState.MergeResult.UPGRADED)) {
                announceDetection(sighting);
            }
        }
        return result;
    }

    public void remove(CrystalHollowsStructure structure) {
        if (lobby != null && lobby.removeStructure(structure)) {
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
        projection.clear();
        active = false;
        lobby = null;
        serverId = null;
        sidebarStructure = null;
        areaSession = null;
        lastRoughPosition = null;
        processedEntityIds.clear();
        tabCrystalStates.clear();
        hasKingsScent = false;
        if (compassController != null) compassController.reset();
        lastDebugSnapshot = DebugSnapshot.inactive();
    }

    private void onTick(Minecraft client) {
        if (!config.crystalHollowsEnabled()) {
            if (active) leaveLobby();
            return;
        }
        if (!active) onZoneChanged(manager.currentZone());
        if (!active || client.level == null || client.player == null) return;
        projection.ensureFolder();
        refreshProjectionSettings();
        ticks++;
        if (delayTicks > 0) delayTicks--;
        if (ticks % 2 == 0) updateSidebar(client);
        if (ticks % 10 == 0) {
            sampleRoughArea(client);
            scanEntities(client);
        }
        if (ticks % 20 == 0) updateTabList(client.getConnection());
        if (ticks % 100 == 0) resolveIdentity(client);
        if (compassController != null) compassController.tick(System.currentTimeMillis());
        lastDebugSnapshot = new DebugSnapshot(true, serverId, lobby == null ? 0 : lobby.sightings().size(),
                sidebarStructure, delayTicks, processedEntityIds.size(), hasKingsScent,
                tabCrystalStates.size());
    }

    private void refreshProjectionSettings() {
        boolean structureWaypoints = config.crystalHollowsStructureWaypoints();
        boolean showRough = config.crystalHollowsShowRoughMarkers();
        boolean nucleusWaypoints = config.crystalHollowsNucleusWaypoints();
        if (structureWaypoints == lastStructureWaypoints
                && showRough == lastShowRough
                && nucleusWaypoints == lastNucleusWaypoints) {
            return;
        }
        lastStructureWaypoints = structureWaypoints;
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
        if (!config.crystalHollowsEntityDetection()) return;
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
        if (!parsed.isEmpty() && !parsed.equals(tabCrystalStates)) {
            tabCrystalStates.clear();
            tabCrystalStates.putAll(parsed);
            lobby.replaceCrystals(parsed);
            persist();
        }
    }

    private void onGameMessage(Component message, boolean overlay) {
        if (overlay || message == null || !active || lobby == null) return;
        if (WaypointerChatFeedback.consumeIfSuppressed(message)) return;
        String text = message.getString();
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
            Optional<PlayerChat> playerChat = CrystalHollowsChatParser.playerChat(text);
            if (playerChat.isPresent()) {
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
        if (resolved == null || resolved.isBlank()) {
            if (lobby == null) createSessionLobby();
            return;
        }
        if (resolved.equals(serverId) && lobby != null) {
            lobby.touch(System.currentTimeMillis(), day);
            return;
        }
        if (lobby != null && isPersistable()) store.put(lobby);
        projection.clear();
        serverId = resolved;
        Optional<CrystalHollowsLobbyState> restored = store.restore(resolved, day);
        lobby = restored.orElseGet(() -> store.getOrCreate(resolved, day));
        projection.ensureFolder();
        projection.rebuild(lobby);
        if (restored.isPresent() && !lobby.sightings().isEmpty()) {
            send(Component.translatable("waypointer.crystal.message.restored",
                    lobby.sightings().size(), resolved).withStyle(ChatFormatting.AQUA));
        }
    }

    private void createSessionLobby() {
        serverId = null;
        lobby = new CrystalHollowsLobbyState(
                SESSION_SERVER_ID, System.currentTimeMillis(), -1);
    }

    private void announceDetection(StructureSighting sighting) {
        MutableComponent message = Component.translatable("waypointer.crystal.message.detected",
                sighting.structure().displayName(), sighting.x(), sighting.y(), sighting.z())
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" "))
                .append(Component.translatable("waypointer.crystal.action.share")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand(
                                        "/wpch share " + sighting.structure().id()))));
        send(message);
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
            client.player.sendSystemMessage(WaypointerChatFeedback.suppress(message));
        }
    }

    public record DebugSnapshot(
            boolean active,
            String serverId,
            int sightings,
            CrystalHollowsStructure sidebarStructure,
            int delayTicks,
            int processedEntities,
            boolean kingsScent,
            int tabCrystalStates) {
        static DebugSnapshot inactive() {
            return new DebugSnapshot(false, null, 0, null, 0, 0, false, 0);
        }
    }
}
