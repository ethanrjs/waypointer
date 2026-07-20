package com.babbur.waypointer.dungeon;

import com.babbur.waypointer.compat.MinecraftCompat;
import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.core.ActiveGroupManager;
import com.babbur.waypointer.core.Zone;
import com.babbur.waypointer.dungeon.config.DungeonConfig;
import com.babbur.waypointer.dungeon.data.DungeonRoomData;
import com.babbur.waypointer.dungeon.data.DungeonRoomDefinition;
import com.babbur.waypointer.dungeon.data.DungeonRouteImporter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.HexFormat;

/**
 * Fetches the community secret-route set and installs it through the normal
 * import pipeline.
 *
 * <p>Waypointer keeps the GPL-3.0 community route data out of the jar and makes
 * the user's own download a single click: the routes land in the local custom
 * store only, with attribution, exactly as if the user had run
 * {@code /wpd import} on a file they fetched themselves.
 *
 * <p>The prompt fires once per session, on entering a dungeon with no route
 * data installed, and can be dismissed permanently.
 */
public final class DungeonRouteDownloader {

    static final String ROUTES_COMMIT = "9cf484146cbffceb93c9e27c2ee1ae3c5ce9e112";
    static final String ROUTES_SHA256 =
            "d7ddc92bb72a93aa86e3d062152d16d29ea04342510b9360c776e43463ecb1b8";
    private static final URI ROUTES_URI = URI.create(
            "https://raw.githubusercontent.com/yourboykyle/SecretRoutes/"
                    + ROUTES_COMMIT + "/routes.json");
    private static final String ATTRIBUTION =
            "Routes by yourboykyle & R-aMcC (SecretRoutes, GPL-3.0), downloaded to your local config.";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;

    /** Attribution shown wherever the download is offered (chat prompt, GUI tooltip). */
    public static String attributionText() {
        return ATTRIBUTION;
    }

    private final ActiveGroupManager manager;
    private final DungeonConfig config;
    private final AtomicBoolean downloadInFlight = new AtomicBoolean();
    private boolean promptShownThisSession;

    public DungeonRouteDownloader(ActiveGroupManager manager, DungeonConfig config) {
        this.manager = manager;
        this.config = config;
    }

    public void install() {
        manager.addZoneListener(this::onZoneChanged);
    }

    private void onZoneChanged(Zone zone) {
        if (promptShownThisSession
                || !config.enabled()
                || config.routesPromptDismissed()
                || !DungeonRoomZoneBridge.isBroadDungeonZone(zone)
                || hasAnyRouteData()) {
            return;
        }
        promptShownThisSession = true;
        sendChat(prompt());
    }

    private static boolean hasAnyRouteData() {
        for (DungeonRoomDefinition definition : DungeonRoomData.customDefinitions()) {
            if (!definition.waypoints().isEmpty()) return true;
        }
        return false;
    }

    private static Component prompt() {
        MutableComponent text = Component.literal("Waypointer: no dungeon secret routes installed. ")
                .withStyle(ChatFormatting.YELLOW);
        text.append(Component.literal("[Download community routes]")
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent.RunCommand("/wpd routes download"))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal(ATTRIBUTION)))));
        text.append(Component.literal(" "));
        text.append(Component.literal("[Don't ask again]")
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.GRAY)
                        .withClickEvent(new ClickEvent.RunCommand("/wpd routes dismiss"))));
        return text;
    }

    /**
     * Starts the async download; feedback lands on the given consumer on the
     * client main thread. No-ops (with a message) when a download is already
     * running.
     */
    public void download(Consumer<Component> feedback) {
        if (!downloadInFlight.compareAndSet(false, true)) {
            feedback.accept(Component.literal("A route download is already running.")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        feedback.accept(Component.literal("Downloading community routes…")
                .withStyle(ChatFormatting.GRAY));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(ROUTES_URI)
                .header("Accept", "application/json")
                .header("User-Agent", "waypointer-route-download")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApplyAsync(DungeonRouteDownloader::parseDownloadResponse)
                .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                    downloadInFlight.set(false);
                    if (error != null) {
                        Throwable cause = unwrapCompletionError(error);
                        Waypointer.LOGGER.warn("Route download failed", cause);
                        feedback.accept(Component.literal(
                                        "Route download failed: " + safeErrorMessage(cause))
                                .withStyle(ChatFormatting.RED));
                        return;
                    }
                    importDownloadedRoutes(result, feedback);
                }));
    }

    static DungeonRouteImporter.Result parseDownloadResponse(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IllegalArgumentException("HTTP " + response.statusCode());
            }
            byte[] payloadBytes = readBounded(body, MAX_DOWNLOAD_BYTES);
            String actualSha256 = sha256Hex(payloadBytes);
            if (!ROUTES_SHA256.equals(actualSha256)) {
                throw new IllegalArgumentException("route payload failed integrity verification");
            }
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            return DungeonRouteImporter.parse(payload);
        } catch (IOException e) {
            throw new CompletionException("could not read the response", e);
        } catch (IllegalArgumentException e) {
            throw new CompletionException(e);
        }
    }

    static String readBoundedUtf8(InputStream input, int maxBytes) throws IOException {
        return new String(readBounded(input, maxBytes), StandardCharsets.UTF_8);
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        if (input == null) throw new IOException("response body is missing");
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must be non-negative");

        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("response is too large (max " + maxBytes + " bytes)");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Throwable unwrapCompletionError(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeErrorMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "unknown error";
        }
        return error.getMessage();
    }

    private void importDownloadedRoutes(DungeonRouteImporter.Result result,
                                        Consumer<Component> feedback) {
        int rooms = DungeonRoomData.importCustomDefinitions(result.definitions());
        feedback.accept(Component.literal("Installed " + result.waypointCount()
                        + " secret waypoints across " + rooms + " rooms.")
                .withStyle(ChatFormatting.GREEN));
        feedback.accept(Component.literal(
                        "Each room now lists its secret route in the Waypointer GUI;"
                                + " waypoints appear in-world when you enter the room.")
                .withStyle(ChatFormatting.GRAY));
        feedback.accept(Component.literal(ATTRIBUTION).withStyle(ChatFormatting.GRAY));
        if (!result.unmatchedRooms().isEmpty()) {
            feedback.accept(Component.literal(result.unmatchedRooms().size()
                            + " room(s) had no catalog match and were skipped.")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    public void dismissPrompt() {
        config.setRoutesPromptDismissed(true);
    }

    private static void sendChat(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) MinecraftCompat.addClientSystemMessage(mc, message);
    }
}
