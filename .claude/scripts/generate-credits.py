#!/usr/bin/env python3
"""
generate-credits.py — generate and gate EVERY CREDITS.md the project ships.

CC-BY 4.0 section 3a requires creator identification to be retained when
sharing the licensed material. `assets/catalog.json` holds the raw attribution
metadata; this script turns it into human-readable Markdown.

Usage:
    python3 .claude/scripts/generate-credits.py            # regenerate
    python3 .claude/scripts/generate-credits.py --check    # CI: exit 1 on drift

Paths resolve from this file, so the working directory does not matter.
`GENERATE_CREDITS_ROOT` overrides the repo root — that is how
`test-generate-credits.sh` drives this script against fixture trees.

──────────────────────────────────────────────────────────────────────────────
Every tracked CREDITS.md, and what this script does with it (#2941)
──────────────────────────────────────────────────────────────────────────────
Before #2941 this script wrote ONE file (`assets/CREDITS.md`) and the gate
checked that one file, while `git ls-files | grep -i CREDITS` returned five.
The copy bundled inside the Play Store APK was two months and six assets
behind, and nothing could have said so. A copy nobody generates and nobody
checks is a copy that drifts in silence — so the full list lives here, and
every entry states its treatment. Adding a CREDITS.md without adding it below
is caught by `test-generate-credits.sh`, which enumerates the tracked files
and fails on any this script does not name.

  assets/CREDITS.md                                GENERATED (full catalog)
  samples/android-demo/src/main/assets/CREDITS.md  GENERATED (bundled scope)
  assets/audio/CREDITS.md                          SOURCE    (hand-written)
  samples/ios-demo/SceneViewDemo/Audio/CREDITS.md  MIRROR    (of the above)
  samples/web-demo/site/audio/CREDITS.md           MIRROR    (of the above)

Rules for the two GENERATED files:
  - Only emit entries with complete metadata (author + license + sourceUrl).
  - Include CC-BY, CC-BY-SA, CC0, and public-domain-ish licenses.
  - EXCLUDE non-commercial (NC) and no-derivatives (ND) licenses that would
    conflict with downstream commercial distribution — we log them to
    stderr so they can be removed from the catalog or re-sourced.
  - Flag entries with missing required fields so they get fixed upstream.

`--check` regenerates in memory and compares against the committed files
without writing. It is a hard CI gate (`ci.yml` → `repo-hygiene`): CREDITS.md
is what discharges the attribution clause of every model's license, so a model
added to catalog.json but never credited is a compliance gap, not a cosmetic
one. Deterministic regenerate-and-compare has no false-positive risk, so it
blocks — same class as `tools/generate-gpt-knowledge.js --check`.

Exit code:
  0 — every CREDITS.md generated (or, with --check, already in sync)
  1 — catalog.json not found or unreadable; an unknown argument was passed;
      --check found drift; or a bundled file has no attribution at all
  (never fail on an incomplete CATALOG entry — just report it; DO fail on a
   bundled file nobody has declared, see UNCREDITED below)
"""
from __future__ import annotations

import json
import os
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(os.environ.get("GENERATE_CREDITS_ROOT") or Path(__file__).resolve().parent.parent.parent)
CATALOG = ROOT / "assets" / "catalog.json"
CREDITS = ROOT / "assets" / "CREDITS.md"

# Licenses we are allowed to ship in an open-source project intended for
# commercial distribution (Play Store, App Store, Maven Central).
SAFE_LICENSES = {
    "CC-BY-4.0",
    "CC-BY-3.0",
    "CC-BY-SA-4.0",     # share-alike is acceptable for the SDK since SceneView is Apache 2.0 and assets are a separate work
    "CC0-1.0",
    "CC0",
    "Public Domain",
    "Apache-2.0",       # first-party + Google model bundles, see NON_CATALOG_BUNDLED
}

# Licenses we keep but flag as needing review before commercial release.
UNSAFE_LICENSES = {
    "CC-BY-NC-4.0",
    "CC-BY-NC-3.0",
    "CC-BY-NC-SA-4.0",
    "CC-BY-NC-SA-3.0",
    "CC-BY-ND-4.0",
    "CC-BY-NC-ND-4.0",
}

