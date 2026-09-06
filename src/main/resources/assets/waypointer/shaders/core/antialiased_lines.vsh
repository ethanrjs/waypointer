#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec3 Normal;
in float LineWidth;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
noperspective out float edgeDistance;
flat out float halfWidth;

void main() {
    // Match vanilla's slight view-space shrink to avoid surface z-fighting.
    mat4 viewScale = mat4(1.0);
    viewScale[0][0] = viewScale[1][1] = viewScale[2][2] = 1.0 - 1.0 / 256.0;
    vec4 start = ProjMat * viewScale * ModelViewMat * vec4(Position, 1.0);
    vec4 end = ProjMat * viewScale * ModelViewMat * vec4(Position + Normal, 1.0);
    vec2 direction = (end.xy / end.w - start.xy / start.w) * ScreenSize;
    float lengthSquared = dot(direction, direction);
    direction = lengthSquared > 0.000001 ? direction * inversesqrt(lengthSquared) : vec2(1.0, 0.0);
    vec2 perpendicular = vec2(-direction.y, direction.x);
    if (perpendicular.x < 0.0) perpendicular = -perpendicular;

    halfWidth = max(LineWidth, 0.0) * 0.5;
    float side = gl_VertexID % 2 == 0 ? 1.0 : -1.0;
    edgeDistance = side * (halfWidth + 0.5);
    gl_Position = start;
    gl_Position.xy += perpendicular * (2.0 * edgeDistance) / ScreenSize * start.w;

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color;
}
