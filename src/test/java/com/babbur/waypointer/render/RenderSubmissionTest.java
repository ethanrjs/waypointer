package com.babbur.waypointer.render;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderSubmissionTest {

    @Test
    void geometryContractInvokesTheSubmittedEmitter() {
        AtomicBoolean emitted = new AtomicBoolean();
        RenderSubmission.Geometry geometry = (vertices, pose) -> emitted.set(true);

        geometry.emit(null, null);

        assertTrue(emitted.get());
    }
}
