package com.babbur.waypointer.update;

import com.babbur.waypointer.Waypointer;
import com.babbur.waypointer.chat.WaypointerChatFeedback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class WaypointerUpdateChecker {

    private static final long MESSAGE_DELAY_SECONDS = 5;

    private WaypointerUpdateChecker() {
    }

    public static void install() {
        String currentVersion = FabricLoader.getInstance()
                .getModContainer(Waypointer.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        GitHubReleaseClient releaseClient = new GitHubReleaseClient(currentVersion);
        UpdateNotificationController controller = new UpdateNotificationController(
                () -> logLookupFailure(releaseClient.findUpdate(currentVersion)),
                task -> CompletableFuture.delayedExecutor(
                        MESSAGE_DELAY_SECONDS, TimeUnit.SECONDS).execute(task),
                WaypointerUpdateChecker::showMessage);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> controller.onJoin());
    }

    static <T> CompletableFuture<T> logLookupFailure(CompletableFuture<T> lookup) {
        return lookup.whenComplete((ignored, failure) -> {
            if (failure != null) {
                Waypointer.LOGGER.debug("Could not check for a Waypointer update", failure);
            }
        });
    }

    private static void showMessage(AvailableUpdate update) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        client.execute(() -> {
            LocalPlayer player = client.player;
            if (player == null) return;
            player.sendSystemMessage(WaypointerChatFeedback.suppress(
                    UpdateNotificationMessage.create(update)));
        });
    }
}
