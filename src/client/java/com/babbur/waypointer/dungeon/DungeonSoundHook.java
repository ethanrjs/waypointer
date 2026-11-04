package com.babbur.waypointer.dungeon;

import net.minecraft.sounds.SoundEvent;

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

    public static void onSoundPacket(SoundEvent sound, float volume, double x, double y, double z) {
        Listener current = listener;
        if (current != null) current.onSound(sound, volume, x, y, z);
    }
}
