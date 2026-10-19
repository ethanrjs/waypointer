package com.babbur.waypointer.crystal;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.crystal.SkyblockerAuthentication.FailureCause;
import com.babbur.waypointer.crystal.SkyblockerAuthentication.SafeFailure;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.network.chat.Component;

public final class SkyblockerStructureController {
    private static volatile SkyblockerStructureController installed;
    private static final URI ENDPOINT = URI.create("wss://ws.hysky.de");
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final CrystalHollowsTracker tracker;
    private final WaypointerConfig config;
    private final String version = FabricLoader.getInstance().getModContainer("waypointer")
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    private final StructureRelayConnection connection;
    private final SkyblockerSocketPolicy socketPolicy = new SkyblockerSocketPolicy(HTTP);
    private final boolean skyblockerLoaded;
    private final EnumSet<CrystalHollowsStructure> published = EnumSet.noneOf(CrystalHollowsStructure.class);
    private final EnumSet<CrystalHollowsStructure> received = EnumSet.noneOf(CrystalHollowsStructure.class);
    private CrystalHollowsLobbyState lobby;
    private Object level;
    private String server;
    private UUID player;
    private SkyblockerAuthentication.Token token;
    private AuthAttempt authentication;
    private long sessionStarted;
    private long nextWork;
    private long nextAttempt;
    private long generation;
    private int failures;
    private boolean subscribed;
    private boolean denied;
    private boolean warned;
    private volatile Failure handshakeFailure;
    private int lastFailureStatus;
    private FailureCause lastFailureCause = FailureCause.NONE;
    private String lastFailureStage = "none";

    public record DebugSnapshot(boolean installed, boolean enabled, boolean lobbyActive,
                                boolean connected, boolean connecting, String authentication,
                                boolean subscribed, boolean denied, long backoffRemainingMillis, int failures,
                                int inboundQueueCount, int publishedCount, int receivedCount,
                                long generation, int lastFailureStatus, String status,
                                FailureCause lastFailureCause, String lastFailureStage) {}

    public static DebugSnapshot debugSnapshot() {
        var controller = installed;
        return controller == null
                ? new DebugSnapshot(false, false, false, false, false, "none", false, false,
                        0, 0, -1, 0, 0, 0, 0, "not_installed", FailureCause.NONE, "none")
                : controller.diagnostics(System.currentTimeMillis());
    }

    DebugSnapshot diagnostics(long now) {
        boolean enabled = config != null && config.crystalHollowsEnabled() && config.crystalHollowsRemoteSharing()
                && config.crystalHollowsSkyblockerSharing();
        boolean open;
        boolean connecting;
        synchronized (connection) {
            open = connection.isOpen();
            connecting = connection.isConnecting();
        }
        String auth = token != null ? (token.needsRefresh(now) ? "refresh_due" : "ready")
                : authentication == null ? "none" : authentication.result.isDone() ? "completed" : "pending";
        long backoff = !open && !connecting && authentication == null && !denied
                ? Math.max(0, nextAttempt - now) : 0;
        String status = !enabled ? "disabled" : skyblockerLoaded ? "deferred_to_skyblocker"
                : !socketPolicy.allowed() ? socketPolicy.status()
                : denied ? "denied" : lobby == null ? "idle"
                : open ? (subscribed ? "subscribed" : "subscribing") : connecting ? "connecting"
                : authentication != null ? "authenticating" : backoff > 0 ? "backoff" : "disconnected";
        Failure pending = handshakeFailure;
        int failureStatus = pending != null && pending.generation == generation ? pending.status : lastFailureStatus;
        FailureCause cause = pending != null && pending.generation == generation ? pending.cause : lastFailureCause;
        String stage = pending != null && pending.generation == generation ? "websocket" : lastFailureStage;
        return new DebugSnapshot(true, enabled, lobby != null, open, connecting, auth, subscribed, denied,
                backoff, failures, -1, published.size(), received.size(), generation, failureStatus, status, cause, stage);
    }

    public SkyblockerStructureController(CrystalHollowsTracker tracker, WaypointerConfig config) {
        this(tracker, config, FabricLoader.getInstance().isModLoaded("skyblocker"));
    }

    SkyblockerStructureController(CrystalHollowsTracker tracker, WaypointerConfig config, boolean skyblockerLoaded) {
        this.tracker = tracker;
        this.config = config;
        this.skyblockerLoaded = skyblockerLoaded;
        connection = new StructureRelayConnection((uri, listener) -> {
            long attempt = generation;
            return HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .header("User-Agent", userAgent()).header("Authorization", token.authorization())
                    .buildAsync(uri, listener).whenComplete((socket, error) -> {
                        if (error != null) recordHandshakeFailure(attempt, error);
                    });
        });
    }

