#!/usr/bin/env python3
"""Hermetic self-test for app-store.yml's `Submit build for App Store review`
step (#2893).

That step is a large Python program (this test prints its exact size at run
time — do not hard-code a line count anywhere, it drifts with every edit), and
it is the single code path between a green tag build and an App Store
submission. Dispatching `app-store.yml` archives, signs, and uploads a real
build to TestFlight — so before this suite existed, every fix to the program
was validated in production, on a real release, after it broke one. Four
consecutive releases (4.19.0 → 4.22.0) "shipped" without ever reaching App
Review before #2731 caught it; 4.24.0 and 4.25.0 then failed on the
build-platform 409 (#2885) and the empty required `whatsNew` (#2893 W4).

This test runs the real program — never a copy, so it cannot drift — against a
stubbed App Store Connect, with `requests` and `jwt` replaced in `sys.modules`
and `time.sleep` neutered. Stdlib only: no network, no secrets, no Apple call,
runs in under a second.

Until #3146 the program was a heredoc inside the workflow and this test had to
regex-carve it back out of the YAML. It now lives in
`.github/scripts/app_store_submit.py` and is read directly — which opens a
drift hole the heredoc could not have: the workflow could stop invoking the
file, and a suite reading only the file would stay green while every release
submitted nothing. `check_workflow_invokes_script()` closes it, and runs
FIRST — the same discipline as the extractor's loud failure it replaces.

It pins the behaviours that silently produced bad releases:

  W1  the build attached is the one THIS run uploaded (matched by
      CFBundleVersion via ASC_EXPECTED_BUILD), not merely the newest VALID
      iOS build — which, while Apple processes our upload, is the PREVIOUS
      release's binary.
  W2  a 401/403 on the builds GET is reported as auth and fails immediately,
      instead of reading like "Apple is still processing" and burning the
      whole poll window; 429/5xx stay retryable.
  W5  a reviewSubmission this run created is cancelled on every post-CREATE
      failure path, and never on success.

Run:  python3 .claude/scripts/test-app-store-submit.py [repo-root]
Wired into ci.yml → repo-hygiene. The optional argument points the loader at
another checkout, which is what makes the suite mutation-testable: copy the
tree, break one guard, and this must go red.
"""
import contextlib
import io
import json
import os
import pathlib
import shutil
import sys
import tempfile
import types

REPO = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else
                    pathlib.Path(__file__).resolve().parents[2]).resolve()
WORKFLOW = REPO / ".github/workflows/app-store.yml"
SCRIPT = REPO / ".github/scripts/app_store_submit.py"
# What the workflow must contain for this suite to be testing the live path.
# Deliberately matched on the script's repo-relative path rather than the whole
# command line, so reformatting the invocation stays legal while MOVING or
# DROPPING it does not.
INVOCATION = "/.github/scripts/app_store_submit.py"


def check_workflow_invokes_script():
    """Assert app-store.yml still runs the file this suite tests.

    The heredoc this replaced could not drift from its caller: carving it out
    of the YAML proved, by construction, that the workflow contained it. A file
    on disk has no such guarantee — delete the step, rename the path, or let a
    merge drop the invocation, and a suite that only reads the file reports
    every guard green while releases silently submit nothing. That is the exact
    shape of #2731 (four releases "shipped" without reaching App Review), so it
    is checked before anything else rather than left implicit.
    """
    if not SCRIPT.is_file():
        raise SystemExit(
            f"::error::{SCRIPT} does not exist — the submit program was moved or "
            "deleted and this self-test now covers nothing."
        )
    if INVOCATION not in WORKFLOW.read_text():
        raise SystemExit(
            f"::error::{WORKFLOW} no longer invokes {INVOCATION} — the submit step "
            "was renamed, restructured or dropped. This self-test would keep "
            "passing while no release reaches App Review (#2731)."
        )


check_workflow_invokes_script()
CODE = SCRIPT.read_text()


class Response:
    """Minimal `requests.Response` stand-in."""

    def __init__(self, status, body=None, text=None):
        self.status_code = status
        self._body = {} if body is None else body
        self.text = text if text is not None else json.dumps(
            self._body if not isinstance(self._body, Exception) else {})

    def json(self):
        if isinstance(self._body, Exception):
            raise self._body
        return self._body

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


