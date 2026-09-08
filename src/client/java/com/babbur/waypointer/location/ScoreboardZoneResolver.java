package com.babbur.waypointer.location;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.crystal.CrystalHollowsSidebar;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

import java.util.Objects;
import java.util.function.Consumer;

/** Detects SkyBlock zones from the sidebar every two ticks. */
public final class ScoreboardZoneResolver implements ZoneSource {

    private static final int POLL_INTERVAL_TICKS = 2;

    private Consumer<Zone> listener;
    private Zone lastEmitted;
    private int tickCounter = 0;
    private Object worldIdentity;
    private boolean retainingZone;

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
        acceptSidebar(SidebarTexts.collectColorStripped(mc));
    }

    void acceptSidebar(String sidebar) {
        // A delayed/partial scoreboard is not a zone transition. Keep same-world evidence.
        retainingZone = lastEmitted != null
                && CrystalHollowsSidebar.areaName(sidebar == null ? "" : sidebar) == null;
        if (retainingZone) return;
        Zone z = resolveSidebarText(sidebar);
        if (!Objects.equals(z, lastEmitted)) {
            lastEmitted = z;
            Waypointer.LOGGER.debug("Scoreboard zone resolved to {}", z);
            if (listener != null) listener.accept(z);
        }
    }

    void updateWorld(Object world) {
        if (worldIdentity == world) return;
        worldIdentity = world;
        tickCounter = 0;
        retainingZone = false;
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
        String area = CrystalHollowsSidebar.areaName(blob);
        if (area == null || area.isBlank()) return null;
        area = area.replaceFirst(",\\s*$", "");

        Zone mineshaftType = Zone.tryResolveMineshaftTypeFromSidebarBlob(blob);
        if (mineshaftType != null) return mineshaftType;
        Zone mineshaftArea = Zone.tryResolveDwarvenSubAreaFromSidebarBlob(blob);
        if (mineshaftArea != null) return mineshaftArea;
        Zone catacombsFloor = CatacombsFloorRefiner.tryResolveFromSidebarBlob(blob);
        if (catacombsFloor != null) return catacombsFloor;

        if (CrystalHollowsSidebar.zoneForArea(area) != null
                || CrystalHollowsSidebar.structureForArea(area) != null) {
            return Zone.fromId("crystal_hollows");
        }
        return Zone.resolveFromDisplayName(area);
    }
}
