package com.babbur.waypointer.chat;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Prevents Waypointer's own messages from triggering coordinate actions. */
public final class WaypointerChatFeedback {

    private static final int MAX_SUPPRESSED_MESSAGES = 16;
    private static final long SUPPRESSION_TTL_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final List<SuppressedMessage> suppressedMessages = new ArrayList<>();

    private WaypointerChatFeedback() {
    }

    public static Component suppress(Component message) {
        if (message == null) return message;

        String text = message.getString();
        if (!CoordScanner.scan(text).isEmpty()) {
            remember(text, System.nanoTime());
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

            for (int i = 0; i < suppressedMessages.size(); i++) {
                if (!suppressedMessages.get(i).text().equals(text)) continue;

                suppressedMessages.remove(i);
                return true;
            }
        }

        return false;
    }

    static void clearForTests() {
        synchronized (suppressedMessages) {
            suppressedMessages.clear();
        }
    }

    private static void remember(String text, long now) {
        if (text == null || text.isEmpty()) return;

        synchronized (suppressedMessages) {
            pruneExpired(now);
            if (suppressedMessages.size() >= MAX_SUPPRESSED_MESSAGES) {
                suppressedMessages.remove(0);
            }
            suppressedMessages.add(new SuppressedMessage(text, now + SUPPRESSION_TTL_NANOS));
        }
    }

    private static void pruneExpired(long now) {
        suppressedMessages.removeIf(message -> message.expiresAtNanos() <= now);
    }

    private record SuppressedMessage(String text, long expiresAtNanos) {
    }
}
