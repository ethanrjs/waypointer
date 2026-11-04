#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec4 vertexColor;
#ifdef ANTIALIASING
layout(location = 1) noperspective in float edgeDistance;
layout(location = 2) flat in float halfWidth;
#endif

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = vertexColor * ColorModulator;
#ifdef ANTIALIASING
    color.a *= clamp(halfWidth + 0.5 - abs(edgeDistance), 0.0, 1.0);
#endif
    fragColor = color;
}
