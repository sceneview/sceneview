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
import io
import subprocess
from pathlib import Path

from PIL import Image

REPO = Path(__file__).resolve().parents[2]
ROOT = REPO / "samples" / "android-demo" / "src"
SRC = ROOT / "main" / "res" / "drawable-nodpi"
DST = ROOT / "debug" / "res" / "drawable-nodpi"
TARGET_HEIGHT = 1080
QUALITY = 85

# (source preview, crop box left/top/right/bottom in source pixels — sources are 800x640,
# git revision to read the source from or None for the working tree).
# Each box is the tallest object-free column of the preview (9:16 when the photo allows).
# The third source is the #3308 fox card, read from history: the AR Placement card now shows
# the Toy Car the demo places, and its photo has no object-free column.
BACKDROPS = [
    ("preview_ar_plane_node_light.webp", (0, 0, 360, 640), None),   # rug + floor, table cropped out
    ("preview_ar_measure_light.webp", (440, 240, 800, 640), None), # table + chair, below the line
    ("preview_ar_placement_light.webp", (0, 0, 230, 640), "b1dc1dc59"),  # window + plant, fox cropped out
]


def open_source(name: str, rev: str | None) -> Image.Image:
    if rev is None:
        return Image.open(SRC / name)
    path = (SRC / name).relative_to(REPO).as_posix()
    blob = subprocess.run(["git", "show", f"{rev}:{path}"], cwd=REPO,
                          check=True, capture_output=True).stdout
    return Image.open(io.BytesIO(blob))


def main() -> None:
    DST.mkdir(parents=True, exist_ok=True)
    for index, (name, box, rev) in enumerate(BACKDROPS, start=1):
        image = open_source(name, rev).convert("RGB").crop(box)
        scale = TARGET_HEIGHT / image.height
        image = image.resize(
            (round(image.width * scale), TARGET_HEIGHT), Image.Resampling.LANCZOS
        )
        out = DST / f"qa_backdrop_{index}.webp"
        image.save(out, "WEBP", quality=QUALITY, method=6)
        print(f"{out.relative_to(ROOT.parent)}  {image.width}x{image.height}  <- {name} {box}")


if __name__ == "__main__":
    main()
