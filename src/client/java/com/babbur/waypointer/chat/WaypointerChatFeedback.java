package com.babbur.waypointer.chat;

import com.babbur.waypointer.crystal.CrystalHollowsChatParser;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Prevents Waypointer's own messages from triggering coordinate actions. */
public final class WaypointerChatFeedback {

    private static final int MAX_SUPPRESSED_MESSAGES = 16;
    private static final long SUPPRESSION_TTL_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final List<SuppressedMessage> suppressedMessages = new ArrayList<>();
    private static final Map<String, Long> forwardedEchoes = new HashMap<>();

    private WaypointerChatFeedback() {
    }

    public static Component suppress(Component message) {
        return suppress(message, null);
    }

    public static Component suppressOutgoing(Component message, String sender) {
        return suppress(message, sender);
    }

    private static Component suppress(Component message, String sender) {
        if (message == null) return message;

        String text = message.getString();
        if (!CoordScanner.scan(text).isEmpty()) {
            remember(text, sender, System.nanoTime());
        }
        return message;
    }

    public static boolean consumeIfSuppressed(Component message) {
        if (message == null) return false;
        String text = message.getString();
        if (text.isEmpty()) return false;
        synchronized (suppressedMessages) {
            long now = System.nanoTime();
            pruneExpired(now);
            if (forwardedEchoes.remove(text) != null) return true;
            for (int i = 0; i < suppressedMessages.size(); i++) {
                SuppressedMessage suppressed = suppressedMessages.get(i);
                if (!matches(text, suppressed)) continue;
                suppressedMessages.remove(i);
                if (!text.equals(suppressed.text())) {
                    if (forwardedEchoes.size() >= MAX_SUPPRESSED_MESSAGES) {
                        forwardedEchoes.clear();
                    }
                    forwardedEchoes.put(text, suppressed.expiresAtNanos());
                }
                return true;
            }
        }
        return false;
    }

    static void clearForTests() {
        synchronized (suppressedMessages) {
            suppressedMessages.clear();
            forwardedEchoes.clear();
        }
    }

    private static void remember(String text, String sender, long now) {
        if (text == null || text.isEmpty()) return;

        synchronized (suppressedMessages) {
            pruneExpired(now);
            if (suppressedMessages.size() >= MAX_SUPPRESSED_MESSAGES) {
                suppressedMessages.remove(0);
            }
            suppressedMessages.add(new SuppressedMessage(
                    text, sender, now + SUPPRESSION_TTL_NANOS));
        }
    }

    private static void pruneExpired(long now) {
        suppressedMessages.removeIf(message -> message.expiresAtNanos() <= now);
        forwardedEchoes.values().removeIf(expiresAt -> expiresAt <= now);
    }

    private static boolean matches(String received, SuppressedMessage suppressed) {
        if (received.equals(suppressed.text())) return true;
        if (!received.endsWith(": " + suppressed.text())) return false;
        return suppressed.sender() == null
                || CrystalHollowsChatParser.playerChat(received)
                        .map(chat -> chat.sender().equalsIgnoreCase(suppressed.sender()))
                        .orElse(false);
    }

    private record SuppressedMessage(String text, String sender, long expiresAtNanos) {
    }
}
