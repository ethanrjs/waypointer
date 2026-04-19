package dev.ethan.waypointer.chat;

import dev.ethan.waypointer.codec.WaypointCodec;

import java.nio.charset.StandardCharsets;

/**
 * Pure decision: does this chat command carry a Waypointer codec that would
 * disconnect the sender on delivery?
 *
 * Why this exists: Hypixel's Watchdog closes the TCP socket the instant a
 * {@code ServerboundChatCommandPacket} serialises past {@value #COMMAND_WIRE_LIMIT_BYTES}
 * UTF-8 bytes, with no kick reason. The client doesn't enforce the cap, so the
 * connection just dies. Even with the v2 base-84 alphabet (1 UTF-8 byte per
 * char) a sufficiently long route still trips the 256-byte cap once framed
 * by a command prefix, so we keep the guard in place regardless of which
 * alphabet the codec uses. This class owns the "would that send kill us?"
 * question; the client-side installer wires it to Fabric's allow-command hook
 * and handles the cancel + chat warning.
 *
 * Keeping the decision here (no Minecraft imports) means it lives in the main
 * source set and stays unit-testable without bootstrapping a client.
 *
 * Scope:
 *
 *   - Only commands are inspected. Regular chat input is already capped at
 *     256 characters by the textbox, which is a safe over-estimate for
 *     vanilla-server chat payloads; the specific disconnect we're defending
 *     against is the command-packet path.
 *   - Only commands that contain the {@link WaypointCodec#MAGIC} prefix are
 *     considered. We deliberately do NOT police every oversized command --
 *     other mods or pasteable macros may produce long commands the server
 *     does accept, and silently cancelling them would look like a bug in
 *     Waypointer rather than a safety net.
 */
public final class CodecSendGuard {

    /**
     * Serverbound command-packet byte ceiling. Strings at {@code <= 256}
     * UTF-8 bytes deliver; strings past it sever the connection with no kick
     * message. Vanilla clients don't apply this check themselves.
     */
    public static final int COMMAND_WIRE_LIMIT_BYTES = 256;

    private CodecSendGuard() {}

    /**
     * Outcome of inspecting a candidate command string. Exposes enough state
     * to render a user-facing warning (byte count, overage, command verb)
     * without pulling any chat formatting into the decision layer.
     *
     * The {@link #commandLeader} is the first whitespace-delimited token of
     * the command (e.g. {@code "pc"}, {@code "msg"}) -- the verb we want to
     * echo back in the warning so the user knows what was cancelled, without
     * quoting the whole codec body at them.
     */
    public record Decision(boolean shouldBlock, int wireBytes, int overByBytes, String commandLeader) {

        public static Decision allow() { return new Decision(false, 0, 0, ""); }

        public static Decision block(int wireBytes, String commandLeader) {
            return new Decision(true, wireBytes, wireBytes - COMMAND_WIRE_LIMIT_BYTES, commandLeader);
        }
    }

    /**
     * Decide whether to cancel {@code command}, the payload as the server
     * will see it (no leading slash).
     *
     * Short-circuits on the cheap containment check first so non-codec
     * commands pay almost nothing on the send hot path -- this fires for
     * every {@code /<anything>} the user types, not just ours.
     */
    public static Decision inspect(String command) {
        if (command == null || command.isEmpty()) return Decision.allow();
        if (!command.contains(WaypointCodec.MAGIC)) return Decision.allow();

        int wireBytes = command.getBytes(StandardCharsets.UTF_8).length;
        if (wireBytes <= COMMAND_WIRE_LIMIT_BYTES) return Decision.allow();

        return Decision.block(wireBytes, leadingWord(command));
    }

    private static String leadingWord(String command) {
        int sp = command.indexOf(' ');
        return sp < 0 ? command : command.substring(0, sp);
    }
}
