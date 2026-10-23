#version 330

// AMD FidelityFX Super Resolution 1.0 - EASU (Edge Adaptive Spatial Upsampling).
// GLSL port of the 32-bit float path of ffx_fsr1.h.
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved. Released under the MIT License.
// Taps are fetched one by one instead of with gathers, so the layout below holds in any texture orientation.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

ivec2 inputMax;

vec3 tap(ivec2 pos) {
    return texelFetch(InSampler, clamp(pos, ivec2(0), inputMax), 0).rgb;
}

// Luma times two, as FSR uses it.
float lumaOf(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

// Accumulates direction and edge length for one of the four bilinear quadrants around the sample.
//    a
//  b c d
//    e
void easuSet(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = 1.0 / max(max(abs(dc), abs(cb)), 1.0 / 32768.0);
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
    len += lenX * lenX * w;

    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = 1.0 / max(max(abs(ec), abs(ca)), 1.0 / 32768.0);
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
    len += lenY * lenY * w;
}

// One tap of the rotated, anisotropic Lanczos-2 approximation.
void easuTap(inout vec3 color, inout float weight, vec2 offset, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    vec2 v = vec2(offset.x * dir.x + offset.y * dir.y, offset.x * -dir.y + offset.y * dir.x);
    v *= len;
    float d2 = min(dot(v, v), clp);
    // (25/16 * (2/5 * x^2 - 1)^2 - (25/16 - 1)) * (lob * x^2 - 1)^2
    float wB = 2.0 / 5.0 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    color += c * w;
    weight += w;
}

void main() {
    ivec2 size = textureSize(InSampler, 0);
    inputMax = size - 1;
    vec2 pp = texCoord * vec2(size) - 0.5;
    vec2 fp = floor(pp);
    pp -= fp;
    ivec2 o = ivec2(fp);

    //    b c
    //  e f g h
    //  i j k l
    //    n o
    vec3 b = tap(o + ivec2(0, -1));
    vec3 c = tap(o + ivec2(1, -1));
    vec3 e = tap(o + ivec2(-1, 0));
    vec3 f = tap(o);
    vec3 g = tap(o + ivec2(1, 0));
    vec3 h = tap(o + ivec2(2, 0));
    vec3 i = tap(o + ivec2(-1, 1));
    vec3 j = tap(o + ivec2(0, 1));
    vec3 k = tap(o + ivec2(1, 1));
    vec3 l = tap(o + ivec2(2, 1));
    vec3 n = tap(o + ivec2(0, 2));
    vec3 oo = tap(o + ivec2(1, 2));

    float bL = lumaOf(b), cL = lumaOf(c), eL = lumaOf(e), fL = lumaOf(f), gL = lumaOf(g), hL = lumaOf(h);
    float iL = lumaOf(i), jL = lumaOf(j), kL = lumaOf(k), lL = lumaOf(l), nL = lumaOf(n), oL = lumaOf(oo);

    vec2 dir = vec2(0.0);
    float len = 0.0;
    easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
    easuSet(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
    easuSet(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
    easuSet(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);

    // Normalize the direction, defaulting to horizontal when there is no gradient.
    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    bool zero = dirR < 1.0 / 32768.0;
    dirR = zero ? 1.0 : inversesqrt(dirR);
    dir.x = zero ? 1.0 : dir.x;
    dir *= dirR;

    // Edge strength from {0..2} to {0..1}, shaped with a square.
    len *= 0.5;
    len *= len;
    // Stretch the kernel from 1 on axis-aligned edges to sqrt(2) on diagonals.
    float stretch = dot(dir, dir) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    // The window narrows from sqrt(2) towards 2 as the edge gets stronger.
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = 1.0 / lob;

    vec3 color = vec3(0.0);
    float weight = 0.0;
    easuTap(color, weight, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
    easuTap(color, weight, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
    easuTap(color, weight, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
    easuTap(color, weight, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
    easuTap(color, weight, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
    easuTap(color, weight, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
    easuTap(color, weight, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
    easuTap(color, weight, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
    easuTap(color, weight, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
    easuTap(color, weight, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
    easuTap(color, weight, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, oo);
    easuTap(color, weight, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);

    // Clamp to the 4 nearest texels to remove ringing.
    vec3 min4 = min(min(f, g), min(j, k));
    vec3 max4 = max(max(f, g), max(j, k));
    fragColor = vec4(min(max4, max(min4, color / weight)), 1.0);
}
