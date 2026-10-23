#version 330

#moj_import <vanillashader:frame.glsl>

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;        // everything, including water and glass
uniform sampler2D OpaqueDepthSampler;  // only opaque geometry
uniform sampler2D LightingSampler;       // half res: R = AO, G = sun visibility, BA = packed distance
uniform sampler2D PointLightsSampler;    // half res: RGB = point light / 4, A = lightning / 4
uniform sampler2D WaterSampler;          // half res: RGB = weighted reflection + glint / 2, A = blend (0 = no water)
uniform sampler2D OpaqueColorSampler;   // level after opaque geometry
uniform sampler2D FeatureColorSampler;  // ...and after translucent features (WeatherStory mist and sand wisps)
uniform sampler2D ShadowMapSampler;     // height map, also marks which columns have water on top

#moj_import <vanillashader:terrain.glsl>

in vec2 texCoord;

out vec4 fragColor;

// Depth-aware upsample of the half-resolution lighting buffers: bilinear weights, reduced for texels whose
// distance differs from this pixel's so light and shadow do not bleed across silhouettes.
void upsampleLighting(float dist, out vec4 lighting, out vec4 points) {
    ivec2 size = textureSize(LightingSampler, 0);
    vec2 coord = texCoord * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(coord));
    vec2 f = coord - floor(coord);
    float tolerance = 0.03 * dist + 0.1;
    lighting = vec4(0.0);
    points = vec4(0.0);
    float total = 0.0;
    for (int i = 0; i < 4; i++) {
        ivec2 offset = ivec2(i & 1, i >> 1);
        ivec2 texel = clamp(base + offset, ivec2(0), size - 1);
        vec4 sampleLighting = texelFetch(LightingSampler, texel, 0);
        float w = (offset.x == 1 ? f.x : 1.0 - f.x) * (offset.y == 1 ? f.y : 1.0 - f.y);
        w *= exp(-abs(unpackDistance(sampleLighting.ba) - dist) / tolerance) + 1e-4;
        lighting += sampleLighting * w;
        points += texelFetch(PointLightsSampler, texel, 0) * w;
        total += w;
    }
    lighting /= total;
    points /= total;
}

// Moving light patterns that sunlight forms through a wavy water surface: thin bright lines where three
// warped wave fields cross zero, at two scales.
float causticPattern(vec2 p, float t) {
    float a = sin(p.x * 1.7 + t * 1.1 + sin(p.y * 1.3 - t * 0.7) * 1.4);
    float b = sin(p.y * 1.9 - t * 0.9 + sin(p.x * 1.1 + t * 0.6) * 1.4);
    float c = sin((p.x + p.y) * 1.2 + t * 0.8 + sin((p.x - p.y) * 1.6 - t) * 1.2);
    return pow(1.0 - abs((a + b + c) / 3.0), 4.0);
}

float caustics(vec3 world, float t) {
    return causticPattern(world.xz * 0.9, t) * 0.65 + causticPattern(world.xz * 1.7 + 13.0, t * 1.3) * 0.35;
}

// How much of a pixel is covered by something drawn over the terrain between the two snapshots, mostly
// WeatherStory's mist and sand. Measured towards the fog colour, plus any change at all for other overlays.
float veilAmount(vec3 base, vec3 withFeatures) {
    vec3 diff = withFeatures - base;
    vec3 towardFog = HorizonColor.rgb - base;
    float fogLength = dot(towardFog, towardFog);
    float alongFog = fogLength > 1e-4 ? dot(diff, towardFog) / fogLength : 0.0;
    return clamp(max(alongFog, length(diff) * 1.5), 0.0, 1.0);
}

