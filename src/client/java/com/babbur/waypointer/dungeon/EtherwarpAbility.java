package com.babbur.waypointer.dungeon;

public record EtherwarpAbility(int range) {
    public static final int BASE_RANGE = 57;
    public static final int MAX_TUNERS = 4;

    public EtherwarpAbility {
        if (range < BASE_RANGE || range > BASE_RANGE + MAX_TUNERS) {
            throw new IllegalArgumentException("Etherwarp range is outside supported limits");
        }
    }

    boolean canUse(boolean sneaking) {
        return sneaking;
    }
}
