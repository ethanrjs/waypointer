package com.babbur.waypointer.dungeon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonTriggerDetectorTest {

    @Test
    void secretItemsAreRecognisedByNameButAnEmptiedStackIsNot() {
        assertTrue(DungeonTriggerDetector.isSecretItemName("Decoy"));
        assertTrue(DungeonTriggerDetector.isSecretItemName("Healing VIII Splash Potion"));
        assertFalse(DungeonTriggerDetector.isSecretItemName("Air"),
                "a picked-up stack is emptied before the entity unloads, so its name cannot be trusted then");
        assertFalse(DungeonTriggerDetector.isSecretItemName(""));
    }
}
