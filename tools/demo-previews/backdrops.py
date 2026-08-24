#!/usr/bin/env python3
"""Build the QA-only synthetic camera backdrops for the Android demo app.

The arm64 ARCore emulator has no camera HAL, so every AR demo screenshot renders on a
flat black surface. When `qa_backdrop` is on (see `QaCameraBackdrop.kt`) the demo app
draws one of these portrait room photos beneath the translucent AR surface instead.

Sources are the generated showcase previews in `src/main/res/drawable-nodpi/`; each
entry crops a column that contains no 3D object or UI, so the backdrop reads as an
empty room. Output goes to the **debug** source set only — release never ships them.

Usage:  python3 tools/demo-previews/backdrops.py
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2] / "samples" / "android-demo" / "src"
SRC = ROOT / "main" / "res" / "drawable-nodpi"
DST = ROOT / "debug" / "res" / "drawable-nodpi"
TARGET_HEIGHT = 1080
QUALITY = 85

# (source preview, crop box left/top/right/bottom in source pixels — sources are 800x640).
# Each box is the tallest object-free column of the preview (9:16 when the photo allows).
BACKDROPS = [
    ("preview_ar_plane_node_light.webp", (0, 0, 360, 640)),   # rug + floor, table cropped out
    ("preview_ar_measure_light.webp", (440, 240, 800, 640)), # table + chair, below the line
    ("preview_ar_placement_light.webp", (0, 0, 230, 640)),    # window + plant, fox cropped out
]


def main() -> None:
    DST.mkdir(parents=True, exist_ok=True)
    for index, (name, box) in enumerate(BACKDROPS, start=1):
        image = Image.open(SRC / name).convert("RGB").crop(box)
        scale = TARGET_HEIGHT / image.height
        image = image.resize(
            (round(image.width * scale), TARGET_HEIGHT), Image.Resampling.LANCZOS
        )
        out = DST / f"qa_backdrop_{index}.webp"
        image.save(out, "WEBP", quality=QUALITY, method=6)
        print(f"{out.relative_to(ROOT.parent)}  {image.width}x{image.height}  <- {name} {box}")


if __name__ == "__main__":
    main()
