package com.babbur.waypointer.crystal;

import java.util.List;
import java.util.Objects;

/** One lobby-scoped observation of a structure or compass target. */
public record StructureSighting(
        CrystalHollowsStructure structure,
        int x,
        int y,
        int z,
        SightingConfidence confidence,
        String source,
        long atMillis,
        List<CrystalHollowsStructure> candidates,
        String note,
        SightingConfidence remoteEvidence) {

    public StructureSighting {
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(confidence, "confidence");
        source = source == null ? "" : source;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        note = note == null ? "" : note;
        if (remoteEvidence != null && (confidence != SightingConfidence.SHARED_REMOTE
                || remoteEvidence != SightingConfidence.ENTITY
                && remoteEvidence != SightingConfidence.COMPASS
                && remoteEvidence != SightingConfidence.NPC_CHAT)) {
            throw new IllegalArgumentException("Invalid remote evidence");
        }
    }

    public StructureSighting(CrystalHollowsStructure structure, int x, int y, int z,
                             SightingConfidence confidence, String source, long atMillis,
                             List<CrystalHollowsStructure> candidates, String note) {
        this(structure, x, y, z, confidence, source, atMillis, candidates, note, null);
    }

    public StructureSighting(CrystalHollowsStructure structure, int x, int y, int z,
                             SightingConfidence confidence, String source, long atMillis) {
        this(structure, x, y, z, confidence, source, atMillis, List.of(), "");
    }

    public CrystalHollowsPosition position() {
        return new CrystalHollowsPosition(x, y, z);
    }

    public StructureSighting withNote(String nextNote) {
        return new StructureSighting(structure, x, y, z, confidence, source, atMillis,
                candidates, nextNote, remoteEvidence);
    }
}
