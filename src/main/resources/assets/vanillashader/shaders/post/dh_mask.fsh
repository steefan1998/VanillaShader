#version 330

#moj_import <vanillashader:common.glsl>
#moj_import <vanillashader:frame.glsl>

// Distant Horizons' terrain (LODs) where the level itself shows sky: RG = its distance (packFarDistance),
// B = how much it faces the sun (N.L * 0.5 + 0.5), A = 0.6 + 0.4 * how much it faces up (so A > 0.5 marks DH's
// terrain); A = 0.25 on DH's clouds. Zero everywhere else, so passes that read this see plain sky exactly as
// without DH.

uniform sampler2D DepthSampler;
uniform sampler2D DhDepthSampler;

in vec2 texCoord;

out vec4 fragColor;

// DH's buffer is cleared to its far value: 1, or 0 with reversed Z.
bool emptyLod(float lod) {
    return DhParams.z > 0.5 ? lod <= 0.00001 : lod >= 0.99999;
}

vec3 lodViewPos(vec2 uv, float lod) {
    vec4 view = InvDhProj * vec4(uv * 2.0 - 1.0, lod * DhParams.x + DhParams.y, 1.0);
    return view.xyz / view.w;
}

// The step to the neighbour on the same surface along one axis: of the two neighbours that hold a LOD, the one
// nearer in depth, which avoids halos at depth edges. Zero when neither holds one.
vec3 lodNeighbour(vec3 center, vec2 offset) {
    float plus = texture(DhDepthSampler, texCoord + offset).r;
    float minus = texture(DhDepthSampler, texCoord - offset).r;
    bool hasPlus = !emptyLod(plus);
    bool hasMinus = !emptyLod(minus);
    vec3 a = hasPlus ? lodViewPos(texCoord + offset, plus) - center : vec3(0.0);
    vec3 b = hasMinus ? center - lodViewPos(texCoord - offset, minus) : vec3(0.0);
    if (!hasPlus) {
        return b;
    }
    if (!hasMinus) {
        return a;
    }
    return abs(a.z) < abs(b.z) ? a : b;
}

// The surface normal from the neighbours `pixels` away, facing the camera; zero without neighbours.
vec3 lodNormal(vec3 position, float pixels) {
    vec3 n = cross(lodNeighbour(position, vec2(ScreenSize.z * pixels, 0.0)), lodNeighbour(position, vec2(0.0, ScreenSize.w * pixels)));
    float lengthSq = dot(n, n);
    if (lengthSq <= 1e-12) {
        return vec3(0.0);
    }
    n *= inversesqrt(lengthSq);
    return dot(n, position) > 0.0 ? -n : n;
}

void main() {
    fragColor = vec4(0.0);
    if (!isSky(texture(DepthSampler, texCoord).r)) {
        return;
    }
    float lod = texture(DhDepthSampler, texCoord).r;
    if (emptyLod(lod)) {
        return;
    }
    vec3 position = lodViewPos(texCoord, lod);
    // DH draws its clouds into the same depth buffer; those are sky to every pass but the composite, which lights
    // them as clouds (A = 0.25).
    if (viewToWorld(position).y + CameraPos.y > Misc2.w - 3.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 0.25);
        return;
    }
    // Far away a pixel covers bits of several block faces, and DH's depth shifts by a hair from frame to frame, so a
    // normal from the nearest neighbours alone flips between top and side: the shading would flicker. Two estimates,
    // one and three pixels out, averaged, keep it steady.
    vec3 n = lodNormal(position, 1.0) + lodNormal(position, 3.0);
    float lengthSq = dot(n, n);
    // Single-pixel LODs (no neighbours) count as flat ground.
    n = lengthSq > 1e-12 ? n * inversesqrt(lengthSq) : viewUp();
    float sunFacing = dot(n, LightDir.xyz) * 0.5 + 0.5;
    float up = clamp(viewToWorld(n).y, 0.0, 1.0);
    fragColor = finiteOr(vec4(packFarDistance(length(position)), sunFacing, 0.6 + 0.4 * up), vec4(0.0));
}