# ─── Bundled scopes ───────────────────────────────────────────────────────────
# A "bundled scope" is a directory whose entire contents ship inside a store
# artefact, plus the CREDITS.md that travels with them. `assets/CREDITS.md`
# lists all 90 catalogue models; only 19 files reach the APK. Crediting the
# catalogue in the APK would be noise, and crediting nothing is what we had —
# so the APK copy is generated from the files that are actually there.
ANDROID_ASSETS = "samples/android-demo/src/main/assets"

# Prose that is NOT derivable from catalog.json and would be lost the moment
# this file became generated. It is engineering documentation about how the
# bundled binaries were produced, kept verbatim from the hand-written file it
# replaces (#934, #2305).
ANDROID_PREAMBLE = """\
> **Optimization note (#934, #2305).** The bundled GLBs and HDRs are compressed
> for a lean APK while preserving on-device visual quality:
> - **GLB models** — geometry is `KHR_draco_mesh_compression`. Textures are
>   **PNG/JPEG**: Filament's Android prebuilt ships `gltfio` with WebP support
>   compiled out (`isWebpSupported() == false`), so `EXT_texture_webp` textures
>   render untextured on Android (#2305). The models are therefore bundled with
>   PNG/JPEG textures the `gltfio` `StbProvider` decodes natively (Draco geometry
>   is decoded natively too). KTX2/Basis is a worthwhile future optimization to
>   reclaim the size PNG costs vs WebP.
> - **HDR environments** — equirect maps are downsampled 2048×1024 → 1024×512
>   in linear-radiance space (2×2 box average, energy-preserving). The `_2k`
>   suffix is kept as a stable filename only; resolution is now 1K.\
"""

BUNDLED_SCOPES = [
    {
        "id": "android-demo",
        "assets_dir": ANDROID_ASSETS,
        "out": f"{ANDROID_ASSETS}/CREDITS.md",
        "title": "Bundled Asset Credits — SceneView Android demo",
        "artefact": "the Play Store APK",
        "preamble": ANDROID_PREAMBLE,
        # Markdown is documentation travelling with the assets, not an asset:
        # the generated CREDITS.md itself lives here and would otherwise
        # demand a credit line for itself.
        "ignore_suffixes": (".md",),
    },
]

# ─── Mirrors ──────────────────────────────────────────────────────────────────
# `assets/audio/CREDITS.md` is hand-written on purpose: `bell.wav` is generated
# locally by ffmpeg and is NOT a catalog.json asset, so there is nothing to
# generate it from. It is the SOURCE. The two demo copies are byte-for-byte
# mirrors of it (verified identical on 2026-08-16) and are checked as such —
# that is what stops one of the three being edited alone.
AUDIO_SOURCE = "assets/audio/CREDITS.md"
MIRRORS = {
    AUDIO_SOURCE: [
        "samples/ios-demo/SceneViewDemo/Audio/CREDITS.md",
        "samples/web-demo/site/audio/CREDITS.md",
    ],
}

