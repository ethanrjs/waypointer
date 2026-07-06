package dev.ethan.waypointer.chat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatImportCacheTest {

    @Test
    void handlesExpireAfterConfiguredTtl() {
        AtomicLong now = new AtomicLong(1_000L);
        ChatImportCache cache = new ChatImportCache(now::get, 500L);

        String handle = cache.put("WP:first");

        assertEquals("WP:first", cache.get(handle));
        now.addAndGet(500L);
        assertNull(cache.get(handle));
        assertEquals(0, cache.size());
    }

    @Test
    void handlesRemainLiveBeforeTtlAndDisappearFromSuggestionsAfterExpiry() {
        AtomicLong now = new AtomicLong(10_000L);
        ChatImportCache cache = new ChatImportCache(now::get, 1_000L);

        String first = cache.put("WP:first");
        now.addAndGet(999L);
        String second = cache.put("WP:second");

        assertEquals(2, cache.size());
        assertEquals(first, cache.handles().get(0));
        now.addAndGet(1L);

        assertNull(cache.get(first));
        assertEquals("WP:second", cache.get(second));
        assertEquals(1, cache.handles().size());
        assertEquals(second, cache.handles().get(0));
    }
}