    void recordHandshakeFailure(long attempt, Throwable error) {
        synchronized (connection) {
            if (attempt == generation) {
                handshakeFailure = new Failure(attempt, status(error), SkyblockerAuthentication.failureCause(error));
            }
        }
    }

    public void install() {
        installed = this;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> reset());
    }

    private String userAgent() {
        return "Skyblocker/" + version + " (" + SharedConstants.getCurrentVersion().name() + ")";
    }

    private void tick(Minecraft client) {
        if (!config.crystalHollowsSkyblockerSharing() || !config.crystalHollowsRemoteSharing()
                || !config.crystalHollowsEnabled()) { reset(); return; }
        if (skyblockerLoaded) { reset(); return; }
        if (client == null || client.level == null || client.getCurrentServer() == null
                || !tracker.active() || tracker.lobby() == null || tracker.serverId() == null) {
            leaveLobby();
            return;
        }
        String host = client.getCurrentServer().ip.toLowerCase(Locale.ROOT).split(":", 2)[0];
        if (!(host.equals("hypixel.net") || host.endsWith(".hypixel.net"))
                || !StructureRelayProtocol.validServer(tracker.serverId().toLowerCase(Locale.ROOT))) {
            leaveLobby();
            return;
        }
        if (!allowRemoteSharing()) return;
        UUID currentPlayer = client.getUser().getProfileId();
        if (currentPlayer == null) { reset(); return; }
        if (player != null && !player.equals(currentPlayer)) reset();
        player = currentPlayer;
        if (lobby != tracker.lobby() || level != client.level || !tracker.serverId().equals(server)) {
            leaveLobby();
            lobby = tracker.lobby();
            level = client.level;
            server = tracker.serverId();
            sessionStarted = System.currentTimeMillis();
        }
        long now = System.currentTimeMillis();
        if (now < nextWork || denied) return;
        nextWork = now + 1000;
        int day = CrystalHollowsLobbyIdentity.currentDay(client.level);
        if (day < 0 || day >= 26) { disconnect(); return; }
        Failure failure = handshakeFailure;
        if (failure != null) {
            handshakeFailure = null;
            if (failure.generation == generation) {
                failed(client, failure.status, failure.cause, "websocket", now);
                return;
            }
        }
        if (token != null && token.needsRefresh(now)) {
            disconnect();
            token = null;
        }
        if (token == null) {
            if (authentication == null && now >= nextAttempt) {
                try {
                    authentication = new AuthAttempt(authenticationKeys(client), currentPlayer,
                            SharedConstants.getCurrentVersion().name(), version, userAgent());
                } catch (RuntimeException unavailable) {
                    failed(client, status(unavailable), SkyblockerAuthentication.failureCause(unavailable), "profile_keys", now);
                    return;
                }
                nextAttempt = now + 30_000;
            }
            if (!completeAuthentication(client, now)) return;
        }
        if (!connection.isOpen()) {
            if (subscribed) {
                subscribed = false;
                retryLater(now);
            }
            if (!connection.isConnecting() && now >= nextAttempt) {
                generation++;
                connection.connect(ENDPOINT);
                retryLater(now);
            }
            return;
        }
        if (!subscribed) {
            if (!connection.send(SkyblockerStructureProtocol.subscribe(server, day, now))) return;
            subscribed = true;
            failures = 0;
            nextAttempt = 0;
            return;
        }
        tracker.batchDetections(() -> connection.drain(message -> {
            for (StructureSighting sighting : SkyblockerStructureProtocol.decode(message, server, now)) {
                received.add(sighting.structure());
                tracker.merge(sighting, false);
            }
        }));
        for (StructureSighting sighting : lobby.localSightings()) {
            if (!tracker.detectionEnabled(sighting)) continue;
            if (published.contains(sighting.structure()) || received.contains(sighting.structure())) continue;
            final String message;
            try { message = SkyblockerStructureProtocol.publish(server, sighting, sessionStarted, now); }
            catch (IllegalArgumentException notLocal) { continue; }
            if (connection.send(message)) published.add(sighting.structure());
            break;
        }
    }

    boolean completeAuthentication(Minecraft client, long now) {
        if (authentication == null || !authentication.result.isDone()) return false;
        try {
            token = authentication.result.join();
            nextAttempt = now;
            if (token.needsRefresh(now)) token = null;
        } catch (RuntimeException error) {
            failed(client, status(error), SkyblockerAuthentication.failureCause(error), "authentication", now);
        }
        authentication = null;
        return token != null;
    }

    boolean allowRemoteSharing() {
        socketPolicy.refresh();
        if (socketPolicy.allowed()) return true;
        reset();
        return false;
    }

    private static ProfileKeyPairManager authenticationKeys(Minecraft client) {
        return authenticationKeys(client, client.getProfileKeyPairManager(),
                FabricLoader.getInstance().isModLoaded("nochatreports"), Minecraft.class);
    }

    static ProfileKeyPairManager authenticationKeys(Object client, ProfileKeyPairManager exposed,
                                                     boolean noChatReports, Class<?> owner) {
        if (exposed != ProfileKeyPairManager.EMPTY_KEY_MANAGER || !noChatReports) return exposed;
        try {
            var field = owner.getDeclaredField("profileKeyPairManager");
            if (field.trySetAccessible() && field.get(client) instanceof ProfileKeyPairManager keys) return keys;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
        }
        throw new SafeFailure(FailureCause.PROFILE_KEYS_UNAVAILABLE);
    }

    private void failed(Minecraft client, int status, FailureCause cause, String stage, long now) {
        lastFailureStatus = status;
        lastFailureCause = cause;
        lastFailureStage = stage;
        disconnect();
        token = null;
        denied = status >= 400 && status < 500 && status != 408 && status != 429;
        retryLater(now);
        if (!warned && client.player != null) {
            warned = true;
            client.player.sendSystemMessage(Component.literal(failureMessage(cause, status)));
        }
    }

    static String failureMessage(FailureCause cause, int status) {
        return "Skyblocker sharing is unavailable: " + cause.explanation()
                + (status == 0 ? "." : " (HTTP " + status + ").") + " Waypointer sharing remains active.";
    }

    private void retryLater(long now) {
        nextAttempt = now + Math.min(300_000, 15_000L << Math.min(failures++, 5));
    }

    private void disconnect() {
        synchronized (connection) {
            if (!connection.isOpen() && !connection.isConnecting() && !subscribed && handshakeFailure == null) return;
            generation++;
            connection.close();
            subscribed = false;
            handshakeFailure = null;
        }
    }

    private void leaveLobby() {
        disconnect();
        if (lobby != null && lobby.clearRemoteSightings(SkyblockerStructureProtocol.SOURCE)
                && lobby == tracker.lobby()) tracker.rebuildProjection();
        lobby = null;
        level = null;
        server = null;
        published.clear();
        received.clear();
        nextWork = 0;
    }

    private void reset() {
        leaveLobby();
        if (authentication != null) { authentication.cancel(); authentication = null; }
        token = null;
        player = null;
        denied = warned = false;
        failures = 0;
        nextAttempt = 0;
        lastFailureStatus = 0;
        lastFailureCause = FailureCause.NONE;
        lastFailureStage = "none";
    }

    static int status(Throwable error) {
        int depth = 0;
        for (Throwable current = error; current != null && depth++ < 16; current = current.getCause()) {
            if (current instanceof SkyblockerAuthentication.Rejected rejected) return rejected.status();
            if (current instanceof WebSocketHandshakeException rejected) return rejected.getResponse().statusCode();
        }
        return 0;
    }

    private record Failure(long generation, int status, FailureCause cause) {}

    static final class AuthAttempt {
        private boolean cancelled;
        private CompletableFuture<SkyblockerAuthentication.Token> exchange;
        final CompletableFuture<SkyblockerAuthentication.Token> result;

        AuthAttempt(ProfileKeyPairManager keys, UUID uuid, String minecraft, String version, String userAgent) {
            result = keys.prepareKeyPair().thenComposeAsync(optional -> {
                synchronized (this) {
                    if (cancelled) return CompletableFuture.failedFuture(new IllegalStateException("Cancelled"));
                    try {
                        var pair = optional.orElseThrow(() -> new SafeFailure(FailureCause.PROFILE_KEYS_UNAVAILABLE));
                        var data = pair.publicKey().data();
                        String proof = SkyblockerAuthentication.proof(uuid, data.key(), data.keySignature(),
                                data.expiresAt().toEpochMilli(), pair.privateKey(), minecraft, version, System.currentTimeMillis());
                        exchange = SkyblockerAuthentication.authenticate(proof, userAgent);
                        return exchange;
                    } catch (Exception error) {
                        return CompletableFuture.failedFuture(error instanceof SafeFailure safe ? safe
                                : new SafeFailure(FailureCause.PROOF_FAILED));
                    }
                }
            }).orTimeout(25, TimeUnit.SECONDS);
            result.whenComplete((value, error) -> {
                if (error != null) {
                    synchronized (this) {
                        cancelled = true;
                        if (exchange != null) exchange.cancel(true);
                    }
                }
            });
        }

        synchronized void cancel() {
            cancelled = true;
            if (exchange != null) exchange.cancel(true);
            result.cancel(true);
        }
    }
}
