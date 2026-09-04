package com.babbur.waypointer.render;

import com.babbur.waypointer.core.WaypointPaint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointPaintTextureCacheTest {

    @Test
    void retainedPaintSelectionUsesDeterministicOverflowFallback() {
        List<WaypointPaint> paints = new ArrayList<>();
        for (int i = 0; i < 257; i++) paints.add(WaypointPaint.solid(i));
        LinkedHashSet<WaypointPaint> selected = new LinkedHashSet<>();

        int overflow = WaypointPaintTextureCache.selectRetainedPaints(paints, selected);

        assertEquals(1, overflow);
        assertEquals(WaypointPaintTextureCache.MAX_RETAINED_ACTIVE_TEXTURES, selected.size());
        assertTrue(selected.contains(paints.get(255)));
        assertFalse(selected.contains(paints.get(256)));
    }

    @Test
    void evictionHasNoCandidateWhenEveryEntryIsReserved() {
        LinkedHashMap<String, Resource> cache = new LinkedHashMap<>(16, 0.75f, true);
        cache.put("A1", new Resource("A1"));
        cache.put("A2", new Resource("A2"));
        LinkedHashSet<String> retained = new LinkedHashSet<>(List.of("A1", "A2"));

        assertNull(WaypointPaintTextureCache.selectEvictionEntry(cache, retained));
    }

    @Test
    void fullRetainedCacheCreatesOnlyTheNewPaintAndKeepsActiveResourcesAlive() {
        LinkedHashMap<String, Resource> cache = new LinkedHashMap<>(16, 0.75f, true);
        LinkedHashSet<String> retained = new LinkedHashSet<>();
        Map<String, Resource> created = new LinkedHashMap<>();
        List<Resource> released = new ArrayList<>();
        AtomicInteger creations = new AtomicInteger();

        for (int i = 1; i <= WaypointPaintTextureCache.MAX_RETAINED_ACTIVE_TEXTURES; i++) {
            String paint = paintName(i);
            Resource resource = create(paint, created, creations);
            cache.put(paint, resource);
            retained.add(paint);
        }
        assertEquals(256, creations.get());

        retained.remove(paintName(256));
        retained.add("N");
        putAndEvict(cache, retained, "N", created, creations, released,
                WaypointPaintTextureCache.MAX_RETAINED_ACTIVE_TEXTURES);

        List<String> active = new ArrayList<>();
        active.add("N");
        for (int i = 1; i < 256; i++) active.add(paintName(i));
        for (String paint : active) {
            Resource resource = cache.get(paint);
            if (resource == null) {
                putAndEvict(cache, retained, paint, created, creations, released,
                        WaypointPaintTextureCache.MAX_RETAINED_ACTIVE_TEXTURES);
            }
        }

        assertEquals(257, creations.get(), "the new paint should be the only extra bake");
        assertEquals(List.of("A256"), released.stream().map(Resource::paint).toList());
        assertEquals(WaypointPaintTextureCache.MAX_RETAINED_ACTIVE_TEXTURES, cache.size());
        for (int i = 1; i < 256; i++) {
            assertSame(created.get(paintName(i)), cache.get(paintName(i)));
        }
        assertSame(created.get("N"), cache.get("N"));
    }

    @Test
    void shrinkingAndResettingReservationReleasesOnlyEntriesThatAreNoLongerProtected() {
        LinkedHashMap<String, Resource> cache = new LinkedHashMap<>(16, 0.75f, true);
        LinkedHashSet<String> retained = new LinkedHashSet<>();
        List<Resource> released = new ArrayList<>();

        for (int i = 1; i <= WaypointPaintTextureCache.MAX_RETAINED_ACTIVE_TEXTURES; i++) {
            String paint = paintName(i);
            cache.put(paint, new Resource(paint));
            retained.add(paint);
        }

        for (int i = 129; i <= WaypointPaintTextureCache.MAX_RETAINED_ACTIVE_TEXTURES; i++) {
            retained.remove(paintName(i));
        }
        evictTo(cache, retained, 128, released);

        assertEquals(128, cache.size());
        assertEquals(128, released.size());
        assertEquals("A129", released.getFirst().paint());
        assertEquals("A256", released.getLast().paint());
        for (int i = 1; i <= 128; i++) assertTrue(cache.containsKey(paintName(i)));

        retained.clear();
        evictTo(cache, retained, 64, released);

        assertEquals(64, cache.size());
        assertEquals(192, released.size());
        assertEquals("A1", released.get(128).paint());
        assertEquals("A64", released.getLast().paint());
        for (int i = 65; i <= 128; i++) assertTrue(cache.containsKey(paintName(i)));
    }

    private static String paintName(int index) {
        return "A" + index;
    }

    private static Resource create(String paint, Map<String, Resource> created,
                                   AtomicInteger creations) {
        Resource resource = new Resource(paint);
        created.put(paint, resource);
        creations.incrementAndGet();
        return resource;
    }

    private static void putAndEvict(Map<String, Resource> cache, Set<String> retained,
                                    String paint, Map<String, Resource> created,
                                    AtomicInteger creations, List<Resource> released,
                                    int capacity) {
        cache.put(paint, create(paint, created, creations));
        evictTo(cache, retained, capacity, released);
    }

    private static void evictTo(Map<String, Resource> cache, Set<String> retained,
                                int capacity, List<Resource> released) {
        while (cache.size() > capacity) {
            Map.Entry<String, Resource> eviction =
                    WaypointPaintTextureCache.selectEvictionEntry(cache, retained);
            assertNotNull(eviction);
            Resource resource = eviction.getValue();
            cache.remove(eviction.getKey());
            released.add(resource);
        }
    }

    private record Resource(String paint) {}
}
