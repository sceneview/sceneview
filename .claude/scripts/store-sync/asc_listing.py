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
inherits the previous set), so uploading them is listing MAINTENANCE rather
than a per-release step. That is why `app-store-screenshots.yml` exists as a
dispatch-only workflow of its own: dispatching `app-store.yml` to refresh
screenshots would also archive and upload a TestFlight build, since its deploy
jobs are gated only on `*_ready`.

There are therefore TWO callers of apply_screenshots(), and the distinction
matters:

  - `app-store.yml`'s submit step calls it inline, between the listing-text
    sync and the review submission. That is the only moment an editable
    version exists — the step creates it, and Apple locks the metadata when it
    submits. Every release goes through this path (#2899).
  - `app-store-screenshots.yml` remains for out-of-band refreshes. It can only
    write when a version happens to be open, and SKIPs honestly otherwise.

Do NOT "restore" the separation by removing the inline call: a dispatch-only
caller alone means the screenshots are only ever written by luck, which is how
v4.26.0 shipped a set four releases stale.

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
import re
import sys
import time
from urllib.parse import quote

DEFAULT_BUNDLE_ID = "io.github.sceneview.demo"
DEFAULT_METADATA_DIR = "samples/ios-demo/distribution/app-store/en-US"
DEFAULT_SCREENSHOTS_DIR = "samples/ios-demo/appstore-screenshots"

BASE = "https://api.appstoreconnect.apple.com/v1"

# Apple's per-display-type screenshot limit.
MAX_SCREENSHOTS_PER_SET = 10

# Explicit HTTP timeouts. The chunk PUT runs AFTER the live set is deleted, so
# a blackholed socket would leave a display type empty until GitHub kills the
# job — fail fast into the PARTIAL-state message instead. (play_listing.py has
# no timeouts either; that is pre-existing and out of scope here.)
HTTP_TIMEOUT_S = 60
UPLOAD_TIMEOUT_S = 300

# Worst-case seconds spent waiting on one asset (see _await_delivery) and the
# two windows it has to fit inside: the JWT minted in _headers, and the CI job
# timeout. The JOB timeout is the binding one (900 < 1200) — checking only the
# JWT would stay silent at 8-10 screenshots and let the job be killed after the
# live set was already deleted.
ASSET_POLL_BUDGET_S = 120
JWT_LIFETIME_S = 1200
JOB_TIMEOUT_S = 900  # timeout-minutes: 15 in app-store-screenshots.yml
RUN_BUDGET_S = min(JWT_LIFETIME_S, JOB_TIMEOUT_S)

# Apple field caps — mirrors app-store.yml step 4b.
FIELDS = [
    ("description", "description.txt", 4000),
    ("keywords", "keywords.txt", 100),
    ("promotionalText", "promotional_text.txt", 170),
    ("marketingUrl", "marketing_url.txt", 2048),
    ("supportUrl", "support_url.txt", 2048),
]

# Repo screenshot dir → ASC screenshotDisplayType. The capture procedure
# (samples/ios-demo/appstore-screenshots/README.md, "How to regenerate") fills
# these two dirs; add a row here when it grows a new device class. An unmapped dir is not just skipped from the
# drift diff — since Phase B it never reaches the App Store either, while the
# run still reports success. test_asc_listing.py's
# `test_committed_dirs_are_all_mapped` fails when a committed dir has no row
# here (run by test-store-sync.sh via unittest discover, in repo-hygiene).
#
# The numbers deliberately DON'T match, and that is not a typo: Apple never
# added a 6.9" or a 13" display type. `APP_IPHONE_67` is the largest iPhone
# slot and takes both 1290x2796 (6.7") and 1320x2868 (6.9"); likewise the
# 13" iPad ships in `APP_IPAD_PRO_3GEN_129`. Verified against the enum
# (spaceship's AppScreenshotSet::DisplayType) — there is no APP_IPHONE_69 and
# no APP_IPAD_13. Do NOT "fix" these to match the dir names: an imageType /
# displayType the store doesn't know is exactly the failure that voided every
# Play listing sync for months (#2794).
DISPLAY_TYPE_MAP = {
    "iphone-6.9": "APP_IPHONE_67",
    "ipad-13": "APP_IPAD_PRO_3GEN_129",
}

# Apple's `ScreenshotDisplayType` enum — the ONLY values ASC accepts for an
# `appScreenshotSet`'s `screenshotDisplayType`. A value outside this set is not
# caught until the first REAL ASC call: on the write path (--apply-screenshots)
# the POST that creates the set 400s, and if an earlier display type was already
# replaced in the same run it leaves the listing partially applied; on the read
# path it silently reports permanent, unresolvable "never uploaded" drift for a
# slot that cannot exist. Pinning the enum lets `unknown_display_types()` reject
# a DISPLAY_TYPE_MAP typo OFFLINE (self-test + pre-flight) instead — symmetric
# with play_listing.py's VALID_IMAGE_TYPES, which closed exactly this hole for
# the Play `AppImageType` enum (#2794).
#
# Transcribed VERBATIM from the machine-readable source Apple publishes, NOT a
# prose summary — an allowlist whose whole job is to be exhaustive has to mirror
# the enum exactly (a web-search summary wrongly added `promoGraphic` on the Play
# side, caught only in review):
#   App Store Connect API OpenAPI spec v4.3 → components.schemas.ScreenshotDisplayType
# Cross-checked against fastlane spaceship's AppScreenshotSet::DisplayType — both
# agree on all 33 values. This enum has NO proto-zero sentinel to exclude (unlike
# AppImageType's appImageTypeUnspecified), so every documented value is kept —
# the iMessage / Watch / TV / Desktop / Vision types SceneView's demo does not
# use today included, so a future DISPLAY_TYPE_MAP row for one validates instead
# of being rejected as a false positive.
VALID_DISPLAY_TYPES = frozenset({
    # iPhone
    "APP_IPHONE_67", "APP_IPHONE_65", "APP_IPHONE_61", "APP_IPHONE_58",
    "APP_IPHONE_55", "APP_IPHONE_47", "APP_IPHONE_40", "APP_IPHONE_35",
    # iPad
    "APP_IPAD_PRO_3GEN_129", "APP_IPAD_PRO_3GEN_11", "APP_IPAD_PRO_129",
    "APP_IPAD_105", "APP_IPAD_97",
    # Mac / TV / Vision
    "APP_DESKTOP", "APP_APPLE_TV", "APP_APPLE_VISION_PRO",
    # Watch
    "APP_WATCH_ULTRA", "APP_WATCH_SERIES_10", "APP_WATCH_SERIES_7",
    "APP_WATCH_SERIES_4", "APP_WATCH_SERIES_3",
    # iMessage extension
    "IMESSAGE_APP_IPHONE_67", "IMESSAGE_APP_IPHONE_65", "IMESSAGE_APP_IPHONE_61",
    "IMESSAGE_APP_IPHONE_58", "IMESSAGE_APP_IPHONE_55", "IMESSAGE_APP_IPHONE_47",
    "IMESSAGE_APP_IPHONE_40",
    "IMESSAGE_APP_IPAD_PRO_3GEN_129", "IMESSAGE_APP_IPAD_PRO_3GEN_11",
    "IMESSAGE_APP_IPAD_PRO_129", "IMESSAGE_APP_IPAD_105", "IMESSAGE_APP_IPAD_97",
})


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


def unknown_display_types(display_map=None):
    """Return the configured screenshotDisplayType(s) Apple would reject.

    Offline guard mirroring play_listing.py's unknown_image_types(): a display
    type outside VALID_DISPLAY_TYPES is not rejected until the first live ASC
    call — on the write path, after an earlier display type's live set may
    already have been replaced. Checked by the self-test and again before any
    network call (main() for the CLI, apply_screenshots() for a direct
    importer). Returns the bad values in sorted-dir order, deterministic.
    """
    m = DISPLAY_TYPE_MAP if display_map is None else display_map
    return [dt for _, dt in sorted(m.items()) if dt not in VALID_DISPLAY_TYPES]


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

    ASSUMPTION, stated precisely (PR #2764 + #2781 reviews):

      - For screenshots uploaded by THIS script, equality holds by
        construction: the client computes MD5 and declares it as
        `sourceFileChecksum` in the commit PATCH, and Apple stores that value.
        (Which also means the apply path cannot independently *verify* the
        MD5 convention — Apple only echoes what we sent. An earlier comment
        claimed that echo was a measurement; it was not.)
      - For screenshots uploaded any OTHER way — the App Store Connect web
        console being the realistic case — what Apple stores is not known to
        be an MD5 of the source bytes, and may be absent.

    Since the repo's screenshots have never been uploaded, every live set out
    there today is in the second category. So before Phase C wires this diff
    into a drift SIGNAL, the shape of the live checksums has to be measured:
    that is what classify_live_checksums() / checksum_provenance_report() do,
    and dry_run() prints their verdict before running this diff. Until that
    verdict is CONFIRMED, a checksum mismatch is a candidate, not a verdict."""
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


# An App Store Connect `sourceFileChecksum` we believe to be MD5 — 32 lowercase
# hex. Shape alone never proves the convention (any 128-bit digest looks like
# this), which is exactly why classify_live_checksums() reports the shape as a
# CANDIDATE and only calls it confirmed on a byte-level match.
MD5_HEX_RE = re.compile(r"[0-9a-f]{32}")


def classify_checksum(value):
    """Bucket one live `sourceFileChecksum` by SHAPE. Pure, offline, total."""
    if value is None or value == "":
        return "absent"
    # Total on any input: a helper advertised as pure should not raise when
    # Apple one day returns a number where a string was expected.
    if not isinstance(value, str):
        return "other"
    if MD5_HEX_RE.fullmatch(value):
        return "md5-shaped"
    return "other"


def classify_live_checksums(live_sets, local_md5s=(), console_sourced=False):
    """Measure what Apple actually stores for CONSOLE-uploaded screenshots.

    This is the probe #2612 Phase C is blocked on, and it exists because the
    checksum diff in diff_screenshots() rests on an assumption nobody has ever
    measured: that `sourceFileChecksum` is the MD5 of the SOURCE bytes.

    That assumption is true by construction for assets this script uploaded
    (we compute the MD5 and declare it; Apple echoes it back), so the apply
    path can never test it — an echo is not a measurement.

    Which is exactly why a repo-MD5 match is NOT self-validating, and why
    `console_sourced` exists. Once apply_screenshots() has run, the sampled
    sets hold the very checksums we declared, so a match would be the echo
    wearing the mask of a measurement — a permanently-green CONFIRMED that
    unblocks a gate nothing ever tested. The caller must attest that the
    sampled screenshots did not come from our own write path; absent that
    attestation a match reports `unattested-match`, a strictly weaker claim.

    The attestation covers EVERY sampled set, live and draft alike, and the
    verdict reports WHICH display type each match came from — because the
    draft is precisely where apply_screenshots() writes. An attestation that
    only ranged over the live version would be trivially true (the write path
    never touches a READY_FOR_SALE version) while a draft match quietly
    supplied the CONFIRMED. That is the same tautology one level down.

    `live_sets` maps display type -> ordered list of live checksums (None when
    Apple returned no value). `local_md5s` is the MD5 of every committed repo
    screenshot; a live value that EQUALS one of them settles the question
    outright *provided* it was not us who put it there — a 128-bit collision
    is not a thing that happens, but an echo is.

    Returns a verdict dict; verdict["overall"] is one of:

      no-live-assets  nothing uploaded to compare against — cannot measure
      confirmed         a sampled checksum equals a repo file's MD5 AND the
                        caller attested console provenance → the convention
                        holds. This is the ONLY Phase C unblocker.
      unattested-match  the same match, provenance NOT attested → could be our
                        own echo. Weaker than it looks; never a gate.
      md5-shaped      all 32-hex → CONSISTENT with MD5, not proof. Closing it
                      needs one console upload of a file whose MD5 we know.
      absent          Apple stores no checksum → a checksum diff can never be
                      a drift signal for console assets; Phase C must re-key.
      other           present but not MD5-shaped → assumption refuted; re-key
                      on whatever Apple actually stores (shape reported).
      mixed           buckets disagree — report rather than average them.
    """
    local_md5s = set(local_md5s)
    per_type, buckets, matched, matched_by_type = {}, {}, [], {}
    # Sort defensively: a live set whose screenshotDisplayType is missing keys
    # this dict on None, and None vs str is a TypeError that would take the
    # whole read-only run down — reported, thanks to the partial-read guard
    # below, but still a probe that measures nothing.
    for dtype, checksums in sorted(live_sets.items(),
                                   key=lambda kv: (kv[0] is None, kv[0] or "")):
        kinds = [classify_checksum(c) for c in checksums]
        per_type[dtype] = kinds
        for kind in kinds:
            buckets[kind] = buckets.get(kind, 0) + 1
        hits = [c for c in checksums if c and c in local_md5s]
        matched += hits
        # WHERE a match was found is load-bearing, not decoration: a hit in a
        # "… (draft)" set is a hit in the exact version apply_screenshots()
        # writes to. Whoever attests provenance has to see which set they are
        # vouching for, or the attestation is uninformed.
        if hits:
            matched_by_type[dtype] = sorted(set(hits))

    # Shapes we could not name, verbatim-ish, so a re-key has something to go
    # on instead of "not MD5". Length is the discriminating detail (a SHA-256
    # is 64 hex, a base64 digest is 24/44 chars with padding). str() because
    # classify_checksum() is total and buckets non-str values here too.
    unknown_shapes = sorted({
        f"len={len(str(c))}" for checksums in live_sets.values() for c in checksums
        if classify_checksum(c) == "other"
    })

    if matched:
        overall = "confirmed" if console_sourced else "unattested-match"
    elif not buckets:
        overall = "no-live-assets"
    elif len(buckets) > 1:
        overall = "mixed"
    else:
        overall = next(iter(buckets))

    return {
        "overall": overall,
        "buckets": buckets,
        "per_display_type": per_type,
        "matched_local": sorted(set(matched)),
        "matched_by_display_type": matched_by_type,
        "unknown_shapes": unknown_shapes,
    }


def checksum_provenance_report(verdict):
    """Turn a classify_live_checksums() verdict into what it LICENSES.

    Deliberately states the consequence for Phase C rather than just the
    observation: the failure mode this whole probe exists to prevent is
    someone reading "4 screenshots differ" and wiring it into a release gate
    without noticing the diff was keyed on an unmeasured assumption."""
    overall = verdict["overall"]
    lines = [f"[probe] sourceFileChecksum provenance: {overall.upper()}"]
    for dtype, kinds in verdict["per_display_type"].items():
        lines.append(f"[probe]   {dtype}: {len(kinds)} live — "
                     + (", ".join(sorted(set(kinds))) or "none"))

    # Where each match sits decides whether it can mean anything at all, so it
    # is printed for every match-bearing verdict, attested or not.
    # Direct subscript, not .get(): these lines ARE the disclosure that makes
    # the attestation an informed one. A verdict built elsewhere without them
    # would print four affirmative CONFIRMED lines and no evidence at all —
    # a KeyError is strictly better than evidence that is silently absent.
    where = [f"[probe] matched in {dtype}: " + ", ".join(digests)
             for dtype, digests in sorted(
                 verdict["matched_by_display_type"].items(),
                 key=lambda kv: kv[0] or "")]

    if overall == "confirmed":
        lines += [
            "[probe] A sampled checksum equals a repo file's MD5, on sets attested as",
            "[probe] console-sourced: Apple stores the MD5 of the SOURCE bytes for",
            "[probe] console uploads too. The screenshot diff is a real drift signal",
            "[probe] — #2612 Phase C can wire it into a gate.",
        ] + where
    elif overall == "unattested-match":
        lines += [
            "[probe] A sampled checksum equals a repo file's MD5 — but NOTHING here",
            "[probe] says who uploaded it. If apply_screenshots() wrote those assets,",
            "[probe] the match is our own declared value echoed back: it proves nothing.",
            "[probe] This is NOT a Phase C unblocker.",
        ] + where + [
            "[probe] READ THE SET NAMES ABOVE FIRST — each carries the VERSION it was",
            "[probe] read from. apply_screenshots() writes to the editable version, and",
            "[probe] that version KEEPS its screenshots when it goes live: a set being",
            "[probe] live today is no evidence about who uploaded it. So 'the script",
            "[probe] never touched the live version' attests nothing, at any date.",
            "[probe] Check EVERY --apply-screenshots run — the app-store-screenshots.yml",
            "[probe] history AND any local invocation, which leaves no CI trace —",
            "[probe] against every version listed above, including already-shipped",
            "[probe] ones; only if none of them wrote these sets, re-run with",
            "[probe] --screenshots-are-console-sourced.",
        ]
    elif overall == "md5-shaped":
        lines += [
            "[probe] Every live checksum is 32-hex — CONSISTENT with MD5, but any",
            "[probe] 128-bit digest is. Not proof, and not enough for a gate.",
            "[probe] To close it: upload ONE repo screenshot via the App Store Connect",
            "[probe] console, then re-run. NOTE: console uploads land on the EDITABLE",
            "[probe] draft, so the probe only sees them once that version is live, or",
            "[probe] via the draft set this probe reads separately (look for the",
            "[probe] '@draft …' display types above). Promoting the resulting match to",
            "[probe] CONFIRMED still needs --screenshots-are-console-sourced.",
        ]
    elif overall == "absent":
        lines += [
            "[probe] Apple stores NO checksum for these assets. The MD5 assumption is",
            "[probe] refuted for console uploads: a checksum diff can never detect drift",
            "[probe] on them. Phase C must re-key the screenshot diff (count / order /",
            "[probe] dimensions) or drop screenshot drift and keep the text diff.",
        ]
    elif overall == "other":
        lines += [
            "[probe] Live checksums exist but are NOT MD5-shaped — assumption refuted.",
            "[probe] observed shapes: " + (", ".join(verdict["unknown_shapes"]) or "?"),
            "[probe] Phase C must re-key on whatever Apple actually stores.",
        ]
    elif overall == "mixed":
        lines += [
            "[probe] Buckets disagree, so the live set has assets from more than one",
            "[probe] upload path. Do NOT average this into a single rule — re-run after",
            "[probe] the next console upload and classify that asset specifically.",
        ]
    else:  # no-live-assets
        lines += [
            "[probe] No live screenshots to measure. Nothing is refuted and nothing is",
            "[probe] confirmed — Phase C stays blocked until a set exists to sample.",
        ]
    return lines


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


def _attestation_source(argv=None):
    """Name where a provenance attestation came from, for the log."""
    passed = argv if argv is not None else sys.argv[1:]
    return ("--screenshots-are-console-sourced"
            if "--screenshots-are-console-sourced" in passed
            else "ASC_PROBE_CONSOLE_SOURCED=1")


def _probe_key(display_type, version_label):
    """Label a sampled set with the VERSION it was read from.

    Not cosmetic. An editable draft keeps its id, its localization and its
    screenshot sets when it transitions to READY_FOR_SALE — so screenshots that
    --apply-screenshots wrote into a draft become, one release later, ordinary
    live screenshots. Any wording resting on "the script never writes the live
    version" is therefore true only until the next release ships, and an
    operator reading an unlabelled `matched in APP_IPHONE_67` months later has
    no way to tell our own echo from a console upload.

    With the version stamped in, the attestation becomes checkable: compare the
    app-store-screenshots.yml run history against the version that set belongs
    to, instead of believing a claim about where writes can land.
    """
    return f"{display_type or '<unknown display type>'} @{version_label}"


def _probe_screenshot_sets(requests, headers, version_id):
    """Read-only: display type -> ordered live `sourceFileChecksum` list.

    Split out of dry_run() so the probe can sample the EDITABLE DRAFT as well
    as the live version. That is not a nicety: screenshots on a READY_FOR_SALE
    version are not editable in the App Store Connect console, so the one
    action that can settle the MD5 question — a human dropping a repo file in
    the console — lands on the draft. A probe that only read the live version
    would tell the operator to do something, then never see them do it.

    Deliberately does NOT reuse _live_sets(): that one belongs to the write
    path and returns set ids for mutation. This returns checksums and nothing
    that could be written back.
    """
    r = requests.get(
        f"{BASE}/appStoreVersions/{version_id}/appStoreVersionLocalizations",
        headers=headers, timeout=HTTP_TIMEOUT_S,
    )
    r.raise_for_status()
    en_us = next((loc for loc in r.json().get("data", [])
                  if loc.get("attributes", {}).get("locale") == "en-US"), None)
    if en_us is None:
        return {}, None
    r = requests.get(
        f"{BASE}/appStoreVersionLocalizations/{en_us['id']}/appScreenshotSets"
        "?include=appScreenshots&limit=50",
        headers=headers, timeout=HTTP_TIMEOUT_S,
    )
    r.raise_for_status()
    payload = r.json()
    shots_by_id = {inc["id"]: inc for inc in payload.get("included", [])
                   if inc.get("type") == "appScreenshots"}
    sets = {}
    for s in payload.get("data", []):
        dtype = s.get("attributes", {}).get("screenshotDisplayType")
        refs = (s.get("relationships", {}).get("appScreenshots", {}).get("data") or [])
        sets[dtype] = [shots_by_id.get(ref["id"], {})
                       .get("attributes", {}).get("sourceFileChecksum")
                       for ref in refs]
    return sets, en_us


def dry_run(headers, bundle_id, meta_dir, shots_dir, console_sourced=False,
            attested_via=None):
    """Diff the live App Store listing against the repo. Returns (drift, skipped).

    `skipped` is a human-readable reason when the diff could not run (no app,
    no live version) — an honest SKIP, never silent."""
    import requests

    r = requests.get(f"{BASE}/apps?filter[bundleId]={quote(bundle_id, safe='')}", headers=headers, timeout=HTTP_TIMEOUT_S)
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

    # An editable draft means a release is in flight — the live listing may lag
    # the repo legitimately until it ships. It is also the ONLY place a console
    # screenshot upload can land, so the probe samples it too (read-only); the
    # text/screenshot diff below stays strictly on the live version.
    draft_sets = {}
    r = requests.get(
        f"{BASE}/apps/{app_id}/appStoreVersions"
        "?filter[platform]=IOS&filter[appStoreState]=PREPARE_FOR_SUBMISSION,READY_FOR_REVIEW&limit=1",
        headers=headers, timeout=HTTP_TIMEOUT_S,
    )
    if r.status_code == 200 and r.json().get("data"):
        draft = r.json()["data"][0]
        draft_vs = draft.get("attributes", {}).get("versionString", "?")
        print(f"[dry-run] note: editable draft {draft_vs} exists — live may lag the repo legitimately")
        try:
            raw, draft_en_us = _probe_screenshot_sets(requests, headers, draft["id"])
            if draft_en_us is None:
                # Distinguish "no en-US localization" from "no screenshots":
                # the report tells the operator to look for draft sets, so
                # their absence must have a stated reason.
                print(f"[dry-run] note: draft {draft_vs} has no en-US localization "
                      "— no draft sets to sample")
            draft_sets = {_probe_key(k, f"draft {draft_vs}"): v for k, v in raw.items()}
        except Exception as exc:  # noqa: BLE001 — the draft is a bonus sample
            # Never let the optional sample take down the diff the job exists
            # for. Say it out loud rather than silently reporting a smaller set.
            print(f"::warning::could not read draft screenshot sets: {exc}")

    # One reader for both versions. Keeping a second inline copy here would let
    # the two drift apart — the `limit=50` cap fixed in one and not the other
    # would have the probe and the diff describing the same version with
    # different counts, each contradicting the other in the same log.
    live_sets, en_us = _probe_screenshot_sets(requests, headers, version_id)
    if en_us is None:
        return [f"en-US: localization missing on the live version {version_string}"], None

    drift = []
    local_fields, notes = read_metadata_fields(meta_dir)
    for note in notes:
        print(f"::warning::{note}")
    drift += diff_fields(local_fields, en_us.get("attributes", {}))

    # Probe FIRST, diff second (#2612 Phase C step 0): the screenshot diff below
    # is keyed on `sourceFileChecksum == MD5(source bytes)`, which is true by
    # construction for any asset THIS script uploaded. Printing what Apple
    # actually stores, before the diff it justifies, is what stops a mismatch
    # from being read as measured drift. The draft sets are folded in because a
    # console upload — the only thing that can settle the question — lands on
    # the editable version. Note it does not STAY there: that version keeps its
    # screenshots when it ships, which is why every set is stamped with the
    # version it came from rather than merely marked draft-or-not.
    local_md5s = [md5_of(p)
                  for device_dir in DISPLAY_TYPE_MAP
                  if (shots_dir / device_dir).is_dir()
                  for p in sorted((shots_dir / device_dir).glob("*.png"))]
    # Version-stamped keys for the probe; the diff below keeps the raw display
    # types it needs to look sets up by.
    probe_sets = {_probe_key(k, version_string): v for k, v in live_sets.items()}
    for key, value in draft_sets.items():
        # Unreachable while versionStrings differ, but a silent overwrite here
        # would drop a live set out of the sample without a word.
        if key in probe_sets:
            print(f"::warning::probe key collision on {key} — draft and live sets "
                  "share a label; reporting the draft one")
        probe_sets[key] = value
    if console_sourced:
        # Announced HERE, on the decision path, not in main(): a direct
        # importer calling dry_run(console_sourced=True) would otherwise get a
        # CONFIRMED with no trace of the attestation that licensed it.
        print(f"[probe] console provenance ATTESTED via "
              f"{attested_via or _attestation_source()} — CONFIRMED is reachable "
              "this run; a repo-MD5 match will be read as proof.")
    for line in checksum_provenance_report(
            classify_live_checksums(probe_sets, local_md5s, console_sourced)):
        print(line)

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
        headers=headers, timeout=HTTP_TIMEOUT_S,
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
        headers=headers, timeout=HTTP_TIMEOUT_S,
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
        timeout=HTTP_TIMEOUT_S,
    )
    if r.status_code not in (200, 201):
        raise RuntimeError(f"reserve failed for {path.name}: {r.status_code} {r.text[:300]}")
    reserved = r.json()["data"]
    shot_id = reserved["id"]

    ops = upload_operations_to_requests(
        blob, reserved.get("attributes", {}).get("uploadOperations"))
    if blob and not ops:
        # No operations means zero bytes were transferred. Committing anyway
        # would mark an empty asset as uploaded — and since the old set is
        # already deleted by then, the display type ends up empty while the
        # run reports success.
        raise RuntimeError(
            f"reserve for {path.name} returned no uploadOperations — refusing to "
            "commit an asset whose bytes were never uploaded")

    for req in ops:
        # requests embeds the full URL in its exception messages, and these
        # URLs are PRESIGNED — a ConnectionError traceback would print the
        # signature into a public repo's workflow log. Re-raise without the
        # original message (and never log req["headers"], same reason).
        try:
            # allow_redirects=False: the presigned target is terminal, so a
            # 3xx is an anomaly — and requests only strips Authorization/Cookie
            # across hosts, so Apple's custom upload headers (and the body)
            # would be replayed verbatim to whatever the redirect names.
            up = requests.request(req["method"], req["url"],
                                  headers=req["headers"], data=req["body"],
                                  timeout=UPLOAD_TIMEOUT_S, allow_redirects=False)
        except Exception as e:
            raise RuntimeError(
                f"chunk upload failed for {path.name}: {type(e).__name__}") from None
        if up.status_code not in (200, 201, 204):
            raise RuntimeError(
                f"chunk upload failed for {path.name}: {up.status_code}"
                + (" (unexpected redirect — presigned target should be terminal)"
                   if 300 <= up.status_code < 400 else ""))

    # Commit. `uploaded` is the attribute Apple documents and the one
    # fastlane/spaceship sends. An earlier cut retried with `isUploaded` on a
    # 400 — speculative (that name only appears in third-party write-ups),
    # doomed if the real cause was anything else, and it overwrote the first
    # response body so the actual rejection reason was lost. Fail on the first
    # non-2xx and surface the body instead.
    commit = {"sourceFileChecksum": local_md5, "uploaded": True}
    r = requests.patch(
        f"{BASE}/appScreenshots/{shot_id}",
        headers={**headers, "Content-Type": "application/json"},
        json={"data": {"type": "appScreenshots", "id": shot_id, "attributes": commit}},
        timeout=HTTP_TIMEOUT_S,
    )
    if r.status_code not in (200, 201):
        raise RuntimeError(f"commit failed for {path.name}: {r.status_code} {r.text[:300]}")

    # NOTE: this is Apple ECHOING the checksum we just declared — it is NOT an
    # independent verification of `sourceFileChecksum == MD5(file)`. It only
    # confirms the value was accepted and stored. See diff_screenshots().
    echoed_checksum = r.json().get("data", {}).get("attributes", {}).get("sourceFileChecksum")
    return shot_id, local_md5, echoed_checksum


def _await_delivery(requests, headers, shot_id, name, attempts=40, delay=3):
    """Poll one screenshot until Apple finishes processing it.

    Returns (ok, detail). `ok` is True ONLY for a clean COMPLETE; every other
    terminal outcome — FAILED, an `errors` payload, a non-200 poll — is False
    so the caller hard-fails.

    The first cut returned a single string and the caller tested
    `state == "FAILED"`. That silently defeated the hard-fail: an asset Apple
    REJECTS comes back as state FAILED *with* an `errors` payload, which the
    string form rendered as "FAILED errors=[…]" — never equal to "FAILED". A
    wrong-dimension PNG was therefore reported as uploaded and the job exited
    0, with the previous set already deleted (PR #2781 review). Terminal
    verdict and diagnostic detail must not share one channel.

    The bound (40 × 3s = ~2 min per asset) is the one non-fatal case: still
    processing is not evidence of failure, so it warns and reports ok=True.
    The first cut used 30s, which is short next to real Apple processing
    (fastlane polls for minutes) — and since the previous set is already
    deleted by then, a slow-but-ultimately-REJECTED asset would have exited
    the job green with a truncated live set. That is the same fake green the
    (ok, detail) split closed, reached through slowness instead of through
    string comparison. A timeout is therefore reported as INDETERMINATE and
    surfaced again in the summary, not buried in one log line.

    Budget: the JWT lasts 1200s and the dispatch workflow allows 15 min. The
    repo currently ships 4 screenshots (2 iPhone + 2 iPad), so 4 × 2 min worst
    case = 8 min, which fits both. Do not treat that as headroom to spend:
    apply_screenshots() re-checks the budget against the ACTUAL upload count
    before deleting anything, precisely because this comment's arithmetic goes
    stale every time the set changes — it said 6 until #3013.
    """
    state = "UNKNOWN"
    for _ in range(attempts):
        r = requests.get(f"{BASE}/appScreenshots/{shot_id}", headers=headers, timeout=HTTP_TIMEOUT_S)
        if r.status_code != 200:
            return False, f"poll failed: HTTP {r.status_code}"
        delivery = r.json().get("data", {}).get("attributes", {}).get("assetDeliveryState") or {}
        state = delivery.get("state", "UNKNOWN")
        errors = delivery.get("errors")
        if errors:
            return False, f"{state} errors={errors}"
        if state == "COMPLETE":
            return True, state
        if state == "FAILED":
            return False, state
        time.sleep(delay)
    print(f"::warning::{name} still {state} after {attempts * delay}s — not waiting further")
    return True, f"{state} (still processing)"


def apply_screenshots(headers, bundle_id, shots_dir):
    """Upload the repo's screenshots to the editable version. Returns (changed, skipped).

    `skipped` is a human-readable reason when nothing could be done — an
    honest SKIP, never a silent green.
    """
    # Re-assert at the WRITE boundary, not just the CLI one (main() guards
    # that): a caller importing apply_screenshots() directly would otherwise
    # reach the live API and 400 at set-creation for a bogus display type —
    # possibly after an earlier display type's live set was already replaced,
    # leaving a partially-applied listing (#2794's failure mode, minus Play's
    # atomic rollback). Before `import requests` so the invariant holds — and
    # stays unit-testable — without the lazy third-party deps.
    bad_types = unknown_display_types()
    if bad_types:
        raise ValueError(
            f"DISPLAY_TYPE_MAP declares screenshotDisplayType(s) Apple does not "
            f"accept: {bad_types}. Valid values: {sorted(VALID_DISPLAY_TYPES)}"
        )

    import requests

    r = requests.get(f"{BASE}/apps?filter[bundleId]={quote(bundle_id, safe='')}", headers=headers, timeout=HTTP_TIMEOUT_S)
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
                     headers=headers, timeout=HTTP_TIMEOUT_S)
    r.raise_for_status()
    en_us = next((loc for loc in r.json().get("data", [])
                  if loc.get("attributes", {}).get("locale") == "en-US"), None)
    if en_us is None:
        return [], f"no en-US localization on version {version_string}"
    loc_id = en_us["id"]

    # Worst-case polling budget vs the credential window, checked BEFORE
    # anything is deleted. Not a blocker (the realistic case is seconds per
    # asset), but a run that could outlive its own token should say so rather
    # than fail halfway with an opaque 401.
    total_files = sum(len(list((shots_dir / d).glob("*.png")))
                      for d in DISPLAY_TYPE_MAP if (shots_dir / d).is_dir())
    worst_case = total_files * ASSET_POLL_BUDGET_S
    if worst_case > RUN_BUDGET_S:
        print(f"::warning::worst-case polling for {total_files} screenshot(s) is "
              f"{worst_case}s, beyond this run's {RUN_BUDGET_S}s budget (min of the "
              f"{JWT_LIFETIME_S}s credential window and the {JOB_TIMEOUT_S}s job "
              "timeout) — a very slow run could be cut off after the live set was "
              "deleted. Re-running is safe (it resumes from the current live state).")

    sets = _live_sets(requests, headers, loc_id)
    changed = []
    # Assets Apple had not finished processing when we stopped waiting. Not
    # failures — but not confirmed successes either, so they are surfaced
    # again at the end instead of scrolling past in the per-file log.
    indeterminate = []
    # How many display types we actually looked at. Without this, "nothing
    # uploaded" cannot be told apart from "nothing found to upload", and the
    # caller would claim the live listing matches a repo it never read.
    compared = 0
    for device_dir, display_type in sorted(DISPLAY_TYPE_MAP.items()):
        ddir = shots_dir / device_dir
        files = sorted(ddir.glob("*.png")) if ddir.is_dir() else []
        if not files:
            print(f"[apply] {device_dir}: no repo screenshots — leaving the live set alone")
            continue
        compared += 1

        set_id, live_shots = sets.get(display_type, (None, []))
        action, delete_ids, uploads = plan_screenshot_sync(files, live_shots)
        if action == "skip":
            print(f"[apply] {device_dir} ({display_type}): already identical — nothing to do")
            continue

        # Apple caps a set at 10. Refuse BEFORE any write — creating the set
        # included: aborting after the POST would leave an EMPTY screenshot set
        # on the draft, which App Review can reject. Nothing may start that
        # cannot finish. (Not reachable with today's 4+2 committed screenshots;
        # the guard is cheap and the capture script can grow.)
        if len(uploads) > MAX_SCREENSHOTS_PER_SET:
            print(f"::error::{device_dir} ({display_type}): {len(uploads)} screenshots "
                  f"exceeds Apple's cap of {MAX_SCREENSHOTS_PER_SET} — refusing to touch "
                  "the live set for an upload that cannot complete.")
            raise SystemExit(1)

        # Re-check the version is STILL editable before writing anything. The
        # shared concurrency group rules out a same-repo deploy in parallel,
        # but a human can submit for review from the console at any moment;
        # writing into a locked version leaves it in review with a truncated
        # set. A narrow TOCTOU window remains — this shrinks it to seconds
        # rather than the whole run.
        if delete_ids:
            still = _editable_version(requests, headers, app_id)
            if still is None or still[0] != version_id:
                print(f"::error::{device_dir} ({display_type}): version {version_string} is no "
                      "longer editable (submitted for review?) — refusing to delete its "
                      "screenshots. This display type was NOT touched"
                      + (f"; {len(changed)} screenshot(s) already uploaded earlier in this "
                         f"run: {', '.join(changed)}" if changed else " and nothing was "
                         "uploaded before it") + ".")
                raise SystemExit(1)

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
                timeout=HTTP_TIMEOUT_S,
            )
            if r.status_code not in (200, 201):
                print(f"::error::could not create {display_type} set: "
                      f"{r.status_code} {r.text[:300]}")
                raise SystemExit(1)
            set_id = r.json()["data"]["id"]
            print(f"[apply] {device_dir}: created {display_type} set {set_id}")

        # Delete-then-upload. Upload-then-delete is not an option: Apple caps
        # a set at 10, so 6 old + 6 new would be refused, and the new ones
        # would land after the old ones anyway.
        #
        # A failed delete is FATAL for this display type rather than a warning:
        # leftovers sit ahead of the new screenshots (breaking the very
        # ordering this strategy exists to produce) and count against the cap,
        # so the next reserve can 409 halfway through and leave a
        # half-populated set. Stopping before the first upload leaves the live
        # set untouched and re-runnable.
        failed_deletes = []
        for shot_id in delete_ids:
            d = requests.delete(f"{BASE}/appScreenshots/{shot_id}", headers=headers, timeout=HTTP_TIMEOUT_S)
            if d.status_code not in (200, 204):
                failed_deletes.append(f"{shot_id} ({d.status_code})")
        if failed_deletes:
            print(f"::error::{device_dir} ({display_type}): could not delete "
                  f"{len(failed_deletes)} live screenshot(s): {', '.join(failed_deletes)}. "
                  "Stopping before upload — this display type's live set is unchanged"
                  + (f"; {len(changed)} screenshot(s) were already uploaded earlier in this "
                     f"run: {', '.join(changed)}" if changed else "")
                  + ". Re-run once the cause is cleared.")
            raise SystemExit(1)

        # From here the set is empty: an exception would leave it that way, so
        # say so explicitly rather than dying with a bare traceback. Scope is
        # the EDITABLE draft only — never the live READY_FOR_SALE listing —
        # and a re-run self-heals (plan_screenshot_sync re-uploads the lot).
        uploaded_checksums = []
        all_echoed = True
        try:
            for path in uploads:
                shot_id, local_md5, echoed = _upload_one(requests, headers, set_id, path)
                ok, detail = _await_delivery(requests, headers, shot_id, path.name)
                if not ok:
                    print(f"::error::{path.name} was not delivered: {detail}")
                    raise SystemExit(1)
                # `echoed` is the checksum Apple echoed back from OUR commit,
                # so a mismatch means Apple stored something other than what we
                # declared. It does NOT independently confirm that
                # sourceFileChecksum is an MD5 — Apple never recomputes it here.
                if echoed is None:
                    all_echoed = False
                    print(f"[apply] {path.name}: uploaded, {detail} — no sourceFileChecksum "
                          "returned, so the declared checksum could not be confirmed")
                elif echoed != local_md5:
                    all_echoed = False
                    print(f"::warning::{path.name}: Apple stored sourceFileChecksum {echoed} "
                          f"but we declared {local_md5} — diff_screenshots() keys on that "
                          "value, so investigate before Phase C trusts the drift diff")
                else:
                    print(f"[apply] {path.name}: uploaded, {detail}, declared checksum stored")
                if "still processing" in detail:
                    indeterminate.append(f"{device_dir}/{path.name}")
                uploaded_checksums.append(local_md5)
                changed.append(f"{device_dir}/{path.name}")
        except BaseException:
            print(f"::error::{device_dir} ({display_type}) left PARTIAL: "
                  f"{len(uploaded_checksums)}/{len(uploads)} uploaded after the old set was "
                  "deleted. Re-run --apply-screenshots to finish; this touched the editable "
                  "draft only, not the live listing.")
            raise

        # Ordering probe. delete-then-upload assumes Apple keeps a set in
        # creation order; nothing in the API contract promises it and this has
        # never run live, so check it instead of asserting it in the docs.
        #
        # This comparison is only meaningful when every checksum came back
        # echoed and matching: the live values are then known identifiers for
        # our files, and their SEQUENCE is real evidence about ordering. If any
        # were missing or altered, a mismatch here could just as well be the
        # checksum keying — so say the probe is inconclusive rather than
        # blaming ordering for it.
        _, live_after = _live_sets(requests, headers, loc_id).get(display_type, (None, []))
        live_order = [s.get("checksum") for s in live_after]
        if not all_echoed:
            print(f"[apply] {device_dir} ({display_type}): ordering probe inconclusive — "
                  "checksums were not all confirmed, so sequence cannot be attributed")
        elif live_order and live_order != uploaded_checksums:
            print(f"::warning::{device_dir} ({display_type}): live screenshot order does not "
                  "match upload order — creation order is NOT set order; an explicit reorder "
                  "(PATCH appScreenshotSets/{id}/relationships/appScreenshots) is required, "
                  "and the docs claiming repo filename order need correcting")

    if indeterminate:
        print(f"::warning::{len(indeterminate)} screenshot(s) were still processing when "
              f"polling stopped — delivery NOT confirmed: {', '.join(indeterminate)}. "
              "Check App Store Connect, or re-run to re-verify.")

    if compared == 0:
        return changed, (f"no repo screenshots found under {shots_dir} — nothing was "
                         "compared or uploaded (live listing NOT verified)")
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
    # The operator's attestation that the SAMPLED screenshots — live *and* the
    # editable draft, which is where --apply-screenshots writes — were not put
    # there by this script. Without it a repo-MD5 match can only report
    # `unattested-match`, because our own upload declares that same MD5 and
    # Apple echoes it back; a match would then be self-fulfilling. Deliberately
    # a manual claim: the script cannot see its own deployment history, and
    # inferring provenance wrongly is the exact failure this probe prevents.
    # BooleanOptionalAction so an inherited ASC_PROBE_CONSOLE_SOURCED=1 can be
    # switched off on the command line instead of silently attesting.
    ap.add_argument("--screenshots-are-console-sourced",
                    action=argparse.BooleanOptionalAction,
                    default=os.environ.get("ASC_PROBE_CONSOLE_SOURCED") == "1",
                    help="attest that every sampled screenshot set (live AND the "
                         "editable draft) came from the App Store Connect console, "
                         "not from --apply-screenshots; required for a CONFIRMED "
                         "provenance verdict")
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

    if args.apply_screenshots and args.fail_on_drift:
        # Silently ignoring a flag is the same class of failure as expanding
        # an abbreviation into a write: the operator asked for behaviour that
        # will not happen. --fail-on-drift only means something while reading.
        print("::error::--fail-on-drift is a --dry-run flag; it has no meaning with "
              "--apply-screenshots (which fixes drift rather than reporting it).")
        return 2

    if args.apply_screenshots and args.screenshots_are_console_sourced:
        # Refuse the self-contradiction outright. Attesting console provenance
        # in the same breath as an upload is how a stale attestation survives
        # into the next read and manufactures a CONFIRMED out of an echo.
        print("::error::--screenshots-are-console-sourced attests that the sampled sets "
              "did NOT come from this script; it cannot be combined with --apply-screenshots.")
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

    # Fail before any network call: a display type Apple doesn't know 400s on
    # the first real ASC request (set-creation on the write path), so catch a
    # DISPLAY_TYPE_MAP typo here — covering both modes at the CLI boundary.
    bad_types = unknown_display_types()
    if bad_types:
        print(
            f"::error::DISPLAY_TYPE_MAP declares screenshotDisplayType(s) Apple "
            f"does not accept: {', '.join(bad_types)}. Valid values: "
            f"{', '.join(sorted(VALID_DISPLAY_TYPES))}."
        )
        return 2

    creds = resolve_asc_credentials()
    if creds is None:
        msg = ("No App Store Connect credential set "
               "(APP_STORE_CONNECT_KEY_ID / _ISSUER_ID / _API_KEY[_PATH]) "
               "— skipping honestly, not a green diff.")
        # On the write path a human explicitly dispatched an upload, so a
        # plain log line reads as "done" against a green check. Annotate it.
        print(f"::warning::[skip] {msg}" if args.apply_screenshots else f"[skip] {msg}")
        return 0

    headers = _headers(*creds)

    if args.apply_screenshots:
        changed, skipped = apply_screenshots(headers, args.bundle_id, shots_dir)
        if skipped:
            # Annotated, not a bare log line: someone dispatched this to
            # upload, and a green check with no annotation reads as success.
            print(f"::warning::[skip] {skipped}")
            return 0
        if not changed:
            # Reachable only when at least one display type WAS compared —
            # apply_screenshots() returns a skip reason otherwise, so this
            # never claims the live listing matches a repo it did not read.
            print(f"[apply] {args.bundle_id}: live screenshots already match the repo "
                  "— nothing uploaded")
            return 0
        print(f"[apply] {args.bundle_id}: {len(changed)} screenshot(s) uploaded:")
        for item in changed:
            print(f"  UPLOADED {item}")
        return 0

    drift, skipped = dry_run(headers, args.bundle_id, meta_dir, shots_dir,
                             args.screenshots_are_console_sourced,
                             attested_via=_attestation_source(argv))
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
