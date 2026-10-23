#version 330

uniform sampler2D InSampler;

layout(std140) uniform BlurConfig {
    vec4 BlurDir;
};

in vec2 texCoord;

out vec4 fragColor;

// 9-tap Gaussian done in 5 bilinear fetches, stretched a little for a wider glow.
const float OFFSETS[3] = float[](0.0, 1.3846153846, 3.2307692308);
const float WEIGHTS[3] = float[](0.2270270270, 0.3162162162, 0.0702702703);

void main() {
    vec2 stride = BlurDir.xy / vec2(textureSize(InSampler, 0)) * 1.5;
    vec3 color = texture(InSampler, texCoord).rgb * WEIGHTS[0];
    for (int i = 1; i < 3; i++) {
        color += texture(InSampler, texCoord + stride * OFFSETS[i]).rgb * WEIGHTS[i];
        color += texture(InSampler, texCoord - stride * OFFSETS[i]).rgb * WEIGHTS[i];
    }
    fragColor = vec4(color, 1.0);
}
