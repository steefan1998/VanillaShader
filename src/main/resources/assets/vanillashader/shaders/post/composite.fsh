#version 330

#moj_import <vanillashader:common.glsl>
#moj_import <vanillashader:frame.glsl>

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;        // everything, including water and glass
uniform sampler2D OpaqueDepthSampler;  // only opaque geometry
uniform sampler2D LightingSampler;       // half res: R = AO, G = sun visibility, BA = packed distance
uniform sampler2D PointLightsSampler;    // half res: RGB = point light / 4, A = lightning / 4
uniform sampler2D WaterSampler;          // half res: RGB = weighted reflection + glint / 2, A = blend (0 = no water)
uniform sampler2D RtLightSampler;        // half res: ray-traced light from emissive blocks / 4 (black when ray tracing is off)
uniform sampler2D RtReflectSampler;      // half res: ray-traced glossy reflections, premultiplied / 2, A = blend
uniform sampler2D OpaqueColorSampler;   // level after opaque geometry
uniform sampler2D FeatureColorSampler;  // ...and after translucent features (WeatherStory mist and sand wisps)
uniform sampler2D ShadowMapSampler;     // height map, also marks which columns have water on top

#moj_import <vanillashader:terrain.glsl>

in vec2 texCoord;

out vec4 fragColor;

// Depth-aware upsample of the half-resolution lighting buffers: bilinear weights, reduced for texels whose
// distance differs from this pixel's so light and shadow do not bleed across silhouettes.
void upsampleLighting(float dist, out vec4 lighting, out vec4 points, out vec4 traced) {
    ivec2 size = textureSize(LightingSampler, 0);
    vec2 coord = texCoord * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(coord));
    vec2 f = coord - floor(coord);
    float tolerance = 0.03 * dist + 0.1;
    lighting = vec4(0.0);
    points = vec4(0.0);
    traced = vec4(0.0);
    float total = 0.0;
    for (int i = 0; i < 4; i++) {
        ivec2 offset = ivec2(i & 1, i >> 1);
        ivec2 texel = clamp(base + offset, ivec2(0), size - 1);
        vec4 sampleLighting = texelFetch(LightingSampler, texel, 0);
        float w = (offset.x == 1 ? f.x : 1.0 - f.x) * (offset.y == 1 ? f.y : 1.0 - f.y);
        w *= exp(-abs(unpackDistance(sampleLighting.ba) - dist) / tolerance) + 1e-4;
        lighting += sampleLighting * w;
        points += texelFetch(PointLightsSampler, texel, 0) * w;
        traced += texelFetch(RtLightSampler, texel, 0) * w;
        total += w;
    }
    lighting /= total;
    points /= total;
    traced /= total;
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

// Golden hour sky colour: yellow near the horizon and around the sun, deeper orange higher up. `shape` is how much
// of the original brightness is kept, so clouds keep their form while glowing orange.
vec3 goldenHour(vec3 color, vec3 viewDir, float sunLike, float shape) {
    float sunSide = sunLike * pow(max(dot(viewDir, LightDir.xyz), 0.0), 2.0);
    float up = viewToWorld(viewDir).y;
    vec3 amber = mix(vec3(1.0, 0.66, 0.3), vec3(0.92, 0.44, 0.17), smoothstep(0.0, 0.55, up));
    // Below the horizon (only seen through fog or from high up) the glow fades towards a darker orange.
    amber *= mix(0.55, 1.0, smoothstep(-0.25, 0.02, up));
    amber = mix(amber, vec3(1.0, 0.8, 0.5), sunSide * 0.6);
    return amber * mix(0.7 + 0.35 * sunSide, min(luma(color) * 2.2 + 0.2, 1.3), shape);
}

