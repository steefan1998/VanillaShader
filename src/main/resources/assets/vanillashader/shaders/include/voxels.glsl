// The material volume (see MaterialVolume) for passes that declare `uniform sampler2D VolumeSampler;`.
const float VOLUME_SIZE = 64.0;
const float VOLUME_TILES = 8.0;

// Keep in sync with MaterialVolume.
const float MATERIAL_AIR = 0.0;
const float MATERIAL_SOLID = 1.0;
const float MATERIAL_GLOSSY = 2.0;
const float MATERIAL_OBSIDIAN = 3.0;
const float MATERIAL_METAL = 4.0;
const float MATERIAL_GLASS = 5.0;
const float MATERIAL_EMISSIVE = 6.0;

// The block at an absolute (wrapped) world position: x = material, y = light emission 0..15, z = light colour
// index. x is -1 outside the volume or where it has not been scanned yet.
vec3 voxelAt(vec3 world) {
    vec3 block = floor(world);
    vec3 fromCamera = block - floor(CameraPos.xyz);
    if (max(abs(fromCamera.x), max(abs(fromCamera.y), abs(fromCamera.z))) > VOLUME_SIZE * 0.5 - 2.0) {
        return vec3(-1.0, 0.0, 0.0);
    }
    vec3 voxel = mod(block, VOLUME_SIZE);
    vec2 texel = vec2(mod(voxel.y, VOLUME_TILES) * VOLUME_SIZE + voxel.x, floor(voxel.y / VOLUME_TILES) * VOLUME_SIZE + voxel.z);
    vec4 data = floor(texture(VolumeSampler, (texel + 0.5) / (VOLUME_SIZE * VOLUME_TILES)) * 255.0 + 0.5);
    if (data.a < 128.0) {
        return vec3(-1.0, 0.0, 0.0);
    }
    return vec3(data.r, data.g / 17.0, data.b / 40.0);
}

bool stopsRays(float material) {
    return material != MATERIAL_AIR && material != MATERIAL_GLASS;
}

// Colour of light sources by palette index: warm, soul, redstone, cool, golden, lava.
vec3 emissionColor(float index) {
    if (index < 0.5) return vec3(1.0, 0.68, 0.36);
    if (index < 1.5) return vec3(0.42, 0.78, 1.0);
    if (index < 2.5) return vec3(1.0, 0.28, 0.18);
    if (index < 3.5) return vec3(0.82, 0.92, 1.0);
    if (index < 4.5) return vec3(1.0, 0.84, 0.52);
    return vec3(1.0, 0.45, 0.15);
}

// Walks a ray block by block through the volume (Amanatides-Woo DDA). Returns xyz = the hit voxel as voxelAt()
// gives it and w = distance travelled; x is -1 when nothing was hit within maxDistance or the volume.
vec4 traceVoxels(vec3 origin, vec3 dir, float maxDistance) {
    vec3 cell = floor(origin);
    vec3 stepDir = sign(dir);
    vec3 tDelta = 1.0 / max(abs(dir), vec3(1e-5));
    vec3 next = vec3(
        (dir.x > 0.0 ? cell.x + 1.0 - origin.x : origin.x - cell.x) * tDelta.x,
        (dir.y > 0.0 ? cell.y + 1.0 - origin.y : origin.y - cell.y) * tDelta.y,
        (dir.z > 0.0 ? cell.z + 1.0 - origin.z : origin.z - cell.z) * tDelta.z
    );
    float t = 0.0;
    for (int i = 0; i < 64; i++) {
        if (next.x < next.y && next.x < next.z) {
            t = next.x;
            next.x += tDelta.x;
            cell.x += stepDir.x;
        } else if (next.y < next.z) {
            t = next.y;
            next.y += tDelta.y;
            cell.y += stepDir.y;
        } else {
            t = next.z;
            next.z += tDelta.z;
            cell.z += stepDir.z;
        }
        if (t > maxDistance) {
            break;
        }
        vec3 voxel = voxelAt(cell + 0.5);
        if (voxel.x < 0.0) {
            break;
        }
        if (stopsRays(voxel.x)) {
            return vec4(voxel, t);
        }
    }
    return vec4(-1.0, 0.0, 0.0, maxDistance);
}
