#!/usr/bin/env python3
"""
generate-credits.py — Generate assets/CREDITS.md from assets/catalog.json.

CC-BY 4.0 section 3a requires creator identification to be retained when
sharing the licensed material. catalog.json holds the raw attribution
metadata; this script turns it into a human-readable Markdown file that
ships with the repo and is linked from the website footer + the About
screen of the demo apps.

Usage:
    python3 .claude/scripts/generate-credits.py            # regenerate
    python3 .claude/scripts/generate-credits.py --check    # CI: exit 1 on drift

Writes to assets/CREDITS.md. Paths resolve from this file, so the working
directory does not matter.

Rules:
  - Only emit entries with complete metadata (author + license + sourceUrl).
  - Include CC-BY, CC-BY-SA, CC0, and public-domain-ish licenses.
  - EXCLUDE non-commercial (NC) and share-alike (SA) licenses that would
    conflict with downstream commercial distribution — we log them to
    stderr so they can be removed from the catalog or re-sourced.
  - Flag entries with missing required fields so they get fixed upstream.

`--check` regenerates in memory and compares against the committed file
without writing. It is a hard CI gate (`ci.yml` → `repo-hygiene`): CREDITS.md
is what discharges the attribution clause of every model's license, so a model
added to catalog.json but never credited is a compliance gap, not a cosmetic
one. Deterministic regenerate-and-compare has no false-positive risk, so it
blocks — same class as `tools/generate-gpt-knowledge.js --check`.

Exit code:
  0 — CREDITS.md generated successfully (or, with --check, already in sync)
  1 — catalog.json not found or unreadable; an unknown argument was passed;
      or --check found drift
  (never fail on incomplete entries — just report them)
"""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
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


def license_url(lic: str) -> str:
    table = {
        "CC-BY-4.0": "https://creativecommons.org/licenses/by/4.0/",
        "CC-BY-3.0": "https://creativecommons.org/licenses/by/3.0/",
        "CC-BY-SA-4.0": "https://creativecommons.org/licenses/by-sa/4.0/",
        "CC0-1.0": "https://creativecommons.org/publicdomain/zero/1.0/",
        "CC0": "https://creativecommons.org/publicdomain/zero/1.0/",
        "CC-BY-NC-4.0": "https://creativecommons.org/licenses/by-nc/4.0/",
        "CC-BY-NC-SA-4.0": "https://creativecommons.org/licenses/by-nc-sa/4.0/",
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

    content = "\n".join(lines) + "\n"

    if check:
        exists = CREDITS.exists()
        current = CREDITS.read_text(encoding="utf-8") if exists else ""
        if current != content:
            reason = (
                "is out of date vs assets/catalog.json"
                if exists
                else "is MISSING (expected a generated file)"
            )
            print(
                f"DRIFT: assets/CREDITS.md {reason}.\n"
                "Regenerate and commit:\n"
                "  python3 .claude/scripts/generate-credits.py",
                file=sys.stderr,
            )
            return 1
        print("assets/CREDITS.md is in sync with assets/catalog.json.")
        return 0

    CREDITS.write_text(content, encoding="utf-8")
    print(f"Wrote {CREDITS}")
    print(f"  complete: {len(complete)}")
    print(f"  non-commercial (flagged): {len(unsafe)}")
    print(f"  incomplete metadata: {len(incomplete)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