# ─── Known bundled files with no catalog.json entry ───────────────────────────
# Every one of these is first-party or a permissively-licensed third-party
# bundle, so none belongs in a catalogue of downloaded 3D models — but each one
# ships to a user and therefore needs a line in the CREDITS they receive.
# Declaring them here (rather than staying silent) is what lets the coverage
# check below treat "a bundled file I have never heard of" as an error.
# Keys are paths relative to the scope's assets_dir.
NON_CATALOG_BUNDLED = {
    "audio/bell.wav": {
        "name": "bell.wav",
        "author": "SceneView project",
        "license": "CC0-1.0",
        "sourceUrl": "https://creativecommons.org/publicdomain/zero/1.0/",
        "note": "Generated locally with ffmpeg (880 Hz sine, 0.6 s) — see `assets/audio/CREDITS.md`",
    },
    "augmented_images/qrcode.png": {
        "name": "qrcode.png",
        "author": "SceneView project",
        "license": "Apache-2.0",
        "sourceUrl": "https://github.com/sceneview/sceneview/blob/main/LICENSE",
        "note": "QR-like reference pattern drawn for `ARImageDemo`",
    },
    "textures/sceneview_logo.png": {
        "name": "sceneview_logo.png",
        "author": "SceneView project",
        "license": "Apache-2.0",
        "sourceUrl": "https://github.com/sceneview/sceneview/blob/main/LICENSE",
        "note": "SceneView brand mark, exported from `branding/exports/logo/logo-1024.png`",
    },
    "videos/sample.mp4": {
        "name": "sample.mp4",
        "author": "SceneView project",
        "license": "Apache-2.0",
        "sourceUrl": "https://github.com/sceneview/sceneview/blob/main/LICENSE",
        "note": "Generated with ffmpeg — 10 s / 1280×720 / H.264 brand animation for `TwoDInThreeDDemo`",
    },
    "splats/rainbow_sphere.ply": {
        "name": "rainbow_sphere.ply",
        "author": "SceneView project",
        "license": "Apache-2.0",
        "sourceUrl": "https://github.com/sceneview/sceneview/blob/main/LICENSE",
        "note": "Generated procedurally by `tools/generate-splat-sphere.py`",
    },
    "mediapipe/pose_landmarker_lite.task": {
        "name": "MediaPipe Pose Landmarker (lite)",
        "author": "Google LLC",
        "license": "Apache-2.0",
        "sourceUrl": "https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker",
        "note": "On-device pose model bundle used by `ARBodyTrackerDemo`",
    },
}

# ─── Blanket source licenses ──────────────────────────────────────────────────
# Some sources license their whole library uniformly and publish that on one
# page. Six of the eight `environments` entries in catalog.json carry no
# per-asset author/license, and inventing one would be worse than omitting it —
# so a bundled asset from such a source is credited under the source's OWN
# published blanket license, linked, with the per-asset author shown when the
# catalogue happens to know it. This asserts nothing the source does not.
SOURCE_BLANKET_LICENSE = {
    "polyhaven": {
        "license": "CC0-1.0",
        "licenseUrl": "https://polyhaven.com/license",
        "siteUrl": "https://polyhaven.com",
        "label": "Poly Haven",
    },
}

# ─── Deliberately NOT handled here, and why ───────────────────────────────────
# Named so that a reader does not have to guess whether the omission is a
# decision or an oversight.
#
#   samples/ios-demo  — bundles 31 catalogue assets (24 CC-BY .usdz models) and
#   samples/web-demo    12 respectively, and NEITHER ships a bundled-model
#                       CREDITS file at all; each has only the audio mirror
#                       above. Generating one is not the missing piece — the
#                       iOS copy would also need adding to the Xcode Resources
#                       build phase (`project.pbxproj` lists Audio/CREDITS.md
#                       as a file reference only, so it is not in the .app),
#                       and the web copy needs a link from `site/index.html`.
#                       That is a shipped-surface change, not a gate change.
#                       Measured and filed separately rather than smuggled in
#                       here. See #2941 for the numbers.


def repo_relative(p: Path) -> str:
    return p.relative_to(ROOT).as_posix()


def load_catalog() -> list[dict]:
    if not CATALOG.exists():
        print(f"error: {CATALOG} not found", file=sys.stderr)
        sys.exit(1)
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    if isinstance(data, list):
        return data
    if isinstance(data, dict) and "models" in data:
        # Credit both 3D models and HDR environments. Environments only
        # surface in CREDITS.md once they carry full attribution metadata
        # (author + license + sourceUrl) — bare entries are reported under
        # "Missing metadata", same as incomplete models.
        return list(data["models"]) + list(data.get("environments", []))
    print(f"error: {CATALOG} has unexpected top-level structure", file=sys.stderr)
    sys.exit(1)


