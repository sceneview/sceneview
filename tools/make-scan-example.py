#!/usr/bin/env python3
"""Builds the 3D Scan demo's bundled example from a scan saved by the demo.

The example is a real scan: the three files the demo writes for every scan
(`capture-manifest.json`, `capture-session.jsonl`, `capture-media.bin`), lightened
for shipping and written under the names the demo reads from its assets
(`samples/android-demo/src/main/assets/rerun/example/`):

- the camera photos named in `--drop` are left out (frames that show more than the
  scanned place), and so are the ones the camera took looking up past `--min-pitch`
  or straight down past `--max-pitch` (the horizon, the scanner's own feet);
- the sparse feature points (`point_cloud`) and the raw-depth index (`depth_frame`)
  go: the dense cloud stands in for the first, the second points at a depth file
  that is not part of a scan file;
- everything else is kept byte for byte: the dense cloud, the camera's path, the
  planes, the depth totals that make the cloud grow on the timeline.

    python3 tools/make-scan-example.py <scan-dir> [--drop 22-33,53-60] [--out DIR]

`<scan-dir>` is a scan's folder as the demo keeps it (or an unzipped `.svscan`).
"""
import argparse
import bisect
import json
import math
import os
import sys

OUT = "samples/android-demo/src/main/assets/rerun/example"


def frame_numbers(spec):
    numbers = set()
    for part in filter(None, (spec or "").split(",")):
        first, _, last = part.partition("-")
        numbers.update(range(int(first), int(last or first) + 1))
    return numbers


def pitch_degrees(quaternion):
    """The camera's look direction above the horizon: -90 straight down."""
    x, y, z, w = quaternion
    forward_y = -(2 * (y * z - w * x))
    return math.degrees(math.asin(max(-1.0, min(1.0, forward_y))))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("scan")
    parser.add_argument("--drop", default="", help="photo numbers to leave out, e.g. 22-33,53-60")
    parser.add_argument("--min-pitch", type=float, default=-66.0, help="leave out photos taken looking further down")
    parser.add_argument("--max-pitch", type=float, default=-35.0, help="leave out photos taken looking further up")
    parser.add_argument("--out", default=OUT)
    args = parser.parse_args()

    manifest = json.load(open(os.path.join(args.scan, "capture-manifest.json")))
    media = open(os.path.join(args.scan, "capture-media.bin"), "rb").read()
    events = [json.loads(line) for line in open(os.path.join(args.scan, "capture-session.jsonl")) if line.strip()]

    poses = [(e["t"], e["quaternion"]) for e in events if e["type"] == "camera_pose"]
    times = [t for t, _ in poses]
    dropped = frame_numbers(args.drop)
    kept_photos = set()
    for number, event in enumerate((e for e in events if e["type"] == "image"), start=1):
        at = min(len(poses) - 1, bisect.bisect_left(times, event["t"]))
        pitch = pitch_degrees(poses[at][1])
        if number not in dropped and args.min_pitch <= pitch <= args.max_pitch:
            kept_photos.add(event["path"])

    dense = (manifest.get("dense") or {}).get("path")
    textures = {t["path"] for t in manifest.get("textures", [])}
    packed, spans = bytearray(), []
    for entry in manifest["media"]:
        path = entry["path"]
        if path != dense and path not in textures and path not in kept_photos:
            continue
        spans.append({"path": path, "offset": len(packed), "length": entry["length"]})
        packed += media[entry["offset"]:entry["offset"] + entry["length"]]
    manifest["media"] = spans
    manifest["frames"] = len(kept_photos)

    skipped = {"point_cloud", "depth_frame"}
    log = [
        e for e in events
        if e["type"] not in skipped and (e["type"] != "image" or e["path"] in kept_photos)
    ]

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "example-manifest.json"), "w") as f:
        json.dump(manifest, f, separators=(",", ":"))
    with open(os.path.join(args.out, "example-session.jsonl"), "w") as f:
        for event in log:
            f.write(json.dumps(event, separators=(",", ":")) + "\n")
    with open(os.path.join(args.out, "example-media.bin"), "wb") as f:
        f.write(packed)
    total = sum(os.path.getsize(os.path.join(args.out, n)) for n in os.listdir(args.out))
    print(f"{len(kept_photos)} photos kept, {len(log)} events, {total // 1024} KB in {args.out}", file=sys.stderr)
    print(" ".join(sorted(p[-8:-4] for p in kept_photos)))


if __name__ == "__main__":
    main()
