package com.babbur.waypointer.dungeon;

public final class DungeonItemPickupHook {

    @FunctionalInterface
    public interface Listener {
        void onPickup(int collectorId, String itemName, double x, double y, double z);
    }

    private static volatile Listener listener;

    private DungeonItemPickupHook() {}

    public static void setListener(Listener next) {
        listener = next;
    }

    public static void onItemTaken(int collectorId, String itemName, double x, double y, double z) {
        Listener current = listener;
        if (current != null) current.onPickup(collectorId, itemName, x, y, z);
    }
}
