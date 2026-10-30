// Helpers without uniforms, so passes that do not read the frame block (the denoise) can use them too.

// Distance from the camera in blocks packed into two 8-bit channels (0..1024, 1/64 block steps).
vec2 packDistance(float dist) {
    float v = floor(clamp(dist / 1024.0, 0.0, 1.0) * 65535.0 + 0.5);
    return vec2(floor(v / 256.0), mod(v, 256.0)) / 255.0;
}

float unpackDistance(vec2 encoded) {
    vec2 bytes = floor(encoded * 255.0 + 0.5);
    return (bytes.x * 256.0 + bytes.y) / 65535.0 * 1024.0;
}

// The same for Distant Horizons' far terrain: up to 32768 blocks in half-block steps.
vec2 packFarDistance(float dist) {
    float v = floor(clamp(dist / 32768.0, 0.0, 1.0) * 65535.0 + 0.5);
    return vec2(floor(v / 256.0), mod(v, 256.0)) / 255.0;
}

float unpackFarDistance(vec2 encoded) {
    vec2 bytes = floor(encoded * 255.0 + 0.5);
    return (bytes.x * 256.0 + bytes.y) / 65535.0 * 32768.0;
}

// Rainbow colours from violet (t = 0) to red (t = 1).
vec3 spectrum(float t) {
    float hue = (1.0 - clamp(t, 0.0, 1.0)) * 0.75;
    vec3 rgb = clamp(abs(fract(hue + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    return rgb * rgb;
}

// NaN or infinity anywhere turns into black blocks on NVIDIA and Intel; replace it before it is written out.
vec3 finiteOr(vec3 value, vec3 fallback) {
    return any(isnan(value)) || any(isinf(value)) ? fallback : value;
}

vec4 finiteOr(vec4 value, vec4 fallback) {
    return any(isnan(value)) || any(isinf(value)) ? fallback : value;
}
