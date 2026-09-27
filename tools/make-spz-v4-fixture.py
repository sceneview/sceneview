#!/usr/bin/env python3
"""Generate the SPZ v4 / ZSTD test fixtures for sceneview-core (#4023).

Writes `sceneview-core/src/commonTest/kotlin/io/github/sceneview/core/splat/SpzV4Fixtures.kt`,
which holds:

  * `SPZ_V4_RACCOON`: a real SPZ **version 4** file, 200 splats with SH degree 3 (six ZSTD
    streams). It is a byte-level subset of Niantic's `racoonfamily.spz` sample
    (https://github.com/nianticlabs/spz/tree/main/samples, MIT licence, (c) 2024 Niantic Labs):
    every 4 663rd splat, so the subset spans the whole capture. The sample is v2, so each
    first-three quaternion is re-encoded as smallest-three, the v3/v4 rotation encoding, with
    the reference packing from `load-spz.cc`. Every other byte is copied verbatim. The v4
    container follows the reference README: a 32-byte header, a TOC, then one ZSTD frame per
    stream, each written by the `zstd` CLI (level 19, no checksum, the same frame shape as
    `ZSTD_compress`).
  * The per-stream uncompressed sizes and CRC-32s, plus the decoded values of three splats,
    computed here with the reference formulas.
  * `ZSTD_TEXT_FRAME`: 200 000 bytes of LCG-generated text (the Kotlin test regenerates it with
    the same generator), compressed by the CLI at level 19 with its default checksum. That
    makes two compressed blocks, so the frame covers Huffman literals in 4 streams,
    FSE-compressed tables and the repeat and treeless modes.
  * `ZSTD_MIXED_FRAMES`: a skippable frame, a small text frame and an RLE frame, concatenated.

Deterministic for a given `zstd` version. Needs the `zstd` CLI (Homebrew `zstd`); stdlib only
otherwise:

    curl -Lo /tmp/racoonfamily.spz \\
        https://github.com/nianticlabs/spz/raw/main/samples/racoonfamily.spz
    python3 tools/make-spz-v4-fixture.py /tmp/racoonfamily.spz
"""
import argparse
import base64
import gzip
import math
import struct
import subprocess
import zlib

OUT = "sceneview-core/src/commonTest/kotlin/io/github/sceneview/core/splat/SpzV4Fixtures.kt"
NGSP_MAGIC = 0x5053474E
SH_DIM_FOR_DEGREE = {0: 0, 1: 3, 2: 8, 3: 15, 4: 24}
COUNT = 200
SPOT_CHECK = [0, 57, 199]
TEXT_SIZE = 200_000
TEXT_SEED = 4023
WORDS = [b"splat", b"gaussian", b"scene", b"view", b"anisotropic", b"covariance",
         b"raccoon", b"stump", b"filament", b"render", b"camera", b"sort"]


def zstd(data, *flags):
    return subprocess.run(["zstd", "-q", "-c", *flags], input=data, check=True,
                          stdout=subprocess.PIPE).stdout


def lcg_text(size, seed):
    """Must match `lcgText` in SpzV4Test.kt byte for byte."""
    out = bytearray()
    x = seed
    while len(out) < size:
        x = (x * 1103515245 + 12345) & 0x7FFFFFFF
        out += WORDS[(x >> 16) % len(WORDS)]
        out += b".\n" if ((x >> 8) & 7) == 0 else b" "
    return bytes(out[:size])


def first_three_to_quat(b):
    x, y, z = (v / 127.5 - 1.0 for v in b)
    w = math.sqrt(max(0.0, 1.0 - (x * x + y * y + z * z)))
    return [x, y, z, w]


def pack_smallest_three(q):
    n = math.sqrt(sum(c * c for c in q))
    q = [c / n for c in q]
    largest = max(range(4), key=lambda i: abs(q[i]))
    negate = q[largest] < 0
    comp = largest
    for i in range(4):
        if i == largest:
            continue
        negbit = int((q[i] < 0) != negate)
        mag = int(math.floor(511 * (abs(q[i]) / math.sqrt(0.5)) + 0.5))
        comp = (comp << 10) | (negbit << 9) | mag
    return struct.pack("<I", comp & 0xFFFFFFFF)


def decode_smallest_three(b):
    comp = struct.unpack("<I", b)[0]
    largest = comp >> 30
    q = [0.0] * 4
    s = 0.0
    for i in (3, 2, 1, 0):
        if i == largest:
            continue
        mag = comp & 511
        neg = (comp >> 9) & 1
        comp >>= 10
        v = math.sqrt(0.5) * mag / 511
        q[i] = -v if neg else v
        s += v * v
    q[largest] = math.sqrt(max(0.0, 1.0 - s))
    n = math.sqrt(sum(c * c for c in q))
    return [c / n for c in q]


