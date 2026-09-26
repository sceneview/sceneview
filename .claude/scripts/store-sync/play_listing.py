#!/usr/bin/env python3
"""play_listing.py — Play Store listing sync/diff as code (#2612 P2, Phase A).

Single code path for the Play listing sync, shared by CI and local runs:

  --apply     what `play-store.yml`'s `sync-listing` job has always done —
              push `samples/android-demo/distribution/play-store/<locale>/`
              (text + graphics) to the Play Console via the `edits` API.
              This is a faithful extraction of the job's former inline
              heredoc; behaviour, prints, and error handling are unchanged
              (delete-then-upload per imageType #1710, 403-tolerant #1386,
              caps + truncation, edit rollback on failure).
  --dry-run   (default) READ-ONLY drift check: open an edit, GET the live
              listing text + per-image SHA-256s, diff them against the
              repo files, then abandon the edit. Never writes. Exits 0;
              pass --fail-on-drift to exit 3 when drift is found (the
              check-doc-drift.sh `--fail` convention).

Two OPT-IN modifiers, off unless named, added with the en-GB -> en-US move
(#3658). The automatic tag path in play-store.yml passes neither, so a release
keeps syncing text and nothing else:

  --set-default-locale LOCALE   set the store's `defaultLanguage`.
  --prune-other-locales         DELETE every listing locale except that one.
                                Requires --set-default-locale: the survivor is
                                named, never inferred. Under --dry-run both are
                                reported as drift lines — the delete list is
                                readable before it is destructive.

Credentials — reuses the deploy service account, NO new scope:
  SERVICE_ACCOUNT_JSON                  JSON content (what the workflow passes)
  PLAY_STORE_SERVICE_ACCOUNT_JSON       alias, JSON content
  PLAY_STORE_SERVICE_ACCOUNT_JSON_PATH  local convenience: path to the file
When none are set, both modes SKIP honestly with exit 0 (advisory-first,
same doctrine as play-vitals.sh #1691 / store-preflight.sh #2612 P1).

Third-party imports (google-auth, requests) are lazy — the pure helpers are
importable and unit-testable without any network dependency installed
(.claude/scripts/store-sync/test/, run by test-store-sync.sh).
"""

import argparse
import hashlib
import json
import os
import pathlib
import sys

DEFAULT_PACKAGE = "io.github.sceneview.demo"
DEFAULT_LISTING_DIR = "samples/android-demo/distribution/play-store"

# Per-locale field caps enforced by Google Play. Truncate with `…` if a file
# exceeds the cap (a `printf` in a localized terminal may have inserted hard
# line breaks that pad the byte count).
CAPS = {"title": 30, "shortDescription": 80, "fullDescription": 4000}
FILE_MAP = {
    "title": "title.txt",
    "shortDescription": "short_description.txt",
    "fullDescription": "full_description.txt",
}

# Google Play's `AppImageType` enum — the ONLY values `edits.images` accepts on
# its `{imageType}` path segment. Anything else is a 400, and because the whole
# listing is pushed inside ONE atomic edit, that 400 rolls back EVERY change in
# the edit — text, icon and phone screenshots included. Pinning the enum here
# lets `unknown_image_types()` catch a typo offline (self-test) instead of at
# the next release, against the live store.
# Transcribed from the v3 API discovery document (the machine-readable source
# Google generates its own clients from), NOT from prose docs — an allowlist
# whose whole job is to be exhaustive has to mirror the enum exactly:
#   googleapis/google-api-go-client → androidpublisher/v3/androidpublisher-api.json
# `appImageTypeUnspecified` is deliberately excluded: it is the proto-zero
# sentinel, never a usable slot. `promoGraphic` is deliberately ABSENT — it is
# a v2-era value that v3 dropped, and leaving it in would let a future
# ("promoGraphic", "promo-*.png") row sail past the guard and reproduce #2794
# verbatim.
VALID_IMAGE_TYPES = frozenset({
    "phoneScreenshots", "sevenInchScreenshots", "tenInchScreenshots",
    "tvScreenshots", "wearScreenshots",
    "icon", "featureGraphic", "tvBanner",
})

