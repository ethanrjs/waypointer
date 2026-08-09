package com.babbur.waypointer.input;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaypointEditPickerTest {

    private static final AABB BOX = new AABB(5.0, -1.0, -1.0, 6.0, 1.0, 1.0);

    @Test
    void rayBoxDistanceFindsHitsAndRejectsMisses() {
        Vec3 origin = Vec3.ZERO;

        assertEquals(5.0, WaypointEditPicker.rayBoxDistance(
                origin, new Vec3(1.0, 0.0, 0.0), BOX, 10.0));
        assertEquals(-1.0, WaypointEditPicker.rayBoxDistance(
                origin, new Vec3(0.0, 1.0, 0.0), BOX, 10.0));
        assertEquals(-1.0, WaypointEditPicker.rayBoxDistance(
                origin, new Vec3(1.0, 0.0, 0.0), BOX, 4.0));
    }

    @Test
    void rayStartingInsideTheBoxHasZeroDistance() {
        assertEquals(0.0, WaypointEditPicker.rayBoxDistance(
                new Vec3(5.5, 0.0, 0.0),
                new Vec3(1.0, 0.0, 0.0),
                BOX,
                10.0));
    }
}
