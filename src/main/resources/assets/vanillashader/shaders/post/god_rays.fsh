#version 330

#moj_import <vanillashader:common.glsl>
#moj_import <vanillashader:frame.glsl>

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    int samples = int(Quality0.w);
    vec2 light = LightScreen.xy;
    vec2 delta = (light - texCoord) / float(samples) * 0.92;
    vec2 pos = texCoord + delta * gradientNoise(gl_FragCoord.xy);

    float decay = 1.0;
    float total = 0.0;
    float lightSum = 0.0;
    for (int i = 0; i < samples; i++) {
        float depth = texture(DepthSampler, pos).r;
        if (isSky(depth)) {
            float brightness = luma(texture(SceneSampler, pos).rgb);
            lightSum += (0.25 + smoothstep(0.45, 1.0, brightness)) * decay;
        }
        total += decay;
        decay *= 0.965;
        pos += delta;
    }
    float shafts = lightSum / max(total, 1e-4);

    vec2 fromLight = texCoord - light;
    fromLight.x *= LightScreen.w;
    float falloff = exp(-length(fromLight) * 1.8);

    float strength = LightColor.w * LightScreen.z * (1.0 - Weather.y * 0.7) * (0.55 + 0.45 * Weather.z);
    fragColor = vec4(finiteOr(LightColor.rgb * shafts * falloff * strength * 0.9, vec3(0.0)), 1.0);
}
