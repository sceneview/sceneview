#!/usr/bin/env python3
"""Generate three synthetic GLBs with the profile of AI-generated 3D models.

Text/image-to-3D services hand back a single GLB of 5 to 60 MB: one dense, indexed mesh of
100k to 500k triangles with POSITION / NORMAL / TEXCOORD_0 (no TANGENT, so the loader has to
compute them for the normal map), and 2K to 4K textures embedded in the binary chunk. These
files reproduce that profile without shipping any third-party asset, so a loading benchmark can
run anywhere:

    ai_small.glb          ~9 MB   100k triangles, 2K base colour + 2K normal map
    ai_large.glb          ~55 MB  500k triangles, 4K base colour + 4K normal + 4K ORM
    ai_unsupported.glb    ~25 MB  250k triangles, 4K + 2K, and EXT_texture_avif listed in
                                  `extensionsRequired` — an extension Filament's gltfio does
                                  not implement, for the error path

Everything is deterministic (seeded) and uses the Python standard library only. The textures are
PNG with a controlled amount of noise, so they neither compress to nothing (a flat colour) nor
balloon past the target size (pure noise). The GLBs are build outputs, never committed:

    python3 tools/generate-ai-glbs.py build/ai-glb

The Android loading benchmark (`AiGlbLoadBenchmark`, sceneview androidTest) reads them from the
device; its KDoc has the push and run commands.
"""
import json
import math
import os
import random
import struct
import sys
import zlib
from array import array

# name, triangles (approximate: a lat/long grid of quads), textures, required extension
MODELS = [
    ("ai_small", 100_000, [("baseColor", 2048), ("normal", 2048)], None),
    ("ai_large", 500_000, [("baseColor", 4096), ("normal", 4096), ("orm", 4096)], None),
    ("ai_unsupported", 250_000, [("baseColor", 4096), ("normal", 2048)], "EXT_texture_avif"),
]


def grid_size(triangles: int) -> int:
    """Quads per side of the lat/long grid that gives about `triangles` triangles."""
    return max(2, round(math.sqrt(triangles / 2)))


def blob_mesh(n: int, seed: int):
    """A lumpy closed blob (displaced sphere), the usual silhouette of a generated object.

    Returns interleaving-free arrays: positions, normals, uvs (float32) and indices (uint32).
    The vertex count is (n + 1)^2, past 65535 for every model here, hence 32-bit indices.
    """
    rnd = random.Random(seed)
    # Low-frequency lumps: a few random sinusoids over the sphere.
    lumps = [(rnd.uniform(1, 5), rnd.uniform(1, 5), rnd.uniform(0, math.tau), rnd.uniform(0.02, 0.08))
             for _ in range(6)]
    positions, normals, uvs = array("f"), array("f"), array("f")
    for i in range(n + 1):
        v = i / n
        theta = v * math.pi
        st, ct = math.sin(theta), math.cos(theta)
        for j in range(n + 1):
            u = j / n
            phi = u * math.tau
            sp, cp = math.sin(phi), math.cos(phi)
            nx, ny, nz = st * cp, ct, st * sp
            r = 0.5 + sum(a * math.sin(f * theta + g * phi + p) for f, g, p, a in lumps)
            positions.extend((nx * r, ny * r, nz * r))
            normals.extend((nx, ny, nz))
            uvs.extend((u, v))
    indices = array("I")
    row = n + 1
    for i in range(n):
        for j in range(n):
            a = i * row + j
            b = a + row
            indices.extend((a, b, a + 1, a + 1, b, b + 1))
    if sys.byteorder != "little":
        for arr in (positions, normals, uvs, indices):
            arr.byteswap()
    return positions, normals, uvs, indices


