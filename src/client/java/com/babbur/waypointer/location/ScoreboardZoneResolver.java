package com.babbur.waypointer.location;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.crystal.CrystalHollowsSidebar;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Detects SkyBlock zones from the sidebar every two ticks. */
public final class ScoreboardZoneResolver implements ZoneSource {

    private static final int POLL_INTERVAL_TICKS = 2;
    /** Sidebar polls (two ticks each) an unchanged player-list area must survive before it loads routes. */
    static final int PLAYER_LIST_CONFIRM_POLLS = 10;

    private Consumer<Zone> listener;
    private Zone lastEmitted;
    private int tickCounter = 0;
    private Object worldIdentity;
    private boolean retainingZone;
    private Zone pendingPlayerListZone;
    private int playerListZonePolls;

    public ScoreboardZoneResolver() {}

    ScoreboardZoneResolver(Consumer<Zone> listener) {
        this.listener = listener;
    }

    @Override
    public void register(Consumer<Zone> listener) {
        this.listener = listener;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> updateWorld(null));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> updateWorld(null));
    }

    private void onTick(Minecraft mc) {
        updateWorld(mc.level);
        if (mc.level == null || mc.player == null) return;
        if (++tickCounter < POLL_INTERVAL_TICKS) return;
        tickCounter = 0;
        acceptSidebar(SidebarTexts.collectColorStripped(mc), () -> TabListTexts.areaName(mc));
    }

    void acceptSidebar(String sidebar) {
        acceptSidebar(sidebar, () -> null);
    }

    void acceptSidebar(String sidebar, Supplier<String> playerListArea) {
        Zone z = resolveSidebarText(sidebar);
        // A delayed/partial scoreboard is not a zone transition. Keep same-world evidence.
        retainingZone = lastEmitted != null
                && z == null
                && CrystalHollowsSidebar.areaName(sidebar == null ? "" : sidebar) == null;
        if (retainingZone) return;
        // A lagging server can send the player list before the sidebar; use it until the sidebar arrives.
        if (z == null && lastEmitted == null) z = confirmedPlayerListZone(playerListArea.get());
        else clearPlayerListZone();
        z = ConfirmedLayoutRetention.retain(lastEmitted, z, sidebar == null ? "" : sidebar);
        if (!Objects.equals(z, lastEmitted)) {
            lastEmitted = z;
            Waypointer.LOGGER.debug("Scoreboard zone resolved to {}", z);
            if (listener != null) listener.accept(z);
        }
    }

    /** The player list can outlive a world change, so its area must repeat before it counts. */
    private Zone confirmedPlayerListZone(String area) {
        Zone zone = resolveKnownAreaName(area);
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
        return playerListZonePolls >= PLAYER_LIST_CONFIRM_POLLS ? zone : null;
    }

    private void clearPlayerListZone() {
        pendingPlayerListZone = null;
        playerListZonePolls = 0;
    }

    void updateWorld(Object world) {
        if (worldIdentity == world) return;
        worldIdentity = world;
        tickCounter = 0;
        retainingZone = false;
        clearPlayerListZone();
        if (lastEmitted != null) {
            lastEmitted = null;
            if (listener != null) listener.accept(null);
        }
    }

    public String detectionState() {
        return "sidebarFallback=true, retainingZoneDuringMissingSidebar=" + retainingZone;
    }

    static Zone resolveSidebarText(String blob) {
        if (blob == null || blob.isBlank()) return null;
        // A layout code identifies the shaft even while its decorative area line is missing.
        Zone mineshaftType = Zone.tryResolveMineshaftTypeFromSidebarBlob(blob);
        if (mineshaftType != null) return mineshaftType;
        String area = CrystalHollowsSidebar.areaName(blob);
        if (area == null || area.isBlank()) return null;
        area = area.replaceFirst(",\\s*$", "");

        Zone mineshaftArea = Zone.tryResolveDwarvenSubAreaFromSidebarBlob(blob);
        if (mineshaftArea != null) return mineshaftArea;
        Zone catacombsFloor = CatacombsFloorRefiner.tryResolveFromSidebarBlob(blob);
        if (catacombsFloor != null) return catacombsFloor;
        return resolveAreaName(area);
    }

    /** Like {@link #resolveAreaName} but never synthesizes a zone the mod does not know. */
    static Zone resolveKnownAreaName(String area) {
        Zone zone = resolveAreaName(area);
        return zone != null && Zone.knownZones().contains(zone) ? zone : null;
    }

    /** Resolves a bare area name, such as the sidebar's area line or the player list's Area widget. */
    static Zone resolveAreaName(String area) {
        if (area == null || area.isBlank()) return null;
        String cleaned = area.trim().replaceFirst(",\\s*$", "");
        if (cleaned.isBlank()) return null;
        if (CrystalHollowsSidebar.zoneForArea(cleaned) != null
                || CrystalHollowsSidebar.structureForArea(cleaned) != null) {
            return Zone.fromId("crystal_hollows");
        }
        return Zone.resolveFromDisplayName(cleaned);
    }
}
