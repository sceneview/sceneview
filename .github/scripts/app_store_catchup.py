"""Decide whether the latest GitHub release still has to be submitted to the App Store.

Called by `.github/workflows/app-store-catch-up.yml` every few hours, on an
ubuntu runner. It changes nothing: it reads App Store Connect and GitHub, then
writes one decision to $GITHUB_OUTPUT for the workflow's next jobs to act on.

Why it exists (2026-09-28): Apple approved every SceneView submission in 20 to
36 hours and never rejected one, yet the live iOS app sat at 4.40.0 while
Android shipped 4.47.0. App Store Connect allows one non-live version at a
time. Each tag that landed while its predecessor was in review got a 409 and
"deferred", and a deferred release was never retried, because retrying needed
a human to press "Re-run". This probe is that retry.

Decision outputs:
    action     none | submit | rebuild
               submit  — a VALID iOS TestFlight build of the release exists:
                         submit it from an ubuntu runner, no Xcode needed.
               rebuild — no usable build: dispatch app-store.yml on the tag,
                         which archives on macOS and submits.
    version    X.Y.Z of the release to submit
    build      CFBundleVersion of the TestFlight build to reuse (submit only):
               the one an app-store.yml run ON THE TAG uploaded, never a
               later manual run from main with the same marketing version
    supersede  "true" when an OLDER version must be withdrawn from review first
               (only when the `supersede` input was set by a human)

Environment:
    ASC_KEY_ID, ASC_ISSUER_ID, ASC_API_KEY   App Store Connect API key (.p8 content)
    GITHUB_TOKEN, GITHUB_REPOSITORY          read releases and workflow runs
    CATCHUP_SUPERSEDE                        "true" = allow superseding
    GITHUB_OUTPUT, GITHUB_STEP_SUMMARY       set by Actions

The decision itself is `decide()`, a pure function covered by
`test_app_store_catchup.py` next to this file. An App Store Connect state it
does not know is an error (exit 1), never a guess: guessing "free slot" is
what would submit on top of a state nobody has classified.
"""
import datetime
import os
import re
import sys
import time

BUNDLE_ID = "io.github.sceneview.demo"
ASC = "https://api.appstoreconnect.apple.com/v1"
GH = "https://api.github.com"

# A deferred release that keeps failing to build must not cost a macOS run
# every few hours forever: after this many app-store.yml runs on the same tag
# without a VALID build, stop and say a human is needed.
MAX_RUNS_PER_TAG = 3

# `appStoreState` is the historical field, `appVersionState` its replacement.
# Both are read; the values below cover both vocabularies.
LIVE_STATES = {"READY_FOR_SALE", "READY_FOR_DISTRIBUTION", "PREORDER_READY_FOR_SALE"}
# With Apple, moving on its own: wait for it to go live.
IN_FLIGHT_STATES = {
    "WAITING_FOR_REVIEW", "IN_REVIEW", "ACCEPTED", "PENDING_APPLE_RELEASE",
    "PROCESSING_FOR_APP_STORE", "PROCESSING_FOR_DISTRIBUTION",
}
# Holds the slot and will not move without a person in App Store Connect.
NEEDS_HUMAN_STATES = {
    "PENDING_DEVELOPER_RELEASE", "WAITING_FOR_EXPORT_COMPLIANCE",
    "PENDING_CONTRACT", "INVALID_BINARY",
}
# app_store_submit.py claims and retargets these (see its EDITABLE_STATES).
EDITABLE_STATES = {
    "PREPARE_FOR_SUBMISSION", "READY_FOR_REVIEW", "DEVELOPER_REJECTED",
    "REJECTED", "METADATA_REJECTED",
}
SUPERSEDABLE_STATES = {"WAITING_FOR_REVIEW", "IN_REVIEW"}
# Stopped by a person: withdrawn from review (DEVELOPER_REJECTED) or rejected
# by Apple. On the release itself — or on anything newer — that is a verdict,
# not a free slot: re-submitting it every 3 hours would undo a human's
# decision. Only a record strictly OLDER than the release may be claimed.
STOPPED_STATES = {"REJECTED", "METADATA_REJECTED", "DEVELOPER_REJECTED", "INVALID_BINARY"}
# History: hold no slot and block nothing.
SETTLED_STATES = {
    "REPLACED_WITH_NEW_VERSION", "REMOVED_FROM_SALE", "DEVELOPER_REMOVED_FROM_SALE",
    "NOT_APPLICABLE",
}
KNOWN_STATES = (LIVE_STATES | IN_FLIGHT_STATES | NEEDS_HUMAN_STATES | EDITABLE_STATES
                | STOPPED_STATES | SETTLED_STATES)

