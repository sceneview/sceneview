#!/usr/bin/env python3
"""Generate a small, visually recognizable 3D Gaussian Splatting PLY asset:
a dense rainbow sphere shell. INRIA binary_little_endian 1.0 layout, exactly the
properties SceneView's PlyParser (P1a) reads:

    x y z  f_dc_0 f_dc_1 f_dc_2  opacity  scale_0 scale_1 scale_2  rot_0 rot_1 rot_2 rot_3

Activations are INVERTED here so the parser's forward activations reproduce the
target values:
  - scale_i stored as ln(sigma)              (parser applies exp)
  - f_dc_i  stored as (c_lin - 0.5)/SH_C0    (parser applies 0.5 + SH_C0*f_dc)
  - opacity stored as logit(o)               (parser applies sigmoid)
  - rot stored scalar-first w,x,y,z          (parser reorders to xyzw, normalizes)

Deterministic (no randomness, no external data) so the committed demo asset is
fully reproducible. Regenerate the bundled splat-preview asset with:

    python3 tools/generate-splat-sphere.py \\
        samples/android-demo/src/main/assets/splats/rainbow_sphere.ply 8000
"""
import math
import struct
import sys
import colorsys

SH_C0 = 0.28209479177387814


def srgb_to_linear(c: float) -> float:
    # Store LINEAR RGB (SplatCloud contract). Convert vivid sRGB hues to linear so
    # they survive Filament's linear->sRGB display transform close to intent.
    if c <= 0.04045:
        return c / 12.92
    return ((c + 0.055) / 1.055) ** 2.4


def logit(p: float) -> float:
    p = min(max(p, 1e-4), 1.0 - 1e-4)
    return math.log(p / (1.0 - p))


def fibonacci_sphere(n: int, radius: float):
    # Even point distribution on a sphere (golden-angle spiral).
    ga = math.pi * (3.0 - math.sqrt(5.0))
    pts = []
    for i in range(n):
        y = 1.0 - (i / float(n - 1)) * 2.0  # 1 .. -1
        r = math.sqrt(max(0.0, 1.0 - y * y))
        theta = ga * i
        x = math.cos(theta) * r
        z = math.sin(theta) * r
        pts.append((x * radius, y * radius, z * radius))
    return pts


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "rainbow_sphere.ply"
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 8000
    radius = 0.5
    sigma = 0.012          # linear gaussian scale -> billboard disc radius = 3*sigma = 0.036 m
    opacity = 0.80
    ln_sigma = math.log(sigma)

    pts = fibonacci_sphere(n, radius)

    # Vertex record: 14 float32 properties, little-endian.
    rec = struct.Struct("<14f")
    body = bytearray()
    for (x, y, z) in pts:
        # Hue wraps with azimuth around the Y axis -> full rainbow when orbiting.
        # A gentle lightness lift toward the top pole makes the shading readable.
        azimuth = math.atan2(z, x)          # -pi..pi
        hue = (azimuth + math.pi) / (2.0 * math.pi)
        elevation = y / radius              # -1..1
        val = 0.72 + 0.18 * elevation       # 0.54 .. 0.90
        sat = 0.95
        sr, sg, sb = colorsys.hsv_to_rgb(hue, sat, val)
        r_lin = srgb_to_linear(sr)
        g_lin = srgb_to_linear(sg)
        b_lin = srgb_to_linear(sb)
        fdc0 = (r_lin - 0.5) / SH_C0
        fdc1 = (g_lin - 0.5) / SH_C0
        fdc2 = (b_lin - 0.5) / SH_C0
        body += rec.pack(
            x, y, z,
            fdc0, fdc1, fdc2,
            logit(opacity),
            ln_sigma, ln_sigma, ln_sigma,
            1.0, 0.0, 0.0, 0.0,   # identity quaternion, scalar-first (w,x,y,z)
        )

    header = (
        "ply\n"
        "format binary_little_endian 1.0\n"
        f"comment SceneView synthetic rainbow-sphere splat ({n} gaussians) - demo asset, issue 2646\n"
        f"element vertex {n}\n"
        "property float x\n"
        "property float y\n"
        "property float z\n"
        "property float f_dc_0\n"
        "property float f_dc_1\n"
        "property float f_dc_2\n"
        "property float opacity\n"
        "property float scale_0\n"
        "property float scale_1\n"
        "property float scale_2\n"
        "property float rot_0\n"
        "property float rot_1\n"
        "property float rot_2\n"
        "property float rot_3\n"
        "end_header\n"
    ).encode("ascii")

    with open(out, "wb") as f:
        f.write(header)
        f.write(body)
    print(f"wrote {out}: {n} splats, {len(header) + len(body)} bytes")


if __name__ == "__main__":
    main()
