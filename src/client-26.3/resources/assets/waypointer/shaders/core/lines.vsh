#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec3 Normal;
layout(location = 3) in float LineWidth;
layout(location = 0) out vec4 vertexColor;

void main() {
    mat4 viewScale = mat4(1.0);
    viewScale[0][0] = viewScale[1][1] = viewScale[2][2] = 1.0 - 1.0 / 256.0;
    vec4 start = ProjMat * viewScale * ModelViewMat * vec4(Position, 1.0);
    vec4 end = ProjMat * viewScale * ModelViewMat * vec4(Position + Normal, 1.0);
    vec2 direction = (end.xy / end.w - start.xy / start.w) * ScreenSize;
    float lengthSquared = dot(direction, direction);
    direction = lengthSquared > 0.000001 ? direction * inversesqrt(lengthSquared) : vec2(1.0, 0.0);
    vec2 perpendicular = vec2(-direction.y, direction.x);
    if (perpendicular.x < 0.0) perpendicular = -perpendicular;
    float side = gl_VertexIndex % 2 == 0 ? 1.0 : -1.0;
    gl_Position = start;
    gl_Position.xy += perpendicular * (side * max(LineWidth, 0.0)) / ScreenSize * start.w;
    vertexColor = Color;
}
