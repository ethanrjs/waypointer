package com.babbur.waypointer.screen;

import com.babbur.waypointer.WaypointerClient;
import com.babbur.waypointer.compat.MinecraftCompat;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.network.chat.Component;

@SuppressWarnings("UnstableApiUsage")
public final class DiagnosticsSmokeTest {
    private DiagnosticsSmokeTest() { }

    public static void run(ClientGameTestContext context) {
        context.computeOnClient(client -> {
            var report = DebugInspectScreen.captureFeedbackSnapshot(WaypointerClient.manager(), WaypointerClient.config());
            if (!report.fullReport().contains("== Server Timing ==")
                    || !report.fullReport().contains("== Publishing Identity ==")
                    || !report.fullReport().contains("Active publisher ID")
                    || !report.fullReport().contains("Observed TPS 10s")) {
                throw new AssertionError("Live diagnostics missing timing or publisher fields");
            }
            MinecraftCompat.setScreen(client, new PublisherNameScreen(null, "Babbur",
                    Component.translatable("waypointer.screen.route_publish.error.publisher_name_taken"), name -> { }));
            return true;
        });
        context.waitTicks(2);
        context.takeScreenshot("publisher-name-error");
        context.computeOnClient(client -> {
            MinecraftCompat.setScreen(client, null);
            return true;
        });
    }
}
