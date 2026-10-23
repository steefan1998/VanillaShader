#version 330

#moj_import <vanillashader:frame.glsl>

// Half-resolution lighting: R = ambient occlusion, G = sun visibility (sun shadows, contact shadows and
// WeatherStory cloud shadows), BA = packed distance for the depth-aware upsample in the composite.

uniform sampler2D DepthSampler;
uniform sampler2D ShadowMapSampler;
uniform sampler2D CloudShadowSampler;

#moj_import <vanillashader:terrain.glsl>

in vec2 texCoord;

out vec4 fragColor;

const float AO_RADIUS = 0.75;

float ambientOcclusion(vec3 position, vec3 normal) {
    int samples = int(Quality0.x);
    if (samples <= 0 || -position.z > 96.0) {
        return 1.0;
    }
    vec3 tangent = normalize(cross(abs(normal.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0), normal));
    vec3 bitangent = cross(normal, tangent);
    float noise = gradientNoise(gl_FragCoord.xy);
    float occlusion = 0.0;
    for (int i = 0; i < samples; i++) {
        // Cosine-weighted spiral over the hemisphere around the normal.
        float f = (float(i) + noise) / float(samples);
        float angle = float(i) * 2.39996 + noise * 6.28318;
        float r = sqrt(f);
        vec3 offset = tangent * (cos(angle) * r) + bitangent * (sin(angle) * r) + normal * sqrt(1.0 - f);
        vec3 samplePos = position + offset * AO_RADIUS * mix(0.25, 1.0, f * f);
        vec3 screen = projectToScreen(samplePos);
        if (!insideScreen(screen.xy)) {
            continue;
        }
        vec3 scene = viewPosFromDepth(screen.xy, texture(DepthSampler, screen.xy).r);
        float range = smoothstep(0.0, 1.0, AO_RADIUS / max(abs(position.z - scene.z), 1e-4));
        occlusion += (scene.z >= samplePos.z + 0.03 ? 1.0 : 0.0) * range;
    }
    float ao = 1.0 - occlusion / float(samples);
    return mix(ao, 1.0, smoothstep(48.0, 96.0, -position.z));
}

// Sun visibility (1 = lit) from a ray march over the terrain height map; sees occluders off screen too.
float sunShadow(vec3 relativePos, vec3 normalWorld, vec3 lightWorld) {
    int steps = int(Quality1.x);
    vec3 start = relativePos + CameraPos.xyz + normalWorld * 0.05;

    // Caves and rooms well below the surface never see the sun; leave them to vanilla lighting.
    float underground = undergroundFactor(start);
    if (underground >= 1.0) {
        return 1.0;
    }

    float jitter = gradientNoise(gl_FragCoord.xy);
    float maxDistance = 24.0 + float(steps) * 2.0;
    float visibility = 1.0;
    for (int i = 0; i < steps; i++) {
        float t = maxDistance * pow((float(i) + jitter) / float(steps), 1.6) + 0.08;
        vec3 pos = start + lightWorld * t;
        vec2 fromCamera = pos.xz - CameraPos.xz;
        // Above all terrain around the camera nothing can block the sun any more.
        if (pos.y > Misc.x + 1.0 || max(abs(fromCamera.x), abs(fromCamera.y)) > SHADOW_MAP_SIZE * 0.5 - 8.0) {
            break;
        }
        float clearance = pos.y - terrainTop(pos.xz);
        if (clearance < 0.0) {
            visibility = 0.0;
            break;
        }
        // Rays that only just clear an edge give a soft penumbra.
        visibility = min(visibility, clearance * 16.0 / t);
    }
    return mix(clamp(visibility, 0.0, 1.0), 1.0, underground);
}

// Light let through WeatherStory's clouds, sampled the same way its own terrain shader does.
float cloudShadow(vec3 relativePos) {
    vec3 sun = normalize(vec3(CloudParams.z, CloudParams.w, 0.0));
    vec2 projected = relativePos.xz - sun.xz * (relativePos.y / sun.y);
    vec2 uv = projected / (CloudParams.x * 2.0) + 0.5;
    float border = smoothstep(0.72, 1.0, max(abs(uv.x - 0.5), abs(uv.y - 0.5)) * 2.0);
    float shade = mix(texture(CloudShadowSampler, clamp(uv, 0.0, 1.0)).r, 1.0, border);
    shade = min(shade, mix(1.0, 0.24, clamp(CloudParams.y, 0.0, 1.0)));
    return mix(shade, 1.0, undergroundFactor(relativePos + CameraPos.xyz));
}

// Short screen-space march for small details the 1-block height map cannot resolve.
float contactShadow(vec3 position, vec3 normal) {
    if (Quality1.x < 32.0 || -position.z > 48.0) {
        return 1.0;
    }
    const int steps = 10;
    float jitter = gradientNoise(gl_FragCoord.xy + 17.0);
    vec3 start = position + normal * 0.04;
    for (int i = 0; i < steps; i++) {
        float t = 2.0 * (float(i) + jitter) / float(steps) + 0.05;
        vec3 pos = start + LightDir.xyz * t;
        vec3 screen = projectToScreen(pos);
        if (!insideScreen(screen.xy)) {
            break;
        }
        vec3 scene = viewPosFromDepth(screen.xy, texture(DepthSampler, screen.xy).r);
        float diff = scene.z - pos.z;
        if (diff > 0.04 && diff < 1.0) {
            return smoothstep(24.0, 48.0, -position.z);
        }
    }
    return 1.0;
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    if (isSky(depth)) {
        fragColor = vec4(1.0, 1.0, packDistance(1024.0));
        return;
    }
    vec3 position = viewPosFromDepth(texCoord, depth);
    vec3 normal = normalFromDepth(DepthSampler, texCoord, position);

    float ao = ambientOcclusion(position, normal);

    float visibility = 1.0;
    if (Quality1.x > 0.0 && LightDir.w > 0.01) {
        vec3 lightWorld = viewToWorld(LightDir.xyz);
        vec3 normalWorld = viewToWorld(normal);
        vec3 relativeWorld = viewToWorld(position);
        // Faces turned away from the sun are in their own shadow; no need to march.
        visibility = smoothstep(-0.1, 0.25, dot(normalWorld, lightWorld));
        if (visibility > 0.0 && lightWorld.y > 0.03) {
            visibility *= sunShadow(relativeWorld, normalWorld, lightWorld);
            if (visibility > 0.0) {
                visibility = min(visibility, contactShadow(position, normal));
            }
        }
        if (CloudParams.x > 0.0) {
            visibility *= cloudShadow(relativeWorld);
        }
    }

    fragColor = finiteOr(vec4(ao, visibility, packDistance(length(position))), vec4(1.0, 1.0, packDistance(1024.0)));
}