def _repeat(pattern: bytes, length: int) -> bytes:
    return (pattern * (length // len(pattern) + 1))[:length]


def texture_png(kind: str, size: int, seed: int) -> bytes:
    """An RGB PNG of `size`², a smooth per-row base plus low-amplitude noise.

    The noise keeps 2 bits per channel (1 for a normal map, which is smoother): zlib then lands
    at a quarter to an eighth of the raw size, where real generated textures (photographic,
    baked lighting) sit.
    """
    rnd = random.Random(seed)
    width = size * 3
    noise_mask = int.from_bytes(_repeat(b"\x01" if kind == "normal" else b"\x03", width), "little")
    raw = bytearray()
    for y in range(size):
        if kind == "normal":
            base = _repeat(b"\x7c\x7c\xfc", width)  # tangent-space "up", low bits left for noise
        elif kind == "orm":
            base = _repeat(bytes((0xfc, 0x80 | (y * 64 // size), 0x10)), width)
        else:
            # A hue ramp that drifts row by row, like an albedo atlas made of islands.
            r = (y * 255 // size) & 0xfc
            g = (128 + y * 97 // size) & 0xfc
            b = (255 - y * 255 // size) & 0xfc
            base = _repeat(bytes((r, g, b)), width)
        noise = int.from_bytes(rnd.randbytes(width), "little") & noise_mask
        line = (int.from_bytes(base, "little") | noise).to_bytes(width, "little")
        raw += b"\x00" + line  # filter type 0 per scanline
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b"")


def build_glb(name: str, triangles: int, textures, required_extension, seed: int) -> bytes:
    n = grid_size(triangles)
    positions, normals, uvs, indices = blob_mesh(n, seed)
    blobs = [positions.tobytes(), normals.tobytes(), uvs.tobytes(), indices.tobytes()]
    images = [texture_png(kind, size, seed * 31 + k) for k, (kind, size) in enumerate(textures)]

    binary = bytearray()
    views = []
    for data in blobs + images:
        while len(binary) % 4:
            binary.append(0)
        views.append({"buffer": 0, "byteOffset": len(binary), "byteLength": len(data)})
        binary += data
    while len(binary) % 4:
        binary.append(0)
    for target, view in zip((34962, 34962, 34962, 34963), views[:4]):
        view["target"] = target

    xs, ys, zs = positions[0::3], positions[1::3], positions[2::3]
    vertex_count = len(positions) // 3
    accessors = [
        {"bufferView": 0, "componentType": 5126, "count": vertex_count, "type": "VEC3",
         "min": [min(xs), min(ys), min(zs)], "max": [max(xs), max(ys), max(zs)]},
        {"bufferView": 1, "componentType": 5126, "count": vertex_count, "type": "VEC3"},
        {"bufferView": 2, "componentType": 5126, "count": vertex_count, "type": "VEC2"},
        {"bufferView": 3, "componentType": 5125, "count": len(indices), "type": "SCALAR"},
    ]

    gltf_images, gltf_textures, slots = [], [], {}
    for k, (kind, _) in enumerate(textures):
        gltf_images.append({"bufferView": 4 + k, "mimeType": "image/png", "name": kind})
        if required_extension == "EXT_texture_avif":
            # What an AVIF-optimised export writes: the image only reachable through the
            # extension, no fallback `source`. (The bytes are PNG — a loader that honours
            # `extensionsRequired` never gets as far as decoding them.)
            gltf_images[-1]["mimeType"] = "image/avif"
            gltf_textures.append({"sampler": 0, "extensions": {"EXT_texture_avif": {"source": k}}})
        else:
            gltf_textures.append({"sampler": 0, "source": k})
        slots[kind] = k

    pbr = {"baseColorTexture": {"index": slots["baseColor"]}, "metallicFactor": 0.0, "roughnessFactor": 0.8}
    material = {"name": f"{name}_material", "pbrMetallicRoughness": pbr}
    if "normal" in slots:
        material["normalTexture"] = {"index": slots["normal"]}
    if "orm" in slots:
        pbr["metallicRoughnessTexture"] = {"index": slots["orm"]}
        pbr["metallicFactor"] = 1.0
        pbr["roughnessFactor"] = 1.0
        material["occlusionTexture"] = {"index": slots["orm"]}

    gltf = {
        "asset": {"version": "2.0", "generator": "sceneview tools/generate-ai-glbs.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": name, "mesh": 0}],
        "meshes": [{"name": name, "primitives": [{
            "attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2},
            "indices": 3, "material": 0}]}],
        "materials": [material],
        "samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}],
        "images": gltf_images,
        "textures": gltf_textures,
        "accessors": accessors,
        "bufferViews": views,
        "buffers": [{"byteLength": len(binary)}],
    }
    if required_extension:
        gltf["extensionsUsed"] = [required_extension]
        gltf["extensionsRequired"] = [required_extension]

    json_chunk = json.dumps(gltf, separators=(",", ":")).encode()
    json_chunk += b" " * (-len(json_chunk) % 4)
    total = 12 + 8 + len(json_chunk) + 8 + len(binary)
    return (struct.pack("<4sII", b"glTF", 2, total)
            + struct.pack("<I4s", len(json_chunk), b"JSON") + json_chunk
            + struct.pack("<I4s", len(binary), b"BIN\x00") + bytes(binary))


def main() -> int:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "build/ai-glb"
    os.makedirs(out_dir, exist_ok=True)
    for seed, (name, triangles, textures, required) in enumerate(MODELS, start=1):
        glb = build_glb(name, triangles, textures, required, seed)
        path = os.path.join(out_dir, f"{name}.glb")
        with open(path, "wb") as f:
            f.write(glb)
        n = grid_size(triangles)
        print(f"{path}: {len(glb) / 1e6:.1f} MB, {2 * n * n} triangles, "
              f"{', '.join(f'{k} {s}px' for k, s in textures)}"
              + (f", requires {required}" if required else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
