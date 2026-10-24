// Shared per-frame data. Must match FrameState.write() field for field.
layout(std140) uniform Frame {
    mat4 Proj;
    mat4 InvProj;
    mat4 InvView;       // view space -> camera-relative world space (rotation only)
    vec4 ScreenSize;    // width, height, 1/width, 1/height
    vec4 LightDir;      // sun or moon direction in view space, w = intensity
    vec4 LightScreen;   // light position in uv, z = how much of it is on screen, w = aspect ratio
    vec4 LightColor;    // rgb, w = intensity
    vec4 SkyColor;      // zenith rgb, w = day factor
    vec4 HorizonColor;  // fog rgb, w = sky light at the camera (0 underground)
    vec4 CameraPos;     // wrapped world position, w = seconds
    vec4 Weather;       // rain, overcast, sunset, medium (0 air, 1 water, 2 lava/snow)
    vec4 Quality0;      // ao samples, ssr steps (-1 no water fx, 0 sky only), ssr refine steps, god ray samples
    vec4 Quality1;      // sun shadow steps, bloom level, fog distance, is overworld
    vec4 Look0;         // exposure, saturation, contrast, warmth
    vec4 Look1;         // atmosphere, water reflectivity, vignette, has water mask
    vec4 DepthParams;   // ndc z = depth * x + y, z = frame counter, w = world min Y
    vec4 CloudParams;   // WeatherStory cloud shadow map half-width (0 = off), storm, sun x, sun y
    vec4 FogParams;     // vanilla fog: environmental start/end (spherical), render distance start/end (cylindrical)
    vec4 Flashes[4];    // lightning: position relative to the camera, w = brightness (0 = none)
    vec4 DynLightPos[32];   // held, dropped and placed light sources: position relative to the camera, w = radius in blocks
    vec4 DynLightColor[32]; // rgb, w = intensity (0 = unused)
    vec4 Misc;          // x = highest terrain top around the camera, y = number of point lights
    vec4 RayTracing;    // x = on, y = rays per pixel, z = ray length in blocks
};

vec3 viewPosFromDepth(vec2 uv, float depth) {
    vec4 ndc = vec4(uv * 2.0 - 1.0, depth * DepthParams.x + DepthParams.y, 1.0);
    vec4 view = InvProj * ndc;
    return view.xyz / view.w;
}

// Returns uv in xy and the depth-buffer value in z.
vec3 projectToScreen(vec3 viewPos) {
    vec4 clip = Proj * vec4(viewPos, 1.0);
    vec3 ndc = clip.xyz / clip.w;
    return vec3(ndc.xy * 0.5 + 0.5, (ndc.z - DepthParams.y) / DepthParams.x);
}

// How much of a point is hidden by vanilla fog, which WeatherStory pulls in close for mist and sandstorms.
float vanillaFog(vec3 relativeWorld) {
    float spherical = length(relativeWorld);
    float cylindrical = max(length(relativeWorld.xz), abs(relativeWorld.y));
    float environmental = clamp((spherical - FogParams.x) / max(FogParams.y - FogParams.x, 1e-3), 0.0, 1.0);
    float renderDistance = clamp((cylindrical - FogParams.z) / max(FogParams.w - FogParams.z, 1e-3), 0.0, 1.0);
    return max(environmental, renderDistance);
}

// Distance from the camera in blocks packed into two 8-bit channels (0..1024, 1/64 block steps).
vec2 packDistance(float dist) {
    float v = floor(clamp(dist / 1024.0, 0.0, 1.0) * 65535.0 + 0.5);
    return vec2(floor(v / 256.0), mod(v, 256.0)) / 255.0;
}

float unpackDistance(vec2 encoded) {
    vec2 bytes = floor(encoded * 255.0 + 0.5);
    return (bytes.x * 256.0 + bytes.y) / 65535.0 * 1024.0;
}

bool isSky(float depth) {
    return depth >= 0.99999;
}

bool insideScreen(vec2 uv) {
    return uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0;
}

float luma(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

// Interleaved gradient noise: cheap per-pixel jitter that blurs away well.
float gradientNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

vec3 worldToView(vec3 dir) {
    return transpose(mat3(InvView)) * dir;
}

vec3 viewToWorld(vec3 dir) {
    return mat3(InvView) * dir;
}

vec3 viewUp() {
    return worldToView(vec3(0.0, 1.0, 0.0));
}

// Picks the neighbour on the same surface on each axis, which avoids halos at depth edges.
vec3 normalFromDepth(sampler2D depthTex, vec2 uv, vec3 center) {
    vec2 texel = ScreenSize.zw;
    vec3 right = viewPosFromDepth(uv + vec2(texel.x, 0.0), texture(depthTex, uv + vec2(texel.x, 0.0)).r);
    vec3 left = viewPosFromDepth(uv - vec2(texel.x, 0.0), texture(depthTex, uv - vec2(texel.x, 0.0)).r);
    vec3 up = viewPosFromDepth(uv + vec2(0.0, texel.y), texture(depthTex, uv + vec2(0.0, texel.y)).r);
    vec3 down = viewPosFromDepth(uv - vec2(0.0, texel.y), texture(depthTex, uv - vec2(0.0, texel.y)).r);
    vec3 dx = abs(right.z - center.z) < abs(left.z - center.z) ? right - center : center - left;
    vec3 dy = abs(up.z - center.z) < abs(down.z - center.z) ? up - center : center - down;
    // A zero cross product (identical neighbours) would normalize to NaN, which some drivers show as black pixels.
    vec3 n = cross(dx, dy);
    float lengthSq = dot(n, n);
    return lengthSq > 1e-12 ? n * inversesqrt(lengthSq) : vec3(0.0, 0.0, 1.0);
}

// NaN or infinity anywhere turns into black blocks on NVIDIA and Intel; replace it before it is written out.
vec3 finiteOr(vec3 value, vec3 fallback) {
    return any(isnan(value)) || any(isinf(value)) ? fallback : value;
}

vec4 finiteOr(vec4 value, vec4 fallback) {
    return any(isnan(value)) || any(isinf(value)) ? fallback : value;
}
