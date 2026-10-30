// Rain helpers for passes that include terrain.glsl: puddles, drop ripples and where rain reaches.
// Misc.z is how wet the world is (0 dry .. 1 after a while of rain), Weather.x how hard it rains right now.

float rainHash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float rainNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(rainHash(i), rainHash(i + vec2(1.0, 0.0)), f.x),
               mix(rainHash(i + vec2(0.0, 1.0)), rainHash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// Where the rain falls on a point: at the top of its column (leaves count, so it stays dry under trees).
float rainExposure(vec3 world) {
    return smoothstep(terrainTop(world.xz) - 0.6, terrainTop(world.xz) - 0.1, world.y);
}

// Patches where water collects, snapped to the 16x16 block texture grid; they grow the longer it rains.
float puddleMask(vec2 worldXZ, float wetness) {
    vec2 p = floor(worldXZ * 16.0) / 16.0;
    float n = rainNoise(p * 0.35) * 0.65 + rainNoise(p * 1.1 + 17.0) * 0.35;
    float threshold = mix(0.78, 0.5, wetness);
    return smoothstep(threshold, threshold + 0.06, n) * wetness;
}

// Rings spreading from where drops land, one drop at a time per half-block cell. Returns the surface slope (xz).
vec2 rainRipples(vec2 worldXZ, float t) {
    vec2 p = floor(worldXZ * 16.0) / 16.0 * 2.0;
    vec2 cell = floor(p);
    vec2 slope = vec2(0.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 c = cell + vec2(float(x), float(y));
            float phase = fract(t * 0.9 + rainHash(c));
            vec2 center = c + 0.5 + (vec2(rainHash(c + 5.3), rainHash(c + 9.1)) - 0.5) * 0.6;
            vec2 d = p - center;
            float r = length(d);
            float ring = r - phase * 1.1;
            float amplitude = (1.0 - phase) * (1.0 - phase) * exp(-ring * ring * 30.0);
            slope += (r > 1e-3 ? d / r : vec2(0.0)) * sin(ring * 25.0) * amplitude;
        }
    }
    return slope;
}
