#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
noperspective in float edgeDistance;
flat in float halfWidth;

out vec4 fragColor;

void main() {
    vec4 color = vertexColor * ColorModulator;
    color.a *= clamp(halfWidth + 0.5 - abs(edgeDistance), 0.0, 1.0);
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
