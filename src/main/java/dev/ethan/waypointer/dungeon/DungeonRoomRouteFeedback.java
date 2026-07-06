package dev.ethan.waypointer.dungeon;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public final class DungeonRoomRouteFeedback {

    private DungeonRoomRouteFeedback() {
    }

    public static Component completionHiddenUntilNextRun() {
        return Component.literal("Room route complete -- hidden until next run. Press Previous to reopen.")
                .withStyle(ChatFormatting.AQUA);
    }
}
