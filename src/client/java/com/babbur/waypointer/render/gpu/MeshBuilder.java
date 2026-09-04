package com.babbur.waypointer.render.gpu;

import com.mojang.blaze3d.vertex.VertexConsumer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** CPU vertex buffer that follows the active pipeline layout. */
public final class MeshBuilder implements VertexConsumer, AutoCloseable {

    private static final int INITIAL_VERTICES = 256;
    private static final int OPAQUE_WHITE_ARGB = 0xFFFFFFFF;

    private final MeshLayout layout;
    private final MeshTopology topology;
    private final int positionOffset;
    private final int colorOffset;
    private final int uv0Offset;
    private final int uv1Offset;
    private final int uv2Offset;
    private final int normalOffset;
    private final int lineWidthOffset;

    private ByteBuffer buffer;
    private int storedVertices;
    private int logicalVertices;
    private int currentVertexStart = -1;

    public MeshBuilder(MeshLayout layout, MeshTopology topology) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.positionOffset = layout.offsetOf(MeshLayout.Attribute.POSITION);
        this.colorOffset = layout.offsetOf(MeshLayout.Attribute.COLOR);
        this.uv0Offset = layout.offsetOf(MeshLayout.Attribute.UV0);
        this.uv1Offset = layout.offsetOf(MeshLayout.Attribute.UV1);
        this.uv2Offset = layout.offsetOf(MeshLayout.Attribute.UV2);
        this.normalOffset = layout.offsetOf(MeshLayout.Attribute.NORMAL);
        this.lineWidthOffset = layout.offsetOf(MeshLayout.Attribute.LINE_WIDTH);
        this.buffer = allocate(INITIAL_VERTICES * layout.stride());
    }

    public MeshLayout layout() {
        return layout;
    }

    public MeshTopology topology() {
        return topology;
    }

    public int logicalVertexCount() {
        finishVertex();
        return logicalVertices;
    }

    public int storedVertexCount() {
        finishVertex();
        return storedVertices;
    }

    public boolean isEmpty() {
        return storedVertexCount() == 0;
    }

    public int indexCount() {
        return topology.indexCountFor(storedVertexCount());
    }

    public int byteSize() {
        return storedVertexCount() * layout.stride();
    }

    /** Returns the current vertex bytes. */
    public ByteBuffer bytes() {
        finishVertex();
        ByteBuffer view = buffer.duplicate().order(ByteOrder.nativeOrder());
        view.position(0).limit(byteSize());
        return view.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    public void reset() {
        storedVertices = 0;
        logicalVertices = 0;
        currentVertexStart = -1;
    }

    @Override
    public void close() {
        reset();
        buffer = allocate(layout.stride());
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        finishVertex();
        beginVertex();
        buffer.putFloat(currentVertexStart + positionOffset, x);
        buffer.putFloat(currentVertexStart + positionOffset + 4, y);
        buffer.putFloat(currentVertexStart + positionOffset + 8, z);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        if (colorOffset >= 0 && currentVertexStart >= 0) {
            int base = currentVertexStart + colorOffset;
            buffer.put(base, (byte) red);
            buffer.put(base + 1, (byte) green);
            buffer.put(base + 2, (byte) blue);
            buffer.put(base + 3, (byte) alpha);
        }
        return this;
    }

    @Override
    public VertexConsumer setColor(int argb) {
        return setColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        if (uv0Offset >= 0 && currentVertexStart >= 0) {
            buffer.putFloat(currentVertexStart + uv0Offset, u);
            buffer.putFloat(currentVertexStart + uv0Offset + 4, v);
        }
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        if (uv1Offset >= 0 && currentVertexStart >= 0) {
            buffer.putShort(currentVertexStart + uv1Offset, (short) u);
            buffer.putShort(currentVertexStart + uv1Offset + 2, (short) v);
        }
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        if (uv2Offset >= 0 && currentVertexStart >= 0) {
            buffer.putShort(currentVertexStart + uv2Offset, (short) u);
            buffer.putShort(currentVertexStart + uv2Offset + 2, (short) v);
        }
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        if (normalOffset >= 0 && currentVertexStart >= 0) {
            buffer.put(currentVertexStart + normalOffset, packNormal(x));
            buffer.put(currentVertexStart + normalOffset + 1, packNormal(y));
            buffer.put(currentVertexStart + normalOffset + 2, packNormal(z));
        }
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        if (lineWidthOffset >= 0 && currentVertexStart >= 0) {
            buffer.putFloat(currentVertexStart + lineWidthOffset, width);
        }
        return this;
    }

    private void beginVertex() {
        int stride = layout.stride();
        int needed = (storedVertices + (topology.duplicatesVertices() ? 2 : 1)) * stride;
        ensureCapacity(needed);
        currentVertexStart = storedVertices * stride;
        for (int i = 0; i < stride; i += 4) {
            if (i + 4 <= stride) buffer.putInt(currentVertexStart + i, 0);
            else for (int j = i; j < stride; j++) buffer.put(currentVertexStart + j, (byte) 0);
        }
        if (colorOffset >= 0) buffer.putInt(currentVertexStart + colorOffset, OPAQUE_WHITE_ARGB);
    }

    private void finishVertex() {
        if (currentVertexStart < 0) return;
        int stride = layout.stride();
        storedVertices++;
        logicalVertices++;
        if (topology.duplicatesVertices()) {
            int copyStart = storedVertices * stride;
            for (int i = 0; i < stride; i++) {
                buffer.put(copyStart + i, buffer.get(currentVertexStart + i));
            }
            storedVertices++;
        }
        currentVertexStart = -1;
    }

    private void ensureCapacity(int bytes) {
        if (buffer.capacity() >= bytes) return;
        int grown = Math.max(bytes, buffer.capacity() * 2);
        ByteBuffer next = allocate(grown);
        buffer.position(0).limit(storedVertices * layout.stride());
        next.put(buffer);
        buffer.clear();
        next.clear();
        buffer = next;
    }

    private static ByteBuffer allocate(int bytes) {
        return ByteBuffer.allocateDirect(Math.max(bytes, 16)).order(ByteOrder.nativeOrder());
    }

    static byte packNormal(float value) {
        float clamped = Math.max(-1.0f, Math.min(1.0f, value));
        return (byte) (int) (clamped * 127.0f);
    }
}
