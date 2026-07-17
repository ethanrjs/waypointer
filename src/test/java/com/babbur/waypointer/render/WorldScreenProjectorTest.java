package com.babbur.waypointer.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldScreenProjectorTest {

    @Test
    void identityCameraEffectsPreserveProjection() {
        Matrix4f projection = new Matrix4f().scale(2.0f, 3.0f, 1.0f);
        Matrix4f composed = WorldScreenProjector.composeViewProjection(
                projection, new Matrix4f(), new Matrix4f(), new Matrix4f());
        Vector3f point = composed.transformProject(new Vector3f(0.25f, 0.5f, 0.0f));

        assertEquals(0.5f, point.x, 0.0001f);
        assertEquals(1.5f, point.y, 0.0001f);
    }

    @Test
    void hurtTiltMovesProjectedLabelWithTheWorld() {
        Matrix4f hurtTilt = new Matrix4f().rotateZ((float) Math.toRadians(15.0));
        Matrix4f composed = WorldScreenProjector.composeViewProjection(
                new Matrix4f(), hurtTilt, new Matrix4f(), new Matrix4f());
        Vector3f point = composed.transformProject(new Vector3f(1.0f, 0.0f, 0.0f));

        assertTrue(Math.abs(point.y) > 0.2f);
    }

    @Test
    void compositionOrderMatchesProjectionThenEffectsThenView() {
        Matrix4f projection = new Matrix4f().scale(2.0f, 1.0f, 1.0f);
        Matrix4f effects = new Matrix4f().translate(1.0f, 0.0f, 0.0f);
        Matrix4f composed = WorldScreenProjector.composeViewProjection(
                projection, effects, new Matrix4f(), new Matrix4f());
        Vector3f point = composed.transformProject(new Vector3f());

        assertEquals(2.0f, point.x, 0.0001f);
    }

    @Test
    void behindCameraRejectionRemainsStrict() {
        assertTrue(WorldScreenProjector.isInFront(0.0, 0.0, 2.0,
                0.0f, 0.0f, 1.0f));
        assertFalse(WorldScreenProjector.isInFront(0.0, 0.0, -0.01,
                0.0f, 0.0f, 1.0f));
        assertFalse(WorldScreenProjector.isInFront(0.0, 0.0, 0.0,
                0.0f, 0.0f, 1.0f));
    }
}
