package com.babbur.waypointer.location;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.crystal.CrystalHollowsSidebar;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.Objects;
import java.util.function.Consumer;

/** Detects SkyBlock zones from the sidebar every two ticks. */
public final class ScoreboardZoneResolver implements ZoneSource {

    private static final int POLL_INTERVAL_TICKS = 2;

    private Consumer<Zone> listener;
    private Zone lastEmitted;
    private int tickCounter = 0;

    @Override
    public void register(Consumer<Zone> listener) {
        this.listener = listener;
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft mc) {
        if (++tickCounter < POLL_INTERVAL_TICKS) return;
        tickCounter = 0;

        Zone z = detect(mc);
        if (!Objects.equals(z, lastEmitted)) {
            lastEmitted = z;
            Waypointer.LOGGER.debug("Scoreboard zone resolved to {}", z);
            if (listener != null) listener.accept(z);
        }
    }

    private Zone detect(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null) return null;
        return resolveSidebarText(SidebarTexts.collectColorStripped(mc));
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
