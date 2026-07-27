package com.babbur.waypointer.dungeon;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public final class DungeonRoomRouteFeedback {

    private DungeonRoomRouteFeedback() {
    }

    public static Component completionHiddenUntilNextRun() {
        return Component.translatable("waypointer.dungeon.route.complete_hidden")
                .withStyle(ChatFormatting.AQUA);
    }
}
