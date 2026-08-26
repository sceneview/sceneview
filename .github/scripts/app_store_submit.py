"""Submit the uploaded iOS build for App Store review (#2893).

Called by `.github/workflows/app-store.yml` → `Submit build for App Store
review`, under that job's pinned venv:

    $RUNNER_TEMP/asc-venv/bin/python3 "$GITHUB_WORKSPACE/.github/scripts/app_store_submit.py"

This is the single code path between a green tag build and an App Store
submission. It lived as a 932-line heredoc inside the workflow YAML until
#3146; the extracted code is byte-identical to what the heredoc carried.
Two things it buys: `.claude/scripts/test-app-store-submit.py` now loads this
file instead of regex-carving it back out of the YAML, and the program is
editable — and greppable — as Python rather than as indented YAML scalar.

Everything comes from the environment, which is what made the extraction a
move rather than a rewrite: there is no `${{ }}` interpolation anywhere below,
so the program never depended on being expanded by Actions.

Identical code is not identical behaviour on one branch, and it is worth
knowing which: the `GITHUB_WORKSPACE` fallback in the versionString resolution
reads `__file__`, which is undefined in a program fed to `python3` on stdin.
Python evaluates a `.get()` default eagerly, so as a heredoc that line raised
NameError on every `workflow_dispatch` — GITHUB_WORKSPACE being set made no
difference — and the documented gradle.properties fallback was dead code. As a
file it works. That branch now has a test; see the suite's dispatch-path case.

    ASC_KEY_ID, ASC_ISSUER_ID   App Store Connect API credentials
    ASC_VERSION_STRING          versionString for the App Store record
    ASC_EXPECTED_BUILD          CFBundleVersion THIS run uploaded (#2893 W1)
    GITHUB_WORKSPACE            repo root — the job runs with
                                `working-directory: samples/ios-demo`

Exit codes are the step's verdict: a non-zero exit is a red step and a release
that did NOT reach App Review. Do not add a bare `except` that swallows one.
"""
import os, sys, json, time, pathlib, jwt, requests

KEY_ID = os.environ["ASC_KEY_ID"]
ISSUER_ID = os.environ["ASC_ISSUER_ID"]
BUNDLE_ID = "io.github.sceneview.demo"

key_path = os.path.expanduser(f"~/.private_keys/AuthKey_{KEY_ID}.p8")
with open(key_path) as f:
    private_key = f.read()

# Apple caps the ASC token lifetime at 20 minutes. The build poll below
# can now legitimately consume most of that window (it waits for OUR
# build, #2893 W1), so the token is minted through a helper and
# re-minted after the poll — otherwise a long-but-successful wait
# would hand an already-expiring token to the submission calls that
# matter most.
def asc_headers():
    token = jwt.encode(
        {"iss": ISSUER_ID, "iat": int(time.time()), "exp": int(time.time()) + 1200, "aud": "appstoreconnect-v1"},
        private_key,
        algorithm="ES256",
        headers={"kid": KEY_ID}
    )
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

headers = asc_headers()
BASE = "https://api.appstoreconnect.apple.com/v1"

# 1. Find the app
r = requests.get(f"{BASE}/apps?filter[bundleId]={BUNDLE_ID}", headers=headers)
r.raise_for_status()
app_id = r.json()["data"][0]["id"]
print(f"App ID: {app_id}")

# 2. Get the latest valid build. Apple's processing routinely exceeds
# the 120s pre-sleep, and the old single-shot `exit(0)` here was a
# silent "never reached App Review" path of exactly the class #2731
# fixed (review-fanout WARNING on PR #2738): a tag release would go
# green having skipped submission entirely. Poll up to 10 more
# minutes (well inside the 20-min JWT), then FAIL loudly — a tag
# deploy whose build never turns VALID must be red, not green.
#
# The build MUST be constrained to the iOS platform. The macOS
# deploy job runs in PARALLEL against the SAME app record (shared
# bundleId → shared app_id), so an unqualified `builds` query can
# return the macOS build; attaching it to the iOS version below 409s
# with "The specified build has a different platform than the
# version". This is the build-side twin of the #2731 version hijack —
# #2731 added filter[platform]=IOS to the *version* lookup but left
# this *build* lookup unfiltered, so iOS submission kept failing on
# 4.24.0 and 4.25.0. We resolve each build's platform via the
# included preReleaseVersion and select client-side (so the fix does
# not depend on filter[preReleaseVersion.platform] being honoured);
# if the include is unavailable for this key we fall back to the
# newest VALID build rather than regress into a 10-minute red poll.
#
# W1 (#2893): "newest VALID iOS build" is not the same thing as "the
# build this run just uploaded". While Apple processes our upload,
# the PREVIOUS release's build is already VALID and sorts first — so
# the loop could exit on attempt 1 and submit a stale binary under
# the new version string, silently. The archive step exports the
# CFBundleVersion it built as ASC_EXPECTED_BUILD, and we pin the
# selection to it: no match yet means our build is still processing,
# which is a reason to keep polling, not to take the older one.
# Without the env var the previous newest-VALID behaviour stands,
# loudly flagged. In this workflow the archive step is unconditional,
# so that branch should never fire — it exists so a future job that
# submits without archiving (or an archive step that stops exporting
# the number) degrades to the old behaviour instead of hard-failing.
expected_build = (os.environ.get("ASC_EXPECTED_BUILD") or "").strip()
if expected_build:
    print(f"Expecting build number {expected_build} (uploaded by this run)")
else:
    print("::warning::ASC_EXPECTED_BUILD is not set — falling back to newest-VALID selection; "
          "this run may attach a build it did not upload (#2893 W1).")

def _same_build(a, b):
    # Apple returns CFBundleVersion as a string. Compare textually,
    # then numerically so a "0300"/"300" formatting difference on
    # either side is not read as a different build.
    a, b = str(a).strip(), str(b).strip()
    if a == b:
        return True
    try:
        return int(a) == int(b)
    except ValueError:
        return False

