package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.Waypointer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Waits for Hypixel's locked-chest response before completing a chest secret. */
public final class DungeonChestInteractionGuard {

    static final int LOCK_GRACE_TICKS = 5;
    private static final String LOCKED_CHEST_MESSAGE = "That chest is locked!";

    private final List<PendingAction> pending = new ArrayList<>();
    private long clientTick;
    private Object observedLevel;

    public void install() {
        ClientReceiveMessageEvents.GAME.register(this::onGameMessage);
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    public void defer(ClientLevel level, Runnable action) {
        deferForLevel(level, action);
    }

    void deferForLevel(Object levelIdentity, Runnable action) {
        if (levelIdentity == null || action == null) return;
        synchronizeLevel(levelIdentity);
        pending.add(new PendingAction(levelIdentity, clientTick, action));
    }

    private void onGameMessage(Component message, boolean overlay) {
        if (message != null) cancelLockedActions(message.getString());
    }

    private void onClientTick(Minecraft client) {
        ClientLevel level = client == null ? null : client.level;
        synchronizeLevel(level);
        advanceTick();
    }

    void synchronizeLevel(Object levelIdentity) {
        if (levelIdentity == observedLevel) return;
        observedLevel = levelIdentity;
        pending.clear();
    }

    void advanceTick() {
        clientTick++;
        if (pending.isEmpty()) return;

        List<Runnable> ready = new ArrayList<>();
        pending.removeIf(action -> {
            if (action.levelIdentity() != observedLevel) return true;
            if (clientTick - action.clickedAtTick() <= LOCK_GRACE_TICKS) return false;
            ready.add(action.commit());
            return true;
        });
        for (Runnable action : ready) {
            try {
                action.run();
            } catch (RuntimeException failure) {
                Waypointer.LOGGER.error("Deferred dungeon chest action failed", failure);
            }
        }
    }

    boolean cancelLockedActions(String message) {
        if (!isLockedChestMessage(message)) return false;
        return pending.removeIf(action -> {
            long age = clientTick - action.clickedAtTick();
            return age >= 0 && age <= LOCK_GRACE_TICKS;
        });
    }

    static boolean isLockedChestMessage(String message) {
        return message != null
                && LOCKED_CHEST_MESSAGE.equalsIgnoreCase(message.trim());
    }

    int pendingCount() {
        return pending.size();
    }

    private record PendingAction(Object levelIdentity, long clickedAtTick, Runnable commit) {}
}
