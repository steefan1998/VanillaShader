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
uniform sampler2D DhMaskSampler;        // Distant Horizons terrain where the depth shows sky: RG = distance,
                                        // B = sun facing, A > 0.5; A = 0.25 on DH's clouds (see dh_mask.fsh)

#moj_import <vanillashader:terrain.glsl>
#moj_import <vanillashader:rain.glsl>
#moj_import <vanillashader:reflect.glsl>

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

// Light added by a rainbow along this view direction: the primary bow 40-42 degrees around the point opposite the
// sun (red outside, violet inside), a fainter secondary bow at 51-54 degrees with the colours reversed, a little
// extra light inside the primary and Alexander's darker band between the two (returned in w, as a multiplier).
vec4 rainbow(vec3 viewDir) {
    float angle = degrees(acos(clamp(dot(viewDir, -LightDir.xyz), -1.0, 1.0)));
    float primary = (angle - 40.0) / 2.4;
    float secondary = (54.0 - angle) / 3.2;
    vec3 bow = vec3(0.0);
    if (primary > 0.0 && primary < 1.0) {
        bow += spectrum(primary) * sin(primary * 3.14159);
    }
    if (secondary > 0.0 && secondary < 1.0) {
        bow += spectrum(secondary) * sin(secondary * 3.14159) * 0.4;
    }
    float inside = (1.0 - smoothstep(30.0, 40.0, angle)) * smoothstep(5.0, 30.0, angle) * 0.08;
    float darkBand = smoothstep(42.4, 44.0, angle) * (1.0 - smoothstep(49.0, 51.0, angle));
    return vec4(bow * 0.3 + inside * vec3(1.0), 1.0 - darkBand * 0.06);
}

// After sunset (and before sunrise) the sky on the sun's side glows: deep red right at the horizon, turning pink and
// violet higher up, over a wide arc around the sun's bearing (not around the sun itself, which is below the
// horizon). RGB = the glow's colour, A = how strongly it shows in this direction.
vec4 afterglow(vec3 viewDir) {
    if (Twilight.x <= 0.001) {
        return vec4(0.0);
    }
    vec3 dirWorld = viewToWorld(viewDir);
    vec3 sunWorld = viewToWorld(Twilight.yzw);
    float up = dirWorld.y;
    float bearing = dot(normalize(dirWorld.xz + vec2(1e-5)), normalize(sunWorld.xz + vec2(1e-5))) * 0.5 + 0.5;
    float band = (1.0 - smoothstep(0.0, 0.45, up)) * smoothstep(-0.15, 0.02, up);
    vec3 glowColor = mix(vec3(0.95, 0.28, 0.12), vec3(0.75, 0.35, 0.6), smoothstep(0.03, 0.3, up));
    return vec4(glowColor, Twilight.x * band * (0.25 + 0.75 * pow(bearing, 2.5)));
}