# Listing graphics live in `<locale>/graphics/`. Each Play `imageType` maps to
# one well-known filename (featureGraphic, icon) or a glob (phone / tablet
# screenshots). The Play `edits.images` API is delete-then-upload PER
# imageType: list+delete the existing images of a type, then upload the local
# files. Screenshot ordering follows the sorted filename, so name them
# `phone-screenshot-1.png` etc.
#
# The repo's `tablet7-` / `tablet10-` filenames describe the FORM FACTOR; the
# Play enum names the same two slots `sevenInchScreenshots` / `tenInchScreenshots`
# (#2794 — they were `tabletScreenshots` / `tabletScreenshots10`, which Play has
# never accepted, silently voiding every listing sync since #1710).
GRAPHICS = [
    ("featureGraphic", ["feature-graphic.png"]),
    ("icon", ["icon-512.png"]),
    ("phoneScreenshots", "phone-screenshot-*.png"),
    ("sevenInchScreenshots", "tablet7-screenshot-*.png"),
    ("tenInchScreenshots", "tablet10-screenshot-*.png"),
]


# ── Pure helpers (no third-party imports — unit-tested offline) ──────────────

def read_listing_text(ldir):
    """Read the text fields for one locale dir, applying Play's caps.

    Returns (body, notes): `body` maps Play field → capped text for every
    file that exists; `notes` lists human-readable truncation warnings.
    """
    body, notes = {}, []
    for field, fname in FILE_MAP.items():
        f = ldir / fname
        if not f.exists():
            continue
        text = f.read_text().strip()
        cap = CAPS[field]
        if len(text) > cap:
            text = text[: cap - 1].rstrip() + "…"
            notes.append(f"{ldir.name}/{fname} exceeded {cap} chars — truncated")
        body[field] = text
    return body, notes


def graphics_for(ldir, pattern):
    """Resolve a graphics pattern to a sorted list of existing files."""
    gdir = ldir / "graphics"
    if not gdir.is_dir():
        return []
    if isinstance(pattern, list):
        return [gdir / n for n in pattern if (gdir / n).exists()]
    return sorted(gdir.glob(pattern))


def unknown_image_types(graphics=None):
    """Return the configured imageTypes Play would reject, in declared order.

    Offline guard for the single most expensive failure mode of this script: an
    imageType typo is a 400 that aborts the *whole* atomic edit, so a broken
    tablet slot silently voids the icon and the description too — and the
    sync-listing job is `continue-on-error`, so the red never surfaces. Checked
    by the self-test and again before any network call in `main`.
    """
    return [t for t, _ in (GRAPHICS if graphics is None else graphics)
            if t not in VALID_IMAGE_TYPES]


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def diff_text(locale, local_body, remote_body):
    """Compare capped local text fields to the live listing. Returns drift lines.

    Only fields present locally are compared — a field with no repo file is
    not "drift", it is simply unmanaged (mirrors --apply, which never touches
    a field whose file is absent).
    """
    drift = []
    for field, local in local_body.items():
        remote = (remote_body or {}).get(field)
        if remote is None:
            drift.append(f"{locale}: {field} missing on the live listing (local {len(local)}c)")
        elif remote != local:
            drift.append(f"{locale}: {field} differs (local {len(local)}c ≠ live {len(remote)}c)")
    return drift


def diff_images(locale, image_type, local_files, remote_sha256s):
    """Compare local graphics (ordered by sorted filename) to the live images.

    `remote_sha256s` is the ordered list the `edits.images.list` call returns.
    Ordering matters — Play displays screenshots in list order, and --apply
    uploads in sorted-filename order, so an order mismatch is real drift.
    """
    local = [(f.name, sha256_of(f)) for f in local_files]
    local_shas = [s for _, s in local]
    if local_shas == list(remote_sha256s):
        return []
    if sorted(local_shas) == sorted(remote_sha256s):
        return [f"{locale}: {image_type} same images but different ORDER (live vs sorted filenames)"]
    missing = [n for n, s in local if s not in remote_sha256s]
    extra = len([s for s in remote_sha256s if s not in local_shas])
    parts = []
    if missing:
        parts.append("not live: " + ", ".join(missing))
    if extra:
        parts.append(f"{extra} live image(s) not in the repo")
    return [f"{locale}: {image_type} differs ({'; '.join(parts) or 'content mismatch'})"]


