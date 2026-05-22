#!/usr/bin/env python3
"""
Play Store AAB upload with automatic FGS declaration (#2120).

Replaces r0adkll/upload-google-play when edits.commit fails with
"You must let us know whether your app uses any Foreground Service permissions."

Flow:
  1. Create edit
  2. Upload AAB
  3. Update track
  4. Declare FGS (best-effort via Play Console internal API)
  5. Commit edit (with changesNotSentForReview fallback if FGS check fails)

Usage:
  python3 play-store-upload.py \
    --aab path/to/app.aab \
    --package io.github.sceneview.demo \
    --track internal \
    --version-code 123 \
    --version-name 4.15.1 \
    [--whatsnew-dir whatsnew/] \
    [--changes-not-for-review]
"""
import argparse
import json
import os
import sys
import time
import requests
from google.oauth2 import service_account
from google.auth.transport.requests import Request, AuthorizedSession

# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------

def make_session(service_account_json: str) -> tuple[AuthorizedSession, str]:
    """Return (authorized session, raw Bearer access token)."""
    creds = service_account.Credentials.from_service_account_info(
        json.loads(service_account_json),
        scopes=["https://www.googleapis.com/auth/androidpublisher"],
    )
    creds.refresh(Request())
    sess = AuthorizedSession(creds)
    return sess, creds.token

# ---------------------------------------------------------------------------
# FGS declaration via Play Console internal API
# ---------------------------------------------------------------------------

def declare_fgs(token: str, app_id: str) -> bool:
    """
    Declare FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION in Play Console.

    Uses the internal playconsoleapps-pa API with the service-account
    Bearer token. Returns True on success, False on any error.

    Proto payload:
      field 1 = repeated list of FGS type declarations
        field 1 = type ID (21 = mediaProjection in Play Console enum)
        field 2 = 1 (declared as in-use)
    """
    url = (
        f"https://playconsoleapps-pa.clients6.google.com"
        f"/v1/apps/{app_id}/foregroundServiceTypes:save"
    )
    payload = {"1": [{"1": 21, "2": 1}]}
    try:
        r = requests.post(
            url,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json+protobuf",
            },
            json=payload,
            timeout=30,
        )
        print(f"[fgs] declare: HTTP {r.status_code}")
        if r.text:
            print(f"[fgs] response: {r.text[:300]}")
        return r.status_code == 200
    except Exception as exc:
        print(f"[fgs] error: {exc}", file=sys.stderr)
        return False

# ---------------------------------------------------------------------------
# Android Publisher API helpers
# ---------------------------------------------------------------------------

def publisher_base(package: str) -> str:
    return (
        f"https://androidpublisher.googleapis.com"
        f"/androidpublisher/v3/applications/{package}"
    )

def create_edit(sess: AuthorizedSession, package: str) -> str:
    r = sess.post(f"{publisher_base(package)}/edits", json={})
    r.raise_for_status()
    edit_id = r.json()["id"]
    print(f"[edit] created edit_id={edit_id}")
    return edit_id

def upload_bundle(sess: AuthorizedSession, package: str, edit_id: str, aab_path: str) -> int:
    upload_url = (
        f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
        f"/applications/{package}/edits/{edit_id}/bundles"
        "?uploadType=media"
    )
    aab_size = os.path.getsize(aab_path)
    print(f"[upload] uploading {aab_path} ({aab_size // 1024 // 1024} MB)…")
    with open(aab_path, "rb") as f:
        r = sess.post(
            upload_url,
            data=f,
            headers={"Content-Type": "application/octet-stream"},
            timeout=(30, 600),  # 30s connect, 600s read for large AAB upload
        )
    r.raise_for_status()
    version_code = r.json()["versionCode"]
    print(f"[upload] OK — versionCode={version_code}")
    return int(version_code)

def update_track(
    sess: AuthorizedSession,
    package: str,
    edit_id: str,
    track: str,
    version_code: int,
    version_name: str,
    release_notes: list[dict] | None,
) -> None:
    body: dict = {
        "releases": [
            {
                "name": version_name,
                "versionCodes": [str(version_code)],
                "status": "completed",
                **({"releaseNotes": release_notes} if release_notes else {}),
            }
        ]
    }
    r = sess.put(
        f"{publisher_base(package)}/edits/{edit_id}/tracks/{track}",
        json=body,
    )
    r.raise_for_status()
    print(f"[track] {track} updated with versionCode {version_code}")

