package com.babbur.waypointer.i18n;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** "1 route" / "3 routes": counted nouns with a translatable singular and plural form. */
public final class Counts {

    private Counts() {}

    /** {@code noun} is the key part after {@code waypointer.count.}, e.g. "route". */
    public static MutableComponent of(String noun, long count) {
        return Component.translatable("waypointer.count." + noun + (count == 1 ? ".one" : ".many"), count);
    }
}
