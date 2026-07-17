package com.babbur.waypointer.dungeon;

import net.minecraft.sounds.SoundEvent;

/**
 * Bridge between {@code ClientPacketListenerMixin} and the dungeon trigger
 * detector. Fabric API exposes no client event for incoming sound packets, and
 * Hypixel's secret-bat kill signal only exists as a sound
 * ({@code BAT_HURT}/{@code BAT_DEATH} at volume {@code 0.1}) -- the bat entity
 * itself is also removed when it simply flies out of tracking range, so entity
 * lifecycle events cannot distinguish a kill.
 *
 * <p>The mixin stays a dumb forwarder; all filtering lives in the listener.
 */
public final class DungeonSoundHook {

    @FunctionalInterface
    public interface Listener {
        void onSound(SoundEvent sound, float volume, double x, double y, double z);
    }

    private static volatile Listener listener;

    private DungeonSoundHook() {}

    public static void setListener(Listener next) {
        listener = next;
    }

    /** Called from the packet handler on the client main thread. */
    public static void onSoundPacket(SoundEvent sound, float volume, double x, double y, double z) {
        Listener current = listener;
        if (current != null) current.onSound(sound, volume, x, y, z);
    }
}