void main() {
    vec3 color = texture(SceneSampler, texCoord).rgb;
    float depth = texture(DepthSampler, texCoord).r;
    bool underwater = Weather.w > 0.5 && Weather.w < 1.5;
    // Distant Horizons' terrain is sky to the depth buffer, but must never get the sky's colouring. By default it is
    // left exactly as DH draws it; A = how much of our image a pixel keeps, which the final pass uses to leave the
    // grading off it as well. With "shade Distant Horizons terrain" on (DhParams.w = 2) it gets the haze, silhouettes
    // and grading of terrain instead, at its real distance.
    vec4 distantMask = texture(DhMaskSampler, texCoord);
    bool distant = isSky(depth) && distantMask.a > 0.5;
    if (distant && DhParams.w < 1.5) {
        fragColor = vec4(color, 0.0);
        return;
    }

    if (isSky(depth) && !distant) {
        // Soft glow around the sun so it blooms like a real light source.
        vec3 viewDir = normalize(viewPosFromDepth(texCoord, FAR_DEPTH)); // far away, so view bobbing's small sideways shift does not swing it; the far plane itself can be degenerate
        float towardLight = max(dot(viewDir, LightDir.xyz), 0.0);
        // Golden hour: the whole sky turns amber, yellow near the horizon and around the sun, deeper orange higher up.
        // The moon is bluish (blue > red), so its side of the sky gets no sun lobe during twilight.
        float sunLike = step(LightColor.b, LightColor.r);
        float golden = Weather.z * (1.0 - Weather.x) * smoothstep(24.0, 128.0, Quality1.z); // not in rain or thick fog
        // Distant Horizons' clouds are lit like clouds rather than coloured like the sky behind them: they keep more
        // of their own shape and catch the low sun.
        bool dhCloud = distantMask.a > 0.1 && distantMask.a < 0.5;
        if (golden > 0.001) {
            // The sun disc itself (the brightest pixels) stays visible through the colouring.
            float disc = smoothstep(0.75, 0.95, luma(color));
            color = mix(color, goldenHour(color, viewDir, sunLike, dhCloud ? 0.65 : 0.35), min(golden * 1.6, 1.0) * (dhCloud ? 0.75 : 0.9) * (1.0 - disc));
        }
        if (dhCloud) {
            color += color * LightColor.rgb * LightColor.w * pow(towardLight, 3.0) * 0.35;
        }
        // The afterglow tints by brightness rather than painting over, so clouds and stars keep their shape.
        vec4 glow = afterglow(viewDir);
        if (glow.a > 0.001) {
            color = mix(color, glow.rgb * (0.35 + luma(color) * 1.2), min(glow.a, 1.0) * 0.8);
        }
        color += LightColor.rgb * LightColor.w * (pow(towardLight, 24.0) * 0.18
            + (pow(towardLight, 4.0) * 0.2 + pow(towardLight, 1.5) * 0.05) * golden * sunLike);
        if (Misc2.x > 0.001) {
            vec4 bow = rainbow(viewDir);
            color = color * mix(1.0, bow.w, Misc2.x) + bow.rgb * Misc2.x * LightColor.w * HorizonColor.w;
        }
        fragColor = vec4(finiteOr(color, texture(SceneSampler, texCoord).rgb), 1.0);
        return;
    }

    // DH's terrain lies along the same ray as the sky would (view bobbing's sideways shift does not matter that far).
    vec3 position = distant
        ? normalize(viewPosFromDepth(texCoord, FAR_DEPTH)) * max(unpackFarDistance(distantMask.rg), 1.0)
        : viewPosFromDepth(texCoord, depth);
    float dist = length(position);
    vec3 viewDir = position / dist;
    // Effects fade out behind fog: in WeatherStory's mist there is barely any direct light or reflection to see.
    // DH's terrain lies beyond the render distance fog by definition; only real (environmental) fog counts there.
    float fog = distant
        ? clamp((dist - FogParams.x) / max(FogParams.y - FogParams.x, 1e-3), 0.0, 1.0)
        : vanillaFog(viewToWorld(position));
    vec4 lightingBuffer = vec4(1.0);
    vec4 pointBuffer = vec4(0.0);
    vec4 tracedLight = vec4(0.0);
    if (!distant) {
        upsampleLighting(dist, lightingBuffer, pointBuffer, tracedLight);
    }

    // Water comes from its own half-resolution pass; only apply it where this pixel really is translucent so the
    // blurrier edges of that buffer stay off the shore.
    float veil = 0.0;
    vec4 water = texture(WaterSampler, texCoord);
    vec3 opaque = viewPosFromDepth(texCoord, texture(OpaqueDepthSampler, texCoord).r);
    if (distant) {
        // The lighting, water and reflection buffers know nothing about DH's terrain, and the shadow map does not
        // reach it. It is lit from its own facing instead (dh_mask.fsh): the sunny side warm, the far side in the
        // cool shade of the sky, a little softer than near terrain since DH already shades its faces.
        float strength = LightDir.w * (1.0 - Weather.y * 0.6) * Quality1.w * smoothstep(24.0, 160.0, Quality1.z) * (1.0 - fog);
        // The farther, the more block faces share a pixel and the less its facing means, until at the far edge, seen
        // almost edge-on, it is no better than noise: the lighting fades out, so pixels do not flicker between sun
        // and shade.
        float grazing = smoothstep(300.0, 1000.0, dist);
        strength *= 1.0 - grazing;
        if (Quality1.x > 0.0 && strength > 0.01) {
            float visibility = smoothstep(0.25, 0.85, distantMask.b);
            vec3 shade = vec3(0.52, 0.58, 0.72);
            vec3 sunlit = vec3(1.0) + (LightColor.rgb - 0.85) * mix(0.15, 0.5, Weather.z);
            color *= mix(vec3(1.0), mix(shade, sunlit, visibility), strength * 0.45);
        }
        // Rain soaks DH's terrain too, its tops most (evenly where the facing is noise).
        float up = clamp((distantMask.a - 0.6) / 0.4, 0.0, 1.0);
        color *= mix(1.0, 0.8, Misc.z * mix(mix(0.45, 1.0, up), 0.7, grazing) * (1.0 - fog));
        // DH does not say which LODs are water; seas and lakes at sea level are flat, level with it and blue. They
        // reflect the sky and the sun like near water (without the screen-space part, which does not reach that far).
        // Told by height and colour only, which hold still; the facing from the depth buffer shifts a little from frame
        // to frame and made distant water flicker in and out. A degree of "water", not a yes or no.
        float height = viewToWorld(position).y + CameraPos.y;
        // No reflections on the farthest water: they add little there and kept the far corner flickering.
        float lodWater = smoothstep(60.5, 61.5, height) * (1.0 - smoothstep(300.0, 800.0, dist)) * (1.0 - smoothstep(64.0, 65.0, height))
            * smoothstep(1.02, 1.15, color.b / max(color.r, 1e-3)) * smoothstep(0.9, 1.0, color.b / max(color.g, 1e-3));
        if (Quality0.y >= 0.0 && lodWater > 0.001) {
            vec3 reflected = reflect(viewDir, viewUp());
            float fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(-viewDir, viewUp()), 0.0), 5.0);
            float blend = clamp(mix(0.12, 1.0, fresnel) * Look1.y, 0.0, 0.92) * (1.0 - fog) * lodWater;
            float towardSun = max(dot(reflected, LightDir.xyz), 0.0);
            float glint = pow(towardSun, 90.0) * 0.9 + pow(towardSun, 60.0) * 0.25;
            color = mix(color * mix(1.0, 0.72, Look1.y * lodWater), skyRadiance(reflected), blend)
                + LightColor.rgb * LightColor.w * glint * Look1.y * HorizonColor.w * (1.0 - fog) * lodWater;
        }
    } else if (water.a > 0.002 && position.z - opaque.z > 0.02) {
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
            // Shaded Distant Horizons terrain is lit from its facing alone; over the outer part of the render distance
            // the chunks' shadows hand over to that, so the two meet without a seam.
            if (DhParams.w > 1.5) {
                float handover = smoothstep(Misc2.y, Misc2.z, length(viewToWorld(position).xz));
                if (handover > 0.0) {
                    float facing = smoothstep(-0.1, 0.3, dot(normalFromDepth(DepthSampler, texCoord, position), LightDir.xyz));
                    visibility = mix(visibility, facing, handover);
                    strength *= mix(1.0, 0.6, handover);
                }
            }
            // Shade is lit by the blue sky; sunlit surfaces pick up a hint of the sun's colour.
            vec3 shade = vec3(0.52, 0.58, 0.72);
            // ...strongly at golden hour, when the low sun paints everything it reaches orange.
            vec3 sunlit = vec3(1.0) + (LightColor.rgb - 0.85) * mix(0.15, 0.5, Weather.z);
            lighting *= mix(vec3(1.0), mix(shade, sunlit, visibility), strength);
        }
        color = max(color - base * (1.0 - lighting) * (1.0 - veil) * (1.0 - fog), 0.0);
        // Rain soaks everything it falls on: wet surfaces turn darker and a little more saturated (tops more than
        // walls), and puddles from the water pass reflect the sky.
        if (Misc.z > 0.01) {
            vec3 world = viewToWorld(position) + CameraPos.xyz;
            vec3 normalWorld = viewToWorld(normalFromDepth(DepthSampler, texCoord, position));
            float exposed = rainExposure(world + normalWorld * 0.1);
            float soak = Misc.z * exposed * (1.0 - fog) * (1.0 - veil) * mix(0.45, 1.0, clamp(normalWorld.y, 0.0, 1.0));
            if (soak > 0.001) {
                color *= mix(1.0, 0.74, soak);
                color = max(mix(vec3(luma(color)), color, 1.0 + 0.2 * soak), 0.0);
                // The water buffer is half resolution; only use it inside a puddle, so water does not bleed onto the shore.
                if (water.a > 0.002 && normalWorld.y > 0.9 && puddleMask(world.xz, Misc.z) > 0.02) {
                    color = color * (1.0 - water.a) + water.rgb * 2.0;
                }
            }
        }
        // Ray-traced reflections on glossy blocks (obsidian, metal, glass, polished stone).
        vec4 traced = texture(RtReflectSampler, texCoord);
        if (traced.a > 0.002) {
            color = color * (1.0 - traced.a) + traced.rgb * 2.0;
        }
    }

    // Under water, sunlight through the waves draws caustics on the bottom and walls, fading with depth and fog.
    if (underwater && !distant && LightDir.w > 0.01) {
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
        // Under a white sky (The Aether) the haze would bury the terrain in white.
        haze *= mix(1.0, 0.3, Misc.w);
        // At sunset the glow around the sun is wider and the air itself turns amber.
        float towardLight = pow(max(dot(viewDir, LightDir.xyz), 0.0), mix(6.0, 3.5, sunsetSun));
        vec3 airColor = mix(HorizonColor.rgb, SkyColor.rgb, 0.35 * (1.0 - Weather.z));
        airColor = mix(airColor, vec3(0.95, 0.6, 0.3), sunsetSun * 0.6);
        // After sunset the haze on the sun's side takes on the afterglow.
        vec4 glow = afterglow(viewDir);
        airColor = mix(airColor, glow.rgb * 0.6, min(glow.a, 1.0) * 0.6);
        vec3 scatter = mix(airColor, LightColor.rgb, towardLight * LightColor.w * (0.35 + 0.65 * Weather.z));
        color = mix(color, scatter, haze);
    }

    // The rainbow also stands in front of hills further away, where the rain between them and the viewer catches the
    // sun; never on nearby blocks.
    if (Misc2.x > 0.001 && !underwater) {
        vec4 bow = rainbow(viewDir);
        float far = smoothstep(48.0, 160.0, dist) * (1.0 - veil);
        color += bow.rgb * Misc2.x * LightColor.w * HorizonColor.w * far * 0.7;
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

    // With Distant Horizons left alone, our effects fade out towards the render distance, so the chunks blend into
    // DH's terrain instead of meeting it in a hard seam (no fade when DH's terrain is shaded too).
    vec3 scene = texture(SceneSampler, texCoord).rgb;
    float shaded = 1.0;
    if (DhParams.w > 0.5 && DhParams.w < 1.5) {
        shaded = 1.0 - smoothstep(Misc2.y, Misc2.z, length(viewToWorld(position).xz));
        color = mix(scene, color, shaded);
    }
    fragColor = vec4(finiteOr(color, scene), shaded);
}