def catalog_by_basename() -> dict[str, dict]:
    """Index every catalog entry by the basename of each file it declares.

    Models declare files under `formats.<fmt>.file`; environments under `file`.
    A bundled binary is matched to its catalog entry by basename because the
    catalogue path (`models/glb/foo.glb`) and the bundled path
    (`models/foo.glb`) differ by layout, not by asset.
    """
    if not CATALOG.exists():
        return {}
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    if isinstance(data, list):
        models, envs = data, []
    else:
        models, envs = list(data.get("models", [])), list(data.get("environments", []))
    index: dict[str, dict] = {}
    for m in models:
        for fmt in (m.get("formats") or {}).values():
            f = (fmt or {}).get("file")
            if f:
                index.setdefault(Path(f).name, m)
    for e in envs:
        f = e.get("file")
        if f:
            index.setdefault(Path(f).name, e)
    return index


def license_url(lic: str) -> str:
    table = {
        "CC-BY-4.0": "https://creativecommons.org/licenses/by/4.0/",
        "CC-BY-3.0": "https://creativecommons.org/licenses/by/3.0/",
        "CC-BY-SA-4.0": "https://creativecommons.org/licenses/by-sa/4.0/",
        "CC0-1.0": "https://creativecommons.org/publicdomain/zero/1.0/",
        "CC0": "https://creativecommons.org/publicdomain/zero/1.0/",
        "CC-BY-NC-4.0": "https://creativecommons.org/licenses/by-nc/4.0/",
        "CC-BY-NC-SA-4.0": "https://creativecommons.org/licenses/by-nc-sa/4.0/",
        "Apache-2.0": "https://www.apache.org/licenses/LICENSE-2.0",
    }
    return table.get(lic, "")


def group_by_source(entries: list[dict]) -> dict[str, list[dict]]:
    groups = defaultdict(list)
    for e in entries:
        key = e.get("source", "other").lower()
        groups[key].append(e)
    return groups


def format_entry(m: dict) -> str:
    name = m.get("name") or m.get("id") or "(unnamed)"
    author = m.get("author", "").strip()
    lic = m.get("license", "").strip()
    lic_link = license_url(lic)
    src = m.get("sourceUrl", "").strip()
    lic_md = f"[{lic}]({lic_link})" if lic_link else lic
    return f"- **[{name}]({src})** by {author} — {lic_md}"


def human_size(num_bytes: int) -> str:
    """Deterministic, decimal size string. Pure function of the byte count, so
    re-compressing a bundled binary forces a CREDITS regeneration — which is
    the point: the shipped file describes what actually shipped."""
    if num_bytes < 1_000_000:
        return f"{round(num_bytes / 1000)} KB"
    return f"{num_bytes / 1_000_000:.1f} MB"


