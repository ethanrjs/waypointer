package com.babbur.waypointer.render.gpu;

import net.minecraft.resources.Identifier;

import java.util.Objects;

/** Pipeline, depth mode, and optional texture for one mesh pair. */
public record MeshBucket(Kind kind, boolean depthTested, Identifier texture) {

    public enum Kind {
        BEAM(MeshTopology.QUADS, true, true),
        PAINTED(MeshTopology.QUADS, true, true),
        QUADS(MeshTopology.QUADS, false, false),
        LINES(MeshTopology.LINES, false, false);

        private final MeshTopology topology;
        private final boolean textured;
        private final boolean sorted;

        Kind(MeshTopology topology, boolean textured, boolean sorted) {
            this.topology = topology;
            this.textured = textured;
            this.sorted = sorted;
        }

        public MeshTopology topology() {
            return topology;
        }

        public boolean textured() {
            return textured;
        }

        public boolean sorted() {
            return sorted;
        }
    }

    public MeshBucket {
        Objects.requireNonNull(kind, "kind");
        if (kind.textured() && texture == null) {
            throw new IllegalArgumentException(kind + " requires a texture");
        }
        if (!kind.textured() && texture != null) {
            throw new IllegalArgumentException(kind + " does not take a texture");
        }
    }

    public static MeshBucket untextured(Kind kind, boolean depthTested) {
        return new MeshBucket(kind, depthTested, null);
    }

    public static MeshBucket textured(Kind kind, boolean depthTested, Identifier texture) {
        return new MeshBucket(kind, depthTested, Objects.requireNonNull(texture, "texture"));
    }

    public MeshTopology topology() {
        return kind.topology();
    }

    public boolean sorted() {
        return kind.sorted();
    }

    public int drawOrder() {
        return kind.ordinal() * 2 + (depthTested ? 1 : 0);
    }
}
