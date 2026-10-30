#version 330

#moj_import <vanillashader:common.glsl>
#moj_import <vanillashader:frame.glsl>

uniform sampler2D LitSampler;
uniform sampler2D BloomNearSampler;
uniform sampler2D BloomFarSampler;
uniform sampler2D RaysSampler;
uniform sampler2D DepthSampler;  // to see whether terrain hides the sun or moon
uniform sampler2D DhMaskSampler; // ...or Distant Horizons' terrain (A > 0.5)

in vec2 texCoord;

out vec4 fragColor;

// Identity below the knee, smooth roll-off above it, so bloom and rays never clip harshly.
vec3 softClip(vec3 color) {
    const float knee = 0.8;
    vec3 over = max(color - knee, 0.0);
    return min(color, vec3(knee)) + (1.0 - knee) * (1.0 - exp(-over / (1.0 - knee)));
}

// How much of the sun's (or moon's) disc is open sky: five depth taps around it. Off screen it cannot be checked;
// it then counts as half hidden, so the flare fades out at the edge instead of popping.
float lightVisible() {
    vec2 light = LightScreen.xy;
    if (!insideScreen(light)) {
        return 0.5;
    }
    float visible = 0.0;
    for (int i = 0; i < 5; i++) {
        vec2 offset = i == 0 ? vec2(0.0) : vec2(i == 1 ? 1.0 : (i == 2 ? -1.0 : 0.0), i == 3 ? 1.0 : (i == 4 ? -1.0 : 0.0)) * 0.008;
        vec2 p = clamp(light + offset, vec2(0.001), vec2(0.999));
        if (isSky(texture(DepthSampler, p).r) && texture(DhMaskSampler, p).a < 0.5) {
            visible += 0.2;
        }
    }
    return visible;
}

// The sun shining into the lens: a small soft halo around it, a thin horizontal streak and a few coloured ghosts on the
// line from the sun through the middle of the screen. The moon gets a faint version.
vec3 lensFlare(vec2 uv) {
    float strength = LightColor.w * LightScreen.z * (1.0 - Weather.y * 0.85) * Quality1.w;
    if (strength < 0.01 || (Weather.w > 0.5 && Weather.w < 1.5)) {
        return vec3(0.0);
    }
    strength *= lightVisible();
    if (strength < 0.01) {
        return vec3(0.0);
    }
    // The moon is bluish: a quieter flare.
    float sunLike = step(LightColor.b, LightColor.r);
    strength *= mix(0.35, 1.0, sunLike);
    vec2 light = LightScreen.xy;
    vec2 aspect = vec2(LightScreen.w, 1.0);
    vec2 toLight = (uv - light) * aspect;
    float dist = length(toLight);

    float halo = exp(-dist * 12.0) * 0.1;
    float streak = exp(-abs(toLight.y) * 90.0) * exp(-abs(toLight.x) * 2.5) * 0.12;
    vec3 flare = LightColor.rgb * (halo + streak);

    // Ghosts: soft discs mirrored through the centre, each its own tint.
    vec2 axis = vec2(0.5) - light;
    const vec3 TINTS[4] = vec3[](vec3(1.0, 0.6, 0.3), vec3(0.4, 0.75, 1.0), vec3(0.6, 1.0, 0.55), vec3(1.0, 0.45, 0.8));
    const float PLACES[4] = float[](0.55, 1.15, 1.55, 2.05);
    const float SIZES[4] = float[](0.035, 0.07, 0.025, 0.11);
    for (int i = 0; i < 4; i++) {
        vec2 ghost = light + axis * PLACES[i];
        float d = length((uv - ghost) * aspect);
        flare += TINTS[i] * (1.0 - smoothstep(SIZES[i] * 0.55, SIZES[i], d)) * 0.045;
    }
    return flare * strength;
}

void main() {
    // Under water the image wobbles gently, as if seen through moving water.
    vec2 uv = texCoord;
    if (Weather.w > 0.5 && Weather.w < 1.5) {
        float t = CameraPos.w;
        uv += vec2(sin(uv.y * 23.0 + t * 2.1), cos(uv.x * 19.0 + t * 1.7)) * 0.0022;
    }
    vec3 color = texture(LitSampler, uv).rgb;
    // With Distant Horizons the composite says per pixel how much of our image it keeps (0 on DH's terrain, fading
    // out towards the render distance); the bloom, rays and grading follow it.
    vec4 lit = texture(LitSampler, texCoord);
    float shaded = DhParams.w > 0.5 ? lit.a : 1.0;

    float bloomLevel = Quality1.y;
    if (bloomLevel > 0.0) {
        float strength = 0.22 + 0.06 * bloomLevel;
        color += texture(BloomNearSampler, texCoord).rgb * strength;
        color += texture(BloomFarSampler, texCoord).rgb * strength * 0.9;
    }
    color += texture(RaysSampler, texCoord).rgb;

    // A bright modded sky (The Aether) is already near white; a little less exposure keeps it from clipping.
    color *= Look0.x * mix(1.0, 0.85, Misc.w);
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
    color = mix(lit.rgb, color, shaded);
    // The lens flare sits on the whole picture, like light in the camera.
    color += lensFlare(texCoord);

    // The vignette belongs to the whole screen, Distant Horizons' terrain included.
    vec2 centered = texCoord - 0.5;
    color *= 1.0 - dot(centered, centered) * Look1.z * 1.4;

    color += (gradientNoise(gl_FragCoord.xy) - 0.5) / 255.0;
    fragColor = vec4(clamp(finiteOr(color, vec3(0.0)), 0.0, 1.0), 1.0);
}