# 15 attempts, not 10 (#2893 review). Before W1 this loop almost
# always exited on attempt 1 — on the PREVIOUS release's build, which
# was the bug — so the window was never really exercised. Waiting for
# a freshly uploaded build is precisely the slow case, and Apple's
# processing routinely runs past 12 minutes. The token is re-minted
# after the loop, so a long wait no longer eats the submission's JWT.
POLL_ATTEMPTS = 15
build_id = None
build_version = None
for attempt in range(1, POLL_ATTEMPTS + 1):
    # Re-mint per iteration: no `requests` call here sets a timeout,
    # so a cumulative stall could outlive the 20-min token and hand
    # W2's classifier a 401 to misreport as bad credentials. A local
    # ES256 sign costs nothing.
    headers = asc_headers()
    r = requests.get(
        f"{BASE}/builds?filter[app]={app_id}&filter[processingState]=VALID"
        f"&sort=-uploadedDate&limit=20&include=preReleaseVersion",
        headers=headers,
    )
    # W2 (#2893): the status code used to be ignored entirely — an
    # error body has no "data" key, so a 401 read exactly like "Apple
    # is still processing" and burned the full ~10-minute window
    # before failing with a message blaming Apple's processing. The
    # three classes need different answers:
    #   401/403 — the JWT is bad, expired, or the key lost its role.
    #             Waiting cannot fix it, and the 20-min token means a
    #             10-min poll can even expire a token that was fine.
    #             Fail now, naming auth.
    #   429/5xx — genuinely transient. Retry within the same window.
    #   other 4xx — a malformed request (bad filter, dropped API
    #             version). Retrying is pointless; fail now.
    if r.status_code in (401, 403):
        print(f"::error::builds GET rejected with {r.status_code} — App Store Connect "
              f"authentication/permission failure, not a processing delay. Check the "
              f"ASC key id/issuer/.p8 secrets and the key's role. {r.text[:300]}")
        raise SystemExit(1)
    if r.status_code != 200:
        if r.status_code == 429 or r.status_code >= 500:
            print(f"::warning::builds GET transient {r.status_code} "
                  f"(attempt {attempt}/{POLL_ATTEMPTS}) — retrying in 60s: {r.text[:200]}")
            time.sleep(60)
            continue
        print(f"::error::builds GET failed with {r.status_code} — request rejected, "
              f"retrying cannot help. {r.text[:300]}")
        raise SystemExit(1)
    try:
        payload = r.json()
    except ValueError:
        # A 200 that is not JSON is an edge/proxy hiccup, not a
        # verdict on the build. Same treatment as a 5xx.
        print(f"::warning::builds GET returned a non-JSON 200 (attempt {attempt}/{POLL_ATTEMPTS}) — "
              f"retrying in 60s: {r.text[:200]}")
        time.sleep(60)
        continue
    included = {(i["type"], i["id"]): i for i in payload.get("included", [])}
    data = payload.get("data", [])
    newest_valid = data[0] if data else None
    platform_seen = False
    ios_builds = []
    for b in data:
        prv = b.get("relationships", {}).get("preReleaseVersion", {}).get("data")
        inc = included.get((prv["type"], prv["id"])) if prv else None
        platform = (inc or {}).get("attributes", {}).get("platform")
        if platform is not None:
            platform_seen = True
        if platform == "IOS":
            ios_builds.append(b)
    chosen = None
    if expected_build:
        chosen = next(
            (b for b in ios_builds
             if _same_build(b.get("attributes", {}).get("version"), expected_build)),
            None,
        )
    elif ios_builds:
        chosen = ios_builds[0]
    if chosen is not None:
        build_id = chosen["id"]
        build_version = chosen["attributes"]["version"]
        break
    # Apple returned VALID builds but NONE exposed a platform (the
    # include failed for this service-account key) — don't spin for
    # 10 min: fall back to the newest VALID build (pre-fix behaviour),
    # loudly, so the platform filter never makes things worse.
    if newest_valid is not None and not platform_seen:
        if expected_build:
            # Degraded path: no platform is resolvable, so match on
            # the build number alone and keep polling until it shows
            # up. Be honest about what this does NOT prove —
            # `deploy-macos` receives the SAME build number (the
            # `check` job's timestamp output, #3081) against the
            # SAME app record, so a number match here
            # cannot distinguish the two platforms' builds, exactly
            # as the pre-#2963 newest-VALID fallback could not. It is
            # no worse than what it replaces, and it is still tighter
            # than "newest", but it is not a platform guarantee.
            m = next(
                (b for b in data
                 if _same_build(b.get("attributes", {}).get("version"), expected_build)),
                None,
            )
            if m is not None:
                print("::warning::Could not resolve build platform via include=preReleaseVersion — "
                      f"selecting build {expected_build} by build-number match instead. NOTE: the macOS "
                      "job builds the same number against the same app record, so this match does not "
                      "prove the platform; a mismatched build 409s on the attach below.")
                build_id = m["id"]
                build_version = m["attributes"]["version"]
                break
        else:
            print("::warning::Could not resolve build platform via include=preReleaseVersion — "
                  "falling back to newest VALID build (iOS platform filter inactive).")
            build_id = newest_valid["id"]
            build_version = newest_valid["attributes"]["version"]
            break
    waiting_for = f"build {expected_build}" if expected_build else "a VALID iOS build"
    print(f"No {waiting_for} yet (attempt {attempt}/{POLL_ATTEMPTS}) — Apple still processing, retrying in 60s...")
    time.sleep(60)
if not build_id:
    detail = (
        f"build {expected_build} (uploaded by this run) never turned VALID"
        if expected_build else "no VALID iOS build appeared"
    )
    print(f"::error::After ~{2 + POLL_ATTEMPTS} minutes, {detail} — Apple processing overran or the "
          "upload failed. The release was NOT submitted for review. RECOVERY: dispatch a FRESH run — "
          "re-running only the failed job reuses the `check` job's build-number output, so it "
          "re-archives the same CFBundleVersion and Apple rejects the duplicate upload before the "
          "submit step is reached (#3081).")
    raise SystemExit(1)
print(f"Selected iOS build: {build_version} (ID: {build_id})")