def render_catalog_credits(models: list[dict]) -> str:
    """`assets/CREDITS.md` — the whole catalogue, unchanged since #2947."""
    complete = []
    incomplete = []
    unsafe = []
    for m in models:
        author = (m.get("author") or "").strip()
        lic = (m.get("license") or "").strip()
        src = (m.get("sourceUrl") or "").strip()
        name = (m.get("name") or m.get("id") or "").strip()
        if not (author and lic and src and name):
            incomplete.append(m)
            continue
        if lic in UNSAFE_LICENSES:
            unsafe.append(m)
            continue
        if lic not in SAFE_LICENSES:
            incomplete.append(m)
            continue
        complete.append(m)

    # Sort alphabetically by name within each group
    complete.sort(key=lambda m: (m.get("name") or "").lower())

    lines: list[str] = []
    lines.append("# 3D Asset Credits")
    lines.append("")
    lines.append("SceneView's sample apps, documentation and website playground use a catalogue of")
    lines.append("3D models authored by third parties and distributed under open licenses. This")
    lines.append("file lists every model that ships with a public release of the repository, in")
    lines.append("compliance with the attribution clause of each license.")
    lines.append("")
    lines.append("SceneView itself is Apache 2.0. The models listed here are NOT covered by that")
    lines.append("license — each model keeps the license granted by its original author.")
    lines.append("")
    lines.append("Source of truth: [`assets/catalog.json`](catalog.json). This file is generated")
    lines.append("by [`.claude/scripts/generate-credits.py`](../.claude/scripts/generate-credits.py).")
    lines.append("Re-run the script after any catalog edit to keep both files in sync.")
    lines.append("")
    lines.append(f"Total models: **{len(complete)}** (plus {len(incomplete)} pending metadata, {len(unsafe)} pending license review).")
    lines.append("")
    lines.append("---")
    lines.append("")

    # Group by source
    groups = group_by_source(complete)
    order = ["sketchfab", "khronos", "polyhaven", "ambientcg", "other"]
    for key in order + [k for k in groups if k not in order]:
        items = groups.get(key)
        if not items:
            continue
        heading = {
            "sketchfab": "Sketchfab",
            "khronos": "Khronos glTF Sample Assets",
            "polyhaven": "Poly Haven",
            "ambientcg": "AmbientCG",
            "other": "Other sources",
        }.get(key, key.title())
        lines.append(f"## {heading}")
        lines.append("")
        for m in items:
            lines.append(format_entry(m))
        lines.append("")

    if unsafe:
        lines.append("---")
        lines.append("")
        lines.append("## Non-commercial licenses (pending review)")
        lines.append("")
        lines.append("The following models use a non-commercial license. They are kept in the")
        lines.append("catalogue for reference but should NOT be bundled in any release that ships")
        lines.append("via an app store, since app store distribution may be considered commercial.")
        lines.append("Replace or remove before the next store publication:")
        lines.append("")
        for m in unsafe:
            lines.append(format_entry(m))
        lines.append("")

    if incomplete:
        lines.append("---")
        lines.append("")
        lines.append(f"## Missing metadata ({len(incomplete)} entries)")
        lines.append("")
        lines.append("These entries in `catalog.json` lack at least one of `author`, `license`,")
        lines.append("`sourceUrl`, or use a license this script does not recognise. Fill in the")
        lines.append("missing fields so they can be credited properly:")
        lines.append("")
        for m in incomplete:
            mid = m.get("id", "?")
            gaps = []
            for f in ("name", "author", "license", "sourceUrl"):
                if not (m.get(f) or "").strip():
                    gaps.append(f)
            lic = (m.get("license") or "").strip()
            if lic and lic not in SAFE_LICENSES and lic not in UNSAFE_LICENSES:
                gaps.append(f"license `{lic}` unrecognised")
            lines.append(f"- `{mid}` — missing: {', '.join(gaps) or 'unknown'}")
        lines.append("")

    return "\n".join(lines) + "\n", len(complete), len(incomplete), len(unsafe)


