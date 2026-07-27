package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.dungeon.config.DungeonConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/** One quiet, debounced cue when a secret stage completes. */
public final class DungeonSecretCompletionSound {

    private static final long DEBOUNCE_MS = 150L;
    private static long lastPlayedAtMillis;

    private DungeonSecretCompletionSound() {}

    public static void play(DungeonConfig config) {
        if (config != null && !config.secretCompletionSound()) return;
        long now = System.currentTimeMillis();
        if (now - lastPlayedAtMillis < DEBOUNCE_MS) return;
        lastPlayedAtMillis = now;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getSoundManager() == null) return;
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(
                SoundEvents.EXPERIENCE_ORB_PICKUP, 1.15f, 0.45f));
    }
}
