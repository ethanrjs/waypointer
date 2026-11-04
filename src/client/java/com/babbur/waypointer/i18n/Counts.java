package com.babbur.waypointer.i18n;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class Counts {

    private Counts() {}

    public static MutableComponent of(String noun, long count) {
        return Component.translatable("waypointer.count." + noun + (count == 1 ? ".one" : ".many"), count);
    }
}
