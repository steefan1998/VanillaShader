#version 330

#moj_import <vanillashader:frame.glsl>

// Half-resolution ray-traced light from light-emitting blocks. Rays leave every surface in a cosine-weighted
// spread and walk through the material volume; rays that reach lava, glowstone, fire or another light source
// bring its light back, so walls glow in the light's colour even when the source is off screen.
// RGB = light / 4.

uniform sampler2D DepthSampler;
uniform sampler2D VolumeSampler;

#moj_import <vanillashader:voxels.glsl>

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    if (isSky(depth)) {
        fragColor = vec4(0.0);
        return;
    }
    vec3 position = viewPosFromDepth(texCoord, depth);
    vec3 normal = viewToWorld(normalFromDepth(DepthSampler, texCoord, position));
    vec3 origin = viewToWorld(position) + CameraPos.xyz + normal * 0.02;
    vec3 tangent = normalize(cross(abs(normal.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0), normal));
    vec3 bitangent = cross(normal, tangent);

    int rays = int(RayTracing.y);
    float noise = gradientNoise(gl_FragCoord.xy);
    vec3 light = vec3(0.0);
    for (int i = 0; i < rays; i++) {
        // Cosine-weighted spiral over the hemisphere around the normal.
        float f = (float(i) + noise) / float(rays);
        float angle = float(i) * 2.39996 + noise * 6.28318;
        float r = sqrt(f);
        vec3 dir = tangent * (cos(angle) * r) + bitangent * (sin(angle) * r) + normal * sqrt(1.0 - f);
        vec4 hit = traceVoxels(origin, dir, RayTracing.z);
        if (hit.x == MATERIAL_EMISSIVE) {
            light += emissionColor(hit.z) * (hit.y / 15.0) * 6.0 / (1.0 + hit.w * hit.w * 0.012);
        }
    }
    light /= float(max(rays, 1));
    fragColor = finiteOr(vec4(min(light * 0.25, vec3(1.0)), 1.0), vec4(0.0));
}
