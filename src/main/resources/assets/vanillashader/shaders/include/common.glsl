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

// NaN or infinity anywhere turns into black blocks on NVIDIA and Intel; replace it before it is written out.
vec3 finiteOr(vec3 value, vec3 fallback) {
    return any(isnan(value)) || any(isinf(value)) ? fallback : value;
}

vec4 finiteOr(vec4 value, vec4 fallback) {
    return any(isnan(value)) || any(isinf(value)) ? fallback : value;
}