# Re-mint the token: the poll above may have burned most of its
# 20-minute life, and everything that follows (version record, build
# attach, localization, review submission) is the part that must not
# fail on an expired JWT.
headers = asc_headers()

# Resolve the App Store version string. Tag pushes set
# ASC_VERSION_STRING via env (e.g. "v4.2.0"). Drop the leading "v"
# via `removeprefix` (Py 3.9+, exact single-occurrence strip — we
# don't want `lstrip("v")` which would eat any number of leading
# v's). On manual workflow_dispatch (no tag, ASC_VERSION_STRING
# empty), fall back to the marketing version declared in the
# repo's root `gradle.properties` (`VERSION_NAME=X.Y.Z`) — that
# is also what `MARKETING_VERSION` is built from. The earlier
# fallback to the build's own version was wrong: xcodebuild's
# `build_version` returns CFBundleVersion (the build number,
# e.g. `367`), not MARKETING_VERSION — which produced nonsense
# App Store version records named `367` (#1795).
#
# Apple's versionString validator accepts only clean `X` or `X.Y`
# or `X.Y.Z` — pre-release suffixes like `4.2.0-rc.1` are rejected
# at the appStoreVersions POST with HTTP 409. The release tagger
# in this repo only cuts clean semver tags so this isn't a
# present issue, but if a future RC flow lands, the resolution
# below would need a stripping step.
version_string = (os.environ.get("ASC_VERSION_STRING") or "").removeprefix("v")
if not version_string:
    workspace = os.environ.get("GITHUB_WORKSPACE", os.path.join(os.path.dirname(__file__), "..", ".."))
    gradle_properties = os.path.join(workspace, "gradle.properties")
    try:
        with open(gradle_properties) as f:
            for line in f:
                if line.startswith("VERSION_NAME="):
                    version_string = line.split("=", 1)[1].strip()
                    break
    except OSError as e:
        print(f"::warning::Could not read VERSION_NAME from {gradle_properties}: {e}")
    if not version_string:
        # Last-resort fallback: build's own version (CFBundleVersion).
        # Should never be hit in practice — gradle.properties is the
        # single source of truth and is always present in the repo.
        print(f"::warning::Falling back to build version {build_version} — VERSION_NAME not resolvable")
        version_string = build_version
print(f"Target App Store versionString: {version_string}")

# 3. Get or create the app store version
# Embed the linked appStoreVersionSubmission in the response via
# `include` (JSON:API). This is the only Apple-supported way for
# this service-account tier to learn the submission's ID — both
# the to-one related-resource endpoint
# (`/appStoreVersions/{id}/appStoreVersionSubmission`) and a
# filter on the top-level collection
# (`/appStoreVersionSubmissions?filter[appStoreVersion]={id}`)
# were tested in #1831 v1/v2 and return 404 / 403 respectively
# for our key.
#
# filter[platform]=IOS is MANDATORY (#2731): without it the
# "reuse the first editable draft" fallback below can grab the
# macOS platform's permanently-editable draft (the macOS app has
# never shipped), and every downstream call 409s — build attach,
# localization sync (against the WRONG listing), and the
# reviewSubmissionItems CREATE ("Supported platforms are not
# compatible"). That exact hijack silently killed every iOS
# App Review submission from 4.19.0 through 4.22.0.
r = requests.get(
    f"{BASE}/apps/{app_id}/appStoreVersions?filter[appStoreState]=PREPARE_FOR_SUBMISSION,READY_FOR_REVIEW&filter[platform]=IOS&include=appStoreVersionSubmission",
    headers=headers,
)
versions = r.json().get("data", [])
# Pick a version record that already targets our versionString,
# otherwise fall back to the first editable one if any, otherwise
# create a new record. Picking by versionString avoids the
# previously-hardcoded "4.0.3" trap where any unrelated editable
# version would get hijacked for an unrelated build.
version_id = None
matched_version = None
for v in versions:
    if v.get("attributes", {}).get("versionString") == version_string:
        version_id = v["id"]
        matched_version = v
        print(f"Using matching version record: {version_id}")
        break
if not version_id:
    # No record for our versionString. Apple allows only ONE
    # version in an editable state at a time, so if a stale
    # PREPARE_FOR_SUBMISSION draft exists (e.g. a release whose
    # submission never completed), creating a second one would
    # 409. Reuse that draft and retarget its versionString
    # instead — this is the "fall back to the first editable
    # one" behaviour, and it lets a release absorb an abandoned
    # draft without a manual App Store Connect cleanup.
    stale = next(
        (v for v in versions
         if v.get("attributes", {}).get("appStoreState") == "PREPARE_FOR_SUBMISSION"),
        None,
    )
    if stale:
        version_id = stale["id"]
        matched_version = stale
        stale_vs = stale.get("attributes", {}).get("versionString")
        r = requests.patch(
            f"{BASE}/appStoreVersions/{version_id}",
            headers=headers,
            json={"data": {
                "type": "appStoreVersions",
                "id": version_id,
                "attributes": {"versionString": version_string},
            }},
        )
        r.raise_for_status()
        print(f"Reused editable draft {version_id}: retargeted {stale_vs} → {version_string}")