class FakeASC(types.ModuleType):
    """Routes the step's App Store Connect calls to a scenario table.

    Every call is recorded so a test can assert on what the step DID, not only
    on what it printed — the orphan-cleanup assertions rest on that.
    """

    def __init__(self, scenario):
        super().__init__("requests")
        self.scenario = scenario
        self.calls = []
        self.build_polls = 0

    def _route(self, method, url, **kw):
        self.calls.append((method, url, kw.get("json")))
        s = self.scenario
        if "/apps?filter[bundleId]" in url:
            return Response(200, {"data": [{"id": "APP1"}]})
        if "/builds?" in url:
            self.build_polls += 1
            return s["builds"](self.build_polls)
        if "appStoreVersions?filter" in url:
            return Response(200, {"data": [{"id": "VER1", "attributes": {
                "versionString": "4.26.0",
                "appStoreState": "PREPARE_FOR_SUBMISSION"}}]})
        if "/relationships/build" in url:
            return Response(204)
        if "appStoreVersionLocalizations" in url:
            if method == "GET":
                return Response(200, {"data": [
                    {"id": "LOC1", "attributes": {"locale": "en-US"}}]})
            return Response(200, {"data": {"id": "LOC1"}})
        if "/reviewSubmissions" in url and "/reviewSubmissions/" not in url:
            if method == "GET":
                # 5a's stale probe carries filter[state]; the cleanup's
                # fallback read does not. Keep them separately scriptable.
                if "filter[state]" in url:
                    return s.get("stale_list", lambda: Response(200, {"data": []}))()
                return s.get("list_probe", lambda: Response(200, {"data": []}))()
            return s.get("create", lambda: Response(201, {"data": {"id": "RS1"}}))()
        if "/reviewSubmissionItems" in url:
            return s.get("items", lambda: Response(201, {"data": {"id": "IT1"}}))()
        if "/reviewSubmissions/" in url and method == "GET":
            return s.get("probe", lambda: Response(200, {"data": {
                "id": "RS1", "attributes": {"state": "READY_FOR_REVIEW"}}}))()
        if "/reviewSubmissions/" in url and method == "PATCH":
            attrs = (kw.get("json") or {}).get("data", {}).get("attributes", {})
            if attrs.get("canceled"):
                return s.get("cancel", lambda: Response(200, {"data": {"id": "RS1"}}))()
            return s.get("submit", lambda: Response(200, {"data": {
                "id": "RS1", "attributes": {"state": "WAITING_FOR_REVIEW"}}}))()
        raise AssertionError(f"unrouted {method} {url}")

    def get(self, url, **kw):
        return self._route("GET", url, **kw)

    def post(self, url, **kw):
        return self._route("POST", url, **kw)

    def patch(self, url, **kw):
        return self._route("PATCH", url, **kw)


def builds_page(versions, with_platform=True):
    """A `GET /builds` page. `with_platform=False` simulates the service-account
    key for which `include=preReleaseVersion` resolves nothing (#2885)."""
    data, included = [], []
    for v in versions:
        entry = {"id": f"B{str(v).strip()}", "attributes": {"version": str(v)}}
        if with_platform:
            entry["relationships"] = {"preReleaseVersion": {
                "data": {"type": "preReleaseVersions", "id": f"P{v}"}}}
            included.append({"type": "preReleaseVersions", "id": f"P{v}",
                             "attributes": {"platform": "IOS"}})
        data.append(entry)
    return Response(200, {"data": data, "included": included})


