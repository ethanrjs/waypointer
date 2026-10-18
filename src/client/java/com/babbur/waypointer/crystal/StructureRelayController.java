package com.babbur.waypointer.crystal;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.location.SidebarTexts;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

public final class StructureRelayController {
    private static volatile StructureRelayController installed;
    private static final String ENDPOINT = "wss://waypointer-structures.ethanmrettinger.workers.dev/v1/structures";
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
    private boolean reportedClosing;
    private long diagnosticGeneration;

    public record DebugSnapshot(boolean installed, boolean enabled, boolean lobbyActive,
                                boolean connected, boolean connecting, String authentication,
                                boolean subscribed, long backoffRemainingMillis, int failures,
                                int inboundQueueCount, int sentCacheCount, int publicationWindowCount,
                                long generation, String status) {}

    public static DebugSnapshot debugSnapshot() {
        var controller = installed;
        return controller == null
                ? new DebugSnapshot(false, false, false, false, false, "not_required", false,
                        0, 0, -1, 0, 0, 0, "not_installed")
                : controller.diagnostics(System.currentTimeMillis());
    }

    DebugSnapshot diagnostics(long now) {
        boolean enabled = config != null && config.crystalHollowsEnabled() && config.crystalHollowsRemoteSharing();
        boolean open;
        boolean connecting;
        synchronized (connection) {
            open = connection.isOpen();
            connecting = connection.isConnecting();
        }
        long backoff = !open && !connecting ? Math.max(0, nextConnect - now) : 0;
        String status = !enabled ? "disabled" : lobby == null ? "idle" : open ? "connected"
                : connecting ? "connecting" : backoff > 0 ? "backoff" : "disconnected";
        return new DebugSnapshot(true, enabled, lobby != null, open, connecting, "not_required",
                open && lobby != null, backoff, failures, -1, sent.size(), publications.size(),
                diagnosticGeneration, status);
    }

    public StructureRelayController(CrystalHollowsTracker tracker, WaypointerConfig config) {
        this.tracker = tracker;
        this.config = config;
    }

    public void install() {
        installed = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> close());
        new SkyblockerStructureController(tracker, config).install();
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
        int day = CrystalHollowsLobbyIdentity.currentDay(client.level);
        long dayTicks = CrystalHollowsLobbyIdentity.currentDayTicks(client.level);
        int closingInSeconds = tracker.closingInSeconds(SidebarTexts.collectColorStripped(client), now);
        if (lobby.expireRemoteSightings(now - 1_800_000, SkyblockerStructureProtocol.SOURCE)) {
            tracker.rebuildProjection();
        }
        if (!connection.isOpen()) {
            if (wasOpen) {
                wasOpen = false;
                sent.clear();
                scheduleRetry(now);
            }
            if (!connection.isConnecting() && now >= nextConnect) {
                lastSyncAt = now;
                lastSyncAge = age;
                reportedClosing = closingInSeconds >= 0;
                diagnosticGeneration++;
                connection.connect(URI.create(ENDPOINT + "?server="
                        + tracker.serverId().toLowerCase(Locale.ROOT) + "&age=" + age
                        + StructureRelayProtocol.connectionTiming(day, dayTicks, closingInSeconds)));
                scheduleRetry(now);
            }
            return;
        }
        if (!wasOpen) {
            wasOpen = true;
            openedAt = now;
            nextSync = now + SYNC_INTERVAL_MILLIS;
        }
        if (failures > 0 && now - openedAt >= STABLE_SESSION_MILLIS) failures = 0;
        tracker.batchDetections(() -> connection.drain(message ->
                tracker.applyRelayUpdate(StructureRelayProtocol.decodeUpdate(message, age, now))));
        if (syncDue(now, age, closingInSeconds)) {
            if (!connection.send(StructureRelayProtocol.sync(age, day, dayTicks, closingInSeconds))) return;
            lastSyncAt = now;
            lastSyncAge = age;
            reportedClosing |= closingInSeconds >= 0;
            nextSync = now + SYNC_INTERVAL_MILLIS;
        }
        int published = 0;
        while (!publications.isEmpty() && now - publications.getFirst() >= 60_000) publications.removeFirst();
        for (var sighting : lobby.localSightings()) {
            if (!tracker.detectionEnabled(sighting)) continue;
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
        return syncDue(now, age, -1);
    }

    boolean syncDue(long now, long age, int closingInSeconds) {
        return closingInSeconds >= 0 && !reportedClosing || now - lastSyncAt >= 55_000 && (now >= nextSync
                || Math.abs(age - lastSyncAge - (now - lastSyncAt) / 50) >= 900);
    }

    private static final long STABLE_SESSION_MILLIS = 15_000;
    private long openedAt;

    private void scheduleRetry(long now) {
        long delay = Math.min(60_000, 1_000L << Math.min(failures++, 6));
        nextConnect = now + ThreadLocalRandom.current().nextLong(delay / 2, delay + 1);
    }

    private void close() {
        diagnosticGeneration++;
        connection.close();
        if (lobby != null && lobby.clearRemoteSightings("relay") && lobby == tracker.lobby()) {
            tracker.rebuildProjection();
        }
        lobby = null;
        level = null;
        sent.clear();
        publications.clear();
        failures = 0;
        wasOpen = false;
        reportedClosing = false;
        nextWork = nextConnect = nextSync = 0;
    }
}
