package com.babbur.waypointer.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.babbur.waypointer.config.WaypointerConfig;
import com.babbur.waypointer.crystal.compass.WishingCompassSolver;
import org.junit.jupiter.api.Test;

class WishingCompassControllerTest {

    @Test
    void disablingSolverClearsAnInProgressCaptureOnTheNextTick() {
        WaypointerConfig config = new WaypointerConfig();
        WishingCompassController controller = new WishingCompassController(null, config);
        controller.solver().onUse(300, 100, 300, CrystalHollowsZone.JUNGLE, 100);
        assertEquals(WishingCompassSolver.State.WAITING_PARTICLES,
                controller.solver().state());

        config.setCrystalHollowsWishingCompassSolver(false);
        controller.tick(101);

        assertEquals(WishingCompassSolver.State.IDLE, controller.solver().state());
        assertEquals("reset", controller.lastEvent());
    }
}