def run_step(scenario, env):
    """Execute the extracted heredoc under stubs. Returns (stdout, exit, api)."""
    api = FakeASC(scenario)
    fake_jwt = types.ModuleType("jwt")
    fake_jwt.encode = lambda *a, **k: "TOKEN"

    # Delegate to the real `time` and override only sleep(): replacing the
    # module wholesale breaks stdlib importers that pull `time.monotonic`.
    import time as real_time
    fake_time = types.ModuleType("time")
    for attr in dir(real_time):
        if not attr.startswith("__"):
            setattr(fake_time, attr, getattr(real_time, attr))
    fake_time.sleep = lambda _s: None

    home = pathlib.Path(tempfile.mkdtemp())
    (home / ".private_keys").mkdir()
    (home / ".private_keys/AuthKey_KID.p8").write_text("-----FAKE KEY-----\n")

    # Mirror the job's layout: working-directory is samples/ios-demo, and the
    # CHANGELOG fallback reads `../../CHANGELOG.md`.
    root = pathlib.Path(tempfile.mkdtemp())
    workdir = root / "samples/ios-demo"
    (workdir / "distribution/app-store/en-US").mkdir(parents=True)
    notes = scenario.get("release_notes", "Polished demos and stability fixes.\n")
    if notes is not None:  # None = the file does not exist at all
        (workdir / "distribution/app-store/en-US/release_notes.txt").write_text(notes)
    (root / "CHANGELOG.md").write_text(
        scenario.get("changelog", "## v4.26.0\n\n- A user-visible change.\n"))
    (root / "gradle.properties").write_text("VERSION_NAME=4.26.0\n")

    saved_env, saved_cwd = dict(os.environ), os.getcwd()
    saved_modules = {m: sys.modules.get(m) for m in ("requests", "jwt", "time")}
    os.environ.update({"ASC_KEY_ID": "KID", "ASC_ISSUER_ID": "ISS",
                       "HOME": str(home), "GITHUB_WORKSPACE": str(root),
                       "ASC_VERSION_STRING": "v4.26.0"})
    os.environ.pop("ASC_EXPECTED_BUILD", None)
    os.environ.update(env)
    sys.modules["requests"], sys.modules["jwt"], sys.modules["time"] = api, fake_jwt, fake_time
    os.chdir(workdir)

    out, code = io.StringIO(), 0
    try:
        with contextlib.redirect_stdout(out):
            # `__file__` must be present, and must be the real path: the
            # program is now run as a FILE, so a harness without it would
            # diverge from the runtime on every `__file__`-dependent branch —
            # and there is one (the GITHUB_WORKSPACE fallback in the version
            # resolution). Omitting it is what let that branch stay untested
            # while it was a guaranteed NameError in the old heredoc form.
            exec(compile(CODE, str(SCRIPT), "exec"),
                 {"__name__": "__main__", "__file__": str(SCRIPT)})
    except SystemExit as e:
        code = e.code or 0
    except BaseException as e:  # noqa: BLE001 — a crash is a test result here
        code = f"EXC {type(e).__name__}: {e}"
    finally:
        os.chdir(saved_cwd)
        os.environ.clear()
        os.environ.update(saved_env)
        # Restore rather than pop: popping discards the real module instead of
        # giving the process its state back.
        for mod, original in saved_modules.items():
            if original is None:
                sys.modules.pop(mod, None)
            else:
                sys.modules[mod] = original
        # Remove the sandboxes. One of them holds a file literally named
        # `.private_keys/AuthKey_KID.p8` (contents: a placeholder), and a
        # secret scanner or a `find ~ -name 'AuthKey_*.p8'` sweep on a dev Mac
        # should not trip over 44 stray copies of it per run.
        for tree in (home, root):
            shutil.rmtree(tree, ignore_errors=True)
    return out.getvalue(), code, api


FAILURES = []


def check(name, ok, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"\n        {detail}" if not ok and detail else ""))
    if not ok:
        FAILURES.append(name)


def cancellations(api):
    return [c for c in api.calls
            if c[0] == "PATCH" and "/reviewSubmissions/" in c[1]
            and ((c[2] or {}).get("data", {}).get("attributes", {}).get("canceled"))]


