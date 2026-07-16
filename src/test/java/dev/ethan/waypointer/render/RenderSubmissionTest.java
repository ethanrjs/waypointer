package dev.ethan.waypointer.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderSubmissionTest {

    @Test
    void currentMinecraftExposesASupportedWorldRenderBackend() {
        assertTrue(RenderSubmission.requiredBindingsAvailable());
    }
}
