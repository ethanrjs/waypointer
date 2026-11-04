package com.babbur.waypointer.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrystalHollowsMinimapEditorScreenTest {
    @Test
    void scrollingUsesExactFiveHundredthStepsAndHonorsBounds() {
        double scale = 1;
        for (int i = 0; i < 10; i++) scale = CrystalHollowsMinimapEditorScreen.scrolledScale(scale, 1);
        assertEquals(1.5, scale);
        for (int i = 0; i < 10; i++) scale = CrystalHollowsMinimapEditorScreen.scrolledScale(scale, -1);
        assertEquals(1, scale);
        assertEquals(0.5, CrystalHollowsMinimapEditorScreen.scrolledScale(0.5, -1));
        assertEquals(2, CrystalHollowsMinimapEditorScreen.scrolledScale(2, 1));
        assertEquals(1, CrystalHollowsMinimapEditorScreen.scrolledScale(1, Double.NaN));
    }

    @Test
    void snapsEachAxisToEdgesOrCenterOnlyInsideEightPixelThreshold() {
        assertEquals(0, CrystalHollowsMinimapEditorScreen.snapPosition(0.08, 100, true));
        assertEquals(0.5, CrystalHollowsMinimapEditorScreen.snapPosition(0.49, 100, true));
        assertEquals(1, CrystalHollowsMinimapEditorScreen.snapPosition(0.98, 100, true));
        assertEquals(0.3, CrystalHollowsMinimapEditorScreen.snapPosition(0.3, 100, true));
        assertEquals(0.49, CrystalHollowsMinimapEditorScreen.snapPosition(0.49, 100, false));
        assertEquals(0, CrystalHollowsMinimapEditorScreen.snapPosition(-0.1, 100, false));
        assertEquals(1, CrystalHollowsMinimapEditorScreen.snapPosition(1.1, 100, false));
    }
}