def resolve_credentials(env=os.environ):
    """Return the service-account JSON dict, or None when no secret is set."""
    raw = env.get("SERVICE_ACCOUNT_JSON") or env.get("PLAY_STORE_SERVICE_ACCOUNT_JSON")
    if not raw:
        path = env.get("PLAY_STORE_SERVICE_ACCOUNT_JSON_PATH")
        if path and pathlib.Path(path).exists():
            raw = pathlib.Path(path).read_text()
    if not raw:
        return None
    return json.loads(raw)


def locales_under(root):
    return [d.name for d in sorted(root.iterdir()) if d.is_dir()]


def locales_to_prune(remote_locales, keep):
    """Locales live on the store that `--prune-other-locales` would delete.

    Pure, so the destructive decision is unit-testable without the API. `keep`
    is never in the result — deleting the locale we just wrote (and made the
    default) would empty the listing entirely.
    """
    return sorted({l for l in remote_locales if l and l != keep})


# ── Network layer (lazy third-party imports) ─────────────────────────────────

def _session(creds_info):
    from google.oauth2 import service_account
    from google.auth.transport.requests import AuthorizedSession

    creds = service_account.Credentials.from_service_account_info(
        creds_info,
        scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    return AuthorizedSession(creds)


def remote_locales(sess, base, edit_id):
    """Every language that has a listing on the store, inside `edit_id`.

    `edits.listings.list` returns `{"listings": [{"language": "fr-FR", ...}]}`;
    a store with no listing at all answers 404, which is an empty set, not an
    error.
    """
    r = sess.get(f"{base}/edits/{edit_id}/listings")
    if r.status_code == 404:
        return []
    r.raise_for_status()
    return [l.get("language") for l in (r.json().get("listings") or [])]


def apply_sync(sess, pkg, root, set_default_locale=None, prune_other_locales=False):
    """Push text + graphics for every locale. Faithful extraction of the former
    play-store.yml heredoc — same flow, same prints, same error handling.

    `set_default_locale` and `prune_other_locales` are OPT-IN and both default
    to off, so the automatic tag path keeps doing exactly what it always did:
    write the repo's locales, touch nothing else. They exist for the manual
    `listing_only` dispatch (#3658).

    ORDER IS LOAD-BEARING inside the single edit, and it is the reason these
    are not three independent scripts:

      1. write the repo locales — `en-US` must EXIST before it can be named
         the default; Play rejects a `defaultLanguage` that has no listing.
      2. switch `defaultLanguage` — must happen before the deletes, because
         Play refuses to delete the listing of the current default language.
      3. delete the others — last, when nothing points at them any more.

    All three ride the same edit, so the commit is atomic: if the prune 400s,
    the default-language switch rolls back with it and the store is left
    exactly as it was. The alternative (three edits) has a window where the
    store has no default listing at all.
    """
    # Re-assert at the WRITE boundary, not just the CLI one: main() checks this
    # too, but a caller importing apply_sync() directly would otherwise open an
    # edit and 400 halfway through it, rolling back everything staged (#2794).
    # Deliberately BEFORE `import requests` so the invariant holds even where
    # the lazy third-party deps are absent (and so it stays unit-testable).
    bad_types = unknown_image_types()
    if bad_types:
        raise ValueError(
            f"GRAPHICS declares imageType(s) Play does not accept: {bad_types}. "
            f"Valid values: {sorted(VALID_IMAGE_TYPES)}"
        )

    import requests

    base = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{pkg}"
    media_upload = (
        "https://androidpublisher.googleapis.com/upload/androidpublisher"
        f"/v3/applications/{pkg}"
    )

    def sync_graphics(edit_id, locale, ldir):
        """Delete-then-upload every listing-graphic type for one locale."""
        for image_type, pattern in GRAPHICS:
            files = graphics_for(ldir, pattern)
            if not files:
                continue
            # Delete every existing image of this type so removed/renamed
            # local files don't linger on the store.
            d = sess.delete(f"{base}/edits/{edit_id}/listings/{locale}/{image_type}")
            d.raise_for_status()
            for f in files:
                u = sess.post(
                    f"{media_upload}/edits/{edit_id}/listings/{locale}/{image_type}",
                    headers={"Content-Type": "image/png"},
                    data=f.read_bytes(),
                )
                u.raise_for_status()
            print(
                f"[listing] {locale}: {image_type} "
                f"({len(files)} image{'s' if len(files) != 1 else ''})"
            )

    locales = locales_under(root)
    if not locales:
        print(f"::warning::No locale subdirectories under {root} — nothing to sync")
        return 0

    # 1. Open a new Edit (independent of any other edits already committed by
    #    the publish or promote steps).
    r = sess.post(f"{base}/edits")
    r.raise_for_status()
    edit_id = r.json()["id"]
    print(f"[listing] edit_id={edit_id}")

    try:
        for locale in locales:
            ldir = root / locale
            body, notes = read_listing_text(ldir)
            for field, fname in FILE_MAP.items():
                if not (ldir / fname).exists():
                    print(f"[listing] {locale}: skip {field} ({fname} missing)")
            for note in notes:
                print(f"::warning::{note}")

            if body:
                r = sess.put(f"{base}/edits/{edit_id}/listings/{locale}", json=body)
                r.raise_for_status()
                fields = ", ".join(f"{k}={len(v)}c" for k, v in body.items())
                print(f"[listing] {locale}: {fields}")
            else:
                print(f"[listing] {locale}: no text fields")

            # Sync the listing graphics (feature graphic, icon, screenshots)
            # for this locale via the Play `edits.images` API — see #1710.
            # Runs even when the locale has no text so a graphics-only locale
            # still gets its images uploaded.
            sync_graphics(edit_id, locale, ldir)

        # 2. (opt-in) Point the store's default language at our one locale.
        #
        #    PATCH, not PUT: `edits.details.update` REPLACES the whole Details
        #    resource, and Details also carries contactEmail / contactPhone /
        #    contactWebsite. A PUT carrying only `defaultLanguage` would blank
        #    the three contact fields on the live listing — a silent data loss
        #    that no diff in this repo would ever surface, since none of those
        #    fields are stored here. `edits.details.patch` sets the one field.
        if set_default_locale:
            r = sess.patch(
                f"{base}/edits/{edit_id}/details",
                json={"defaultLanguage": set_default_locale},
            )
            r.raise_for_status()
            print(f"[listing] defaultLanguage -> {set_default_locale}")

        # 3. (opt-in) Delete every other locale's listing. Ordered after the
        #    default switch on purpose — see the docstring.
        if prune_other_locales:
            keep = set_default_locale or (locales[0] if locales else None)
            doomed = locales_to_prune(remote_locales(sess, base, edit_id), keep)
            if not doomed:
                print(f"[listing] prune: nothing to delete (only {keep} is live)")
            for locale in doomed:
                d = sess.delete(f"{base}/edits/{edit_id}/listings/{locale}")
                d.raise_for_status()
                print(f"[listing] prune: deleted {locale}")

        # 4. Commit the edit.
        r = sess.post(f"{base}/edits/{edit_id}:commit")
        r.raise_for_status()
        print("[listing] commit OK")
        return 0
    except requests.HTTPError as e:
        # Best-effort rollback — abandon the edit so we don't leave a dangling
        # Edit ID in Play Console.
        try:
            sess.delete(f"{base}/edits/{edit_id}")
        except Exception:
            pass
        status = e.response.status_code if e.response is not None else None
        if status == 403:
            # The deploy service account lacks the Play Console 'Edit store
            # listing' permission (#1386). The app deploy already succeeded —
            # a listing-sync 403 is best-effort and must not fail the release.
            # Warn and exit cleanly; the job is also `continue-on-error: true`
            # as a safety net.
            print(
                "::warning::Play Store listing sync got 403 Forbidden — "
                "the deploy service account lacks the 'Edit store "
                "listing' permission. Listing copy NOT updated; the app "
                "deploy is unaffected. Grant the permission in the Play "
                "Console to enable listing sync (see #1386)."
            )
            return 0
        # Any other HTTP error abandons the edit, so NOTHING was applied. The
        # job is `continue-on-error: true` and lives in a matrix, so a bare red
        # job goes unnoticed release after release — that is exactly how the
        # #2794 tablet-imageType 400 rotted undetected. Emit an annotation that
        # actually surfaces in the run summary before re-raising.
        url = e.response.url if e.response is not None else "unknown URL"
        print(
            f"::warning::Play Store listing sync FAILED (HTTP {status} on {url}). "
            "The edit was abandoned — listing text, icon and screenshots are ALL "
            "unchanged on the store. The app rollout is unaffected."
        )
        raise
    except Exception:
        try:
            sess.delete(f"{base}/edits/{edit_id}")
        except Exception:
            pass
        raise


def dry_run(sess, pkg, root, set_default_locale=None, prune_other_locales=False):
    """READ-ONLY drift check: live listing vs repo files. Returns drift lines.

    Opens a throwaway edit (the `edits.listings` / `edits.images` GETs need an
    edit id; a fresh edit reflects the live state), never commits it, and
    abandons it in a finally block.

    With `--prune-other-locales`, this is also the ONLY way to see what the
    destructive run would remove before running it: every locale it would
    delete is reported as a drift line naming it. A destructive flag whose
    blast radius can only be discovered by firing it is not reviewable.
    """
    base = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{pkg}"

    locales = locales_under(root)
    if not locales:
        print(f"::warning::No locale subdirectories under {root} — nothing to diff")
        return []

    r = sess.post(f"{base}/edits")
    r.raise_for_status()
    edit_id = r.json()["id"]
    print(f"[dry-run] probe edit_id={edit_id} (will be abandoned, nothing is written)")

    drift = []
    try:
        for locale in locales:
            ldir = root / locale
            body, _notes = read_listing_text(ldir)

            lr = sess.get(f"{base}/edits/{edit_id}/listings/{locale}")
            if lr.status_code == 200:
                remote_body = lr.json()
            elif lr.status_code == 404:
                remote_body = {}  # locale never published — every field drifts
            else:
                lr.raise_for_status()
            drift += diff_text(locale, body, remote_body)

            for image_type, pattern in GRAPHICS:
                files = graphics_for(ldir, pattern)
                if not files:
                    continue
                ir = sess.get(f"{base}/edits/{edit_id}/listings/{locale}/{image_type}")
                if ir.status_code == 200:
                    images = ir.json().get("images") or []
                elif ir.status_code == 404:
                    images = []
                else:
                    ir.raise_for_status()
                remote_shas = [i.get("sha256") for i in images]
                drift += diff_images(locale, image_type, files, remote_shas)

        if prune_other_locales:
            keep = set_default_locale or (locales[0] if locales else None)
            doomed = locales_to_prune(remote_locales(sess, base, edit_id), keep)
            for locale in doomed:
                drift.append(
                    f"{locale}: listing is live on the store and WOULD BE "
                    f"DELETED by --prune-other-locales (keeping {keep})"
                )
            if not doomed:
                print(f"[dry-run] prune: nothing to delete (only {keep} is live)")

        if set_default_locale:
            dr = sess.get(f"{base}/edits/{edit_id}/details")
            if dr.status_code == 200:
                current = dr.json().get("defaultLanguage")
                if current != set_default_locale:
                    drift.append(
                        f"defaultLanguage: store={current!r} "
                        f"WOULD BECOME {set_default_locale!r}"
                    )
    finally:
        try:
            sess.delete(f"{base}/edits/{edit_id}")
        except Exception:
            pass
    return drift


def main(argv=None):
    # allow_abbrev=False: argparse accepts any unambiguous prefix by default,
    # so `--appl` — or any other near-miss — would resolve to --apply and push
    # to the Play Console. A write flag on a store script must require its
    # exact name (same reasoning as asc_listing.py).
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0], allow_abbrev=False)
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true",
                      help="push the repo listing to the Play Console (CI mode)")
    mode.add_argument("--dry-run", action="store_true",
                      help="read-only live-vs-repo diff (default)")
    ap.add_argument("--fail-on-drift", action="store_true",
                    help="dry-run only: exit 3 when drift is found")
    # Both opt-in, both off by default: the automatic tag path must keep
    # syncing text and nothing else. These are for the manual `listing_only`
    # dispatch (#3658) — see the apply_sync docstring for why the order of
    # the three operations inside the edit is not free.
    ap.add_argument("--set-default-locale", metavar="LOCALE", default=None,
                    help="set the store's defaultLanguage to LOCALE "
                         "(opt-in; dry-run reports it instead)")
    ap.add_argument("--prune-other-locales", action="store_true",
                    help="DELETE every listing locale except the kept one "
                         "(opt-in and destructive; dry-run names them first)")
    ap.add_argument("--package", default=os.environ.get("PACKAGE_NAME", DEFAULT_PACKAGE))
    ap.add_argument("--listing-dir",
                    default=os.environ.get("LISTING_DIR", DEFAULT_LISTING_DIR))
    args = ap.parse_args(argv)

    # `--prune-other-locales` deletes whatever it is not told to keep. Without
    # `--set-default-locale` the kept locale would be inferred from whichever
    # directory sorts first, which is a silent, filesystem-ordering-dependent
    # answer to "which listing survives". Make the pairing explicit instead.
    if args.prune_other_locales and not args.set_default_locale:
        print(
            "::error::--prune-other-locales requires --set-default-locale "
            "LOCALE — the locale to keep must be named, not inferred."
        )
        return 2

    root = pathlib.Path(args.listing_dir)
    if not root.is_dir():
        print(f"::error::Listing dir not found: {root}")
        return 2

    # Fail before opening an edit: an imageType Play doesn't know would 400
    # mid-flight and roll back everything already staged in that edit.
    bad_types = unknown_image_types()
    if bad_types:
        print(
            f"::error::GRAPHICS declares imageType(s) Play does not accept: "
            f"{', '.join(bad_types)}. Valid values: "
            f"{', '.join(sorted(VALID_IMAGE_TYPES))}."
        )
        return 2

    # Naming a default locale the repo does not carry would point the store at
    # a listing this sync never writes — the #1710 failure mode (an `en-US`
    # directory that updated nothing), in reverse and on the live store.
    # Checked HERE, before the credential probe: it is a pure repo-side fact,
    # and a bad flag must be rejected on a developer's laptop (where there are
    # no credentials and main() would otherwise return 0) and not only in CI.
    if args.set_default_locale and args.set_default_locale not in locales_under(root):
        print(
            f"::error::--set-default-locale {args.set_default_locale} has no "
            f"directory under {root} (found: "
            f"{', '.join(locales_under(root)) or 'none'}). The default "
            "language must be a locale this repo actually maintains."
        )
        return 2

    creds_info = resolve_credentials()
    if creds_info is None:
        print(
            "[skip] No Play service-account credential set "
            "(SERVICE_ACCOUNT_JSON / PLAY_STORE_SERVICE_ACCOUNT_JSON[_PATH]) "
            "— skipping honestly, not a green sync."
        )
        return 0

    sess = _session(creds_info)
    if args.apply:
        return apply_sync(sess, args.package, root,
                          set_default_locale=args.set_default_locale,
                          prune_other_locales=args.prune_other_locales)

    drift = dry_run(sess, args.package, root,
                    set_default_locale=args.set_default_locale,
                    prune_other_locales=args.prune_other_locales)
    if not drift:
        print(f"[dry-run] {args.package}: live listing matches the repo — no drift")
        return 0
    print(f"[dry-run] {args.package}: {len(drift)} drift item(s):")
    for line in drift:
        print(f"  DRIFT {line}")
    return 3 if args.fail_on_drift else 0


if __name__ == "__main__":
    sys.exit(main())
