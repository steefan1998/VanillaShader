#version 330

#moj_import <vanillashader:common.glsl>
#moj_import <vanillashader:frame.glsl>

// Minecraft 26.3+ with improved transparency only: rebuilds a depth buffer with translucent surfaces (water, glass,
// ice) from the opaque depth and OIT's depth bounds, whose x holds minus the linear distance of the nearest
// translucent surface (-FLT_MAX where there is none).
uniform sampler2D DepthSampler;
uniform sampler2D BoundsSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    float nearest = -texture(BoundsSampler, texCoord).x;
    if (nearest > 0.0 && nearest < 1.0e30) {
        // Inverse of Minecraft's deviceToLinearDepth, back to a depth buffer value.
        float ndc = Proj[3][2] / nearest - Proj[2][2];
        float translucent = (ndc - DepthParams.y) / DepthParams.x;
#ifdef VANILLASHADER_REVERSED_Z
        depth = max(depth, translucent);
#else
        depth = min(depth, translucent);
#endif
    }
    fragColor = vec4(depth, 0.0, 0.0, 1.0);
}