def commit_edit(
    sess: AuthorizedSession,
    package: str,
    edit_id: str,
    changes_not_for_review: bool = False,
) -> dict:
    params = {}
    if changes_not_for_review:
        params["changesNotSentForReview"] = "true"
    r = sess.post(
        f"{publisher_base(package)}/edits/{edit_id}:commit",
        params=params,
    )
    return r  # caller inspects status

def delete_edit(sess: AuthorizedSession, package: str, edit_id: str) -> None:
    try:
        sess.delete(f"{publisher_base(package)}/edits/{edit_id}")
    except Exception:
        pass

# ---------------------------------------------------------------------------
# Release notes helpers
# ---------------------------------------------------------------------------

def load_release_notes(whatsnew_dir: str | None) -> list[dict] | None:
    if not whatsnew_dir:
        return None
    notes = []
    try:
        for fname in os.listdir(whatsnew_dir):
            lang = fname.replace("whatsnew-", "").replace("_", "-")
            path = os.path.join(whatsnew_dir, fname)
            text = open(path).read().strip()
            if text:
                notes.append({"language": lang, "text": text[:500]})
    except Exception as exc:
        print(f"[notes] warning: {exc}", file=sys.stderr)
    return notes or None

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--aab", required=True)
    ap.add_argument("--package", required=True)
    ap.add_argument("--track", required=True)
    ap.add_argument("--version-code", type=int, required=True)
    ap.add_argument("--version-name", required=True)
    ap.add_argument("--whatsnew-dir", default=None)
    ap.add_argument("--app-id", default="4976357948291389972",
                    help="Numeric Play Console app ID for the FGS declaration step")
    ap.add_argument("--changes-not-for-review", action="store_true",
                    help="Commit with changesNotSentForReview=true if the normal commit fails")
    args = ap.parse_args()

    sa_json = os.environ.get("PLAY_STORE_SERVICE_ACCOUNT_JSON") or \
              os.environ.get("SERVICE_ACCOUNT_JSON")
    if not sa_json:
        print("ERROR: PLAY_STORE_SERVICE_ACCOUNT_JSON env var not set", file=sys.stderr)
        return 1

    sess, token = make_session(sa_json)
    release_notes = load_release_notes(args.whatsnew_dir)
    edit_id: str | None = None

    try:
        # 1. Create edit
        edit_id = create_edit(sess, args.package)

        # 2. Upload AAB
        version_code = upload_bundle(sess, args.package, edit_id, args.aab)

        # 3. Update track
        update_track(
            sess, args.package, edit_id, args.track,
            version_code, args.version_name, release_notes,
        )

        # 4. Declare FGS (best-effort — the commit may still fail if the
        #    service-account Bearer token is rejected by the internal API,
        #    but at least we tried)
        fgs_ok = declare_fgs(token, args.app_id)
        if not fgs_ok:
            print("[fgs] declaration failed or not accepted — will attempt commit anyway",
                  file=sys.stderr)

        # 5. Commit (first attempt — normal)
        r = commit_edit(sess, args.package, edit_id, changes_not_for_review=False)
        if r.status_code == 200:
            print(f"[commit] OK — versionCode {version_code} live on {args.track} track")
            return 0

        body = r.text
        print(f"[commit] HTTP {r.status_code}: {body[:400]}", file=sys.stderr)

        # 5b. If the commit failed with the FGS error AND --changes-not-for-review
        #     is set, retry with changesNotSentForReview=true. This parks the AAB
        #     in Play Console without triggering a review, so Thomas can manually
        #     declare FGS and send for review from the UI.
        if args.changes_not_for_review and "Foreground Service" in body:
            print("[commit] retrying with changesNotSentForReview=true …")
            r2 = commit_edit(sess, args.package, edit_id, changes_not_for_review=True)
            if r2.status_code == 200:
                print(
                    f"[commit] AAB committed with changesNotSentForReview=true.\n"
                    f"  → Go to Play Console > App content > Foreground service type\n"
                    f"    declarations, declare MEDIA_PROJECTION, then send for review."
                )
                return 0
            print(f"[commit] fallback also failed: HTTP {r2.status_code}: {r2.text[:300]}",
                  file=sys.stderr)

        r.raise_for_status()
        return 1

    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        if edit_id:
            delete_edit(sess, args.package, edit_id)
        return 1


if __name__ == "__main__":
    sys.exit(main())
