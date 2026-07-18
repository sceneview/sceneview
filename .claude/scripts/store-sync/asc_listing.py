#!/usr/bin/env python3
"""asc_listing.py — App Store listing drift check + screenshot upload (#2612 P2).

Two modes, `--dry-run` being the default:

  --dry-run             read-only live-vs-repo diff, writes nothing (Phase A)
  --apply-screenshots   upload the repo's screenshots to the EDITABLE App
                        Store version, replacing that version's sets (Phase B)

The apply path deliberately covers screenshots ONLY. Listing *text* is synced
by app-store.yml's submit step (freshly repaired in #2731/#2738, and the
4.22.0 submission is still pending) — duplicating its find-or-create-version
logic here would risk regressing the accumulated fixes it carries (#1831
editable-draft reuse, #2731 `filter[platform]=IOS`). This script therefore
never CREATES a version: it targets an existing editable one and SKIPs
honestly when there is none.

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

Version selection differs per mode, on purpose:
  --dry-run           the LIVE version (`READY_FOR_SALE`) — drift is what
                      users actually see. No live version → SKIP.
  --apply-screenshots the EDITABLE version (`PREPARE_FOR_SUBMISSION` /
                      `READY_FOR_REVIEW`) — the only one Apple lets us write
                      to. No editable version → SKIP (never create one).
Both filter on `filter[platform]=IOS`: without it a macOS draft can hijack
the query and every downstream call targets the wrong listing (#2731).

Screenshots persist from one App Store version to the next (a new version
inherits the previous set), so uploading is listing MAINTENANCE, not a
per-release step — which is why its CI caller is a dispatch-only workflow of
its own (`app-store-screenshots.yml`) rather than a job in app-store.yml:
that workflow's deploy jobs are gated only on `*_ready`, so a screenshot
dispatch there would also archive and upload a TestFlight build.

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
from urllib.parse import quote

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
    screenshots in set order.

    UNVALIDATED ASSUMPTION (review-fanout warning, PR #2764): equality relies
    on Apple's `sourceFileChecksum` being the plain MD5 of the exact uploaded
    PNG bytes. That is what the reservation-upload flow implies (the checksum
    is declared FOR the source file), but it has never been checked against a
    live set because the repo screenshots have never been uploaded. Before
    Phase C wires this diff into maintenance.yml / release-checklist.sh as a
    drift SIGNAL, run one live dry-run against a really-uploaded set and
    confirm md5_of(local png) == sourceFileChecksum; if Apple normalizes or
    re-encodes, re-key this diff on whatever Apple actually stores. Until
    then a checksum mismatch here is a candidate, not a verdict."""
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


def plan_screenshot_sync(local_files, remote):
    """Decide what one display type needs. Returns (action, delete_ids, uploads).

    `remote` is the ordered live set as [{"id": …, "checksum": …}, …].

    Two outcomes only — "skip" when the live set is already byte-identical IN
    ORDER, else "replace" (delete every live screenshot, upload every local
    one). A finer per-file diff is deliberately NOT attempted: the App Store
    displays screenshots in set order, so a partial update would still need a
    reorder call, and delete-then-upload is the same deterministic,
    idempotent shape the Play image sync already uses (#1710).

    Note that "replace yields repo order" rests on Apple keeping a set in
    creation order — plausible, and what the delete-then-upload shape is built
    on, but NOT promised by the API contract and never yet observed live. The
    apply path probes it after each upload and warns on mismatch rather than
    asserting it here (same treatment as the sourceFileChecksum==MD5
    assumption); if the probe ever fires, this strategy needs an explicit
    reorder call.
    """
    local = [(f, md5_of(f)) for f in local_files]
    if [c for _, c in local] == [r.get("checksum") for r in remote]:
        return "skip", [], []
    return "replace", [r["id"] for r in remote if r.get("id")], [f for f, _ in local]


def upload_operations_to_requests(blob, operations):
    """Turn Apple's `uploadOperations` into concrete requests.

    Each operation carries method/url/offset/length plus its own
    requestHeaders (a presigned upload target). Apple may split one asset
    into several chunks; slicing is pure arithmetic on the file bytes, so it
    is unit-tested offline rather than discovered against the live API.
    """
    requests_ = []
    for op in operations or []:
        offset = int(op.get("offset") or 0)
        length = int(op.get("length") if op.get("length") is not None else len(blob) - offset)
        headers = {h["name"]: h["value"] for h in (op.get("requestHeaders") or [])
                   if h.get("name")}
        requests_.append({
            "method": (op.get("method") or "PUT").upper(),
            "url": op["url"],
            "headers": headers,
            "body": blob[offset:offset + length],
        })
    return requests_


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

    r = requests.get(f"{BASE}/apps?filter[bundleId]={quote(bundle_id, safe='')}", headers=headers)
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


