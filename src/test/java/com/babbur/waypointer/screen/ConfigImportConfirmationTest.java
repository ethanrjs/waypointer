package com.babbur.waypointer.screen;

import com.babbur.waypointer.config.WaypointerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigImportConfirmationTest {

    @Test
    void cancelIsANoOpAndConfirmIsTheOnlyMutationGate() {
        WaypointerConfig current = new WaypointerConfig();
        WaypointerConfig imported = new WaypointerConfig();
        imported.setShowTracer(false);

        ConfigImportConfirmation.Outcome cancelled =
                ConfigImportConfirmation.complete(current, imported, false);
        assertFalse(cancelled.confirmed());
        assertEquals(1, cancelled.changedSettings());
        assertTrue(current.showTracer(), "cancel must not mutate live settings");

        ConfigImportConfirmation.Outcome confirmed =
                ConfigImportConfirmation.complete(current, imported, true);
        assertTrue(confirmed.confirmed());
        assertEquals(1, confirmed.changedSettings());
        assertFalse(current.showTracer(), "affirmative confirmation applies settings");
    }
}
