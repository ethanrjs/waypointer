package com.babbur.waypointer.render.gpu;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshBucketTest {

    @Test
    void texturedKindsRequireATextureAndUntexturedKindsRejectOne() {
        Identifier texture = Identifier.fromNamespaceAndPath("waypointer", "paint");
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> MeshBucket.untextured(MeshBucket.Kind.BEAM, false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> MeshBucket.textured(MeshBucket.Kind.LINES, false, texture)),
                () -> assertEquals(texture, MeshBucket.textured(MeshBucket.Kind.PAINTED, true, texture).texture()));
    }

    @Test
    void drawOrderMatchesLegacyKindThenDepthSequence() {
        MeshBucket beamThrough = MeshBucket.textured(MeshBucket.Kind.BEAM, false,
                Identifier.fromNamespaceAndPath("waypointer", "beam"));
        MeshBucket beamDepth = MeshBucket.textured(MeshBucket.Kind.BEAM, true,
                Identifier.fromNamespaceAndPath("waypointer", "beam"));
        MeshBucket linesThrough = MeshBucket.untextured(MeshBucket.Kind.LINES, false);
        MeshBucket quadsDepth = MeshBucket.untextured(MeshBucket.Kind.QUADS, true);
        assertTrue(beamThrough.drawOrder() < beamDepth.drawOrder());
        assertTrue(beamDepth.drawOrder() < quadsDepth.drawOrder());
        assertTrue(quadsDepth.drawOrder() < linesThrough.drawOrder());
        assertEquals(MeshTopology.LINES, linesThrough.topology());
    }

    @Test
    void irisProgramMappingCoversEveryKind() {
        for (MeshBucket.Kind kind : MeshBucket.Kind.values()) {
            assertEquals(kind == MeshBucket.Kind.LINES ? IrisBridge.Program.LINES
                    : kind == MeshBucket.Kind.QUADS ? IrisBridge.Program.BASIC
                    : IrisBridge.Program.BEACON_BEAM, OverlayPipelines.irisProgramFor(kind));
        }
    }
}
