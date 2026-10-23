#version 330

#moj_import <vanillashader:frame.glsl>

// Half-resolution point lights: RGB = light from torches, lanterns and other light sources (held, dropped or
// placed), A = lightning brightening. Both are stored at a quarter so values up to 4 fit in 8 bits.

uniform sampler2D DepthSampler;
uniform sampler2D ShadowMapSampler;

#moj_import <vanillashader:terrain.glsl>

in vec2 texCoord;

out vec4 fragColor;

// Screen-space test whether something sits between the surface and the light, so light does not shine through
// walls on screen. Light whose path leaves the screen is assumed to get through.
float lightVisibility(vec3 start, vec3 lightView, float jitter) {
    vec3 toLight = lightView - start;
    float total = length(toLight);
    // Stop short of the light's own block.
    float reach = total - 0.7;
    if (reach <= 0.3) {
        return 1.0;
    }
    vec3 dir = toLight / total;
    const int steps = 5;
    for (int i = 0; i < steps; i++) {
        float t = reach * (float(i) + jitter) / float(steps);
        vec3 pos = start + dir * t;
        if (pos.z > -0.05) {
            continue;
        }
        vec3 screen = projectToScreen(pos);
        if (!insideScreen(screen.xy)) {
            continue;
        }
        float sceneZ = viewPosFromDepth(screen.xy, texture(DepthSampler, screen.xy).r).z;
        float diff = sceneZ - pos.z;
        if (diff > 0.15 && diff < 3.0) {
            return 0.0;
        }
    }
    return 1.0;
}

vec3 pointLights(vec3 position, vec3 normal, vec3 relativeWorld, vec3 normalWorld) {
    vec3 light = vec3(0.0);
    int count = int(Misc.y);
    float jitter = gradientNoise(gl_FragCoord.xy);
    vec3 start = position + normal * 0.1;
    for (int i = 0; i < count; i++) {
        vec4 color = DynLightColor[i];
        vec3 toLight = DynLightPos[i].xyz - relativeWorld;
        float d = length(toLight);
        float radius = DynLightPos[i].w;
        if (d >= radius || color.w <= 0.0) {
            continue;
        }
        float facing = 0.25 + 0.75 * max(dot(normalWorld, toLight / max(d, 1e-3)), 0.0);
        float falloff = 1.0 - d / radius;
        float amount = color.w * falloff * falloff * facing;
        if (amount < 0.004) {
            continue;
        }
        light += color.rgb * amount * lightVisibility(start, worldToView(DynLightPos[i].xyz), jitter);
    }
    return light;
}

// Brightening from lightning flashes near this point; caves stay dark.
float lightningLight(vec3 relativeWorld, vec3 normalWorld) {
    float light = 0.0;
    for (int i = 0; i < 4; i++) {
        vec4 flash = Flashes[i];
        if (flash.w <= 0.0) {
            continue;
        }
        vec3 toFlash = flash.xyz - relativeWorld;
        float d2 = dot(toFlash, toFlash);
        float facing = 0.3 + 0.7 * max(dot(normalWorld, toFlash * inversesqrt(max(d2, 1e-4))), 0.0);
        light += flash.w * facing * 40000.0 / (d2 + 40000.0);
    }
    if (light <= 0.0) {
        return 0.0;
    }
    return light * 1.6 * (1.0 - undergroundFactor(relativeWorld + CameraPos.xyz));
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    if (isSky(depth)) {
        fragColor = vec4(0.0);
        return;
    }
    vec3 position = viewPosFromDepth(texCoord, depth);
    vec3 normal = normalFromDepth(DepthSampler, texCoord, position);
    vec3 relativeWorld = viewToWorld(position);
    vec3 normalWorld = viewToWorld(normal);

    vec3 light = pointLights(position, normal, relativeWorld, normalWorld);
    float lightning = lightningLight(relativeWorld, normalWorld);
    fragColor = vec4(min(light * 0.25, vec3(1.0)), min(lightning * 0.25, 1.0));
}