if not version_id:
    payload = {
        "data": {
            "type": "appStoreVersions",
            "attributes": {"platform": "IOS", "versionString": version_string},
            "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}
        }
    }
    r = requests.post(f"{BASE}/appStoreVersions", headers=headers, json=payload)
    if r.status_code == 409:
        # App Store Connect allows exactly ONE non-live version at a time, and
        # the GET above cannot see the one holding the slot: its filter admits
        # only PREPARE_FOR_SUBMISSION and READY_FOR_REVIEW, so a predecessor
        # sitting in IN_REVIEW / WAITING_FOR_REVIEW is invisible. We reach this
        # POST believing nothing exists, and Apple 409s (#3143). Measured:
        # 4.30.0's release run died here at 16:48 on 2026-08-12 while 4.29.0
        # was still in review — it went live at 20:39 the same day.
        #
        # STOP HERE. Do not route around it. Step 5a below cancels every open
        # reviewSubmission, and its OPEN_STATES includes IN_REVIEW: carrying on
        # would pull the PREVIOUS release out of App Review to make room for
        # this one. Deferring costs a re-run; continuing costs a release.
        #
        # Not every state can hold the slot, so name the one that does rather
        # than guess. A probe failure degrades to "could not determine" — the
        # 409 is the finding, the identification is the courtesy.
        OCCUPYING_STATES = ",".join([
            "WAITING_FOR_REVIEW", "IN_REVIEW", "PENDING_APPLE_RELEASE",
            "PENDING_DEVELOPER_RELEASE", "PROCESSING_FOR_APP_STORE",
            "WAITING_FOR_EXPORT_COMPLIANCE", "REJECTED", "METADATA_REJECTED",
            "DEVELOPER_REJECTED", "INVALID_BINARY", "PENDING_CONTRACT",
        ])
        holder = ""
        try:
            probe = requests.get(
                f"{BASE}/apps/{app_id}/appStoreVersions"
                f"?filter[appStoreState]={OCCUPYING_STATES}&filter[platform]=IOS&limit=5",
                headers=headers,
            )
            if probe.status_code == 200:
                for v in (probe.json() or {}).get("data", []):
                    a = v.get("attributes", {})
                    holder = f"{a.get('versionString', '?')} is {a.get('appStoreState', '?')}"
                    break
        except Exception as e:  # noqa: BLE001 — diagnosis must never mask the 409
            print(f"::warning::Could not identify the blocking version: {e}")
        if not holder:
            holder = "another version is in a non-live state (could not identify which)"
        print(
            f"::error::iOS submission for {version_string} DEFERRED, not failed: {holder}. "
            "App Store Connect allows one non-live version at a time, so its version record "
            "cannot be created yet. Re-run app-store.yml once that version is live (or reject "
            "it in App Store Connect). Nothing was cancelled and no review was touched; "
            "Maven Central and npm publish earlier in the release and are unaffected."
        )
        print(f"POST /v1/appStoreVersions → 409: {r.text[:400]}")
        # Exit 2, not 1: this run is deferred by Apple's state machine, not
        # broken. A distinct code lets app-store.yml grade it without parsing
        # prose. It is still non-zero — nothing was submitted, and a green
        # badge over an unsubmitted release is the false green this repo pays
        # for most.
        raise SystemExit(2)
    r.raise_for_status()
    version_id = r.json()["data"]["id"]
    print(f"Created new version record: {version_id} ({version_string})")

# 4. Attach the build to the version
payload = {"data": {"type": "builds", "id": build_id}}
r = requests.patch(f"{BASE}/appStoreVersions/{version_id}/relationships/build", headers=headers, json=payload)
# 2xx or bust (#2731): the pre-fix runs printed "Build attached: 409"
# and sailed on — a version with no build can never pass review, so
# any non-2xx here means the submission below is doomed. Fail now,
# loudly, with the response body.
if r.status_code not in (200, 204):
    print(f"::error::Build attach failed: {r.status_code} {r.text[:300]}")
    raise SystemExit(1)
print(f"Build attached: {r.status_code}")

