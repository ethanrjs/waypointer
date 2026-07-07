package dev.ethan.waypointer.dungeon;

import dev.ethan.waypointer.Waypointer;
import dev.ethan.waypointer.core.ActiveGroupManager;
import dev.ethan.waypointer.core.Zone;
import dev.ethan.waypointer.dungeon.config.DungeonConfig;
import dev.ethan.waypointer.dungeon.data.DungeonRoomData;
import dev.ethan.waypointer.dungeon.data.DungeonRoomDefinition;
import dev.ethan.waypointer.dungeon.data.DungeonRouteImporter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Fetches the community secret-route set and installs it through the normal
 * import pipeline.
 *
 * <p>Waypointer cannot bundle route data: every complete Catacombs route set
 * (SecretRoutes, DungeonRoomsMod lineage) is GPL-3.0, which cannot be
 * redistributed inside this PolyForm Noncommercial jar. What it can do is make
 * the user's own download a single click: the routes land in the local custom
 * store only, with attribution, exactly as if the user had run
 * {@code /wpd import} on a file they fetched themselves.
 *
 * <p>The prompt fires once per session, on entering a dungeon with no route
 * data installed, and can be dismissed permanently.
 */
public final class DungeonRouteDownloader {

    private static final URI ROUTES_URI = URI.create(
            "https://raw.githubusercontent.com/yourboykyle/SecretRoutes/main/routes.json");
    private static final String ATTRIBUTION =
            "Routes by yourboykyle & R-aMcC (SecretRoutes, GPL-3.0), downloaded to your local config.";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

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

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> Minecraft.getInstance().execute(() -> {
                    downloadInFlight.set(false);
                    if (error != null) {
                        Waypointer.LOGGER.warn("Route download failed", error);
                        feedback.accept(Component.literal(
                                        "Route download failed: " + error.getMessage())
                                .withStyle(ChatFormatting.RED));
                        return;
                    }
                    if (response.statusCode() / 100 != 2) {
                        feedback.accept(Component.literal(
                                        "Route download failed (HTTP " + response.statusCode() + ").")
                                .withStyle(ChatFormatting.RED));
                        return;
                    }
                    importDownloadedRoutes(response.body(), feedback);
                }));
    }

    private void importDownloadedRoutes(String payload, Consumer<Component> feedback) {
        DungeonRouteImporter.Result result;
        try {
            result = DungeonRouteImporter.parse(payload);
        } catch (IllegalArgumentException e) {
            feedback.accept(Component.literal("Downloaded routes could not be parsed: "
                    + e.getMessage()).withStyle(ChatFormatting.RED));
            return;
        }
        int rooms = DungeonRoomData.importCustomDefinitions(result.definitions());
        feedback.accept(Component.literal("Installed " + result.waypointCount()
                        + " secret waypoints across " + rooms + " rooms.")
                .withStyle(ChatFormatting.GREEN));
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
        if (mc.player != null) mc.gui.getChat().addClientSystemMessage(message);
    }
}
