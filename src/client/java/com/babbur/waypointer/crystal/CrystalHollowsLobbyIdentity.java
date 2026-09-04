package com.babbur.waypointer.crystal;

import com.babbur.waypointer.location.HypixelApiZoneSource;
import com.babbur.waypointer.location.SidebarTexts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.timeline.Timelines;

/** Resolves lobby identity from server packets first and the visible sidebar second. */
public final class CrystalHollowsLobbyIdentity {

    private CrystalHollowsLobbyIdentity() {}

    public static String currentServerId(Minecraft client) {
        String packetId = HypixelApiZoneSource.lastServerName();
        if (packetId != null && !packetId.isBlank()) return packetId;
        return CrystalHollowsSidebar.serverId(SidebarTexts.collectColorStripped(client));
    }

    public static int currentDay(ClientLevel level) {
        if (level == null) return -1;
        try {
            return level.registryAccess()
                    .lookupOrThrow(Registries.TIMELINE)
                    .get(Timelines.OVERWORLD_DAY)
                    .map(holder -> holder.value().getPeriodCount(level.clockManager()))
                    .map(count -> count > Integer.MAX_VALUE ? Integer.MAX_VALUE : count.intValue())
                    .orElse(-1);
        } catch (RuntimeException failure) {
            return -1;
        }
    }
}