# 4b. Sync App Store localization metadata (en-US).
#
# Three buckets, ordered by per-release churn:
#
# - whatsNew (per-release) — extracted from CHANGELOG.md `## vX.Y.Z`
#   section. Apple does NOT auto-fill this when a new
#   appStoreVersion is created; without an explicit PATCH the App
#   Store keeps the previous version's notes verbatim, which ages
#   out fast (a v4.5.0 release shipping with v4.4.0's notes).
#
# - promotionalText (per-release, no review required) — short
#   tagline updateable at any time. Synced on every tag.
#
# - description / keywords / marketingUrl / supportUrl (per minor
#   bump only) — these fields TRIGGER an Apple review when
#   changed. Syncing on every patch would cost 4-5 review days
#   per patch for no user-visible improvement. Gated to
#   `vX.Y.0` tags so a metadata refresh lands once per minor
#   cycle, alongside the whatsNew that ships with every tag.
#
# Sourced from `samples/ios-demo/distribution/app-store/{locale}/`
# (fastlane-style layout, one file per field). Apple caps:
#   description     ≤4000   keywords         ≤100
#   promotionalText ≤170    marketingUrl/supportUrl ≤any
#
# Non-fatal on failure so the submission still proceeds with the
# previous metadata if a file is missing or the PATCH errors out.
try:
    import re, subprocess, pathlib
    attrs = {}

    # ── whatsNew (every tag) ──────────────────────────────────
    # Preferred source: a hand-maintained, user-facing
    # release_notes.txt. Apple's "What's New" must describe the
    # DEMO app for its App Store users, NOT the SDK's technical,
    # cross-platform CHANGELOG (Apple Guideline 2.3.10 rejects
    # notes referencing other ecosystems). Deriving whatsNew from
    # the CHANGELOG left the field near-empty for Web/Flutter-heavy
    # releases (e.g. 4.25.0), and an empty *required* whatsNew makes
    # the review submission 409 with ENTITY_STATE_INVALID far
    # downstream (#2893).
    whats_new = None
    notes_file = pathlib.Path("distribution/app-store/en-US/release_notes.txt")
    if notes_file.exists():
        whats_new = notes_file.read_text().strip() or None
        if whats_new:
            print(f"whatsNew sourced from release_notes.txt ({len(whats_new)}c)")
    if not whats_new:
        # Fallback (file absent): extract the `## vX.Y.Z` CHANGELOG
        # section as before, so existing tags keep working.
        # `gsub(/\./, "\\.", ver)` escapes the dots in `4.3.1` so the
        # regex doesn't also match `4-3-1` etc. Same hygiene as
        # play-store.yml's awk extractor.
        awk_out = subprocess.run(
            ["awk", "-v", f"ver=## v{version_string}",
             r'BEGIN { gsub(/\./, "\\.", ver) } '
             r'$0 ~ "^"ver"( |$)" { found=1; next } '
             r'found && /^## v[0-9]/ { exit } '
             r'found { print }',
             "../../CHANGELOG.md"],
            capture_output=True, text=True, check=False,
        )
        raw = awk_out.stdout
        if raw.strip():
            text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", raw)
            text = re.sub(r"\*\*([^*]+)\*\*", r"\1", text)
            text = re.sub(r"`([^`]+)`", r"\1", text)
            text = re.sub(r"^#{1,6}\s*", "", text, flags=re.M)
            # Apple Guideline 2.3.10 (Accurate Metadata): App Store
            # reviewers reject "What's New" notes that reference other
            # platforms (Android/Google Play/etc.) — irrelevant to App
            # Store users. The CHANGELOG is cross-platform, so drop any
            # bullet line that mentions a non-Apple ecosystem before it
            # reaches the iOS/macOS whatsNew. #2252 follow-up.
            _other_platform = re.compile(
                r"\b(android|google play|play store|maven|gradle|"
                r"jetpack|kotlin|flutter|react[ -]native|web|webgl|"
                r"webxr|wasm|\.aar|desktop)\b",
                re.I,
            )
            text = "\n".join(
                ln for ln in text.splitlines()
                if not (ln.lstrip()[:1] in "-*•" and _other_platform.search(ln))
            )
            whats_new = re.sub(r"\n{3,}", "\n\n", text).strip() or None
        if not whats_new:
            # Name the real state of the file: "No release_notes.txt"
            # sent whoever read this log looking for a missing file
            # that was actually there but blank (#2908 review nit).
            notes_state = (
                f"{notes_file} exists but is empty/whitespace-only"
                if notes_file.exists() else "No release_notes.txt"
            )
            print(f"::warning::{notes_state}, and no usable '## v{version_string}' CHANGELOG section — whatsNew left empty; the review submission will 409 (ENTITY_STATE_INVALID) if the field is blank on App Store Connect")
    if whats_new:
        if len(whats_new) > 4000:
            whats_new = whats_new[:3997].rstrip() + "…"
        attrs["whatsNew"] = whats_new

    # ── Read file-backed metadata ─────────────────────────────
    meta_dir = pathlib.Path("distribution/app-store/en-US")

    def _read(field, filename, cap):
        f = meta_dir / filename
        if not f.exists():
            return None
        v = f.read_text().strip()
        if len(v) > cap:
            print(f"::warning::{filename} exceeded {cap} chars — truncating")
            v = v[: cap - 1].rstrip() + "…"
        return v

    # promotionalText syncs on every release (Apple allows
    # updating without a new review).
    v = _read("promotionalText", "promotional_text.txt", 170)
    if v is not None:
        attrs["promotionalText"] = v

    # Description / keywords / urls = per minor bump.
    # `version_string` is already stripped of the leading `v`.
    is_minor_bump = bool(re.fullmatch(r"\d+\.\d+\.0", version_string))
    if is_minor_bump:
        print(f"Tag {version_string} is a minor/major bump — syncing description/keywords/urls")
        for field, fname, cap in [
            ("description", "description.txt", 4000),
            ("keywords", "keywords.txt", 100),
            ("marketingUrl", "marketing_url.txt", 2048),
            ("supportUrl", "support_url.txt", 2048),
        ]:
            v = _read(field, fname, cap)
            if v is not None:
                attrs[field] = v
    else:
        print(f"Tag {version_string} is a patch — skipping description/keywords/urls (gated to vX.Y.0)")

    # ── Find or create the en-US localization ─────────────────
    if not attrs:
        print("No localization attributes to PATCH — skipping")
    else:
        r = requests.get(
            f"{BASE}/appStoreVersions/{version_id}/appStoreVersionLocalizations",
            headers=headers,
        )
        r.raise_for_status()
        locs = r.json().get("data", [])
        en_us = next(
            (loc for loc in locs
             if loc.get("attributes", {}).get("locale") == "en-US"),
            None,
        )
        field_summary = ", ".join(f"{k}={len(v)}c" for k, v in attrs.items())
        if en_us:
            loc_id = en_us["id"]
            r = requests.patch(
                f"{BASE}/appStoreVersionLocalizations/{loc_id}",
                headers=headers,
                json={"data": {
                    "type": "appStoreVersionLocalizations",
                    "id": loc_id,
                    "attributes": attrs,
                }},
            )
            r.raise_for_status()
            print(f"Localization PATCHed on en-US ({loc_id}): {field_summary}")
        else:
            # Build a separate dict for POST: `locale` is read-only on
            # PATCH so leaking it back into `attrs` would 409 on a
            # future refactor that PATCHes from the same dict.
            post_attrs = {**attrs, "locale": "en-US"}
            payload = {"data": {
                "type": "appStoreVersionLocalizations",
                "attributes": post_attrs,
                "relationships": {"appStoreVersion": {
                    "data": {"type": "appStoreVersions", "id": version_id}
                }},
            }}
            r = requests.post(
                f"{BASE}/appStoreVersionLocalizations",
                headers=headers, json=payload,
            )
            r.raise_for_status()
            print(f"Localization CREATED on en-US: {field_summary}")
except Exception as e:
    print(f"::warning::Failed to update App Store localization: {e}")

