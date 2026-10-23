#version 330

#moj_import <vanillashader:frame.glsl>

// Half-resolution water: RGB = reflection already weighted by its blend factor plus the sun glint (stored at
// half so glints up to 2 fit), A = blend factor (0 = not water). Kept out of the composite because the
// reflection march makes a shader heavy for every pixel, water or not.

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform sampler2D OpaqueDepthSampler;
uniform sampler2D ShadowMapSampler;

#moj_import <vanillashader:terrain.glsl>

in vec2 texCoord;

out vec4 fragColor;

vec3 skyRadiance(vec3 viewDir) {
    float up = viewToWorld(viewDir).y;
    vec3 color = mix(HorizonColor.rgb, SkyColor.rgb, smoothstep(-0.05, 0.45, up));
    float towardLight = max(dot(viewDir, LightDir.xyz), 0.0);
    color += LightColor.rgb * LightColor.w * (pow(towardLight, 6.0) * (0.25 + 0.6 * Weather.z) + pow(towardLight, 64.0) * 0.5);
    // Reflections of the sky look far too bright in caves and under overhangs.
    return color * mix(0.25, 1.0, HorizonColor.w);
}

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
    return normalize(vec3((h - hx) / e * strength, 1.0, (h - hz) / e * strength));
}

// Screen-space ray march. Returns the hit uv in xy and a confidence in z.
vec3 traceReflection(vec3 origin, vec3 dir) {
    int steps = int(Quality0.y);
    int refine = int(Quality0.z);
    float maxDistance = min(Quality1.z, 128.0);
    float jitter = gradientNoise(gl_FragCoord.xy + DepthParams.z * 5.588);
    float previous = 0.0;

    for (int i = 1; i <= steps; i++) {
        float t = maxDistance * pow((float(i) - 1.0 + jitter) / float(steps), 1.9) + 0.15;
        vec3 pos = origin + dir * t;
        if (pos.z > -0.05) {
            break;
        }
        vec3 screen = projectToScreen(pos);
        if (!insideScreen(screen.xy)) {
            break;
        }
        float sceneDepth = texture(DepthSampler, screen.xy).r;
        vec3 scene = viewPosFromDepth(screen.xy, sceneDepth);
        float diff = scene.z - pos.z;
        if (!isSky(sceneDepth) && diff > 0.0 && diff < 0.6 + t * 0.12) {
            float lo = previous;
            float hi = t;
            for (int j = 0; j < refine; j++) {
                float mid = (lo + hi) * 0.5;
                vec3 midPos = origin + dir * mid;
                vec3 midScreen = projectToScreen(midPos);
                vec3 midScene = viewPosFromDepth(midScreen.xy, texture(DepthSampler, midScreen.xy).r);
                if (midScene.z - midPos.z > 0.0) {
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            vec3 hit = projectToScreen(origin + dir * hi);
            vec2 edge = abs(hit.xy * 2.0 - 1.0);
            float confidence = (1.0 - smoothstep(0.8, 1.0, max(edge.x, edge.y))) * (1.0 - smoothstep(0.6, 1.0, hi / maxDistance));
            return vec3(hit.xy, confidence);
        }
        previous = t;
    }
    return vec3(0.0);
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
    // Water: a translucent surface in front of opaque geometry that faces up...
    if (position.z - opaque.z <= 0.02) {
        return;
    }
    vec3 normal = normalFromDepth(DepthSampler, texCoord, position);
    if (dot(normal, viewUp()) <= 0.9) {
        return;
    }
    // ...at the water level of a column with water on top, so ice and glass are left alone.
    vec3 world = viewToWorld(position) + CameraPos.xyz;
    float waterTop = topIsWater(world.xz);
    if (waterTop >= 0.0 && (waterTop < 0.5 || abs(world.y - (terrainTop(world.xz) - 0.11)) >= 0.5)) {
        return;
    }

    float dist = length(position);
    vec3 viewDir = position / dist;
    vec3 n = worldToView(waterNormal(world, dist));
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
    float blend = clamp(mix(0.12, 1.0, fresnel) * strength, 0.0, 0.92) * (1.0 - fog);
    float glint = pow(max(dot(reflected, LightDir.xyz), 0.0), 700.0) * 6.0 + pow(max(dot(reflected, LightDir.xyz), 0.0), 60.0) * 0.25;
    vec3 sun = LightColor.rgb * LightColor.w * glint * strength * HorizonColor.w * (1.0 - fog);
    fragColor = vec4(min((reflection * blend + sun) * 0.5, vec3(1.0)), max(blend, 1.0 / 255.0));
}
