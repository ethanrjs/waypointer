package dev.ethan.waypointer.chat;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Client-side wiring for {@link CodecSendGuard}.
 *
 * Registers a Fabric allow-command hook that runs {@link CodecSendGuard#inspect}
 * on every outbound command and, when the decision is {@code block}, cancels
 * the send and prints an inline chat message explaining what happened. The
 * explanation matters: Watchdog disconnects are silent from the user's POV,
 * so without a visible reason the user would see the guard as "chat randomly
 * ate my command" and lose trust in the mod.
 *
 * This installer exists as a thin adapter so {@link CodecSendGuard} can stay
 * in the main source set (and thus in unit tests) without growing Minecraft
 * imports.
 */
public final class ClientCodecSendGuard {

    private ClientCodecSendGuard() {}

    public static void install() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(ClientCodecSendGuard::onAllowCommand);
    }

    private static boolean onAllowCommand(String command) {
        CodecSendGuard.Decision d = CodecSendGuard.inspect(command);
        if (!d.shouldBlock()) return true;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(buildWarning(d), false);
        }
        return false;
    }

    /**
     * Build the cancel-notification chat line. Kept separate from the hook
     * body so the formatting choices (colors, verb echo, byte math phrasing)
     * can be tuned without touching the registration path.
     */
    private static Component buildWarning(CodecSendGuard.Decision d) {
        MutableComponent prefix = Component.literal("[Waypointer] ").withStyle(ChatFormatting.AQUA);
        MutableComponent body = Component.literal(
                "Blocked your /" + d.commandLeader() + " -- it would have disconnected you. "
                + "The command was " + d.wireBytes() + " B (cap is "
                + CodecSendGuard.COMMAND_WIRE_LIMIT_BYTES + " B, over by "
                + d.overByBytes() + " B). "
                + "Shrink the codec by stripping toggles in the export screen, "
                + "or share via a channel other than a chat command."
        ).withStyle(ChatFormatting.RED);
        return prefix.append(body);
    }
}