# 4c. Sync the repo's screenshots BEFORE submitting (#2899).
#
# This is the ONLY moment in a release when an editable version
# exists: section 3 just created it, and section 5 below submits it,
# after which Apple locks the metadata until the next release.
#
# `app-store-screenshots.yml` can therefore never reliably do this on
# its own. Dispatched after a release it skips honestly ("no editable
# iOS version"); dispatched before one there is nothing to write to.
# That is exactly how v4.26.0 shipped with a correct promotionalText
# and a screenshot set four releases stale — the window was real, and
# nothing was writing in it. The manual workflow stays for out-of-band
# refreshes when a version happens to be open; this is the path that
# runs every time.
#
# Reuses `asc_listing.apply_screenshots()` instead of reimplementing
# the delete-then-upload dance, so the automatic and manual paths
# cannot drift apart.
#
# NEVER fatal, deliberately: a screenshot that fails to upload must
# not stop a release from reaching App Review, and the listing
# self-heals on the next release. It is loud, though — a quiet skip
# here is precisely what let the drift survive four releases.
try:
    sys.path.insert(0, os.path.join(
        os.environ["GITHUB_WORKSPACE"], ".claude", "scripts", "store-sync"))
    from asc_listing import apply_screenshots

    shots_dir = (pathlib.Path(os.environ["GITHUB_WORKSPACE"])
                 / "samples" / "ios-demo" / "appstore-screenshots")
    # Fresh token: the one minted at the top of this step is good for
    # 1200s, and section 2's build poll alone can burn 900s of that
    # waiting on Apple's processing. Uploading assets against a token
    # with two minutes left would 401 halfway through a
    # delete-then-upload, which is the one way this can leave the
    # listing worse than it found it.
    changed, skipped = apply_screenshots(asc_headers(), BUNDLE_ID, shots_dir)
    if skipped:
        print(f"::warning::Screenshot sync SKIPPED — {skipped}")
    elif changed:
        print(f"Screenshots synced for {len(changed)} display type(s): {changed}")
    else:
        print("Screenshots already match the repo — nothing to upload.")
# BaseException, not Exception — mirroring section 5 below, and
# load-bearing rather than defensive: apply_screenshots() signals its
# write-path failures with `raise SystemExit(1)` (a failed set POST, a
# failed DELETE, non-delivery after polling), and SystemExit derives
# from BaseException, so `except Exception` would NOT catch it. The
# release would abort before section 5 ever ran — and the case that
# aborts it is the worst one, a partial upload after the live set was
# already deleted, which is precisely when reaching App Review still
# matters. Caught in review of PR #3013.
except BaseException as e:
    # Two failure classes, two volumes — because `except BaseException`
    # is broad enough to hide the one that matters most.
    #
    # apply_screenshots() signals its write-path failures with
    # SystemExit, and API trouble as requests exceptions. Those are
    # expected, usually transient, and the listing self-heals on the
    # next release: a ::warning:: is the honest level.
    #
    # An ImportError / NameError / AttributeError / TypeError is
    # different in kind — the uploader never ran at all, because this
    # integration broke (a moved script, a renamed symbol, a changed
    # signature). ValueError belongs here too: apply_screenshots()
    # raises it for a DISPLAY_TYPE_MAP Apple would reject, re-asserted
    # at the write boundary before any network call. That is a config
    # break which never self-heals, so "release continues, self-heals
    # next release" would be a lie repeated at every release.
    #
    # Left at ::warning:: any of these would silently stop syncing
    # screenshots while every release stayed green, which is the exact
    # silent-drift class #2899 exists to end, reintroduced one level up
    # in the automation. Still not fatal — a broken sync must not block
    # App Review — but loud enough that a release cannot pass it
    # unnoticed. RuntimeError stays a warning: apply_screenshots()
    # raises it for genuinely transient upload trouble.
    # Raised in review of PR #3013.
    # JSONDecodeError subclasses ValueError, so it has to be excluded
    # BEFORE the tuple test — a truncated or non-JSON 2xx body is the
    # textbook transient case, and calling it a permanent break would
    # cry wolf at every flaky response.
    #
    # SyntaxError (and IndentationError, its subclass) has to be IN the
    # tuple: a broken asc_listing.py makes the import itself raise, the
    # uploader never runs, and nothing about that self-heals. Leaving it
    # out was the dangerous direction — announcing a permanent break as
    # transient — which is the mistake this whole PR is about.
    # Both raised in review of PR #3013.
    # getattr, not `requests.exceptions.JSONDecodeError` directly: this
    # runs INSIDE an exception handler, where an AttributeError would
    # escape the very try/except meant to keep the release alive and
    # kill the step before section 5 ever submits. `test-app-store-
    # submit.py` stubs `requests` with a module that has no
    # `.exceptions`, and caught exactly that — 32 submission tests died
    # on a handler that could not run.
    _json_err = getattr(getattr(requests, "exceptions", None),
                        "JSONDecodeError", None)
    transient_json = _json_err is not None and isinstance(e, _json_err)
    broken_wiring = not transient_json and isinstance(
        e, (ImportError, SyntaxError, NameError,
            AttributeError, TypeError, ValueError))
    level = "error" if broken_wiring else "warning"
    detail = ("the screenshot sync integration is BROKEN, not merely failing"
              if broken_wiring else "release continues")
    print(f"::{level}::Screenshot sync FAILED, {detail} — {e!r}")

# 5. Submit for review via reviewSubmissions (v4 — closes #1831).
#
# The old `POST /v1/appStoreVersionSubmissions` flow was brittle:
# a stale submission attached to an absorbed draft returned 403
# "Allowed operation is: DELETE" even when the submission couldn't
# be retrieved via GET (all three probe variants tried in v1–v3
# returned 404 or 403 from the service account). The new
# `reviewSubmissions` API (App Store Connect API v3, 2023+) avoids
# the problem entirely — it creates a fresh review request,
# independently of any legacy appStoreVersionSubmission state.
#
# Flow: POST /v1/reviewSubmissions (IOS) →
#       POST /v1/reviewSubmissionItems (attach version) →
#       PATCH /v1/reviewSubmissions/{id} {submitted: true}
# Fresh token before the submission, for the same reason 4c mints its
# own — and more sharply here. This step's original token lasts 1200s,
# section 2's build poll can burn ~900s of it, and section 4c now
# spends more on top uploading screenshots. A 401 on the
# reviewSubmissions POST raises SystemExit(1) and fails the release,
# so the single most important call in this workflow must not be the
# one running closest to expiry. Raised in review of PR #3013.
headers = asc_headers()