def _editable_version(requests, headers, app_id):
    """The one version Apple lets us write to, or None.

    `filter[platform]=IOS` is mandatory (#2731): without it the macOS app's
    permanently-editable draft can be returned instead, and every write then
    lands on the wrong listing.
    """
    r = requests.get(
        f"{BASE}/apps/{app_id}/appStoreVersions"
        "?filter[platform]=IOS"
        "&filter[appStoreState]=PREPARE_FOR_SUBMISSION,READY_FOR_REVIEW&limit=1",
        headers=headers,
    )
    r.raise_for_status()
    data = r.json().get("data", [])
    if not data:
        return None
    return data[0]["id"], data[0].get("attributes", {}).get("versionString", "?")


def _live_sets(requests, headers, loc_id):
    """Live screenshot sets for a localization: {displayType: (set_id, [shots])}."""
    r = requests.get(
        f"{BASE}/appStoreVersionLocalizations/{loc_id}/appScreenshotSets"
        "?include=appScreenshots&limit=50",
        headers=headers,
    )
    r.raise_for_status()
    payload = r.json()
    by_id = {inc["id"]: inc for inc in payload.get("included", [])
             if inc.get("type") == "appScreenshots"}
    sets = {}
    for s in payload.get("data", []):
        dtype = s.get("attributes", {}).get("screenshotDisplayType")
        refs = (s.get("relationships", {}).get("appScreenshots", {}).get("data") or [])
        shots = [{"id": ref["id"],
                  "checksum": by_id.get(ref["id"], {}).get("attributes", {}).get("sourceFileChecksum")}
                 for ref in refs]
        sets[dtype] = (s["id"], shots)
    return sets


def _upload_one(requests, headers, set_id, path):
    """Reserve → upload chunks → commit one screenshot. Returns (id, local_md5, live_checksum).

    The chunk PUTs use ONLY the presigned headers Apple hands back — the ASC
    JWT is deliberately not attached to them (different host, no need to
    widen where the credential travels), and no upload header is ever logged.
    """
    blob = path.read_bytes()
    local_md5 = md5_of(path)

    r = requests.post(
        f"{BASE}/appScreenshots",
        headers={**headers, "Content-Type": "application/json"},
        json={"data": {
            "type": "appScreenshots",
            "attributes": {"fileSize": len(blob), "fileName": path.name},
            "relationships": {"appScreenshotSet": {
                "data": {"type": "appScreenshotSets", "id": set_id}}},
        }},
    )
    if r.status_code not in (200, 201):
        raise RuntimeError(f"reserve failed for {path.name}: {r.status_code} {r.text[:300]}")
    reserved = r.json()["data"]
    shot_id = reserved["id"]

    for req in upload_operations_to_requests(
            blob, reserved.get("attributes", {}).get("uploadOperations")):
        up = requests.request(req["method"], req["url"],
                              headers=req["headers"], data=req["body"])
        if up.status_code not in (200, 201, 204):
            raise RuntimeError(
                f"chunk upload failed for {path.name}: {up.status_code}")

    # Commit. Apple's documented attribute is `uploaded`, which is what
    # fastlane/spaceship sends; some third-party write-ups use `isUploaded`.
    # Try the documented name, fall back once rather than leave a reserved
    # asset dangling in AWAITING_UPLOAD.
    commit = {"sourceFileChecksum": local_md5, "uploaded": True}
    r = requests.patch(
        f"{BASE}/appScreenshots/{shot_id}",
        headers={**headers, "Content-Type": "application/json"},
        json={"data": {"type": "appScreenshots", "id": shot_id, "attributes": commit}},
    )
    if r.status_code == 400:
        print(f"::warning::commit with 'uploaded' rejected for {path.name} "
              f"({r.status_code}) — retrying with 'isUploaded'")
        commit = {"sourceFileChecksum": local_md5, "isUploaded": True}
        r = requests.patch(
            f"{BASE}/appScreenshots/{shot_id}",
            headers={**headers, "Content-Type": "application/json"},
            json={"data": {"type": "appScreenshots", "id": shot_id, "attributes": commit}},
        )
    if r.status_code not in (200, 201):
        raise RuntimeError(f"commit failed for {path.name}: {r.status_code} {r.text[:300]}")

    live_checksum = r.json().get("data", {}).get("attributes", {}).get("sourceFileChecksum")
    return shot_id, local_md5, live_checksum


