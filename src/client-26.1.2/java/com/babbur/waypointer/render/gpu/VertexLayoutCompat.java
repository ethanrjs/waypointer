package com.babbur.waypointer.render.gpu;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import java.util.EnumMap;
import java.util.Map;

/** Minecraft 26.1.2 vertex-layout adapter. */
final class VertexLayoutCompat {

    private VertexLayoutCompat() {}

    static MeshLayout describe(RenderPipeline pipeline) {
        VertexFormat format = pipeline.getVertexFormat();
        Map<MeshLayout.Attribute, Integer> attributes = new EnumMap<>(MeshLayout.Attribute.class);
        for (VertexFormatElement element : format.getElements()) {
            MeshLayout.Attribute attribute = classify(element);
            if (attribute != null) attributes.put(attribute, format.getOffset(element));
        }
        return new MeshLayout(format.getVertexSize(), attributes);
    }

    private static MeshLayout.Attribute classify(VertexFormatElement element) {
        if (element == VertexFormatElement.POSITION) return MeshLayout.Attribute.POSITION;
        if (element == VertexFormatElement.COLOR) return MeshLayout.Attribute.COLOR;
        if (element == VertexFormatElement.UV0) return MeshLayout.Attribute.UV0;
        if (element == VertexFormatElement.UV1) return MeshLayout.Attribute.UV1;
        if (element == VertexFormatElement.UV2) return MeshLayout.Attribute.UV2;
        if (element == VertexFormatElement.NORMAL) return MeshLayout.Attribute.NORMAL;
        if (element == VertexFormatElement.LINE_WIDTH) return MeshLayout.Attribute.LINE_WIDTH;
        return null;
    }
}