void main() {
    vec3 color = texture(SceneSampler, texCoord).rgb;
    float depth = texture(DepthSampler, texCoord).r;
    bool underwater = Weather.w > 0.5 && Weather.w < 1.5;

    if (isSky(depth)) {
        // Soft glow around the sun so it blooms like a real light source.
        vec3 viewDir = normalize(viewPosFromDepth(texCoord, 1.0));
        float towardLight = max(dot(viewDir, LightDir.xyz), 0.0);
        color += LightColor.rgb * LightColor.w * (pow(towardLight, 24.0) * 0.18 + pow(towardLight, 4.0) * 0.08 * Weather.z);
        fragColor = vec4(color, 1.0);
        return;
    }

    vec3 position = viewPosFromDepth(texCoord, depth);
    float dist = length(position);
    vec3 viewDir = position / dist;
    // Effects fade out behind fog: in WeatherStory's mist there is barely any direct light or reflection to see.
    float fog = vanillaFog(viewToWorld(position));
    vec4 lightingBuffer;
    vec4 pointBuffer;
    upsampleLighting(dist, lightingBuffer, pointBuffer);

    // Water comes from its own half-resolution pass; only apply it where this pixel really is translucent so the
    // blurrier edges of that buffer stay off the shore.
    float veil = 0.0;
    vec4 water = texture(WaterSampler, texCoord);
    vec3 opaque = viewPosFromDepth(texCoord, texture(OpaqueDepthSampler, texCoord).r);
    if (water.a > 0.002 && position.z - opaque.z > 0.02) {
        vec3 deep = color * mix(1.0, 0.72, Look1.y);
        color = deep * (1.0 - water.a) + water.rgb * 2.0;
    } else {
        // Lighting belongs to the terrain; WeatherStory's mist and sand wisps in front of it must not be shaded.
        vec3 base = texture(OpaqueColorSampler, texCoord).rgb;
        veil = veilAmount(base, texture(FeatureColorSampler, texCoord).rgb);
        vec3 lighting = vec3(mix(1.0, lightingBuffer.r, 0.85));

        // With cloud shadows the clouds dim the sun locally, so overall cloud cover must not weaken every shadow.
        bool clouds = CloudParams.x > 0.0;
        // Thick fog (a short fog distance) diffuses the sunlight, so shadows get weaker too.
        float strength = LightDir.w * (clouds ? 1.0 : 1.0 - Weather.y * 0.6) * Quality1.w * smoothstep(24.0, 160.0, Quality1.z);
        if (Quality1.x > 0.0 && strength > 0.01) {
            float visibility = lightingBuffer.g;
            // Shade is lit by the blue sky; sunlit surfaces pick up a hint of the sun's colour.
            vec3 shade = vec3(0.52, 0.58, 0.72);
            vec3 sunlit = vec3(1.0) + (LightColor.rgb - 0.85) * 0.15;
            lighting *= mix(vec3(1.0), mix(shade, sunlit, visibility), strength);
        }
        color = max(color - base * (1.0 - lighting) * (1.0 - veil) * (1.0 - fog), 0.0);
    }

    // Under water, sunlight through the waves draws caustics on the bottom and walls, fading with depth and fog.
    if (underwater && LightDir.w > 0.01) {
        vec3 world = viewToWorld(position) + CameraPos.xyz;
        float outdoors = 1.0 - undergroundFactor(world);
        vec3 normal = normalFromDepth(DepthSampler, texCoord, position);
        float facingUp = clamp(viewToWorld(normal).y * 0.7 + 0.3, 0.0, 1.0);
        float pattern = caustics(world, CameraPos.w);
        color += color * pattern * LightDir.w * outdoors * facingUp * (1.0 - fog) * 1.6 * vec3(0.8, 1.0, 1.0);
        // Water absorbs red first: the further away, the bluer and darker.
        float absorbed = 1.0 - exp(-dist * 0.06);
        color = mix(color, color * vec3(0.3, 0.72, 0.9), absorbed);
    }

    // Lightning lights up everything outdoors around the flash; the small added term makes it show at night too.
    float lightning = pointBuffer.a * 4.0;
    vec3 flashColor = vec3(0.88, 0.92, 1.0);
    color += color * lightning * flashColor + flashColor * lightning * 0.06 * (1.0 - fog);

    // Dynamic lights: tint by the surface's own colour, strongest in the dark (like vanilla block light in daylight).
    vec3 handLight = pointBuffer.rgb * 4.0;
    if (dot(handLight, handLight) > 1e-5) {
        // Estimate how dark the surroundings are from a few nearby samples, then brighten with one smooth gain so
        // the texture keeps its contrast instead of being normalised pixel by pixel.
        float surroundings = 0.0;
        for (int i = 0; i < 4; i++) {
            vec2 offset = vec2(i < 2 ? -1.0 : 1.0, i % 2 == 0 ? -1.0 : 1.0) * ScreenSize.zw * 6.0;
            vec3 neighbour = texture(SceneSampler, texCoord + offset).rgb;
            surroundings += max(neighbour.r, max(neighbour.g, neighbour.b));
        }
        float gain = min(0.6 / max(surroundings * 0.25, 0.04), 8.0);
        color += color * gain * handLight * (1.0 - luma(color)) * 1.5;
    }

    // Aerial perspective: distant terrain fades into a sun-tinted haze.
    if (!underwater && Weather.w < 1.5) {
        // Thicker haze at golden hour, light at midday so colours stay saturated.
        float density = 0.0022 * Look1.x * (1.0 + Weather.y * 1.5 + Weather.z) * mix(0.4, 1.0, Quality1.w) * mix(0.3, 1.0, HorizonColor.w);
        float haze = (1.0 - exp(-dist * density)) * 0.6;
        // Where WeatherStory already fogs the view (mist, sandstorms), our haze would only wash it out further.
        haze *= (1.0 - veil) * smoothstep(24.0, 128.0, Quality1.z);
        float towardLight = pow(max(dot(viewDir, LightDir.xyz), 0.0), 6.0);
        vec3 airColor = mix(HorizonColor.rgb, SkyColor.rgb, 0.35 * (1.0 - Weather.z));
        vec3 scatter = mix(airColor, LightColor.rgb, towardLight * LightColor.w * (0.35 + 0.65 * Weather.z));
        color = mix(color, scatter, haze);
    }

    fragColor = vec4(color, 1.0);
}
