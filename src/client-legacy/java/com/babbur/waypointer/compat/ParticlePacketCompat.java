package com.babbur.waypointer.compat;

import com.babbur.waypointer.crystal.CrystalHollowsParticleHook;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;

public final class ParticlePacketCompat {
    private ParticlePacketCompat() {}

    public static void onPacket(ClientboundLevelParticlesPacket packet) {
        CrystalHollowsParticleHook.onParticlePacket(packet.getParticle().getType(),
                packet.getX(), packet.getY(), packet.getZ(), packet.getCount());
    }
}
