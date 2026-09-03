package com.babbur.waypointer.render.gpu;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Runtime byte layout for one vertex. */
public record MeshLayout(int stride, Map<Attribute, Integer> attributes) {

    public enum Attribute {
        POSITION(12),
        COLOR(4),
        UV0(8),
        UV1(4),
        UV2(4),
        NORMAL(3),
        LINE_WIDTH(4);

        private final int byteSize;

        Attribute(int byteSize) {
            this.byteSize = byteSize;
        }

        public int byteSize() {
            return byteSize;
        }
    }

    public MeshLayout {
        if (stride <= 0) throw new IllegalArgumentException("stride must be positive: " + stride);
        Objects.requireNonNull(attributes, "attributes");
        if (!attributes.containsKey(Attribute.POSITION)) {
            throw new IllegalArgumentException("layout must contain POSITION");
        }
        EnumMap<Attribute, Integer> copy = new EnumMap<>(Attribute.class);
        attributes.forEach((attribute, offset) -> {
            Objects.requireNonNull(attribute, "attribute");
            Objects.requireNonNull(offset, "offset");
            if (offset < 0 || offset + attribute.byteSize() > stride) {
                throw new IllegalArgumentException(attribute + " at " + offset
                        + " does not fit in stride " + stride);
            }
            copy.put(attribute, offset);
        });
        attributes = Map.copyOf(copy);
    }

    public static MeshLayout packed(List<Attribute> ordered) {
        Objects.requireNonNull(ordered, "ordered");
        EnumMap<Attribute, Integer> offsets = new EnumMap<>(Attribute.class);
        int offset = 0;
        for (Attribute attribute : ordered) {
            if (offsets.containsKey(attribute)) {
                throw new IllegalArgumentException("duplicate attribute " + attribute);
            }
            offsets.put(attribute, offset);
            offset += attribute.byteSize();
        }
        return new MeshLayout(offset, offsets);
    }

    public static MeshLayout packedAligned(List<Attribute> ordered) {
        MeshLayout packed = packed(ordered);
        int aligned = (packed.stride() + 3) & ~3;
        return aligned == packed.stride() ? packed : new MeshLayout(aligned, packed.attributes());
    }

    public boolean has(Attribute attribute) {
        return attributes.containsKey(attribute);
    }

    public int offsetOf(Attribute attribute) {
        Integer offset = attributes.get(attribute);
        return offset == null ? -1 : offset;
    }

    public static MeshLayout positionColor() {
        return packed(List.of(Attribute.POSITION, Attribute.COLOR));
    }
}
