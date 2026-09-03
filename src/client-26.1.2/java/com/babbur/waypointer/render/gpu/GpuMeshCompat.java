package com.babbur.waypointer.render.gpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

/** Minecraft 26.1.2 GPU buffer adapter. */
final class GpuMeshCompat {

    private GpuMeshCompat() {}

    static GpuBuffer createVertexBuffer(Supplier<String> label, int bytes) {
        return RenderSystem.getDevice().createBuffer(label,
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, bytes);
    }

    static GpuBuffer createIndexBuffer(Supplier<String> label, int bytes) {
        return RenderSystem.getDevice().createBuffer(label,
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, bytes);
    }

    static CommandEncoder createCommandEncoder() {
        return RenderSystem.getDevice().createCommandEncoder();
    }

    static void upload(CommandEncoder encoder, GpuBuffer buffer, ByteBuffer data) {
        encoder.writeToBuffer(buffer.slice(0, data.remaining()), data);
    }

    static void bindVertexBuffer(RenderPass pass, GpuBuffer buffer) {
        pass.setVertexBuffer(0, buffer);
    }

    static void bindSequentialIndexBuffer(RenderPass pass, MeshTopology topology, int indexCount) {
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(mode(topology));
        pass.setIndexBuffer(indices.getBuffer(indexCount), indices.type());
    }

    static void bindIndexBuffer(RenderPass pass, GpuBuffer buffer) {
        pass.setIndexBuffer(buffer, VertexFormat.IndexType.INT);
    }

    static void drawIndexed(RenderPass pass, int firstIndex, int indexCount) {
        pass.drawIndexed(0, firstIndex, indexCount, 1);
    }

    private static VertexFormat.Mode mode(MeshTopology topology) {
        return switch (topology) {
            case LINES -> VertexFormat.Mode.LINES;
            case QUADS -> VertexFormat.Mode.QUADS;
        };
    }
}