def kotlin_bytes(name, data, doc):
    b64 = base64.b64encode(data).decode()
    lines = [b64[i:i + 96] for i in range(0, len(b64), 96)]
    body = "\n".join(f'    "{l}" +' for l in lines).rstrip(" +")
    return f"/** {doc} */\ninternal val {name}: ByteArray by lazy {{\n  decodeBase64(\n{body},\n  )\n}}\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source", help="racoonfamily.spz (legacy gzip, v2)")
    args = ap.parse_args()

    d = gzip.open(args.source).read()
    magic, version, n, sh_degree, frac, flags, _ = struct.unpack_from("<IIIBBBB", d, 0)
    assert magic == NGSP_MAGIC and version == 2, (magic, version)
    sh_dim = SH_DIM_FOR_DEGREE[sh_degree]
    off = 16
    pos_o = off; off += n * 9
    alp_o = off; off += n
    col_o = off; off += n * 3
    sca_o = off; off += n * 3
    rot_o = off; off += n * 3
    sh_o = off; off += n * sh_dim * 3
    assert off <= len(d)

    stride = n // COUNT
    picks = [i * stride for i in range(COUNT)]
    positions = b"".join(d[pos_o + i * 9: pos_o + i * 9 + 9] for i in picks)
    alphas = bytes(d[alp_o + i] for i in picks)
    colors = b"".join(d[col_o + i * 3: col_o + i * 3 + 3] for i in picks)
    scales = b"".join(d[sca_o + i * 3: sca_o + i * 3 + 3] for i in picks)
    rotations = b"".join(pack_smallest_three(first_three_to_quat(d[rot_o + i * 3: rot_o + i * 3 + 3]))
                         for i in picks)
    sh = b"".join(d[sh_o + i * sh_dim * 3: sh_o + (i + 1) * sh_dim * 3] for i in picks)
    streams = [positions, alphas, colors, scales, rotations, sh]

    frames = [zstd(s, "-19", "--no-check") for s in streams]
    header = struct.pack("<IIIBBBBI12x", NGSP_MAGIC, 4, COUNT, sh_degree, frac, flags & 0x1,
                         len(streams), 32)
    toc = b"".join(struct.pack("<QQ", len(f), len(s)) for f, s in zip(frames, streams))
    v4 = header + toc + b"".join(frames)

    text = lcg_text(TEXT_SIZE, TEXT_SEED)
    text_frame = zstd(text, "-19")
    hello = b"Hello, SPZ v4! Hello, ZSTD! Hello, SceneView!"
    skippable = struct.pack("<II", 0x184D2A5E, 5) + b"skip!"
    mixed = skippable + zstd(hello, "-1", "--no-check") + zstd(bytes([0x2A]) * 4096, "-3")
    for frame, expected in ((text_frame, text), (mixed, hello + bytes([0x2A]) * 4096)):
        assert subprocess.run(["zstd", "-q", "-d", "-c"], input=frame, check=True,
                              stdout=subprocess.PIPE).stdout == expected

    spots = []
    for k in SPOT_CHECK:
        p = []
        for axis in range(3):
            b = positions[k * 9 + axis * 3: k * 9 + axis * 3 + 3]
            v = b[0] | (b[1] << 8) | (b[2] << 16)
            if v & 0x800000:
                v -= 1 << 24
            p.append(v / (1 << frac))
        sc = [math.exp(v / 16 - 10) for v in scales[k * 3: k * 3 + 3]]
        q = decode_smallest_three(rotations[k * 4: k * 4 + 4])
        spots.append((k, p, sc, q, alphas[k] / 255, list(colors[k * 3: k * 3 + 3])))

    def floats(xs):
        return ", ".join(f"{x:.7g}f" for x in xs)

    spot_src = ",\n".join(
        f"    SpzSpot(index = {k}, position = floatArrayOf({floats(p)}), scale = floatArrayOf({floats(sc)}), "
        f"quaternionXyzw = floatArrayOf({floats(q)}), opacity = {a:.7g}f, colorBytes = intArrayOf({', '.join(map(str, c))}))"
        for k, p, sc, q, a, c in spots)

    sizes = ", ".join(str(len(s)) for s in streams)
    crcs = ", ".join(f"0x{zlib.crc32(s):08X}.toInt()" for s in streams)

    src = f"""// GENERATED by tools/make-spz-v4-fixture.py — do not edit by hand.
// Source splats: nianticlabs/spz samples/racoonfamily.spz (MIT, (c) 2024 Niantic Labs).
@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
@file:Suppress("MaxLineLength")

package io.github.sceneview.core.splat

private fun decodeBase64(text: String): ByteArray = kotlin.io.encoding.Base64.decode(text)

{kotlin_bytes("SPZ_V4_RACCOON", v4, f"SPZ v4 file: {COUNT} real raccoon-scan splats, SH degree {sh_degree}, {len(v4)} bytes.")}
/** Uncompressed size of each v4 stream: positions, alphas, colors, scales, rotations, SH. */
internal val SPZ_V4_STREAM_SIZES = intArrayOf({sizes})

/** CRC-32 of each uncompressed v4 stream, same order. */
internal val SPZ_V4_STREAM_CRCS = intArrayOf({crcs})

internal const val SPZ_V4_COUNT = {COUNT}
internal const val SPZ_V4_SH_DEGREE = {sh_degree}

internal class SpzSpot(
    val index: Int,
    val position: FloatArray,
    val scale: FloatArray,
    val quaternionXyzw: FloatArray,
    val opacity: Float,
    val colorBytes: IntArray,
)

/** Reference decodes (formulas of `load-spz.cc`) of three splats of [SPZ_V4_RACCOON]. */
internal val SPZ_V4_SPOTS = listOf(
{spot_src},
)

{kotlin_bytes("ZSTD_TEXT_FRAME", text_frame, f"`zstd -19` of `lcgText({TEXT_SIZE}, {TEXT_SEED})`: two blocks, with checksum.")}
internal const val ZSTD_TEXT_SIZE = {TEXT_SIZE}
internal const val ZSTD_TEXT_SEED = {TEXT_SEED}
internal const val ZSTD_TEXT_CRC = 0x{zlib.crc32(text):08X}.toInt()

{kotlin_bytes("ZSTD_MIXED_FRAMES", mixed, "Skippable frame + `zstd -1` of [ZSTD_HELLO] + `zstd -3` of 4096 × 0x2A.")}
internal const val ZSTD_HELLO = "{hello.decode()}"
"""
    with open(OUT, "w") as f:
        f.write(src)
    print(f"wrote {OUT}: v4 {len(v4)} B, text frame {len(text_frame)} B, mixed {len(mixed)} B")


if __name__ == "__main__":
    main()
