#version 330

#moj_import <vanillashader:common.glsl>
#moj_import <vanillashader:frame.glsl>

// Half-resolution ray-traced reflections on glossy blocks, chosen per block from the material volume: obsidian,
// metal blocks, glass and ice, polished and smooth stone. The reflection comes from the screen where possible and
// otherwise from a ray through the volume (light sources glow, open sky shows the sky).
// RGB = reflection weighted by its blend factor / 2, A = blend factor (0 = not glossy).

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform sampler2D VolumeSampler;

#moj_import <vanillashader:voxels.glsl>
#moj_import <vanillashader:reflect.glsl>

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = vec4(0.0);
    float depth = texture(DepthSampler, texCoord).r;
    if (isSky(depth)) {
        return;
    }
    vec3 position = viewPosFromDepth(texCoord, depth);
    vec3 normal = normalFromDepth(DepthSampler, texCoord, position);
    vec3 normalWorld = viewToWorld(normal);
    vec3 world = viewToWorld(position) + CameraPos.xyz;

    // The block this surface belongs to sits just behind it.
    float material = voxelAt(world - normalWorld * 0.05).x;
    float f0;
    float strength;
    float roughness;
    if (material == MATERIAL_OBSIDIAN) {
        f0 = 0.06;
        strength = 0.85;
        roughness = 0.04;
    } else if (material == MATERIAL_METAL) {
        f0 = 0.6;
        strength = 0.9;
        roughness = 0.12;
    } else if (material == MATERIAL_GLASS) {
        f0 = 0.04;
        strength = 0.7;
        roughness = 0.01;
    } else if (material == MATERIAL_GLOSSY) {
        f0 = 0.04;
        strength = 0.45;
        roughness = 0.18;
    } else {
        return;
    }

    float dist = length(position);
    vec3 viewDir = position / dist;
    // Rougher surfaces scatter the reflection a little per pixel; the denoise and FSR smooth it out. Kept small:
    // a wider spread sends neighbouring rays to the sky and to the ground, which no blur can hide.
    vec3 jitter = vec3(gradientNoise(gl_FragCoord.xy), gradientNoise(gl_FragCoord.yx + 7.3), gradientNoise(gl_FragCoord.xy + 3.1)) - 0.5;
    vec3 n = normalize(normal + jitter * roughness);
    vec3 reflected = reflect(viewDir, n);
    float fresnel = f0 + (1.0 - f0) * pow(1.0 - max(dot(-viewDir, n), 0.0), 5.0);

    vec3 albedo = texture(SceneSampler, texCoord).rgb;
    vec4 hit = traceVoxels(world + normalWorld * 0.02, viewToWorld(reflected), RayTracing.z);
    vec3 reflection;
    if (hit.x == MATERIAL_EMISSIVE) {
        reflection = emissionColor(hit.z) * (hit.y / 15.0) * 1.5;
    } else if (hit.x < 0.0) {
        reflection = skyRadiance(reflected);
    } else {
        // Something off screen in the way: a dim guess in the ambient colour.
        reflection = mix(HorizonColor.rgb, albedo, 0.5) * 0.2;
    }
    if (Quality0.y > 0.0) {
        vec3 screenHit = traceReflection(position, reflected);
        if (screenHit.z > 0.0) {
            reflection = mix(reflection, texture(SceneSampler, screenHit.xy).rgb, screenHit.z);
        }
    }
    if (material == MATERIAL_METAL) {
        // Metals tint what they reflect with their own colour.
        reflection *= albedo / max(max(albedo.r, max(albedo.g, albedo.b)), 0.05);
    }

    float fog = vanillaFog(viewToWorld(position));
    float blend = clamp(fresnel * strength, 0.0, 0.9) * (1.0 - fog);
    fragColor = finiteOr(vec4(min(reflection * blend * 0.5, vec3(1.0)), max(blend, 1.0 / 255.0)), vec4(0.0));
}
