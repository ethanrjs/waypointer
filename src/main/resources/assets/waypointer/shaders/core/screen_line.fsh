#version 330

#moj_import <minecraft:dynamictransforms.glsl>

in vec4 vertexColor;
noperspective in float edgeDistance;
flat in float halfWidth;

out vec4 fragColor;

void main() {
    vec4 color = vertexColor * ColorModulator;
#ifdef ANTIALIASING
    color.a *= clamp(halfWidth + 0.5 - abs(edgeDistance), 0.0, 1.0);
#endif
    fragColor = color;
}
