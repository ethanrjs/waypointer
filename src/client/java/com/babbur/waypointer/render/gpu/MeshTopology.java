package com.babbur.waypointer.render.gpu;

/** Version-neutral primitive topology. */
public enum MeshTopology {

    LINES(2, 4, 6),

    QUADS(4, 4, 6);

    private final int logicalVerticesPerPrimitive;
    private final int storedVerticesPerPrimitive;
    private final int indicesPerPrimitive;

    MeshTopology(int logicalVerticesPerPrimitive, int storedVerticesPerPrimitive,
                 int indicesPerPrimitive) {
        this.logicalVerticesPerPrimitive = logicalVerticesPerPrimitive;
        this.storedVerticesPerPrimitive = storedVerticesPerPrimitive;
        this.indicesPerPrimitive = indicesPerPrimitive;
    }

    public int logicalVerticesPerPrimitive() {
        return logicalVerticesPerPrimitive;
    }

    public int storedVerticesPerPrimitive() {
        return storedVerticesPerPrimitive;
    }

    public int indicesPerPrimitive() {
        return indicesPerPrimitive;
    }

    public boolean duplicatesVertices() {
        return storedVerticesPerPrimitive != logicalVerticesPerPrimitive;
    }

    public int indexCountFor(int storedVertexCount) {
        if (storedVertexCount <= 0) return 0;
        int primitives = storedVertexCount / storedVerticesPerPrimitive;
        return primitives * indicesPerPrimitive;
    }
}
