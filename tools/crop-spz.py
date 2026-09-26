#!/usr/bin/env python3
"""Crop a real-world SPZ capture down to a bundleable subject-only scene.

Phone captures (Scaniverse, Polycam, Luma) ship the whole environment: a
raccoon family in a tree hollow is 932 560 splats / 24 MB, most of it distant
grass and sky. That is too big for an APK, and above the 512x512 data-texture
budget SceneView's `SplatNode` packs splats into (262 144 splats, see
`SplatBuffers.textureSize`).

This tool keeps the subject at FULL capture density and simply throws away
everything outside a sphere around it. Decimating instead (voxel or random)
was tried and rejected: gaussians are ~1 mm wide, so dropping 2 of every 3
turns recognisable fur into fog.

The crop is a pure byte-level subset of the source file — positions, colours,
scales, rotations and alphas are copied verbatim, so the output is exactly the
capture, minus the discarded splats:

  * spherical-harmonic coefficients are dropped (`shDegree = 0`); SceneView's
    P1 renderer ignores them, and they are ~70 % of the file,
  * positions are re-centred on the crop centre (integer fixed-point shift, no
    resampling), so the subject sits at the scene origin,
  * everything else keeps its original quantised bytes.

Deterministic: same input, same flags, byte-identical output.

Regenerate the bundled demo asset with:

    curl -Lo /tmp/racoonfamily.spz \\
        https://github.com/nianticlabs/spz/raw/main/samples/racoonfamily.spz
    python3 tools/crop-spz.py /tmp/racoonfamily.spz \\
        samples/android-demo/src/main/assets/splats/raccoon_family.spz \\
        --radius 0.45

Needs no third-party module (stdlib only).
"""
import argparse
import gzip
import struct
import sys

HEADER_SIZE = 16
NGSP_MAGIC = 0x5053474E  # "NGSP"
SH_DIM_FOR_DEGREE = {0: 0, 1: 3, 2: 8, 3: 15, 4: 24}
# SplatBuffers packs splats into a square RGBA16F texture whose side is the next
# power of two; 512x512 is the largest size that stays cheap on mid-range GPUs.
MAX_SPLATS = 512 * 512


def read_u32(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def decode_positions(data, offset, count, fractional_bits):
    """24-bit signed fixed point -> list of (x, y, z) floats, and the raw ints."""
    scale = 1.0 / (1 << fractional_bits)
    raw = []
    for i in range(count * 3):
        base = offset + i * 3
        fixed = data[base] | (data[base + 1] << 8) | (data[base + 2] << 16)
        if fixed & 0x800000:
            fixed -= 1 << 24
        raw.append(fixed)
    return raw, scale


def encode_position(fixed):
    if fixed < -(1 << 23) or fixed > (1 << 23) - 1:
        sys.exit("crop-spz: re-centred position overflows 24-bit fixed point")
    unsigned = fixed & 0xFFFFFF
    return bytes((unsigned & 0xFF, (unsigned >> 8) & 0xFF, (unsigned >> 16) & 0xFF))


def median(values):
    ordered = sorted(values)
    return ordered[len(ordered) // 2]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", help="input .spz (version 2, gzip container)")
    parser.add_argument("output", help="output .spz")
    parser.add_argument("--radius", type=float, default=0.45, help="crop sphere radius, metres (default: 0.45)")
    parser.add_argument(
        "--center",
        type=float,
        nargs=3,
        metavar=("X", "Y", "Z"),
        help="crop centre in source coordinates (default: the per-axis median splat position)",
    )
    args = parser.parse_args()

    with open(args.source, "rb") as handle:
        raw = handle.read()
    if raw[:2] != b"\x1f\x8b":
        sys.exit("crop-spz: not a gzip SPZ container")
    data = gzip.decompress(raw)

    if len(data) < HEADER_SIZE or read_u32(data, 0) != NGSP_MAGIC:
        sys.exit("crop-spz: bad NGSP magic")
    version = read_u32(data, 4)
    if version != 2:
        sys.exit(f"crop-spz: only SPZ version 2 is handled (got {version})")
    count = read_u32(data, 8)
    sh_degree = data[12]
    fractional_bits = data[13]
    flags = data[14]
    if sh_degree not in SH_DIM_FOR_DEGREE:
        sys.exit(f"crop-spz: unsupported SH degree {sh_degree}")

    positions_offset = HEADER_SIZE
    alphas_offset = positions_offset + count * 9
    colors_offset = alphas_offset + count
    scales_offset = colors_offset + count * 3
    rotations_offset = scales_offset + count * 3
    end = rotations_offset + count * 3 + count * SH_DIM_FOR_DEGREE[sh_degree] * 3
    if end > len(data):
        sys.exit(f"crop-spz: truncated payload (need {end} bytes, have {len(data)})")

    fixed, position_scale = decode_positions(data, positions_offset, count, fractional_bits)

    if args.center is None:
        center_fixed = [median(fixed[axis::3]) for axis in range(3)]
    else:
        center_fixed = [int(round(value / position_scale)) for value in args.center]

    radius_fixed = args.radius / position_scale
    radius_squared = radius_fixed * radius_fixed

    kept = []
    for i in range(count):
        dx = fixed[i * 3] - center_fixed[0]
        dy = fixed[i * 3 + 1] - center_fixed[1]
        dz = fixed[i * 3 + 2] - center_fixed[2]
        if dx * dx + dy * dy + dz * dz <= radius_squared:
            kept.append(i)

    if not kept:
        sys.exit("crop-spz: crop sphere is empty — widen --radius or move --center")
    if len(kept) > MAX_SPLATS:
        sys.exit(
            f"crop-spz: {len(kept)} splats exceeds the {MAX_SPLATS} data-texture budget — "
            "shrink --radius"
        )

    positions = bytearray()
    alphas = bytearray()
    colors = bytearray()
    scales = bytearray()
    rotations = bytearray()
    for i in kept:
        for axis in range(3):
            positions += encode_position(fixed[i * 3 + axis] - center_fixed[axis])
        alphas.append(data[alphas_offset + i])
        colors += data[colors_offset + i * 3:colors_offset + i * 3 + 3]
        scales += data[scales_offset + i * 3:scales_offset + i * 3 + 3]
        rotations += data[rotations_offset + i * 3:rotations_offset + i * 3 + 3]

    header = struct.pack("<IIIBBBB", NGSP_MAGIC, 2, len(kept), 0, fractional_bits, flags, 0)
    payload = bytes(header + positions + alphas + colors + scales + rotations)
    # mtime=0 so the gzip container is byte-identical across runs.
    with gzip.GzipFile(args.output, "wb", compresslevel=9, mtime=0) as handle:
        handle.write(payload)

    with open(args.output, "rb") as handle:
        written = len(handle.read())
    print(
        f"{args.source}: {count} splats -> {len(kept)} kept "
        f"(radius {args.radius} m, centre {[round(c * position_scale, 3) for c in center_fixed]})"
    )
    print(f"{args.output}: {written / 1024 / 1024:.2f} MB ({len(payload) / 1024 / 1024:.2f} MB uncompressed)")


if __name__ == "__main__":
    main()
