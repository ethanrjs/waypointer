package com.babbur.waypointer.crystal;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;

/** Filters compass particles without allocations. */
public final class CrystalHollowsParticleHook {

    @FunctionalInterface
    public interface Listener {
        void onHappyVillagerParticle(double x, double y, double z, int count);
    }

    private static volatile Listener listener;

    private CrystalHollowsParticleHook() {}

    public static void setListener(Listener next) {
        listener = next;
    }

    public static void onParticlePacket(
            ParticleType<?> type, double x, double y, double z, int count) {
        if (type != ParticleTypes.HAPPY_VILLAGER) return;
        Listener current = listener;
        if (current != null) current.onHappyVillagerParticle(x, y, z, count);
    }
}
