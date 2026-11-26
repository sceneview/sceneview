#!/usr/bin/env python3
"""Send one "What's new" push to the SceneView Demo through FCM HTTP v1, to a topic.

Usage:
    python3 tools/send-demo-push.py --title "New sample: Measure Object" \\
        --body "Point at a box and get its length, width and height in AR." \\
        --sample ar-measure [--campaign measure-2026-12] [--topic new_samples] [--send]

Without --send the script only prints the message it would send (a dry run, no key needed).

Cadence (samples/android-demo/.../telemetry/PushPostGate.kt):
  - `new_samples` gets at most 2 pushes a month, each one announcing a sample that shipped.
    The app enforces it too: a third broadcast within 30 days posts nothing on the phone.
  - No inactivity reminders, no "come back" pushes: a SDK demo has no reason to call people back.
  - Use `--topic qa` to try a message on the QA build first; it does not count toward the cap.

The message is data-only for Android, so the app builds the notification itself: the in-app
switch, the system permission, the `whats_new` channel and the cadence all apply, and a tap
opens `--sample`. iOS gets an `apns` alert, since a data-only message is silent there.

The service-account key never lives in the repository. It is read, in this order, from:
  1. the env var SCENEVIEW_FCM_SERVICE_ACCOUNT_JSON (the JSON itself), or
  2. the macOS Keychain, generic password service "sceneview-fcm-service-account"
     (`security add-generic-password -s sceneview-fcm-service-account -a fcm -w "$(cat key.json)"`).
The key is never printed. Only the Python standard library and `openssl` are used.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

KEYCHAIN_SERVICE = "sceneview-fcm-service-account"
ENV_KEY = "SCENEVIEW_FCM_SERVICE_ACCOUNT_JSON"
TOPICS = ("new_samples", "all", "qa")
SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
TOKEN_URL = "https://oauth2.googleapis.com/token"
# The plan's limits for a notification: title at most 40 characters, body at most 90.
MAX_TITLE = 40
MAX_BODY = 90

REPO = pathlib.Path(__file__).resolve().parent.parent
FRAGMENTS = REPO / "samples/android-demo/src/main/java/io/github/sceneview/demo/fragments"


def known_sample_ids() -> set[str]:
    ids: set[str] = set()
    for path in FRAGMENTS.glob("*.kt"):
        ids.update(re.findall(r'^\s*id\s*=\s*"([a-z0-9-]+)"', path.read_text(encoding="utf-8"), re.M))
    return ids


def build_message(topic: str, title: str, body: str, sample: str, campaign: str) -> dict:
    data = {"title": title, "body": body, "sample": sample, "campaign": campaign}
    return {
        "message": {
            "topic": topic,
            # No top-level `notification` block: Android receives a data message and
            # DemoMessagingService draws it, opt-outs and cadence included.
            "data": data,
            "android": {"priority": "normal"},
            "apns": {
                "payload": {"aps": {"alert": {"title": title, "body": body}}, "sample": sample, "campaign": campaign},
            },
        }
    }


def read_service_account() -> dict:
    raw = os.environ.get(ENV_KEY, "").strip()
    if not raw and shutil.which("security"):
        result = subprocess.run(
            ["security", "find-generic-password", "-s", KEYCHAIN_SERVICE, "-w"],
            capture_output=True,
            text=True,
        )
        if result.returncode == 0:
            raw = result.stdout.strip()
    if not raw:
        sys.exit(f"No service-account key: set {ENV_KEY} or store it in the Keychain as '{KEYCHAIN_SERVICE}'.")
    try:
        account = json.loads(raw)
    except json.JSONDecodeError:
        sys.exit("The service-account key is not valid JSON.")
    for field in ("client_email", "private_key", "project_id"):
        if field not in account:
            sys.exit(f"The service-account key has no '{field}'.")
    return account


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def access_token(account: dict) -> str:
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(
        json.dumps(
            {"iss": account["client_email"], "scope": SCOPE, "aud": TOKEN_URL, "iat": now, "exp": now + 600}
        ).encode()
    )
    signing_input = f"{header}.{claims}".encode()
    # The key goes to openssl through a private temp file, removed right after signing.
    with tempfile.TemporaryDirectory() as tmp:
        key_path = pathlib.Path(tmp) / "key.pem"
        key_path.touch(mode=0o600)
        key_path.write_text(account["private_key"])
        signed = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(key_path)],
            input=signing_input,
            capture_output=True,
        )
    if signed.returncode != 0:
        sys.exit("openssl could not sign the token request.")
    assertion = f"{header}.{claims}.{b64url(signed.stdout)}"
    form = urllib.parse.urlencode(
        {"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion}
    ).encode()
    with urllib.request.urlopen(urllib.request.Request(TOKEN_URL, data=form)) as response:
        return json.load(response)["access_token"]


def send(account: dict, message: dict) -> str:
    url = f"https://fcm.googleapis.com/v1/projects/{account['project_id']}/messages:send"
    request = urllib.request.Request(
        url,
        data=json.dumps(message).encode(),
        headers={"Authorization": f"Bearer {access_token(account)}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request) as response:
            return json.load(response).get("name", "")
    except urllib.error.HTTPError as error:
        sys.exit(f"FCM refused the message: HTTP {error.code} {error.read().decode(errors='replace')}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--title", required=True)
    parser.add_argument("--body", required=True)
    parser.add_argument("--sample", required=True, help="Demo id the notification opens, e.g. ar-measure")
    parser.add_argument("--campaign", help="Label logged in push_opened (default: the sample id)")
    parser.add_argument("--topic", default="new_samples", choices=TOPICS)
    parser.add_argument("--allow-unknown-sample", action="store_true", help="Send even if the id is not a demo")
    parser.add_argument("--send", action="store_true", help="Really send (default: print the message only)")
    args = parser.parse_args()

    if len(args.title) > MAX_TITLE or len(args.body) > MAX_BODY:
        sys.exit(f"Keep the title within {MAX_TITLE} characters and the body within {MAX_BODY}.")
    ids = known_sample_ids()
    if ids and args.sample not in ids and not args.allow_unknown_sample:
        sys.exit(f"'{args.sample}' is not a demo id of this checkout; the tap would land on Home.")

    message = build_message(args.topic, args.title, args.body, args.sample, args.campaign or args.sample)
    if not args.send:
        print(json.dumps(message, indent=2))
        print("\nDry run: nothing sent. Add --send to send it.", file=sys.stderr)
        return
    print(f"Sent: {send(read_service_account(), message)}")


if __name__ == "__main__":
    main()
