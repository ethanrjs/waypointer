package com.babbur.waypointer.location;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.crystal.CrystalHollowsSidebar;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;
import net.minecraft.client.Minecraft;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class HypixelApiZoneSource implements ZoneSource {

    private static final int REFINE_POLL_TICKS = 2;
    private static volatile DebugSnapshot lastDebugSnapshot;
    private static volatile String lastServerName;

    private Zone lastEmitted;
    private Zone lastRawPacketZone;
    private boolean receivedLocationPacket;
    private boolean skyblockPacket;
    private boolean retainingSidebarZone;
    private String packetServerType;
    private String packetMap;
    private String packetMode;
    private Object worldIdentity;
    private boolean adoptNextWorld;
    private Instant packetReceivedAt;
    private Zone pendingPlayerListZone;
    private int playerListZonePolls;
    private Consumer<Zone> listener;
    private int tickCounter;

    public HypixelApiZoneSource() {}

    HypixelApiZoneSource(Consumer<Zone> listener) {
        this.listener = listener;
    }

    @Override
    public void register(Consumer<Zone> listener) {
        this.listener = listener;
        HypixelModAPI api = HypixelModAPI.getInstance();
        api.subscribeToEventPacket(ClientboundLocationPacket.class);
        api.createHandler(ClientboundLocationPacket.class, this::handleLocationPacket);

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetLocation());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetLocation());
    }

    private void handleLocationPacket(ClientboundLocationPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            worldIdentity = null;
            adoptNextWorld = true;
        } else {
            updateWorld(mc.level);
        }
        String serverType = packet.getServerType().map(s -> s.name()).orElse(null);
        String map  = packet.getMap().orElse(null);
        String mode = packet.getMode().orElse(null);
        acceptLocation(serverType, map, mode, packet.getServerName(), SidebarTexts.collectColorStripped(mc));
    }

    private void onTick(Minecraft mc) {
        if (mc.level == null || mc.getCurrentServer() == null) {
            if (!adoptNextWorld || mc.getCurrentServer() == null) resetLocation();
            return;
        }
        updateWorld(mc.level);
        if (++tickCounter < REFINE_POLL_TICKS) return;
        tickCounter = 0;
        if (receivedLocationPacket && !usesSidebarFallback()
                && !shouldPollSidebarRefinement(lastRawPacketZone)) return;
        acceptSidebar(SidebarTexts.collectColorStripped(mc), () -> TabListTexts.areaName(mc));
    }

    void markPacketAwaitingLevel() {
        adoptNextWorld = true;
    }

    void updateWorld(Object world) {
        if (worldIdentity == world) return;
        if (adoptNextWorld && worldIdentity == null && world != null) {
            adoptNextWorld = false;
            worldIdentity = world;
            return;
        }
        resetLocation();
        worldIdentity = world;
    }

    void acceptLocation(String serverType, String map, String mode, String serverName, String sidebar) {
        lastServerName = serverName;
        packetReceivedAt = Instant.now();
        packetServerType = serverType;
        packetMap = map;
        packetMode = mode;
        receivedLocationPacket = true;
        skyblockPacket = "SKYBLOCK".equalsIgnoreCase(serverType);
        retainingSidebarZone = false;
        lastRawPacketZone = Zone.resolve(serverType, map, mode);
        emitRefined(serverType, map, mode, sidebar, null);
    }

    void acceptSidebar(String sidebar) {
        acceptSidebar(sidebar, () -> null);
    }

    void acceptSidebar(String sidebar, Supplier<String> playerListArea) {
        String sidebarText = sidebar == null ? "" : sidebar;
        boolean hasPacketRefinement = usesSidebarFallback()
                ? Zone.tryResolveMineshaftTypeFromSidebarBlob(sidebarText) != null
                : hasSidebarRefinement(lastRawPacketZone, sidebarText);
        boolean missingAreaLine =
                CrystalHollowsSidebar.areaName(sidebarText) == null;
        if ((lastRawPacketZone != null || lastEmitted != null) && missingAreaLine && !hasPacketRefinement) {
            retainingSidebarZone = true;
            return;
        }
        retainingSidebarZone = false;
        Zone playerListZone = null;
        if (missingAreaLine && !hasPacketRefinement && usesSidebarFallback()) {
            playerListZone = confirmedPlayerListZone(playerListArea.get());
        } else {
            clearPlayerListZone();
        }
        emitRefined(null, null, null, sidebar, playerListZone);
    }

    private Zone confirmedPlayerListZone(String area) {
        Zone zone = ScoreboardZoneResolver.resolveKnownAreaName(area);
        if (zone == null) {
            clearPlayerListZone();
            return null;
        }
        if (zone.equals(pendingPlayerListZone)) {
            playerListZonePolls++;
        } else {
            pendingPlayerListZone = zone;
            playerListZonePolls = 1;
        }
        return playerListZonePolls >= ScoreboardZoneResolver.PLAYER_LIST_CONFIRM_POLLS ? zone : null;
    }

    private void clearPlayerListZone() {
        pendingPlayerListZone = null;
        playerListZonePolls = 0;
    }

    private static boolean hasSidebarRefinement(Zone packetZone, String sidebar) {
        if (packetZone == null) return false;
        if (CatacombsFloorRefiner.shouldPoll(packetZone)) {
            return CatacombsFloorRefiner.tryResolveFromSidebarBlob(sidebar) != null;
        }
        if (!"dwarven_mines".equals(packetZone.id())
                && !"mineshaft_unknown".equals(packetZone.id())) return false;
        return Zone.tryResolveMineshaftTypeFromSidebarBlob(sidebar) != null
                || Zone.tryResolveDwarvenSubAreaFromSidebarBlob(sidebar) != null;
    }

    void resetLocation() {
        lastRawPacketZone = null;
        receivedLocationPacket = false;
        skyblockPacket = false;
        retainingSidebarZone = false;
        worldIdentity = null;
        adoptNextWorld = false;
        packetReceivedAt = null;
        clearPlayerListZone();
        packetServerType = packetMap = packetMode = null;
        lastServerName = null;
        tickCounter = 0;
        lastDebugSnapshot = null;
        if (lastEmitted == null) return;
        lastEmitted = null;
        if (listener != null) listener.accept(null);
    }

    private static boolean shouldPollSidebarRefinement(Zone zone) {
        if (zone == null || zone.id() == null) return false;
        String id = zone.id();
        if ("dwarven_mines".equals(id) || "mineshaft_unknown".equals(id)) return true;
        return CatacombsFloorRefiner.shouldPoll(zone);
    }

    private void emitRefined(String serverType, String map, String mode, String sidebar,
                             Zone fallback) {
        String sidebarText = sidebar != null ? sidebar : "";
        Zone refined = !usesSidebarFallback()
                ? Zone.refineIfDwarvenMinesContext(lastRawPacketZone, sidebarText)
                : ScoreboardZoneResolver.resolveSidebarText(sidebarText);
        if (refined == null) refined = fallback;
        refined = CatacombsFloorRefiner.refine(refined, sidebarText);
        if (serverType == null) refined = ConfirmedLayoutRetention.retain(lastEmitted, refined, sidebarText);
        lastDebugSnapshot = new DebugSnapshot(
                packetServerType, packetMap, packetMode, lastServerName, lastRawPacketZone, refined,
                Instant.now(), packetReceivedAt);

        if (!Objects.equals(refined, lastEmitted)) {
            lastEmitted = refined;
            if (serverType != null) {
                Waypointer.LOGGER.info("Location event: {} / map={} mode={} -> zone={} (raw={})",
                        serverType, map, mode, refined, lastRawPacketZone);
            } else {
                Waypointer.LOGGER.debug("Location refine (tick): raw={} -> zone={}", lastRawPacketZone, refined);
            }
            if (listener != null) listener.accept(refined);
        }
    }

    public static DebugSnapshot debugSnapshot() {
        return lastDebugSnapshot;
    }

    public static String lastServerName() {
        return lastServerName;
    }

    public String detectionState() {
        return "apiPacket=" + receivedLocationPacket + ", skyblockPacket=" + skyblockPacket
                + ", sidebarFallback=" + usesSidebarFallback()
                + ", retainingZoneDuringMissingSidebar=" + retainingSidebarZone;
    }

    private boolean usesSidebarFallback() {
        return !receivedLocationPacket || skyblockPacket
                && (lastRawPacketZone == null || Zone.UNKNOWN.equals(lastRawPacketZone));
    }

    public record DebugSnapshot(
            String serverType,
            String map,
            String mode,
            String serverName,
            Zone rawZone,
            Zone refinedZone,
            Instant capturedAt,
            Instant packetReceivedAt) {}
}
