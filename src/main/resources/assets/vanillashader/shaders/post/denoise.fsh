#version 330

#moj_import <vanillashader:common.glsl>

// One direction of a depth-aware blur over the half-resolution lighting buffers. It smooths the per-pixel jitter
// of AO, soft shadows and light occlusion without letting light or shadow cross depth edges.

uniform sampler2D InSampler;
uniform sampler2D GuideSampler; // the lighting buffer: its BA channels hold each texel's packed distance

layout(std140) uniform DenoiseConfig {
    // xy = direction in texels, z = 1 to blur only RG and keep BA (the distances) as they are, 2 to blur only texels
    // with A > 0 (reflection buffers, where A = 0 means "no reflection here" and must not bleed onto the neighbours)
    vec4 Denoise;
};

in vec2 texCoord;

out vec4 fragColor;

const float WEIGHTS[3] = float[](0.4, 0.22, 0.08);

void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 center = ivec2(texCoord * vec2(size));
    ivec2 dir = ivec2(Denoise.xy);
    vec4 centerValue = texelFetch(InSampler, center, 0);
    bool masked = Denoise.z > 1.5;
    if (masked && centerValue.a <= 0.0) {
        fragColor = vec4(0.0);
        return;
    }
    float centerDistance = unpackDistance(texelFetch(GuideSampler, center, 0).ba);
    float tolerance = 0.03 * centerDistance + 0.1;

    vec4 sum = centerValue * WEIGHTS[0];
    float total = WEIGHTS[0];
    for (int i = 1; i <= 2; i++) {
        for (int side = -1; side <= 1; side += 2) {
            ivec2 texel = clamp(center + dir * (i * side), ivec2(0), size - 1);
            float sampleDistance = unpackDistance(texelFetch(GuideSampler, texel, 0).ba);
            vec4 sampleValue = texelFetch(InSampler, texel, 0);
            float w = WEIGHTS[i] * exp(-abs(sampleDistance - centerDistance) / tolerance);
            if (masked && sampleValue.a <= 0.0) {
                w = 0.0;
            }
            sum += sampleValue * w;
            total += w;
        }
    }
    vec4 result = sum / total;
    if (Denoise.z > 0.5 && !masked) {
        result.ba = centerValue.ba;
    }
    fragColor = finiteOr(result, centerValue);
}
