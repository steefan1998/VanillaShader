#version 330

#moj_import <vanillashader:frame.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    // 4 bilinear taps = a 4x4 box filter when downsampling to quarter size.
    vec2 texel = 1.0 / vec2(textureSize(InSampler, 0));
    vec3 color = texture(InSampler, texCoord + texel * vec2(-1.0, -1.0)).rgb;
    color += texture(InSampler, texCoord + texel * vec2(1.0, -1.0)).rgb;
    color += texture(InSampler, texCoord + texel * vec2(-1.0, 1.0)).rgb;
    color += texture(InSampler, texCoord + texel * vec2(1.0, 1.0)).rgb;
    color *= 0.25 * Look0.x;

    float brightness = max(color.r, max(color.g, color.b));
    float weight = smoothstep(0.68, 1.0, brightness);
    fragColor = vec4(color * weight, 1.0);
}