def main():
    print(f"Self-testing the submit program at {SCRIPT.relative_to(REPO) if SCRIPT.is_relative_to(REPO) else SCRIPT}")
    print(f"({len(CODE.splitlines())} lines of Python)\n")

    # ── W1 — attach the build THIS run uploaded ───────────────────────────
    print("W1 — build-number match")
    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300, 1299] if n >= 3 else [1299])},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("keeps polling for our build instead of attaching the older VALID one",
          "Selected iOS build: 1300" in out and api.build_polls == 3, out[-400:])
    check("never selects the stale 1299", "Selected iOS build: 1299" not in out)

    out, code, api = run_step({"builds": lambda n: builds_page([1299])},
                              {"ASC_EXPECTED_BUILD": "1300"})
    check("fails loudly when our build never turns VALID",
          code == 1 and "never turned VALID" in out, f"code={code}")

    out, code, api = run_step({"builds": lambda n: builds_page([1300, 1299])}, {})
    check("without ASC_EXPECTED_BUILD the newest-VALID behaviour stands, flagged",
          "Selected iOS build: 1300" in out and "ASC_EXPECTED_BUILD is not set" in out)

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1299, 1300], with_platform=False)},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("unresolvable platform → matches by build number, not by recency",
          "Selected iOS build: 1300" in out and "by build-number match" in out, out[-400:])

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1299, 1298], with_platform=False)}, {})
    check("unresolvable platform without the pin → #2885 fallback preserved",
          "Selected iOS build: 1299" in out and "platform filter inactive" in out)

    out, code, api = run_step({"builds": lambda n: builds_page([" 1300 "])},
                              {"ASC_EXPECTED_BUILD": "1300"})
    check("build-number comparison tolerates surrounding whitespace",
          "Selected iOS build" in out, f"code={code}")

    # Exercises the numeric leg of _same_build specifically — the textual
    # `.strip()` comparison alone cannot match a zero-padded number, so this is
    # the only case that would redden if `int(a) == int(b)` were dropped.
    out, code, api = run_step({"builds": lambda n: builds_page(["01300"])},
                              {"ASC_EXPECTED_BUILD": "1300"})
    check("build-number comparison tolerates zero-padding (numeric compare)",
          "Selected iOS build: 01300" in out, f"code={code} {out[-200:]}")

    # ── W2 — auth vs transient on the builds GET ──────────────────────────
    print("\nW2 — auth vs transient classification")
    out, code, api = run_step(
        {"builds": lambda n: Response(401, {"errors": [{"title": "expired token"}]})},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("401 fails on the first attempt, not after the full poll window",
          code == 1 and api.build_polls == 1, f"code={code} polls={api.build_polls}")
    check("401 is diagnosed as auth, not as Apple processing",
          "authentication/permission" in out and "processing overran" not in out, out[-300:])

    out, code, api = run_step({"builds": lambda n: Response(403, {"errors": []})},
                              {"ASC_EXPECTED_BUILD": "1300"})
    check("403 fails on the first attempt",
          code == 1 and api.build_polls == 1, f"code={code} polls={api.build_polls}")

    out, code, api = run_step(
        {"builds": lambda n: Response(503) if n < 3 else builds_page([1300])},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("5xx is transient → retried, then succeeds",
          "Selected iOS build: 1300" in out and api.build_polls == 3, out[-300:])

    out, code, api = run_step(
        {"builds": lambda n: Response(429) if n < 2 else builds_page([1300])},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("429 is transient → retried", "Selected iOS build: 1300" in out, out[-300:])

    out, code, api = run_step(
        {"builds": lambda n: Response(400, {"errors": [{"title": "bad filter"}]})},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("a non-retryable 4xx fails on the first attempt",
          code == 1 and api.build_polls == 1, f"code={code} polls={api.build_polls}")

    html = Response(200, ValueError("not json"), text="<html>502</html>")
    out, code, api = run_step(
        {"builds": lambda n: html if n < 2 else builds_page([1300])},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("a non-JSON 200 is retried, not raised out of the step",
          "Selected iOS build: 1300" in out, f"code={code}")

    # ── W5 — orphan reviewSubmission cleanup ──────────────────────────────
    print("\nW5 — orphan reviewSubmission cleanup")
    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]),
         "items": lambda: Response(409, {"errors": [{"code": "STATE_ERROR"}]})},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("reviewSubmissionItems 409 → the submission this run created is cancelled",
          len(cancellations(api)) == 1, f"cancellations={len(cancellations(api))}")
    check("reviewSubmissionItems 409 → the step still exits non-zero",
          code == 1, f"code={code}")

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]), "submit": lambda: Response(409, {})},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("submit PATCH failure → the submission is cancelled",
          len(cancellations(api)) == 1, f"cancellations={len(cancellations(api))}")

    def transport_error():
        raise ConnectionError("connection reset")

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]), "items": transport_error},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("a transport error after CREATE → the submission is cancelled",
          len(cancellations(api)) == 1, f"cancellations={len(cancellations(api))}")

    out, code, api = run_step({"builds": lambda n: builds_page([1300])},
                              {"ASC_EXPECTED_BUILD": "1300"})
    check("a SUCCESSFUL submission is never cancelled",
          len(cancellations(api)) == 0 and code == 0,
          f"cancellations={len(cancellations(api))} code={code}")
    check("the success path still reports the review state",
          "Successfully submitted" in out, out[-300:])

    # A 200 whose body cannot be read is a REPORTING problem, not a release
    # failure: the submission is live. Asserting only "not cancelled" (as this
    # check first did) missed that the step still went RED on a successful
    # submission — and a red submit cannot be re-run, because the same
    # github.run_number re-archives a duplicate CFBundleVersion (#2963 review).
    for shape, body in (("non-JSON", ValueError("truncated body")),
                        ("no data key", {}),
                        ("data: null", {"data": None}),
                        ("no attributes", {"data": {"id": "RS1"}})):
        out, code, api = run_step(
            {"builds": lambda n: builds_page([1300]),
             "submit": (lambda b: (lambda: Response(200, b)))(body)},
            {"ASC_EXPECTED_BUILD": "1300"})
        check(f"a submitted-but-unreadable response ({shape}) is neither cancelled nor failed",
              len(cancellations(api)) == 0 and code == 0 and "Successfully submitted" in out,
              f"cancellations={len(cancellations(api))} code={code}")

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]),
         "items": lambda: Response(409, {}),
         "cancel": lambda: Response(401, {"errors": []})},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("a failing cleanup never masks the failure that triggered it",
          code == 1 and "reviewSubmissionItems CREATE failed" in out
          and "cancel it by hand" in out, out[-400:])

    # The one window where cancelling would be WORSE than the orphan: the
    # submit PATCH may have reached Apple before the response was lost, so
    # cleanup must read the state back instead of assuming failure.
    def lost_response():
        raise ConnectionError("read timeout after the request was sent")

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]), "submit": lost_response,
         "probe": lambda: Response(200, {"data": {"id": "RS1", "attributes": {
             "state": "WAITING_FOR_REVIEW"}}})},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("a lost submit response with a LIVE submission is never withdrawn",
          len(cancellations(api)) == 0 and "NOT cancelling" in out,
          f"cancellations={len(cancellations(api))} {out[-400:]}")

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]), "submit": lost_response,
         "probe": lambda: Response(200, {"data": {"id": "RS1", "attributes": {
             "state": "READY_FOR_REVIEW"}}})},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("a lost submit response that provably never landed IS cleaned up",
          len(cancellations(api)) == 1, f"cancellations={len(cancellations(api))} {out[-300:]}")

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]), "submit": lost_response,
         "probe": lambda: Response(403, {"errors": []})},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("an unreadable state after a lost response leaves the submission alone",
          len(cancellations(api)) == 0 and "NOT cancelling" in out,
          f"cancellations={len(cancellations(api))} {out[-400:]}")

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]), "submit": lost_response,
         "probe": lost_response},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("a raising state probe does not crash the cleanup",
          len(cancellations(api)) == 0 and code == "EXC ConnectionError: read timeout after the request was sent",
          f"cancellations={len(cancellations(api))} code={code}")

    # An ANSWERED 5xx/408 is as ambiguous as no answer at all — a gateway can
    # lose the response to a write Apple already committed — so it must reach
    # the probe too. Before this was measured, all four statuses withdrew a
    # live submission (found in this PR's security review).
    live = lambda: Response(200, {"data": {"id": "RS1", "attributes": {
        "state": "WAITING_FOR_REVIEW"}}})
    for status in (500, 502, 503, 504, 408):
        out, code, api = run_step(
            {"builds": lambda n: builds_page([1300]),
             "submit": (lambda s: (lambda: Response(s, {"errors": []})))(status),
             "probe": live},
            {"ASC_EXPECTED_BUILD": "1300"})
        check(f"an answered {status} consults the probe and spares a live submission",
              len(cancellations(api)) == 0 and "NOT cancelling" in out,
              f"cancellations={len(cancellations(api))}")

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]),
         "submit": lambda: Response(504, {"errors": []}),
         "probe": lambda: Response(200, {"data": {"id": "RS1", "attributes": {
             "state": "READY_FOR_REVIEW"}}})},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("an answered 504 over a NOT-submitted record still cleans it up",
          len(cancellations(api)) == 1, f"cancellations={len(cancellations(api))}")

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]),
         "submit": lambda: Response(429, {"errors": []}), "probe": live},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("a 429 is a real answer (not processed) → cancel without probing",
          len(cancellations(api)) == 1
          and not [c for c in api.calls if c[0] == "GET" and "/reviewSubmissions/" in c[1]],
          f"cancellations={len(cancellations(api))}")

    # The by-id read has never been measured against this service-account key
    # (#1831 found several ASC reads 403/404 for it), so an unreadable by-id
    # must fall back to the app→reviewSubmissions list, which 5a and
    # store-preflight.sh do exercise in production.
    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]), "submit": lost_response,
         "probe": lambda: Response(403, {"errors": []}),
         "list_probe": lambda: Response(200, {"data": [
             {"id": "RS1", "attributes": {"state": "WAITING_FOR_REVIEW"}}]})},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("by-id read refused → the list fallback still spares a live submission",
          len(cancellations(api)) == 0 and "falling back" in out,
          f"cancellations={len(cancellations(api))} {out[-300:]}")

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]), "submit": lost_response,
         "probe": lambda: Response(403, {"errors": []}),
         "list_probe": lambda: Response(200, {"data": [
             {"id": "RS1", "attributes": {"state": "READY_FOR_REVIEW"}}]})},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("by-id read refused → the list fallback still cleans a real orphan",
          len(cancellations(api)) == 1, f"cancellations={len(cancellations(api))}")

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]), "submit": lost_response,
         "probe": lambda: Response(403, {"errors": []}),
         "list_probe": lambda: Response(200, {"data": [
             {"id": "OTHER", "attributes": {"state": "READY_FOR_REVIEW"}}]})},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("the list fallback matches on OUR id, never a stranger's submission",
          len(cancellations(api)) == 0, f"cancellations={len(cancellations(api))}")

    # A 2xx CREATE whose body cannot be read leaves a submission this run can
    # never identify — the one orphan the cleanup structurally cannot reach.
    for shape, body in (("non-JSON", ValueError("not json")),
                        ("no data key", {}),
                        ("data: null", {"data": None}),
                        ("data as a list", {"data": []}),
                        ("no id", {"data": {}})):
        out, code, api = run_step(
            {"builds": lambda n: builds_page([1300]),
             "create": (lambda b: (lambda: Response(201, b)))(body)},
            {"ASC_EXPECTED_BUILD": "1300"})
        check(f"an unreadable CREATE body ({shape}) fails loudly, cancels nothing",
              code == 1 and "unreadable body" in out and len(cancellations(api)) == 0,
              f"code={code} {out[-200:]}")

    # A submit PATCH that was ANSWERED with a rejection is unambiguous — no
    # probe, cancel straight away (the W5 path proper).
    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]), "submit": lambda: Response(409, {}),
         "probe": lambda: Response(200, {"data": {"id": "RS1", "attributes": {
             "state": "WAITING_FOR_REVIEW"}}})},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("an answered rejection is cancelled without consulting the probe",
          len(cancellations(api)) == 1
          and not [c for c in api.calls if c[0] == "GET" and "/reviewSubmissions/" in c[1]],
          f"cancellations={len(cancellations(api))}")

    # A transport error is as fatal as a rejection, and the operator reads the
    # log, not the exit code: the FAILED banner has to fire for a raw exception
    # too. Catching only SystemExit left exactly these failures undiagnosed
    # (#2963 review). Asserted on both legs that can still be in flight.
    class Transport(Exception):
        pass

    def boom():
        raise Transport("connection reset by peer")

    for leg, scenario in (("items POST", {"items": boom}),
                          ("submit PATCH", {"submit": boom})):
        out, code, api = run_step(
            {"builds": lambda n: builds_page([1300]), **scenario},
            {"ASC_EXPECTED_BUILD": "1300"})
        check(f"a transport error on the {leg} still prints the FAILED banner",
              "review submission FAILED" in out and str(code).startswith("EXC Transport"),
              f"code={code} {out[-300:]}")

    # ── whatsNew sourcing (#2893 W4, shipped in #2908) ────────────────────
    print("\nwhatsNew sourcing")
    out, code, api = run_step({"builds": lambda n: builds_page([1300])},
                              {"ASC_EXPECTED_BUILD": "1300"})
    check("release_notes.txt is the whatsNew source",
          "whatsNew sourced from release_notes.txt" in out, out[-300:])

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]), "release_notes": None},
        {"ASC_EXPECTED_BUILD": "1300"})
    # Assert on the localization PATCH's field summary (`whatsNew=NNNc`), which
    # is printed ONLY when attrs["whatsNew"] was actually set. Asserting on the
    # bare word "whatsNew" would be vacuous: it also appears in the warning the
    # step emits when the field is left EMPTY — i.e. the check would pass on the
    # exact #2893 W4 defect it is named after (caught in this PR's review).
    check("without the file, the CHANGELOG fallback still fills whatsNew (#2908)",
          "whatsNew=" in out, out[-300:])

    # Both sources empty is the only state that leaves the required field
    # blank — and the warning has to name which one is missing, since "No
    # release_notes.txt" sent a reader hunting for an absent file that was
    # actually present but blank (#2908 review nit).
    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]),
         "release_notes": "   \n\n", "changelog": "## v4.25.0\n\n- Older.\n"},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("a blank release_notes.txt is reported as blank, not as absent",
          "exists but is empty" in out and "No release_notes.txt" not in out,
          out[out.find("::warning"):][:300] if "::warning" in out else out[-300:])

    out, code, api = run_step(
        {"builds": lambda n: builds_page([1300]),
         "release_notes": None, "changelog": "## v4.25.0\n\n- Older.\n"},
        {"ASC_EXPECTED_BUILD": "1300"})
    check("an absent release_notes.txt is still reported as absent",
          "No release_notes.txt" in out and "exists but is empty" not in out,
          out[out.find("::warning"):][:300] if "::warning" in out else out[-300:])

    # ── versionString fallback on a dispatch (#3146) ──────────────────────
    # A tag push sets ASC_VERSION_STRING from the tag; a workflow_dispatch
    # leaves it empty and the program is documented to fall back to
    # gradle.properties' VERSION_NAME. That fallback was dead code for as long
    # as this ran from a heredoc: its first line is
    #
    #     os.environ.get("GITHUB_WORKSPACE", os.path.join(os.path.dirname(__file__), ...))
    #
    # and Python evaluates a `.get()` default EAGERLY — so `__file__`, absent
    # from a program fed on stdin, raised NameError on every dispatch even
    # though GITHUB_WORKSPACE was set. Nothing exercised it: every scenario
    # above passes ASC_VERSION_STRING. Extracting the program to a file (#3146)
    # gave it a `__file__` and made the branch live, so it gets a test.
    print("\nversionString fallback (dispatch path)")
    out, code, api = run_step({"builds": lambda n: builds_page([1300])},
                              {"ASC_EXPECTED_BUILD": "1300",
                               "ASC_VERSION_STRING": ""})
    check("an empty ASC_VERSION_STRING resolves VERSION_NAME from gradle.properties",
          "Target App Store versionString: 4.26.0" in out, out[-400:])
    check("the dispatch path does not crash resolving the workspace",
          not str(code).startswith("EXC NameError"), f"code={code}")

    print()
    if FAILURES:
        print(f"::error::{len(FAILURES)} failure(s): " + "; ".join(FAILURES))
        return 1
    print("ALL GREEN — the submit program behaves as specified (#2893)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