# app-store.yml's `check` job stamps CFBundleVersion as `date -u +%Y%m%d%H%M`.
BUILD_STAMP = "%Y%m%d%H%M"
# A tag run's `check` job starts within minutes of the run; the slack covers a
# slow runner pickup without reaching a later run's builds.
TAG_BUILD_SLACK = datetime.timedelta(minutes=30)

SEMVER_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")


def vtuple(version):
    return tuple(int(x) for x in version.split("."))


def version_state(v):
    a = v.get("attributes", {})
    return a.get("appStoreState") or a.get("appVersionState") or "?"


def _parse_time(value):
    """GitHub's `2026-09-28T04:05:12Z` → aware datetime, or None."""
    try:
        return datetime.datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None


def tag_build_numbers(builds, tag_run_windows):
    """Build numbers uploaded by an app-store.yml run on the tag.

    builds           [(marketingVersion, buildNumber)]
    tag_run_windows  [(created_at, run_started_at)] aware datetimes, one per
                     app-store.yml run whose head is the tag
    A build belongs to a run when its `date -u +%Y%m%d%H%M` stamp falls between
    the run's creation (to the minute) and its latest start plus the slack.
    """
    out = set()
    for _, number in builds:
        try:
            stamp = datetime.datetime.strptime(str(number), BUILD_STAMP).replace(
                tzinfo=datetime.timezone.utc)
        except ValueError:
            continue
        for created, started in tag_run_windows:
            low = created.replace(second=0, microsecond=0)
            if low <= stamp <= max(created, started) + TAG_BUILD_SLACK:
                out.add(str(number))
                break
    return out


