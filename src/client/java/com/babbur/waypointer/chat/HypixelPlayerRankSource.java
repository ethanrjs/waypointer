package com.babbur.waypointer.chat;

import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.ClientboundHelloPacket;
import net.hypixel.modapi.packet.impl.clientbound.ClientboundPlayerInfoPacket;
import net.hypixel.modapi.packet.impl.serverbound.ServerboundPlayerInfoPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.Component;

public final class HypixelPlayerRankSource {
    private static volatile Component currentRankPrefix;

    private HypixelPlayerRankSource() {
    }

    public static void install() {
        HypixelModAPI api = HypixelModAPI.getInstance();
        api.createHandler(ClientboundHelloPacket.class, packet -> {
            currentRankPrefix = null;
            api.sendPacket(new ServerboundPlayerInfoPacket());
        });
        api.createHandler(ClientboundPlayerInfoPacket.class, HypixelPlayerRankSource::handlePlayerInfo);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> currentRankPrefix = null);
    }

    private static void handlePlayerInfo(ClientboundPlayerInfoPacket packet) {
        currentRankPrefix = WaypointerContributorBadge.hypixelRankPrefix(
                packet.getPlayerRank().name(),
                packet.getPackageRank().name(),
                packet.getMonthlyPackageRank().name(),
                packet.getPrefix().orElse(null));
    }

    public static Component currentRankPrefix() {
        Component prefix = currentRankPrefix;
        return prefix == null ? null : prefix.copy();
    }
}
