// Height map lookups for passes that declare `uniform sampler2D ShadowMapSampler;` (see ShadowHeightmap).
const float SHADOW_MAP_SIZE = 512.0;

// First free block heights of a world column: x = including leaves, y = without leaves.
// Both are a huge negative value where the column is not loaded.
vec2 terrainHeights(vec2 worldXZ) {
    vec2 texel = mod(floor(worldXZ), SHADOW_MAP_SIZE);
    vec4 data = floor(texture(ShadowMapSampler, (texel + 0.5) / SHADOW_MAP_SIZE) * 255.0 + 0.5);
    if (data.a < 64.0) {
        return vec2(-1e6);
    }
    float top = data.r * 16.0 + floor(data.g / 16.0);
    float ground = mod(data.g, 16.0) * 256.0 + data.b;
    return vec2(top, ground) + DepthParams.w;
}

// 1 when the top block of the column holds water, 0 when it does not (ice, glass), -1 when unknown.
float topIsWater(vec2 worldXZ) {
    vec2 texel = mod(floor(worldXZ), SHADOW_MAP_SIZE);
    float a = floor(texture(ShadowMapSampler, (texel + 0.5) / SHADOW_MAP_SIZE).a * 255.0 + 0.5);
    return a < 64.0 ? -1.0 : (a > 192.0 ? 1.0 : 0.0);
}

float terrainTop(vec2 worldXZ) {
    return terrainHeights(worldXZ).x;
}

// How far a point is in a cave or room, measured against the ground without leaves so that the floor under even
// the tallest jungle canopy still counts as outdoors. 0 = outside, 1 = well underground.
float undergroundFactor(vec3 world) {
    return smoothstep(8.0, 14.0, terrainHeights(world.xz).y - world.y);
}