try:
    # 5a. Cancel any STALE open reviewSubmission first (#2301).
    #
    # App Store Connect allows only ONE open reviewSubmission per
    # app. A previous run that died between the CREATE below and the
    # final `submitted: true` PATCH (e.g. reviewSubmissionItems
    # errored) leaves an open, unsubmitted reviewSubmission attached
    # to the app — the next run's POST then 409s ("stranded resource
    # blocks CREATE", the same class #1831 fixed on the old API).
    #
    # The app→reviewSubmissions related-resource list IS exposed by
    # Apple (unlike the appStoreVersionSubmission traversal that
    # #1831 found 404s for this service-account tier), and it
    # supports filter[platform] + filter[state]. The non-terminal
    # states below are the "open" ones that block a new CREATE;
    # COMPLETE / COMPLETING are terminal and harmless, so we leave
    # them alone. Cancellation is PATCH {canceled: true} — there is
    # NO DELETE for reviewSubmissions (verified against Apple's API +
    # Fastlane spaceship; the issue's "DELETE" wording is wrong).
    OPEN_STATES = "READY_FOR_REVIEW,WAITING_FOR_REVIEW,IN_REVIEW,UNRESOLVED_ISSUES"
    ls = requests.get(
        f"{BASE}/apps/{app_id}/reviewSubmissions"
        f"?filter[platform]=IOS&filter[state]={OPEN_STATES}&limit=10",
        headers=headers,
    )
    print(f"Stale-open reviewSubmissions probe → {ls.status_code}")
    if ls.status_code == 200:
        for stale in (ls.json() or {}).get("data", []):
            stale_id = stale["id"]
            stale_state = stale.get("attributes", {}).get("state", "?")
            cancel = requests.patch(
                f"{BASE}/reviewSubmissions/{stale_id}",
                headers=headers,
                json={"data": {
                    "type": "reviewSubmissions",
                    "id": stale_id,
                    "attributes": {"canceled": True},
                }},
            )
            print(
                f"Canceling stale open reviewSubmission {stale_id} "
                f"(was {stale_state}) → {cancel.status_code}"
            )
    elif ls.status_code not in (404,):
        # Non-fatal: a probe failure shouldn't block a fresh CREATE.
        print(f"::warning::Could not list reviewSubmissions: {ls.status_code} {ls.text[:200]}")

    # Create review submission
    rs_payload = {
        "data": {
            "type": "reviewSubmissions",
            "attributes": {"platform": "IOS"},
            "relationships": {"app": {"data": {"type": "apps", "id": app_id}}}
        }
    }
    rs = requests.post(f"{BASE}/reviewSubmissions", headers=headers, json=rs_payload)
    if rs.status_code not in (200, 201):
        print(f"::error::reviewSubmissions CREATE failed: {rs.status_code} {rs.text[:300]}")
        raise SystemExit(1)
    try:
        rs_id = rs.json()["data"]["id"]
    except (ValueError, KeyError, TypeError) as ide:
        # Apple accepted the CREATE but the body is unreadable, so we
        # hold no id to cancel with — the one orphan the cleanup below
        # structurally cannot reach. Say so instead of dying silently.
        print(f"::error::reviewSubmissions CREATE returned {rs.status_code} with an unreadable body "
              f"({ide}): a submission may have been created that this run cannot identify or cancel. "
              "Check App Store Connect for an open, itemless submission.")
        raise SystemExit(1)
    print(f"Created reviewSubmission {rs_id}")

    # W5 (#2893). From this point THIS run owns an OPEN, EMPTY
    # reviewSubmission. Every remaining failure path below used to
    # `raise SystemExit(1)` and leave it behind: an orphan
    # READY_FOR_REVIEW submission with 0 items, created by the CI
    # service account, that a human then had to clear by hand in App
    # Store Connect (one was deleted manually after run
    # 30269459288). The 5a stale-cancel probe above does NOT cover it
    # — that probe only runs on the NEXT deploy, so the orphan sits
    # in the account until then, and `store-preflight.sh`'s #2731
    # detector reports it as a blocker in the meantime.
    #
    # So: cancel what this run created, on any post-CREATE failure.
    # `finally` (not `except`) so a transport error or a KeyError on a
    # malformed body is covered too, gated on `submitted_ok` so a
    # SUCCESSFUL submission is never cancelled. Cancellation is
    # PATCH {canceled: true} — reviewSubmissions has no DELETE (the
    # issue's "DELETE" wording is wrong; verified against Apple's API
    # + Fastlane spaceship, same as 5a above).
    #
    # The cleanup is strictly best-effort and swallows its own
    # errors: it must never mask the failure that triggered it (a
    # cancel that itself 401s on an expired JWT would otherwise
    # replace the real diagnosis with a cleanup traceback).
    #
    # One case is NOT unambiguous, and cancelling there would be
    # worse than the orphan this cleanup exists to kill: if the
    # submit PATCH below raises (read timeout, RST, runner network
    # blip) the request may still have REACHED Apple, who committed
    # the submission to WAITING_FOR_REVIEW before the response was
    # lost. Cancelling then WITHDRAWS a live App Review submission.
    # So the ambiguous window is tracked separately (`submit_answered`)
    # and resolved by re-reading the submission's state before
    # touching it — never cancelling on an unread state.
    submitted_ok = False
    submit_answered = True  # False only while the PATCH is in flight
    try:
        # Add version to submission
        item_payload = {
            "data": {
                "type": "reviewSubmissionItems",
                "relationships": {
                    "reviewSubmission": {"data": {"type": "reviewSubmissions", "id": rs_id}},
                    "appStoreVersion": {"data": {"type": "appStoreVersions", "id": version_id}}
                }
            }
        }
        ri = requests.post(f"{BASE}/reviewSubmissionItems", headers=headers, json=item_payload)
        if ri.status_code not in (200, 201):
            print(f"::error::reviewSubmissionItems CREATE failed: {ri.status_code} {ri.text[:300]}")
            raise SystemExit(1)
        print(f"Added version {version_id} to review submission")

        # Submit for review
        patch_payload = {
            "data": {
                "id": rs_id,
                "type": "reviewSubmissions",
                "attributes": {"submitted": True}
            }
        }
        submit_answered = False
        rp = requests.patch(f"{BASE}/reviewSubmissions/{rs_id}", headers=headers, json=patch_payload)
        # A 5xx or a 408 is as ambiguous as no answer at all: an edge
        # or gateway can lose the RESPONSE to a write the backend
        # already committed, so those route to the state probe too.
        # 429 (rate-limited → not processed) and 4xx rejections like
        # 409/422 are real answers: Apple did not take the submission,
        # and cancelling is unambiguously right.
        submit_answered = rp.status_code < 500 and rp.status_code != 408
        if rp.status_code in (200, 201):
            # Set BEFORE reading the body: the submission is live on
            # Apple's side at this point, so a parse error on the
            # response must not trigger the orphan cleanup and
            # cancel a real submission.
            submitted_ok = True
            try:
                state = rp.json()["data"]["attributes"].get("state", "?")
            except (ValueError, KeyError, TypeError):
                # The submission is live on Apple's side; an
                # unreadable body is a reporting problem, not a
                # release failure. Raising here would turn a
                # SUCCESSFUL submission into a red step that cannot
                # even be re-run (re-running the failed job reuses
                # the `check` job's build-number output, so it
                # re-archives a duplicate CFBundleVersion, which
                # Apple rejects — #3081). Pre-existing on main, kept from
                # biting here (#2963 review).
                state = "? (response body unreadable)"
            print(f"Successfully submitted for App Store review! State: {state}")
        else:
            print(f"::error::Submission PATCH failed: {rp.status_code} - {rp.text[:300]}")
            raise SystemExit(1)
    finally:
        if not submitted_ok and not submit_answered:
            # Ambiguous window: the submit PATCH never came back, so
            # Apple may or may not hold a live submission. Read the
            # state before deciding; anything other than a confirmed
            # not-yet-submitted state means hands off.
            try:
                probe = requests.get(f"{BASE}/reviewSubmissions/{rs_id}", headers=headers)
                if probe.status_code == 200:
                    probe_state = probe.json().get("data", {}).get("attributes", {}).get("state")
                else:
                    # The by-id read is documented and this key already
                    # PATCHes that same URL, but it has never been
                    # MEASURED here, and #1831 found several ASC reads
                    # 404/403 for this service-account tier. Fall back
                    # to the app→reviewSubmissions list — the only
                    # reviewSubmissions read this key is known to
                    # perform (5a above, and store-preflight.sh daily).
                    print(f"reviewSubmission {rs_id} by-id read → {probe.status_code}; "
                          "falling back to the app→reviewSubmissions list")
                    listing = requests.get(
                        f"{BASE}/apps/{app_id}/reviewSubmissions?filter[platform]=IOS&limit=50",
                        headers=headers,
                    )
                    probe_state = next(
                        (s.get("attributes", {}).get("state")
                         for s in (listing.json() or {}).get("data", [])
                         if s.get("id") == rs_id),
                        None,
                    ) if listing.status_code == 200 else None
            except BaseException as pe:
                probe_state = None
                print(f"::warning::Could not read back reviewSubmission {rs_id}: {pe}")
            if probe_state in ("READY_FOR_REVIEW", "UNRESOLVED_ISSUES"):
                print(f"reviewSubmission {rs_id} is still {probe_state} — the lost PATCH did not "
                      "reach Apple; cleaning it up")
                submit_answered = True  # resolved: safe to cancel below
            else:
                # Either Apple took it (WAITING_FOR_REVIEW/IN_REVIEW)
                # or the state is unreadable for this service-account
                # key. Leaving an orphan is recoverable: 5a's stale
                # probe cancels it on the next deploy, and
                # store-preflight.sh reports it meanwhile.
                #
                # Be precise about what this protects, because 5a's
                # OPEN_STATES above deliberately includes
                # WAITING_FOR_REVIEW and IN_REVIEW — the next tag
                # cancels a live submission anyway, by design (a new
                # release replaces the one in the queue). What is
                # bought here is that THIS run does not withdraw a
                # submission it cannot prove it failed to make, so the
                # release standing in Apple's queue survives until a
                # human or a deliberate next release supersedes it.
                print(
                    f"::warning::The submit PATCH for reviewSubmission {rs_id} got no response and its "
                    f"state reads as {probe_state or 'unknown'} — NOT cancelling, because it may be a "
                    "live App Review submission. Check it in App Store Connect: if it is still assembled "
                    "but never submitted, cancel it there (the next deploy's stale probe also clears it)."
                )
        if not submitted_ok and submit_answered:
            try:
                cancel = requests.patch(
                    f"{BASE}/reviewSubmissions/{rs_id}",
                    headers=headers,
                    json={"data": {
                        "type": "reviewSubmissions",
                        "id": rs_id,
                        "attributes": {"canceled": True},
                    }},
                )
                if cancel.status_code in (200, 201):
                    print(f"Cleaned up the orphan reviewSubmission {rs_id} created by this run (canceled)")
                else:
                    print(
                        f"::warning::Could not cancel the orphan reviewSubmission {rs_id} this run created: "
                        f"{cancel.status_code} {cancel.text[:200]} — cancel it by hand in App Store Connect, "
                        "or it will block the next deploy until 5a's stale probe clears it"
                    )
            except BaseException as ce:
                print(
                    f"::warning::Could not cancel the orphan reviewSubmission {rs_id} this run created: {ce} "
                    "— cancel it by hand in App Store Connect"
                )
except BaseException:
    # FATAL (#2731). This used to be a swallowed ::warning and the
    # step stayed green — four consecutive releases (4.19.0→4.22.0)
    # "shipped" without ever reaching App Review and nobody saw it.
    # A tag-push deploy that cannot submit for review IS a failed
    # deploy: re-raise so the job goes red and the release flow stops.
    #
    # BaseException, not SystemExit (#2963 review): a raw transport
    # error — a ConnectionError on the reviewSubmissionItems POST or
    # on the submit PATCH — is exactly as fatal, and catching only
    # SystemExit skipped this banner for precisely those failures,
    # leaving a red step whose log never says what went wrong. Every
    # deliberate exit in this block is `SystemExit(1)`; there is no
    # success path out of here, so the banner cannot fire on a
    # successful submission. The `raise` keeps the original
    # traceback, so widening the catch only ADDS the diagnostic.
    print("::error::App Store review submission FAILED — this release did NOT reach App Review (#2731).")
    raise
