package com.babbur.waypointer.crystal;

import com.babbur.waypointer.config.WaypointerConfig;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

public final class StructureRelayController {
    private static final String ENDPOINT = "wss://waypointer-structures.ethanmrettinger.workers.dev/v1/structures";
    // Live updates arrive immediately; snapshots only reconcile lobby age and missed state.
    private static final long SYNC_INTERVAL_MILLIS = 5 * 60_000L;
    private final CrystalHollowsTracker tracker;
    private final WaypointerConfig config;
    private final StructureRelayConnection connection = new StructureRelayConnection();
    private final LinkedHashMap<String, Long> sent = new LinkedHashMap<>();
    private final ArrayDeque<Long> publications = new ArrayDeque<>();
    private CrystalHollowsLobbyState lobby;
    private Object level;
    private long nextWork;
    private long nextConnect;
    private long nextSync;
    private long lastSyncAt;
    private long lastSyncAge;
    private long sessionStarted;
    private int failures;
    private boolean wasOpen;

    public StructureRelayController(CrystalHollowsTracker tracker, WaypointerConfig config) {
        this.tracker = tracker;
        this.config = config;
    }

    public void install() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> close());
    }

    private boolean enabled(Minecraft client) {
        if (!config.crystalHollowsEnabled() || !config.crystalHollowsRemoteSharing()
                || !tracker.active() || tracker.lobby() == null || client.level == null
                || client.getCurrentServer() == null || tracker.serverId() == null) return false;
        String host = client.getCurrentServer().ip.toLowerCase(Locale.ROOT).split(":", 2)[0];
        return (host.equals("hypixel.net") || host.endsWith(".hypixel.net"))
                && StructureRelayProtocol.validServer(tracker.serverId().toLowerCase(Locale.ROOT))
                && client.level.getGameTime() >= 0
                && client.level.getGameTime() <= StructureRelayProtocol.MAX_AGE;
    }

    private void tick(Minecraft client) {
        if (!enabled(client)) {
            if (lobby != null) close();
            return;
        }
        if (lobby != tracker.lobby() || level != client.level) {
            close();
            lobby = tracker.lobby();
            level = client.level;
            sessionStarted = System.currentTimeMillis() - 1_000;
        }
        long now = System.currentTimeMillis();
        if (now < nextWork) return;
        nextWork = now + 1_000;
        long age = client.level.getGameTime();
        if (lobby.expireRemoteSightings(now - 1_800_000)) tracker.rebuildProjection();
        if (!connection.isOpen()) {
            if (wasOpen) {
                wasOpen = false;
                sent.clear();
                scheduleRetry(now);
            }
            if (!connection.isConnecting() && now >= nextConnect) {
                lastSyncAt = now;
                lastSyncAge = age;
                connection.connect(URI.create(ENDPOINT + "?server="
                        + tracker.serverId().toLowerCase(Locale.ROOT) + "&age=" + age));
                scheduleRetry(now);
            }
            return;
        }
        if (!wasOpen) {
            wasOpen = true;
            failures = 0;
            nextSync = now + SYNC_INTERVAL_MILLIS;
        }
        tracker.batchDetections(() -> {
            String message;
            while ((message = connection.poll()) != null) {
                for (var sighting : StructureRelayProtocol.decode(message, age, now)) {
                    tracker.merge(sighting, false);
                }
            }
        });
        if (syncDue(now, age)) {
            if (!connection.send("{\"type\":\"sync\",\"age\":" + age + "}")) return;
            lastSyncAt = now;
            lastSyncAge = age;
            nextSync = now + SYNC_INTERVAL_MILLIS;
        }
        int published = 0;
        while (!publications.isEmpty() && now - publications.getFirst() >= 60_000) publications.removeFirst();
        for (var sighting : lobby.localSightings()) {
            if (publications.size() >= 24) break;
            if (!StructureRelayProtocol.local(sighting)
                    || sighting.atMillis() < sessionStarted && sighting.atMillis() < now - 1_800_000) continue;
            String key = publicationKey(sighting);
            if (now - sent.getOrDefault(key, 0L) >= 900_000
                    && connection.send(StructureRelayProtocol.encode(sighting, age))) {
                sent.put(key, now);
                publications.addLast(now);
                if (sent.size() > 128) sent.remove(sent.keySet().iterator().next());
                if (++published >= 8) break;
            }
        }
    }

    static String publicationKey(StructureSighting sighting) {
        return sighting.structure().id() + ':' + sighting.x() + ':' + sighting.y() + ':' + sighting.z()
                + ':' + sighting.confidence();
    }

    boolean syncDue(long now, long age) {
        // Resync early when a lagging server approaches the relay's 60-second age tolerance.
        return now >= nextSync || now - lastSyncAt >= 55_000
                && Math.abs(age - lastSyncAge - (now - lastSyncAt) / 50) >= 900;
    }

    private void scheduleRetry(long now) {
        long delay = Math.min(60_000, 1_000L << Math.min(failures++, 6));
        nextConnect = now + ThreadLocalRandom.current().nextLong(delay / 2, delay + 1);
    }

    private void close() {
        connection.close();
        if (lobby != null && lobby.clearRemoteSightings() && lobby == tracker.lobby()) {
            tracker.rebuildProjection();
        }
        lobby = null;
        level = null;
        sent.clear();
        publications.clear();
        failures = 0;
        wasOpen = false;
        nextWork = nextConnect = nextSync = 0;
    }
}
