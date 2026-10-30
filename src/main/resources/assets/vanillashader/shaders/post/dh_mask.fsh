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
    vec3 dx = lodNeighbour(position, vec2(ScreenSize.z, 0.0));
    vec3 dy = lodNeighbour(position, vec2(0.0, ScreenSize.w));
    vec3 n = cross(dx, dy);
    float lengthSq = dot(n, n);
    // Single-pixel LODs (no neighbours) count as flat ground.
    n = lengthSq > 1e-12 ? n * inversesqrt(lengthSq) : viewUp();
    if (dot(n, position) > 0.0) {
        n = -n;
    }
    float sunFacing = dot(n, LightDir.xyz) * 0.5 + 0.5;
    float up = clamp(viewToWorld(n).y, 0.0, 1.0);
    fragColor = finiteOr(vec4(packFarDistance(length(position)), sunFacing, 0.6 + 0.4 * up), vec4(0.0));
}
