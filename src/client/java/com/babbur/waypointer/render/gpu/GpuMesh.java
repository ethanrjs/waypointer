package com.babbur.waypointer.render.gpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** GPU-resident copy of one mesh. */
public final class GpuMesh implements AutoCloseable {

    private final String label;
    private final MeshTopology topology;
    private final boolean sorted;
    private GpuBuffer vertexBuffer;
    private GpuBuffer indexBuffer;
    private int capacityBytes;
    private int indexCapacityBytes;
    private int storedVertices;
    private float[] quadCenters = new float[0];
    private int[] quadOrder = new int[0];
    private int[] sortScratch = new int[0];
    private double[] quadDistances = new double[0];
    private ByteBuffer indexBytes = allocate(24);
    private boolean sortDirty;
    private double sortedCameraX = Double.NaN;
    private double sortedCameraY = Double.NaN;
    private double sortedCameraZ = Double.NaN;

    public GpuMesh(String label, MeshTopology topology, boolean sorted) {
        this.label = Objects.requireNonNull(label, "label");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.sorted = sorted;
        if (sorted && topology != MeshTopology.QUADS) {
            throw new IllegalArgumentException("Only quad meshes can be sorted");
        }
    }

    public boolean isEmpty() {
        return storedVertices == 0;
    }

    public int indexCount() {
        return topology.indexCountFor(storedVertices);
    }

    /** Uploads the mesh and returns the byte count. */
    public int upload(MeshBuilder builder, CommandEncoder encoder) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(encoder, "encoder");
        storedVertices = builder.storedVertexCount();
        int bytes = builder.byteSize();
        if (bytes == 0) return 0;
        if (vertexBuffer == null || capacityBytes < bytes) {
            int previousCapacity = capacityBytes;
            release();
            capacityBytes = Math.max(bytes, Math.max(4096, previousCapacity * 2));
            vertexBuffer = GpuMeshCompat.createVertexBuffer(() -> "Waypointer " + label, capacityBytes);
        }
        ByteBuffer payload = builder.bytes();
        if (sorted) captureQuadCenters(builder, payload);
        GpuMeshCompat.upload(encoder, vertexBuffer, payload);
        return bytes;
    }

    public int prepareIndices(CommandEncoder encoder,
                              double cameraX, double cameraY, double cameraZ) {
        Objects.requireNonNull(encoder, "encoder");
        if (!sorted || isEmpty()) return 0;
        if (!sortDirty && cameraX == sortedCameraX && cameraY == sortedCameraY
                && cameraZ == sortedCameraZ) return 0;

        int quads = storedVertices / 4;
        if (quads == 0) return 0;
        ensureSortCapacity(quads);
        boolean changed = QuadSorter.sortBackToFront(quadCenters, quadOrder, quadDistances, sortScratch,
                quads, !sortDirty, cameraX, cameraY, cameraZ);
        sortedCameraX = cameraX;
        sortedCameraY = cameraY;
        sortedCameraZ = cameraZ;
        if (!changed) return 0;

        int bytes = quads * 6 * Integer.BYTES;
        ensureIndexCapacity(bytes);
        indexBytes.clear();
        for (int i = 0; i < quads; i++) {
            int base = quadOrder[i] * 4;
            indexBytes.putInt(base).putInt(base + 1).putInt(base + 2)
                    .putInt(base + 2).putInt(base + 3).putInt(base);
        }
        indexBytes.flip();
        GpuMeshCompat.upload(encoder, indexBuffer, indexBytes);
        sortDirty = false;
        return bytes;
    }

    public void draw(RenderPass pass) {
        if (isEmpty() || vertexBuffer == null) return;
        int indexCount = indexCount();
        if (indexCount == 0) return;
        GpuMeshCompat.bindVertexBuffer(pass, vertexBuffer);
        if (sorted && indexBuffer != null) {
            GpuMeshCompat.bindIndexBuffer(pass, indexBuffer);
        } else {
            GpuMeshCompat.bindSequentialIndexBuffer(pass, topology, indexCount);
        }
        GpuMeshCompat.drawIndexed(pass, 0, indexCount);
    }

    private void captureQuadCenters(MeshBuilder builder, ByteBuffer payload) {
        int quads = storedVertices / 4;
        ensureSortCapacity(quads);
        int stride = builder.layout().stride();
        int position = builder.layout().offsetOf(MeshLayout.Attribute.POSITION);
        for (int quad = 0; quad < quads; quad++) {
            float x = 0.0f;
            float y = 0.0f;
            float z = 0.0f;
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = (quad * 4 + vertex) * stride + position;
                x += payload.getFloat(offset);
                y += payload.getFloat(offset + 4);
                z += payload.getFloat(offset + 8);
            }
            int center = quad * 3;
            quadCenters[center] = x * 0.25f;
            quadCenters[center + 1] = y * 0.25f;
            quadCenters[center + 2] = z * 0.25f;
        }
        sortDirty = true;
    }

    private void ensureSortCapacity(int quads) {
        if (quadOrder.length >= quads) return;
        int capacity = Math.max(quads, Math.max(16, quadOrder.length * 2));
        quadOrder = new int[capacity];
        sortScratch = new int[capacity];
        quadDistances = new double[capacity];
        quadCenters = new float[capacity * 3];
    }

    private void ensureIndexCapacity(int bytes) {
        if (indexBuffer != null && indexCapacityBytes >= bytes) return;
        if (indexBuffer != null) indexBuffer.close();
        int previous = indexCapacityBytes;
        indexCapacityBytes = Math.max(bytes, Math.max(4096, previous * 2));
        indexBuffer = GpuMeshCompat.createIndexBuffer(
                () -> "Waypointer " + label + " indices", indexCapacityBytes);
        indexBytes = allocate(indexCapacityBytes);
    }

    private void release() {
        Throwable failure = null;
        if (vertexBuffer != null) {
            try {
                vertexBuffer.close();
            } catch (RuntimeException | LinkageError closeFailure) {
                failure = closeFailure;
            }
            vertexBuffer = null;
        }
        if (indexBuffer != null) {
            try {
                indexBuffer.close();
            } catch (RuntimeException | LinkageError closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
            indexBuffer = null;
        }
        capacityBytes = 0;
        indexCapacityBytes = 0;
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof LinkageError linkageFailure) throw linkageFailure;
    }

    @Override
    public void close() {
        release();
        storedVertices = 0;
        quadCenters = new float[0];
        quadOrder = new int[0];
        sortScratch = new int[0];
        quadDistances = new double[0];
        indexBytes = allocate(24);
        sortDirty = false;
    }

    private static ByteBuffer allocate(int bytes) {
        return ByteBuffer.allocateDirect(Math.max(bytes, 24)).order(ByteOrder.nativeOrder());
    }
}
