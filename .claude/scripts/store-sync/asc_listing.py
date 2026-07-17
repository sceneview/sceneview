#!/usr/bin/env python3
"""asc_listing.py — App Store listing drift check, read-only (#2612 P2, Phase A).

READ-ONLY in this phase: diffs the live App Store listing against the repo's
declared state and never writes. The upload half (screenshots via
`appScreenshotSets`) is Phase B and will extend this script — until then any
--apply* flag exits with an explicit error rather than pretending.

What it diffs (en-US):
  - text fields — `samples/ios-demo/distribution/app-store/en-US/*.txt`
    (description, keywords, promotional_text, marketing_url, support_url)
    against the live `appStoreVersionLocalizations` attributes. `whatsNew` is
    deliberately NOT diffed: it is per-release, sourced from CHANGELOG.md by
    app-store.yml at tag time, and has no standalone repo file.
  - screenshots — `samples/ios-demo/appstore-screenshots/<device-dir>/*.png`
    against the live `appScreenshots` `sourceFileChecksum` (MD5) per display
    type. As of Phase A the repo screenshots have NEVER been uploaded
    (generated + committed only), so a first real dry-run reporting them all
    as "not live" is the expected, honest baseline — that is the #2384 gap
    Phase B closes.

Version selection: the LIVE version (`READY_FOR_SALE`) — drift is measured
against what users actually see. When the app has no live version yet, the
check SKIPs. An editable draft, if present, is mentioned informationally.

Credentials — reuses app-store.yml / store-preflight.sh secrets, NO new scope,
same alias set as store-preflight.sh:
  APP_STORE_CONNECT_KEY_ID     (aliases: APP_STORE_CONNECT_API_KEY_ID, ASC_KEY_ID)
  APP_STORE_CONNECT_ISSUER_ID  (aliases: APP_STORE_CONNECT_API_ISSUER_ID, ASC_ISSUER_ID)
  APP_STORE_CONNECT_API_KEY    .p8 private-key CONTENT (alias: ASC_API_KEY)
  APP_STORE_CONNECT_API_KEY_PATH  local convenience: path to the .p8 file
  (fallback: ~/.private_keys/AuthKey_{KEY_ID}.p8 — app-store.yml's convention)
When credentials are missing, SKIP honestly with exit 0 (advisory-first).
Exit 3 on drift only under --fail-on-drift (check-doc-drift.sh convention).

Third-party imports (PyJWT, requests) are lazy — pure helpers are importable
and unit-tested offline (.claude/scripts/store-sync/test/).
"""

import argparse
import hashlib
import os
import pathlib
import sys
import time

DEFAULT_BUNDLE_ID = "io.github.sceneview.demo"
DEFAULT_METADATA_DIR = "samples/ios-demo/distribution/app-store/en-US"
DEFAULT_SCREENSHOTS_DIR = "samples/ios-demo/appstore-screenshots"

BASE = "https://api.appstoreconnect.apple.com/v1"

# Apple field caps — mirrors app-store.yml step 4b.
FIELDS = [
    ("description", "description.txt", 4000),
    ("keywords", "keywords.txt", 100),
    ("promotionalText", "promotional_text.txt", 170),
    ("marketingUrl", "marketing_url.txt", 2048),
    ("supportUrl", "support_url.txt", 2048),
]

# Repo screenshot dir → ASC screenshotDisplayType. The capture script
# (capture-appstore-screenshots.sh) emits these two dirs; add a row here when
# it grows a new device class. test-store-sync.sh asserts every committed dir
# has a mapping so a new dir can't silently escape the drift check.
DISPLAY_TYPE_MAP = {
    "iphone-6.9": "APP_IPHONE_67",
    "ipad-13": "APP_IPAD_PRO_3GEN_129",
}


# ── Pure helpers (no third-party imports — unit-tested offline) ──────────────

def read_metadata_fields(meta_dir):
    """Read the repo's declared text fields, applying Apple's caps.

    Returns (fields, notes): `fields` maps ASC attribute → capped text for
    every file that exists; `notes` lists truncation warnings.
    """
    fields, notes = {}, []
    for attr, fname, cap in FIELDS:
        f = meta_dir / fname
        if not f.exists():
            continue
        v = f.read_text().strip()
        if len(v) > cap:
            notes.append(f"{fname} exceeded {cap} chars — truncated")
            v = v[: cap - 1].rstrip() + "…"
        fields[attr] = v
    return fields, notes