void main() {
    vec3 color = texture(SceneSampler, texCoord).rgb;
    float depth = texture(DepthSampler, texCoord).r;
    bool underwater = Weather.w > 0.5 && Weather.w < 1.5;

    if (isSky(depth)) {
        // Soft glow around the sun so it blooms like a real light source.
        vec3 viewDir = normalize(viewPosFromDepth(texCoord, FAR_DEPTH)); // far away, so view bobbing's small sideways shift does not swing it; the far plane itself can be degenerate
        float towardLight = max(dot(viewDir, LightDir.xyz), 0.0);
        // Golden hour: the whole sky turns amber, yellow near the horizon and around the sun, deeper orange higher up.
        // The moon is bluish (blue > red), so its side of the sky gets no sun lobe during twilight.
        float sunLike = step(LightColor.b, LightColor.r);
        float golden = Weather.z * (1.0 - Weather.x) * smoothstep(24.0, 128.0, Quality1.z); // not in rain or thick fog
        if (golden > 0.001) {
            // The sun disc itself (the brightest pixels) stays visible through the colouring.
            float disc = smoothstep(0.75, 0.95, luma(color));
            color = mix(color, goldenHour(color, viewDir, sunLike, 0.35), min(golden * 1.6, 1.0) * 0.9 * (1.0 - disc));
        }
        color += LightColor.rgb * LightColor.w * (pow(towardLight, 24.0) * 0.18
            + (pow(towardLight, 4.0) * 0.2 + pow(towardLight, 1.5) * 0.05) * golden * sunLike);
        fragColor = vec4(finiteOr(color, texture(SceneSampler, texCoord).rgb), 1.0);
        return;
    }

    vec3 position = viewPosFromDepth(texCoord, depth);
    float dist = length(position);
    vec3 viewDir = position / dist;
    // Effects fade out behind fog: in WeatherStory's mist there is barely any direct light or reflection to see.
    float fog = vanillaFog(viewToWorld(position));
    vec4 lightingBuffer;
    vec4 pointBuffer;
    vec4 tracedLight;
    upsampleLighting(dist, lightingBuffer, pointBuffer, tracedLight);

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
            // ...strongly at golden hour, when the low sun paints everything it reaches orange.
            vec3 sunlit = vec3(1.0) + (LightColor.rgb - 0.85) * mix(0.15, 0.5, Weather.z);
            lighting *= mix(vec3(1.0), mix(shade, sunlit, visibility), strength);
        }
        color = max(color - base * (1.0 - lighting) * (1.0 - veil) * (1.0 - fog), 0.0);
        // Ray-traced reflections on glossy blocks (obsidian, metal, glass, polished stone).
        vec4 traced = texture(RtReflectSampler, texCoord);
        if (traced.a > 0.002) {
            color = color * (1.0 - traced.a) + traced.rgb * 2.0;
        }
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
    // Ray-traced light from emissive blocks joins the point lights here.
    vec3 handLight = pointBuffer.rgb * 4.0 + tracedLight.rgb * 4.0;
    if (dot(handLight, handLight) > 1e-5) {
        // Estimate how dark the surroundings are from a few nearby samples, then brighten with one smooth gain so
        // the texture keeps its contrast instead of being normalised pixel by pixel.
        float surroundings = 0.0;
        for (int i = 0; i < 4; i++) {
            vec2 offset = vec2(i < 2 ? -1.0 : 1.0, i % 2 == 0 ? -1.0 : 1.0) * ScreenSize.zw * 6.0;
            vec3 neighbour = texture(SceneSampler, texCoord + offset).rgb;
            surroundings += max(neighbour.r, max(neighbour.g, neighbour.b));
        }
        float gain = min(0.6 / max(surroundings * 0.25, 0.04), 4.0);
        // Saturating curve: right next to a light it levels off instead of blowing the surface out to white.
        vec3 added = handLight / (1.0 + handLight);
        color += color * gain * added * (1.0 - luma(color)) * 1.5;
    }

    // Looking into a low sun, everything shows its shaded side: terrain becomes a dark silhouette against the sky.
    float sunsetSun = Weather.z * step(LightColor.b, LightColor.r) * (1.0 - Weather.x) * HorizonColor.w * smoothstep(24.0, 128.0, Quality1.z);
    if (!underwater && sunsetSun > 0.001) {
        float backlit = pow(max(dot(viewDir, LightDir.xyz), 0.0), 3.0) * smoothstep(8.0, 48.0, dist);
        color *= 1.0 - sunsetSun * backlit * 0.4 * (1.0 - fog) * (1.0 - veil);
    }

    // Aerial perspective: distant terrain fades into a sun-tinted haze.
    if (!underwater && Weather.w < 1.5) {
        // Thicker haze at golden hour, light at midday so colours stay saturated.
        float density = 0.0022 * Look1.x * (1.0 + Weather.y * 1.5 + Weather.z) * mix(0.4, 1.0, Quality1.w) * mix(0.3, 1.0, HorizonColor.w);
        float haze = (1.0 - exp(-dist * density)) * 0.6;
        // Where WeatherStory already fogs the view (mist, sandstorms), our haze would only wash it out further.
        haze *= (1.0 - veil) * smoothstep(24.0, 128.0, Quality1.z);
        // At sunset the glow around the sun is wider and the air itself turns amber.
        float towardLight = pow(max(dot(viewDir, LightDir.xyz), 0.0), mix(6.0, 3.5, sunsetSun));
        vec3 airColor = mix(HorizonColor.rgb, SkyColor.rgb, 0.35 * (1.0 - Weather.z));
        airColor = mix(airColor, vec3(0.95, 0.6, 0.3), sunsetSun * 0.6);
        vec3 scatter = mix(airColor, LightColor.rgb, towardLight * LightColor.w * (0.35 + 0.65 * Weather.z));
        color = mix(color, scatter, haze);
    }

    // Clouds catch the low sun as well: anything floating far above the terrain glows orange at golden hour.
    if (sunsetSun > 0.001) {
        // Above both the terrain and the camera, and looked up at, so far-away hills never count as cloud (the
        // height map can lag right after a teleport).
        vec3 offset = viewToWorld(position);
        float floorY = max(Misc.x, CameraPos.y) + 8.0;
        float cloud = smoothstep(floorY, floorY + 16.0, offset.y + CameraPos.y) * smoothstep(0.02, 0.1, offset.y / dist)
            * smoothstep(48.0, 128.0, dist);
        color = mix(color, goldenHour(color, viewDir, 1.0, 0.5), cloud * min(sunsetSun * 1.6, 1.0) * 0.85);
    }

    fragColor = vec4(finiteOr(color, texture(SceneSampler, texCoord).rgb), 1.0);
}
