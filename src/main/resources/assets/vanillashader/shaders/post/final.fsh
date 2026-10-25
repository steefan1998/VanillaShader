#version 330

#moj_import <vanillashader:common.glsl>
#moj_import <vanillashader:frame.glsl>

uniform sampler2D LitSampler;
uniform sampler2D BloomNearSampler;
uniform sampler2D BloomFarSampler;
uniform sampler2D RaysSampler;

in vec2 texCoord;

out vec4 fragColor;

// Identity below the knee, smooth roll-off above it, so bloom and rays never clip harshly.
vec3 softClip(vec3 color) {
    const float knee = 0.8;
    vec3 over = max(color - knee, 0.0);
    return min(color, vec3(knee)) + (1.0 - knee) * (1.0 - exp(-over / (1.0 - knee)));
}

void main() {
    // Under water the image wobbles gently, as if seen through moving water.
    vec2 uv = texCoord;
    if (Weather.w > 0.5 && Weather.w < 1.5) {
        float t = CameraPos.w;
        uv += vec2(sin(uv.y * 23.0 + t * 2.1), cos(uv.x * 19.0 + t * 1.7)) * 0.0022;
    }
    vec3 color = texture(LitSampler, uv).rgb;

    float bloomLevel = Quality1.y;
    if (bloomLevel > 0.0) {
        float strength = 0.22 + 0.06 * bloomLevel;
        color += texture(BloomNearSampler, texCoord).rgb * strength;
        color += texture(BloomFarSampler, texCoord).rgb * strength * 0.9;
    }
    color += texture(RaysSampler, texCoord).rgb;

    color *= Look0.x;
    color = softClip(color);

    // Contrast around mid grey, then saturation.
    color = clamp((color - 0.5) * Look0.z + 0.5, 0.0, 1.0);
    float grey = luma(color);
    float saturation = Look0.y * (1.0 - Weather.y * 0.2);
    color = max(mix(vec3(grey), color, saturation), 0.0);

    // Golden hour warmth during sunrise/sunset, a cool tint at night.
    vec3 tint = mix(vec3(1.0), vec3(1.12, 0.98, 0.8), Weather.z * (1.0 - Weather.x));
    tint = mix(tint, vec3(0.9, 0.96, 1.08), (1.0 - SkyColor.w) * Quality1.w);
    if (Weather.w > 0.5 && Weather.w < 1.5) {
        tint *= vec3(0.72, 0.93, 1.05);
    }
    color *= mix(vec3(1.0), tint, Look0.w);

    vec2 centered = texCoord - 0.5;
    color *= 1.0 - dot(centered, centered) * Look1.z * 1.4;

    color += (gradientNoise(gl_FragCoord.xy) - 0.5) / 255.0;
    fragColor = vec4(clamp(finiteOr(color, vec3(0.0)), 0.0, 1.0), 1.0);
}
