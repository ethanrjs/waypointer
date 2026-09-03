package com.babbur.waypointer.render.gpu;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import java.util.EnumMap;
import java.util.Map;

/** Minecraft 26.2 vertex-layout adapter. */
final class VertexLayoutCompat {

    private VertexLayoutCompat() {}

    static MeshLayout describe(RenderPipeline pipeline) {
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        Map<MeshLayout.Attribute, Integer> attributes = new EnumMap<>(MeshLayout.Attribute.class);
        for (VertexFormatElement element : format.getElements()) {
            MeshLayout.Attribute attribute = classify(element);
            if (attribute != null) attributes.put(attribute, element.offset());
        }
        return new MeshLayout(format.getVertexSize(), attributes);
    }

    private static MeshLayout.Attribute classify(VertexFormatElement element) {
        String name = element.name();
        if (DefaultVertexFormat.POSITION_SEMANTIC_NAME.equals(name)) return MeshLayout.Attribute.POSITION;
        if (DefaultVertexFormat.COLOR_SEMANTIC_NAME.equals(name)) return MeshLayout.Attribute.COLOR;
        if (DefaultVertexFormat.UV0_SEMANTIC_NAME.equals(name)) return MeshLayout.Attribute.UV0;
        if (DefaultVertexFormat.UV1_SEMANTIC_NAME.equals(name)) return MeshLayout.Attribute.UV1;
        if (DefaultVertexFormat.UV2_SEMANTIC_NAME.equals(name)) return MeshLayout.Attribute.UV2;
        if (DefaultVertexFormat.NORMAL_SEMANTIC_NAME.equals(name)) return MeshLayout.Attribute.NORMAL;
        if (DefaultVertexFormat.LINE_WIDTH_SEMANTIC_NAME.equals(name)) {
            return MeshLayout.Attribute.LINE_WIDTH;
        }
        return null;
    }
}
