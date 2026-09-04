package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CrystalHollowsDetectionPolicyTest {

    @Test
    void suppressesEntityScansDuringThePostTransitionDelay() {
        assertFalse(CrystalHollowsDetectionPolicy.shouldScanEntities(true, 50));
        assertFalse(CrystalHollowsDetectionPolicy.shouldScanEntities(true, 1));
        assertTrue(CrystalHollowsDetectionPolicy.shouldScanEntities(true, 0));
    }

    @Test
    void honorsTheEntityDetectionSettingAfterTheDelay() {
        assertFalse(CrystalHollowsDetectionPolicy.shouldScanEntities(false, 0));
        assertTrue(CrystalHollowsDetectionPolicy.shouldScanEntities(true, 0));
    }
}