def decide(latest_tag, versions, builds, supersede=False, deploy_running=False, tag_runs=0,
           tag_builds=None):
    """Pure decision. Returns {action, version, build, supersede, reason, level}.

    latest_tag      tag of the latest GitHub release, e.g. "v4.47.0"
    versions        [(versionString, state)] — every iOS appStoreVersion
    builds          [(marketingVersion, buildNumber)] — VALID, unexpired iOS
                    builds, OLDEST first
    supersede       the human opted in to withdrawing an older version
    deploy_running  an app-store.yml run is queued or in progress
    tag_runs        app-store.yml runs already made on this tag
    tag_builds      build numbers uploaded by a run on the tag
                    (tag_build_numbers); None = unknown, oldest upload wins
    level "error" means the caller must exit non-zero: nothing is decided.
    """
    out = {"action": "none", "version": "", "build": "", "supersede": "false",
           "reason": "", "level": "notice"}
    m = SEMVER_TAG.match(latest_tag or "")
    if not m:
        out.update(reason=f"latest release tag {latest_tag!r} is not a vX.Y.Z tag — nothing to do",
                   level="warning")
        return out
    target = ".".join(m.groups())
    out["version"] = target
    t = vtuple(target)

    unknown = sorted({f"{vs} {state}" for vs, state in versions if state not in KNOWN_STATES})
    if unknown:
        out.update(level="error",
                   reason=f"unclassified App Store Connect state(s): {', '.join(unknown)} — "
                          "refusing to guess whether the slot is free; classify the state in "
                          "app_store_catchup.py")
        return out

    if deploy_running:
        out["reason"] = "app-store.yml is already queued or running — it owns this release"
        return out

    parsed = []
    for vs, state in versions:
        try:
            parsed.append((vtuple(vs), vs, state))
        except ValueError:
            continue

    live = max((p for p in parsed if p[2] in LIVE_STATES), default=None)
    if live and live[0] >= t:
        out["reason"] = f"{live[1]} is live — the App Store is up to date with {target}"
        return out

    stopped = next((p for p in parsed if p[0] >= t and p[2] in STOPPED_STATES), None)
    if stopped:
        out.update(level="warning",
                   reason=f"{stopped[1]} is {stopped[2]}: rejected or withdrawn by a human — "
                          f"not resubmitting automatically. Resubmit it in App Store Connect, "
                          f"or cut a newer release")
        return out
    newer_draft = next((p for p in parsed if p[0] > t and p[2] in EDITABLE_STATES), None)
    if newer_draft:
        out.update(level="warning",
                   reason=f"{newer_draft[1]} ({newer_draft[2]}) is newer than the latest release "
                          f"{target}; a record is never renamed to a lower version — "
                          "clean it up in App Store Connect")
        return out

    holder = next((p for p in parsed if p[2] in IN_FLIGHT_STATES | NEEDS_HUMAN_STATES), None)
    if holder:
        h_t, h_vs, h_state = holder
        if h_t >= t:
            out["reason"] = f"{h_vs} is already {h_state} — nothing to submit"
            return out
        if h_state in NEEDS_HUMAN_STATES:
            out.update(level="warning",
                       reason=f"{h_vs} is {h_state} and holds the only non-live slot; it needs "
                              f"action in App Store Connect before {target} can be submitted")
            return out
        if not (supersede and h_state in SUPERSEDABLE_STATES):
            hint = ""
            if h_state in SUPERSEDABLE_STATES:
                hint = f" (dispatch this workflow with supersede=true to replace it with {target})"
            out["reason"] = f"waiting: {h_vs} is {h_state}, {target} follows once it is live{hint}"
            return out
        out["supersede"] = "true"

    candidates = [str(b) for mv, b in builds if mv == target]
    origin = ""
    if tag_builds is not None:
        from_tag = [b for b in candidates if b in tag_builds]
        if from_tag:
            candidates = from_tag
        elif candidates:
            origin = " (not tied to a run on the tag: oldest upload of the version)"
    build = candidates[0] if candidates else None
    if build:
        out.update(action="submit", build=build,
                   reason=f"submitting {target} with TestFlight build {build}{origin}"
                          + (f", superseding {holder[1]}" if out["supersede"] == "true" else ""))
        return out
    if tag_runs >= MAX_RUNS_PER_TAG:
        out.update(level="warning",
                   reason=f"no VALID iOS build of {target} on TestFlight after {tag_runs} "
                          f"app-store.yml runs on v{target} — fix the build, then dispatch "
                          "app-store.yml on the tag")
        return out
    out.update(action="rebuild",
               reason=f"no VALID iOS build of {target} on TestFlight — dispatching app-store.yml "
                      f"on v{target} to archive, upload and submit it")
    return out


# ── I/O ─────────────────────────────────────────────────────────────────────
def _asc_headers():
    import jwt
    now = int(time.time())
    token = jwt.encode(
        {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 1200,
         "aud": "appstoreconnect-v1"},
        os.environ["ASC_API_KEY"], algorithm="ES256",
        headers={"kid": os.environ["ASC_KEY_ID"]},
    )
    return {"Authorization": f"Bearer {token}"}


def _get(url, headers):
    import requests
    r = requests.get(url, headers=headers, timeout=30)
    if r.status_code != 200:
        # Say which call failed, never the headers: they carry the tokens.
        print(f"::error::GET {url.split('?')[0]} → {r.status_code}: {r.text[:300]}")
        raise SystemExit(1)
    return r.json()


# Hard stop for `links.next`: 20 pages of 200 is 4,000 versions, far past this
# app's history. A cursor that never ends must fail, not spin.
MAX_PAGES = 20


