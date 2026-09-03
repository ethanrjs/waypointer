package com.babbur.waypointer.render;

import com.babbur.waypointer.core.WaypointPaint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