def md5_of(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def diff_fields(local_fields, remote_attrs):
    """Compare declared fields to the live localization. Returns drift lines.

    Only fields with a repo file are compared (an absent file = unmanaged,
    mirroring app-store.yml which never touches a field whose file is
    missing)."""
    drift = []
    for attr, local in local_fields.items():
        remote = (remote_attrs or {}).get(attr)
        if remote is None or remote == "":
            drift.append(f"en-US: {attr} empty on the live listing (local {len(local)}c)")
        elif remote.strip() != local:
            drift.append(f"en-US: {attr} differs (local {len(local)}c ≠ live {len(remote.strip())}c)")
    return drift


def diff_screenshots(device_dir, display_type, local_files, remote_checksums):
    """Compare local screenshots (sorted filename order) to the live set.

    `remote_checksums` is the ordered list of `sourceFileChecksum` (MD5) the
    live appScreenshotSet carries. Order matters — the App Store displays
    screenshots in set order."""
    local = [(f.name, md5_of(f)) for f in local_files]
    local_sums = [s for _, s in local]
    if local_sums == list(remote_checksums):
        return []
    if not remote_checksums:
        return [f"{device_dir} ({display_type}): no live screenshot set — "
                f"{len(local_files)} repo screenshot(s) never uploaded"]
    if sorted(local_sums) == sorted(remote_checksums):
        return [f"{device_dir} ({display_type}): same screenshots but different ORDER"]
    missing = [n for n, s in local if s not in remote_checksums]
    extra = len([s for s in remote_checksums if s not in local_sums])
    parts = []
    if missing:
        parts.append("not live: " + ", ".join(missing))
    if extra:
        parts.append(f"{extra} live screenshot(s) not in the repo")
    return [f"{device_dir} ({display_type}): differs ({'; '.join(parts) or 'content mismatch'})"]


def resolve_asc_credentials(env=os.environ, home=None):
    """Return (key_id, issuer_id, private_key_pem) or None when unset.

    Same env-alias set as store-preflight.sh, so a shell that can run the
    preflight can run this too."""
    key_id = (env.get("APP_STORE_CONNECT_KEY_ID")
              or env.get("APP_STORE_CONNECT_API_KEY_ID")
              or env.get("ASC_KEY_ID"))
    issuer_id = (env.get("APP_STORE_CONNECT_ISSUER_ID")
                 or env.get("APP_STORE_CONNECT_API_ISSUER_ID")
                 or env.get("ASC_ISSUER_ID"))
    if not key_id or not issuer_id:
        return None
    pem = env.get("APP_STORE_CONNECT_API_KEY") or env.get("ASC_API_KEY")
    if not pem:
        path = env.get("APP_STORE_CONNECT_API_KEY_PATH")
        if not path:
            home_dir = pathlib.Path(home) if home else pathlib.Path.home()
            path = home_dir / ".private_keys" / f"AuthKey_{key_id}.p8"
        p = pathlib.Path(path)
        if not p.exists():
            return None
        pem = p.read_text()
    return key_id, issuer_id, pem


# ── Network layer (lazy third-party imports) ─────────────────────────────────

def _headers(key_id, issuer_id, pem):
    import jwt

    token = jwt.encode(
        {"iss": issuer_id, "iat": int(time.time()),
         "exp": int(time.time()) + 1200, "aud": "appstoreconnect-v1"},
        pem,
        algorithm="ES256",
        headers={"kid": key_id},
    )
    return {"Authorization": f"Bearer {token}"}


def dry_run(headers, bundle_id, meta_dir, shots_dir):
    """Diff the live App Store listing against the repo. Returns (drift, skipped).

    `skipped` is a human-readable reason when the diff could not run (no app,
    no live version) — an honest SKIP, never silent."""
    import requests

    r = requests.get(f"{BASE}/apps?filter[bundleId]={bundle_id}", headers=headers)
    r.raise_for_status()
    apps = r.json().get("data", [])
    if not apps:
        return [], f"no app found for bundleId {bundle_id}"
    app_id = apps[0]["id"]

    r = requests.get(
        f"{BASE}/apps/{app_id}/appStoreVersions"
        "?filter[platform]=IOS&filter[appStoreState]=READY_FOR_SALE&limit=1",
        headers=headers,
    )
    r.raise_for_status()
    live = r.json().get("data", [])
    if not live:
        return [], "no READY_FOR_SALE iOS version — nothing live to diff against"
    version_id = live[0]["id"]
    version_string = live[0].get("attributes", {}).get("versionString", "?")
    print(f"[dry-run] live iOS version: {version_string} ({version_id})")

    # Informational only: an editable draft means a release is in flight — the
    # live listing may lag the repo legitimately until it ships.
    r = requests.get(
        f"{BASE}/apps/{app_id}/appStoreVersions"
        "?filter[platform]=IOS&filter[appStoreState]=PREPARE_FOR_SUBMISSION,READY_FOR_REVIEW&limit=1",
        headers=headers,
    )
    if r.status_code == 200 and r.json().get("data"):
        draft_vs = r.json()["data"][0].get("attributes", {}).get("versionString", "?")
        print(f"[dry-run] note: editable draft {draft_vs} exists — live may lag the repo legitimately")

    r = requests.get(
        f"{BASE}/appStoreVersions/{version_id}/appStoreVersionLocalizations",
        headers=headers,
    )
    r.raise_for_status()
    locs = r.json().get("data", [])
    en_us = next((loc for loc in locs if loc.get("attributes", {}).get("locale") == "en-US"), None)
    if en_us is None:
        return [f"en-US: localization missing on the live version {version_string}"], None

    drift = []
    local_fields, notes = read_metadata_fields(meta_dir)
    for note in notes:
        print(f"::warning::{note}")
    drift += diff_fields(local_fields, en_us.get("attributes", {}))

    # Screenshots: live appScreenshotSets per display type vs the repo dirs.
    r = requests.get(
        f"{BASE}/appStoreVersionLocalizations/{en_us['id']}/appScreenshotSets"
        "?include=appScreenshots&limit=50",
        headers=headers,
    )
    r.raise_for_status()
    payload = r.json()
    shots_by_id = {
        inc["id"]: inc for inc in payload.get("included", [])
        if inc.get("type") == "appScreenshots"
    }
    live_sets = {}
    for s in payload.get("data", []):
        dtype = s.get("attributes", {}).get("screenshotDisplayType")
        refs = (s.get("relationships", {}).get("appScreenshots", {}).get("data") or [])
        checksums = []
        for ref in refs:
            shot = shots_by_id.get(ref["id"], {})
            checksums.append(shot.get("attributes", {}).get("sourceFileChecksum"))
        live_sets[dtype] = checksums

    for device_dir, display_type in sorted(DISPLAY_TYPE_MAP.items()):
        ddir = shots_dir / device_dir
        files = sorted(ddir.glob("*.png")) if ddir.is_dir() else []
        if not files:
            continue
        drift += diff_screenshots(device_dir, display_type,
                                  files, live_sets.get(display_type, []))

    unmapped = [d for d in live_sets
                if d not in DISPLAY_TYPE_MAP.values() and live_sets[d]]
    for dtype in unmapped:
        print(f"[dry-run] note: live screenshot set {dtype} has no repo counterpart (unmanaged)")

    return drift, None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--dry-run", action="store_true",
                    help="read-only live-vs-repo diff (default and only mode in Phase A)")
    ap.add_argument("--fail-on-drift", action="store_true",
                    help="exit 3 when drift is found")
    ap.add_argument("--bundle-id", default=os.environ.get("ASC_BUNDLE_ID", DEFAULT_BUNDLE_ID))
    ap.add_argument("--metadata-dir", default=DEFAULT_METADATA_DIR)
    ap.add_argument("--screenshots-dir", default=DEFAULT_SCREENSHOTS_DIR)
    args, unknown = ap.parse_known_args(argv)
    if unknown:
        # An --apply-style flag must fail loudly, not silently no-op — the
        # write path is Phase B (#2612), not a forgotten default.
        print(f"::error::Unknown option(s) {unknown} — asc_listing.py is READ-ONLY "
              "in Phase A; the screenshot/metadata upload path is Phase B (#2612).")
        return 2

    meta_dir = pathlib.Path(args.metadata_dir)
    shots_dir = pathlib.Path(args.screenshots_dir)
    if not meta_dir.is_dir():
        print(f"::error::Metadata dir not found: {meta_dir}")
        return 2

    creds = resolve_asc_credentials()
    if creds is None:
        print(
            "[skip] No App Store Connect credential set "
            "(APP_STORE_CONNECT_KEY_ID / _ISSUER_ID / _API_KEY[_PATH]) "
            "— skipping honestly, not a green diff."
        )
        return 0

    drift, skipped = dry_run(_headers(*creds), args.bundle_id, meta_dir, shots_dir)
    if skipped:
        print(f"[skip] {skipped}")
        return 0
    if not drift:
        print(f"[dry-run] {args.bundle_id}: live App Store listing matches the repo — no drift")
        return 0
    print(f"[dry-run] {args.bundle_id}: {len(drift)} drift item(s):")
    for line in drift:
        print(f"  DRIFT {line}")
    return 3 if args.fail_on_drift else 0


if __name__ == "__main__":
    sys.exit(main())