def get_all(url, headers, get=None):
    """Every `data` item of a paginated App Store Connect collection."""
    get = get or _get
    items = []
    for _ in range(MAX_PAGES):
        page = get(url, headers)
        items.extend(page.get("data", []))
        url = (page.get("links") or {}).get("next")
        if not url:
            return items
    print(f"::error::appStoreVersions still paginating after {MAX_PAGES} pages — refusing to "
          "decide on a partial list")
    raise SystemExit(1)


def read_app_store():
    h = _asc_headers()
    app_id = _get(f"{ASC}/apps?filter[bundleId]={BUNDLE_ID}", h)["data"][0]["id"]
    versions = [
        (v.get("attributes", {}).get("versionString", ""), version_state(v))
        for v in get_all(f"{ASC}/apps/{app_id}/appStoreVersions?filter[platform]=IOS&limit=200",
                         h)
    ]
    payload = _get(
        f"{ASC}/builds?filter[app]={app_id}&filter[processingState]=VALID&filter[expired]=false"
        "&sort=-uploadedDate&limit=100&include=preReleaseVersion", h)
    included = {(i["type"], i["id"]): i for i in payload.get("included", [])}
    builds = []
    for b in payload.get("data", []):
        rel = b.get("relationships", {}).get("preReleaseVersion", {}).get("data")
        prv = included.get((rel["type"], rel["id"]), {}) if rel else {}
        attrs = prv.get("attributes", {})
        # iOS only: the macOS leg uploads the same numbers to the same app.
        if attrs.get("platform") == "IOS" and not b.get("attributes", {}).get("expired"):
            builds.append((attrs.get("version"), b["attributes"]["version"]))
    # Oldest first. decide() prefers the build a run on the tag uploaded — a
    # later manual run from main can carry the same marketing version with
    # commits the tag does not have — and falls back to the oldest upload.
    builds.reverse()
    return versions, builds


def read_github():
    repo = os.environ["GITHUB_REPOSITORY"]
    h = {"Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
         "Accept": "application/vnd.github+json"}
    tag = _get(f"{GH}/repos/{repo}/releases/latest", h)["tag_name"]
    runs = f"{GH}/repos/{repo}/actions/workflows/app-store.yml/runs"
    running = any(
        _get(f"{runs}?status={s}&per_page=1", h).get("total_count", 0)
        for s in ("queued", "in_progress", "waiting", "pending", "requested")
    )
    on_tag = _get(f"{runs}?branch={tag}&per_page=100", h)
    windows = []
    for run in on_tag.get("workflow_runs", []):
        if run.get("head_branch") != tag:
            continue
        created = _parse_time(run.get("created_at"))
        started = _parse_time(run.get("run_started_at")) or created
        if created:
            windows.append((created, started))
    return tag, running, on_tag.get("total_count", 0), windows


def main():
    tag, running, tag_runs, windows = read_github()
    versions, builds = read_app_store()
    supersede = (os.environ.get("CATCHUP_SUPERSEDE") or "").strip().lower() == "true"
    d = decide(tag, versions, builds, supersede=supersede,
               deploy_running=running, tag_runs=tag_runs,
               tag_builds=tag_build_numbers(builds, windows))

    live = ", ".join(f"{vs} {st}" for vs, st in versions
                     if st in LIVE_STATES | IN_FLIGHT_STATES | NEEDS_HUMAN_STATES
                     | EDITABLE_STATES) or "none"
    print(f"Latest release: {tag} · iOS versions: {live} · "
          f"app-store.yml running: {running} · runs on {tag}: {tag_runs}")
    print(f"::{d['level']}::App Store catch-up — {d['reason']}")
    if d["level"] == "error":
        return 1
    with open(os.environ["GITHUB_OUTPUT"], "a") as f:
        for k in ("action", "version", "build", "supersede"):
            f.write(f"{k}={d[k]}\n")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(f"### App Store catch-up\n\n- Latest release: `{tag}`\n"
                    f"- iOS versions: {live}\n- Decision: **{d['action']}** — {d['reason']}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