def _await_delivery(requests, headers, shot_id, name, attempts=15, delay=2):
    """Poll one screenshot until Apple finishes processing it. Returns a state string.

    Bounded on purpose (~30s): a stuck asset must surface as a warning, not
    hang the job.
    """
    state = "UNKNOWN"
    for _ in range(attempts):
        r = requests.get(f"{BASE}/appScreenshots/{shot_id}", headers=headers)
        if r.status_code != 200:
            return f"HTTP {r.status_code}"
        delivery = r.json().get("data", {}).get("attributes", {}).get("assetDeliveryState") or {}
        state = delivery.get("state", "UNKNOWN")
        if delivery.get("errors"):
            return f"{state} errors={delivery['errors']}"
        if state in ("COMPLETE", "FAILED"):
            return state
        time.sleep(delay)
    print(f"::warning::{name} still {state} after {attempts * delay}s — not waiting further")
    return state


def apply_screenshots(headers, bundle_id, shots_dir):
    """Upload the repo's screenshots to the editable version. Returns (changed, skipped).

    `skipped` is a human-readable reason when nothing could be done — an
    honest SKIP, never a silent green.
    """
    import requests

    r = requests.get(f"{BASE}/apps?filter[bundleId]={quote(bundle_id, safe='')}", headers=headers)
    r.raise_for_status()
    apps = r.json().get("data", [])
    if not apps:
        return [], f"no app found for bundleId {bundle_id}"
    app_id = apps[0]["id"]

    editable = _editable_version(requests, headers, app_id)
    if editable is None:
        return [], ("no editable iOS version (PREPARE_FOR_SUBMISSION / READY_FOR_REVIEW) — "
                    "screenshots can only be written to an editable version, and this "
                    "script never creates one (app-store.yml owns version creation)")
    version_id, version_string = editable
    print(f"[apply] editable iOS version: {version_string} ({version_id})")

    r = requests.get(f"{BASE}/appStoreVersions/{version_id}/appStoreVersionLocalizations",
                     headers=headers)
    r.raise_for_status()
    en_us = next((loc for loc in r.json().get("data", [])
                  if loc.get("attributes", {}).get("locale") == "en-US"), None)
    if en_us is None:
        return [], f"no en-US localization on version {version_string}"
    loc_id = en_us["id"]

    sets = _live_sets(requests, headers, loc_id)
    changed = []
    for device_dir, display_type in sorted(DISPLAY_TYPE_MAP.items()):
        ddir = shots_dir / device_dir
        files = sorted(ddir.glob("*.png")) if ddir.is_dir() else []
        if not files:
            print(f"[apply] {device_dir}: no repo screenshots — leaving the live set alone")
            continue

        set_id, live_shots = sets.get(display_type, (None, []))
        action, delete_ids, uploads = plan_screenshot_sync(files, live_shots)
        if action == "skip":
            print(f"[apply] {device_dir} ({display_type}): already identical — nothing to do")
            continue

        if set_id is None:
            r = requests.post(
                f"{BASE}/appScreenshotSets",
                headers={**headers, "Content-Type": "application/json"},
                json={"data": {
                    "type": "appScreenshotSets",
                    "attributes": {"screenshotDisplayType": display_type},
                    "relationships": {"appStoreVersionLocalization": {
                        "data": {"type": "appStoreVersionLocalizations", "id": loc_id}}},
                }},
            )
            if r.status_code not in (200, 201):
                print(f"::error::could not create {display_type} set: "
                      f"{r.status_code} {r.text[:300]}")
                raise SystemExit(1)
            set_id = r.json()["data"]["id"]
            print(f"[apply] {device_dir}: created {display_type} set {set_id}")

        # Delete-then-upload. Upload-then-delete is not an option: Apple caps
        # a set at 10 screenshots, so 6 old + 6 new would be refused, and the
        # new ones would land after the old ones anyway.
        #
        # A failed delete is FATAL for this display type rather than a
        # warning: leftovers sit ahead of the new screenshots (breaking the
        # very ordering this strategy exists to guarantee) and count against
        # the cap, so the next reserve can 409 halfway through and leave a
        # half-populated set. Stopping before the first upload leaves the
        # live set untouched and re-runnable.
        failed_deletes = []
        for shot_id in delete_ids:
            d = requests.delete(f"{BASE}/appScreenshots/{shot_id}", headers=headers)
            if d.status_code not in (200, 204):
                failed_deletes.append(f"{shot_id} ({d.status_code})")
        if failed_deletes:
            print(f"::error::{device_dir} ({display_type}): could not delete "
                  f"{len(failed_deletes)} live screenshot(s): {', '.join(failed_deletes)}. "
                  "Stopping before upload — the live set is unchanged; re-run once the "
                  "cause is cleared.")
            raise SystemExit(1)

        # From here the set is empty: an exception would leave it that way, so
        # say so explicitly rather than dying with a bare traceback. Scope is
        # the EDITABLE draft only — never the live READY_FOR_SALE listing —
        # and a re-run self-heals (plan_screenshot_sync re-uploads the lot).
        uploaded_checksums = []
        try:
            for path in uploads:
                shot_id, local_md5, live_checksum = _upload_one(requests, headers, set_id, path)
                state = _await_delivery(requests, headers, shot_id, path.name)
                if state == "FAILED":
                    print(f"::error::{path.name} failed Apple-side processing")
                    raise SystemExit(1)
                # Measure the assumption the drift diff relies on, instead of
                # trusting it (review-fanout warning, PR #2764).
                if live_checksum and live_checksum != local_md5:
                    print(f"::warning::{path.name}: live sourceFileChecksum {live_checksum} "
                          f"!= local MD5 {local_md5} — diff_screenshots() keys on MD5 equality; "
                          "re-key it before Phase C treats screenshot drift as a signal")
                else:
                    print(f"[apply] {path.name}: uploaded, {state}, checksum matches local MD5")
                uploaded_checksums.append(local_md5)
                changed.append(f"{device_dir}/{path.name}")
        except BaseException:
            print(f"::error::{device_dir} ({display_type}) left PARTIAL: "
                  f"{len(uploaded_checksums)}/{len(uploads)} uploaded after the old set was "
                  "deleted. Re-run --apply-screenshots to finish; this touched the editable "
                  "draft only, not the live listing.")
            raise

        # Ordering probe. The delete-then-upload strategy assumes Apple keeps
        # a set in creation order; nothing in the API contract promises it and
        # this has never run live, so MEASURE it (same treatment as the MD5
        # assumption) rather than asserting it in the docs.
        _, live_after = _live_sets(requests, headers, loc_id).get(display_type, (None, []))
        live_order = [s.get("checksum") for s in live_after]
        if live_order and live_order != uploaded_checksums:
            print(f"::warning::{device_dir} ({display_type}): live screenshot order does not "
                  "match upload order — creation order is NOT set order; an explicit reorder "
                  "(PATCH appScreenshotSets/{id}/relationships/appScreenshots) is required, "
                  "and the docs claiming repo filename order need correcting")

    return changed, None


