package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.WaypointGroup;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DungeonRoomTimer {

    private final ActiveGroupManager manager;
    private final DungeonStateTracker tracker;
    private final DungeonConfig config;
    private final Clock clock = new Clock();

    public DungeonRoomTimer(ActiveGroupManager manager, DungeonStateTracker tracker,
                            DungeonConfig config) {
        this.manager = manager;
        this.tracker = tracker;
        this.config = config;
    }

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft mc) {
        if (config == null || !config.enabled() || !tracker.inDungeon()) {
            clock.clear();
            return;
        }
        DungeonRoom room = tracker.currentRoom();
        if (room == null || !room.hasRoomId()) return;
        WaypointGroup mirror = manager.get(DungeonRoomRouteProjection.generatedGroupId(room.roomId()));
        if (!DungeonRoomRouteProjection.isGeneratedGroup(mirror) || mirror.showAllWaypoints()) return;
        String routeId = mirror.runtimeSourceGroupId();
        long elapsed = clock.observe(mirror.id() + "|" + routeId, mirror.isComplete(),
                System.currentTimeMillis());
        if (elapsed <= 0 || !config.roomTimes()) return;
        long previousBest = config.roomBestMillis(routeId);
        boolean newBest = config.recordRoomTime(routeId, elapsed);
        MinecraftCompat.setOverlayMessage(mc, message(room.displayName(), elapsed,
                previousBest, newBest), false);
    }

    static Component message(String roomName, long elapsed, long previousBest, boolean newBest) {
        String time = seconds(elapsed);
        if (previousBest <= 0) {
            return Component.translatableWithFallback("waypointer.dungeon.room_time",
                    "%s %s", roomName, time);
        }
        if (newBest) {
            return Component.translatableWithFallback("waypointer.dungeon.room_time.new_best",
                    "%s %s, new best", roomName, time);
        }
        return Component.translatableWithFallback("waypointer.dungeon.room_time.best",
                "%s %s, best %s", roomName, time, seconds(previousBest));
    }

    static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    static final class Clock {
        private final Map<String, Long> startedAt = new HashMap<>();
        private final Set<String> finished = new HashSet<>();

        long observe(String key, boolean complete, long now) {
            if (complete) {
                if (!finished.add(key)) return 0L;
                Long start = startedAt.remove(key);
                return start == null ? 0L : Math.max(1L, now - start);
            }
            if (finished.remove(key)) startedAt.remove(key);
            startedAt.putIfAbsent(key, now);
            return 0L;
        }

        void clear() {
            startedAt.clear();
            finished.clear();
        }
    }
}
