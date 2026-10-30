#version 330

#moj_import <vanillashader:common.glsl>
#moj_import <vanillashader:frame.glsl>

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;  // to tell the sky from everything else
uniform sampler2D DhMaskSampler; // A > 0.5 on Distant Horizons' terrain, which is not sky

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

    // Not the strongest channel alone: a clear blue sky has blue near full and would glow everywhere, washing the
    // whole sky out to white. White light, the sun, torches and lava still count as bright.
    float brightness = max(luma(color), max(color.r, color.g));
    // Under a bright modded sky most of the image is near white; only the brightest parts may glow.
    float weight = smoothstep(mix(0.68, 0.9, Misc.w), 1.0, brightness);
    // The sky and its clouds are bright but are no light source: glowing, they washed out everything around the
    // sun. Only the near-white disc of the sun itself still glows there.
    if (isSky(texture(DepthSampler, texCoord).r) && texture(DhMaskSampler, texCoord).a < 0.5) {
        weight = smoothstep(0.93, 1.0, luma(color));
    }
    fragColor = vec4(finiteOr(color * weight, vec3(0.0)), 1.0);
}