def main(argv=None):
    # allow_abbrev=False is a safety requirement, not a style choice: argparse
    # otherwise accepts any unambiguous prefix, so `--apply` (the sibling
    # play_listing.py's real flag, and the obvious thing to type by habit) or
    # a typo like `--apply-screenshot` would silently resolve to
    # --apply-screenshots and push assets to the App Store.
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0], allow_abbrev=False)
    ap.add_argument("--dry-run", action="store_true",
                    help="read-only live-vs-repo diff (default)")
    ap.add_argument("--apply-screenshots", action="store_true",
                    help="upload the repo screenshots to the EDITABLE version (writes)")
    ap.add_argument("--fail-on-drift", action="store_true",
                    help="exit 3 when drift is found")
    ap.add_argument("--bundle-id", default=os.environ.get("ASC_BUNDLE_ID", DEFAULT_BUNDLE_ID))
    ap.add_argument("--metadata-dir", default=DEFAULT_METADATA_DIR)
    ap.add_argument("--screenshots-dir", default=DEFAULT_SCREENSHOTS_DIR)
    args, unknown = ap.parse_known_args(argv)
    if unknown:
        # A mistyped write flag must fail loudly rather than silently
        # degrade to the read-only default — a "successful" run that
        # uploaded nothing is exactly the fake green this repo bans.
        print(f"::error::Unknown option(s) {unknown}. Valid modes: --dry-run (default), "
              "--apply-screenshots. Listing TEXT is synced by app-store.yml, not here.")
        return 2

    if args.apply_screenshots and args.dry_run:
        print("::error::--dry-run and --apply-screenshots are mutually exclusive — "
              "pick reading or writing, not both.")
        return 2

    meta_dir = pathlib.Path(args.metadata_dir)
    shots_dir = pathlib.Path(args.screenshots_dir)
    if args.apply_screenshots:
        if not shots_dir.is_dir():
            print(f"::error::Screenshots dir not found: {shots_dir}")
            return 2
    elif not meta_dir.is_dir():
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

    headers = _headers(*creds)

    if args.apply_screenshots:
        changed, skipped = apply_screenshots(headers, args.bundle_id, shots_dir)
        if skipped:
            print(f"[skip] {skipped}")
            return 0
        if not changed:
            print(f"[apply] {args.bundle_id}: live screenshots already match the repo "
                  "— nothing uploaded")
            return 0
        print(f"[apply] {args.bundle_id}: {len(changed)} screenshot(s) uploaded:")
        for item in changed:
            print(f"  UPLOADED {item}")
        return 0

    drift, skipped = dry_run(headers, args.bundle_id, meta_dir, shots_dir)
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
