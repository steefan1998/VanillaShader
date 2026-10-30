#version 330

#moj_import <vanillashader:common.glsl>
#moj_import <vanillashader:frame.glsl>

// Half-resolution water and rain puddles: RGB = reflection already weighted by its blend factor plus the sun glint
// (stored at half so glints up to 2 fit), A = blend factor (0 = not water). Kept out of the composite because the
// reflection march makes a shader heavy for every pixel, water or not.

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform sampler2D OpaqueDepthSampler;
uniform sampler2D ShadowMapSampler;

#moj_import <vanillashader:terrain.glsl>
#moj_import <vanillashader:reflect.glsl>
#moj_import <vanillashader:rain.glsl>

in vec2 texCoord;

out vec4 fragColor;

// Small pixel-snapped waves, so ripples line up with the 16x16 block texture grid.
float waveHeight(vec2 p, float t) {
    float h = sin(p.x * 1.9 + t * 1.4) * 0.35;
    h += sin(p.y * 2.7 - t * 1.1) * 0.30;
    h += sin((p.x + p.y) * 4.3 + t * 2.3) * 0.18;
    h += sin((p.x - p.y) * 6.1 - t * 2.9) * 0.10;
    return h;
}

vec3 waterNormal(vec3 worldPos, float dist) {
    vec2 p = floor(worldPos.xz * 16.0) / 16.0;
    float t = CameraPos.w;
    float e = 1.0 / 16.0;
    float h = waveHeight(p, t);
    float hx = waveHeight(p + vec2(e, 0.0), t);
    float hz = waveHeight(p + vec2(0.0, e), t);
    float strength = mix(0.06, 0.012, smoothstep(8.0, 64.0, dist)) * (1.0 + Weather.x * 1.5);
    vec3 n = vec3((h - hx) / e * strength, 1.0, (h - hz) / e * strength);
    // Raindrops falling on the water, close by only: further away the rings are smaller than a pixel.
    float drops = Weather.x * rainExposure(worldPos) * (1.0 - smoothstep(12.0, 40.0, dist));
    if (drops > 0.01) {
        n.xz -= rainRipples(worldPos.xz, t) * 0.18 * drops;
    }
    return normalize(n);
}

// Reflection plus sun glint for a mirror-like surface; `minBlend` and `maxBlend` set how much it shows head-on
// and at most. RGB = reflection weighted by its blend factor plus the glint, / 2; A = blend factor.
vec4 reflectSurface(vec3 position, vec3 n, float minBlend, float maxBlend) {
    float dist = length(position);
    vec3 viewDir = position / dist;
    vec3 reflected = reflect(viewDir, n);
    float fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(-viewDir, n), 0.0), 5.0);

    vec3 reflection = skyRadiance(reflected);
    if (Quality0.y > 0.0) {
        vec3 hit = traceReflection(position, reflected);
        if (hit.z > 0.0) {
            reflection = mix(reflection, texture(SceneSampler, hit.xy).rgb, hit.z);
        }
    }

    float fog = vanillaFog(viewToWorld(position));
    float strength = Look1.y;
    float blend = clamp(mix(minBlend, 1.0, fresnel) * strength, 0.0, maxBlend) * (1.0 - fog);
    // The ripples are snapped to the texture grid, so far away one ripple is smaller than a pixel and a sharp glint
    // lands on single pixels: white sparkles all over the water. With distance the glint widens and dims instead,
    // into one smooth path of sunlight.
    float far = smoothstep(8.0, 48.0, dist);
    float towardSun = max(dot(reflected, LightDir.xyz), 0.0);
    float glint = pow(towardSun, mix(700.0, 90.0, far)) * mix(6.0, 0.9, far) + pow(towardSun, 60.0) * 0.25;
    vec3 sun = LightColor.rgb * LightColor.w * glint * strength * HorizonColor.w * (1.0 - fog);
    return finiteOr(vec4(min((reflection * blend + sun) * 0.5, vec3(1.0)), max(blend, 1.0 / 255.0)), vec4(0.0));
}

void main() {
    fragColor = vec4(0.0);
    if (Look1.w < 0.5 || Quality0.y < 0.0 || (Weather.w > 0.5 && Weather.w < 1.5)) {
        return;
    }
    float depth = texture(DepthSampler, texCoord).r;
    if (isSky(depth)) {
        return;
    }
    vec3 position = viewPosFromDepth(texCoord, depth);
    vec3 opaque = viewPosFromDepth(texCoord, texture(OpaqueDepthSampler, texCoord).r);
    bool translucent = position.z - opaque.z > 0.02;
    // Only water and, while the world is wet, puddles get a reflection; skip the rest before the normal is built.
    if (!translucent && Misc.z <= 0.01) {
        return;
    }
    vec3 normal = normalFromDepth(DepthSampler, texCoord, position);
    if (dot(normal, viewUp()) <= 0.9) {
        return;
    }
    vec3 world = viewToWorld(position) + CameraPos.xyz;
    float dist = length(position);

    if (!translucent) {
        // Not water. In the rain, puddles on open ground reflect the sky (and, with drops falling, ripple).
        float puddle = Misc.z > 0.01 ? puddleMask(world.xz, Misc.z) * rainExposure(world) : 0.0;
        if (puddle < 0.02) {
            return;
        }
        vec3 n = vec3(0.0, 1.0, 0.0);
        if (dist < 40.0) {
            n.xz -= rainRipples(world.xz, CameraPos.w) * 0.12 * Weather.x * (1.0 - smoothstep(12.0, 40.0, dist));
        }
        fragColor = reflectSurface(position, worldToView(normalize(n)), 0.2 * puddle, 0.75 * puddle);
        return;
    }

    // Water: a translucent surface in front of opaque geometry that faces up, at the water level of a column with
    // water on top, so ice and glass are left alone.
    float waterTop = topIsWater(world.xz);
    if (waterTop >= 0.0 && (waterTop < 0.5 || abs(world.y - (terrainTop(world.xz) - 0.11)) >= 0.5)) {
        return;
    }
    fragColor = reflectSurface(position, worldToView(waterNormal(world, dist)), 0.12, 0.92);
}
