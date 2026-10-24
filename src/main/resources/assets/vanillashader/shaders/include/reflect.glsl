// Reflection helpers for passes that declare `uniform sampler2D DepthSampler;`.

vec3 skyRadiance(vec3 viewDir) {
    float up = viewToWorld(viewDir).y;
    vec3 color = mix(HorizonColor.rgb, SkyColor.rgb, smoothstep(-0.05, 0.45, up));
    float towardLight = max(dot(viewDir, LightDir.xyz), 0.0);
    color += LightColor.rgb * LightColor.w * (pow(towardLight, 6.0) * (0.25 + 0.6 * Weather.z) + pow(towardLight, 64.0) * 0.5);
    // Reflections of the sky look far too bright in caves and under overhangs.
    return color * mix(0.25, 1.0, HorizonColor.w);
}

// Screen-space ray march. Returns the hit uv in xy and a confidence in z.
vec3 traceReflection(vec3 origin, vec3 dir) {
    int steps = int(Quality0.y);
    int refine = int(Quality0.z);
    float maxDistance = min(Quality1.z, 128.0);
    // Fixed per pixel: a pattern that changes every frame makes the water shimmer.
    float jitter = gradientNoise(gl_FragCoord.xy);
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