def scan_bundled(scope: dict) -> list[Path]:
    """Every file that ships inside the scope's artefact, sorted.

    Dot-prefixed files and directories are skipped. That is not tidiness: on
    macOS the Finder drops a `.DS_Store` into any folder it displays, it is
    gitignored so CI never sees it, and without this the gate would go red on
    a developer's machine over a file that cannot reach the APK — a false red
    nobody could reproduce in CI.
    """
    base = ROOT / scope["assets_dir"]
    if not base.is_dir():
        return []
    out: list[Path] = []
    for p in sorted(base.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(base).as_posix()
        if any(part.startswith(".") for part in rel.split("/")):
            continue
        if rel == Path(scope["out"]).name:
            continue
        if p.suffix in scope["ignore_suffixes"]:
            continue
        out.append(p)
    return out


def classify_bundled(scope: dict, index: dict[str, dict]) -> tuple[list, list, list]:
    """Split the bundled files into (credited, blanket, uncredited).

    credited   — a catalog entry (or NON_CATALOG_BUNDLED declaration) with a
                 full author + license + sourceUrl triple
    blanket    — a catalog entry from a source with a published blanket license
                 but no per-asset license of its own
    uncredited — nothing anywhere describes this file. This is the class the
                 gate exists to make impossible; it is an error, not a note.
    """
    base = ROOT / scope["assets_dir"]
    credited: list[tuple[str, dict, int]] = []
    blanket: list[tuple[str, dict, int]] = []
    uncredited: list[str] = []
    for p in scan_bundled(scope):
        rel = p.relative_to(base).as_posix()
        size = p.stat().st_size
        declared = NON_CATALOG_BUNDLED.get(rel)
        if declared is not None:
            credited.append((rel, declared, size))
            continue
        entry = index.get(p.name)
        if entry is None:
            uncredited.append(rel)
            continue
        lic = (entry.get("license") or "").strip()
        author = (entry.get("author") or "").strip()
        src = (entry.get("sourceUrl") or "").strip()
        if lic and author and src:
            credited.append((rel, entry, size))
            continue
        source = (entry.get("source") or "").lower()
        if source in SOURCE_BLANKET_LICENSE:
            blanket.append((rel, entry, size))
            continue
        uncredited.append(rel)
    return credited, blanket, uncredited


def _kind(rel: str) -> str:
    suffix = Path(rel).suffix.lower()
    if suffix in (".glb", ".gltf", ".usdz", ".ply"):
        return "3D models"
    if suffix in (".hdr", ".exr", ".ktx"):
        return "HDR environments"
    return "Other bundled assets"


KIND_ORDER = ["3D models", "HDR environments", "Other bundled assets"]


def render_bundled_credits(scope: dict, index: dict[str, dict]) -> tuple[str, list[str]]:
    credited, blanket, uncredited = classify_bundled(scope, index)
    out_rel = scope["out"]
    depth = out_rel.count("/")
    to_root = "../" * depth

    lines: list[str] = []
    lines.append(f"# {scope['title']}")
    lines.append("")
    lines.append(f"Every asset bundled in {scope['artefact']} is listed below with its author,")
    lines.append("license and original source, in compliance with the attribution clause of each")
    lines.append("license (CC-BY 4.0 §3a in particular).")
    lines.append("")
    lines.append("SceneView itself is Apache 2.0. Third-party assets listed here are NOT covered")
    lines.append("by that license — each keeps the license granted by its original author.")
    lines.append("")
    lines.append("<!-- GENERATED FILE — DO NOT EDIT BY HAND. -->")
    lines.append(f"Generated from [`assets/catalog.json`]({to_root}assets/catalog.json) and the")
    lines.append(f"contents of `{scope['assets_dir']}` by")
    lines.append(f"[`.claude/scripts/generate-credits.py`]({to_root}.claude/scripts/generate-credits.py).")
    lines.append("Re-run that script after adding, removing or re-compressing a bundled asset;")
    lines.append("`ci.yml` → `repo-hygiene` fails if this file and the assets disagree.")
    lines.append("")
    total = len(credited) + len(blanket)
    lines.append(f"Assets bundled: **{total}**.")
    lines.append("")
    if scope.get("preamble"):
        lines.append(scope["preamble"])
        lines.append("")
    lines.append("---")
    lines.append("")

    by_kind: dict[str, list] = defaultdict(list)
    for rel, entry, size in credited:
        by_kind[_kind(rel)].append((rel, entry, size))

    for kind in KIND_ORDER:
        items = sorted(by_kind.get(kind, []), key=lambda t: t[0])
        if not items:
            continue
        lines.append(f"## {kind}")
        lines.append("")
        for rel, entry, size in items:
            name = entry.get("name") or entry.get("id") or Path(rel).name
            author = (entry.get("author") or "").strip()
            lic = (entry.get("license") or "").strip()
            src = (entry.get("sourceUrl") or "").strip()
            lic_link = license_url(lic)
            lic_md = f"[{lic}]({lic_link})" if lic_link else lic
            title = f"[{name}]({src})" if src else name
            line = f"- `{rel}` — **{title}** by {author} — {lic_md} ({human_size(size)})"
            note = (entry.get("note") or "").strip()
            if note:
                line += f"  \n  {note}"
            lines.append(line)
        lines.append("")

    if blanket:
        by_source: dict[str, list] = defaultdict(list)
        for rel, entry, size in blanket:
            by_source[(entry.get("source") or "").lower()].append((rel, entry, size))
        for source, items in sorted(by_source.items()):
            meta = SOURCE_BLANKET_LICENSE[source]
            lines.append(f"## {meta['label']} — blanket {meta['license']}")
            lines.append("")
            lines.append(f"[{meta['label']}]({meta['siteUrl']}) publishes its entire library under")
            lines.append(f"[{meta['license']}]({meta['licenseUrl']}); no attribution is required. The")
            lines.append("per-asset author is shown where `assets/catalog.json` records one.")
            lines.append("")
            for rel, entry, size in sorted(items, key=lambda t: t[0]):
                name = entry.get("name") or entry.get("id") or Path(rel).name
                author = (entry.get("author") or "").strip()
                by = f" by {author}" if author else ""
                lines.append(f"- `{rel}` — **{name}**{by} ({human_size(size)})")
            lines.append("")

    return "\n".join(lines) + "\n", uncredited


def main() -> int:
    # Reject unknown arguments instead of falling through to the write path.
    # `--chekc` must NOT silently regenerate the file and exit 0: that turns
    # the blocking CI gate into a false green that also mutates the workspace.
    args = sys.argv[1:]
    unknown = [a for a in args if a != "--check"]
    if unknown:
        print(
            f"error: unknown argument(s): {' '.join(unknown)}\n"
            "usage: generate-credits.py [--check]",
            file=sys.stderr,
        )
        return 1
    check = "--check" in args
    models = load_catalog()
    index = catalog_by_basename()

    # path -> (content, label). Built first, compared or written second, so
    # --check never touches the working tree on any path.
    outputs: list[tuple[Path, str]] = []
    catalog_content, n_complete, n_incomplete, n_unsafe = render_catalog_credits(models)
    outputs.append((CREDITS, catalog_content))

    uncredited_all: list[tuple[str, list[str]]] = []
    for scope in BUNDLED_SCOPES:
        content, uncredited = render_bundled_credits(scope, index)
        outputs.append((ROOT / scope["out"], content))
        if uncredited:
            uncredited_all.append((scope["id"], uncredited))

    # Mirrors resolve from the SOURCE file on disk, not from anything
    # generated: the source is hand-written by design (see MIRRORS above).
    mirror_pairs: list[tuple[Path, Path]] = []
    for src_rel, dest_rels in MIRRORS.items():
        src = ROOT / src_rel
        if not src.exists():
            print(f"error: mirror source {src_rel} not found", file=sys.stderr)
            return 1
        for dest_rel in dest_rels:
            mirror_pairs.append((src, ROOT / dest_rel))
    for src, dest in mirror_pairs:
        outputs.append((dest, src.read_text(encoding="utf-8")))

    # An uncredited bundled file is the exact hole #2941 is about: something a
    # user receives that no CREDITS file describes. Report it before the drift
    # verdict — regenerating cannot fix it, only a catalog entry or a
    # NON_CATALOG_BUNDLED declaration can.
    if uncredited_all:
        for scope_id, files in uncredited_all:
            print(
                f"UNCREDITED: {len(files)} bundled file(s) in scope `{scope_id}` have no "
                "attribution anywhere:",
                file=sys.stderr,
            )
            for f in files:
                print(f"  - {f}", file=sys.stderr)
        print(
            "Fix: add the asset to assets/catalog.json with author + license + sourceUrl,\n"
            "or declare it in NON_CATALOG_BUNDLED in .claude/scripts/generate-credits.py.",
            file=sys.stderr,
        )
        return 1

    if check:
        drifted: list[str] = []
        for path, content in outputs:
            exists = path.exists()
            current = path.read_text(encoding="utf-8") if exists else ""
            if current != content:
                rel = repo_relative(path)
                reason = (
                    "is out of date" if exists else "is MISSING (expected a generated file)"
                )
                print(f"DRIFT: {rel} {reason}.", file=sys.stderr)
                drifted.append(rel)
        if drifted:
            print(
                "Regenerate and commit:\n"
                "  python3 .claude/scripts/generate-credits.py",
                file=sys.stderr,
            )
            return 1
        for path, _ in outputs:
            print(f"{repo_relative(path)} is in sync.")
        return 0

    for path, content in outputs:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        print(f"Wrote {repo_relative(path)}")
    print(f"  catalog complete: {n_complete}")
    print(f"  catalog non-commercial (flagged): {n_unsafe}")
    print(f"  catalog incomplete metadata: {n_incomplete}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
