"""Offline tests for app_store_submit.py — no network, no secrets.

Covers the version-record guards and the review submission (section 5).

Run: python3 -m unittest discover -s .github/scripts -p 'test_app_store_*.py'

app_store_submit.py is a top-level program, not a library, so each case runs
the whole file with runpy against stub `jwt` and `requests` modules that answer
App Store Connect's calls from a table. Every request is recorded, which is how
a case proves that nothing was renamed, cancelled or created.
"""
import contextlib
import io
import os
import pathlib
import runpy
import sys
import tempfile
import types
import unittest
from unittest import mock

SCRIPT = pathlib.Path(__file__).resolve().parent / "app_store_submit.py"
BUILD = "202609280405"


class _Response:
    def __init__(self, status, body=None):
        self.status_code = status
        self._body = body if body is not None else {}
        self.text = str(self._body)

    def json(self):
        return self._body

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


def _version(vid, vs, state):
    return {"id": vid, "type": "appStoreVersions",
            "attributes": {"versionString": vs, "appStoreState": state}}


class SubmitScriptTest(unittest.TestCase):
    def run_submit(self, editable, holders, version="v4.47.0", platform=None, bodies=None):
        """Run app_store_submit.py; return (exit code, [(method, url)], step summary).

        `holders` answers the slot-holder probe. A tuple of lists answers
        successive probes in order (the last one repeats), which is how a case
        makes the slot fill between the look-before-create and the POST.
        `platform` sets ASC_PLATFORM (unset by default, which means IOS), and
        `bodies`, when given, collects the JSON of every POST.
        """
        calls = []
        probes = list(holders) if isinstance(holders, tuple) else [holders]

        def get(url, headers=None, **_):
            calls.append(("GET", url))
            if "/apps?filter[bundleId]" in url:
                return _Response(200, {"data": [{"id": "APP"}]})
            if "/builds?" in url:
                return _Response(200, {
                    "data": [{"id": "B1", "attributes": {"version": BUILD},
                              "relationships": {"preReleaseVersion": {
                                  "data": {"type": "preReleaseVersions", "id": "P1"}}}}],
                    "included": [{"type": "preReleaseVersions", "id": "P1",
                                  "attributes": {"platform": platform or "IOS",
                                                 "version": "4.47.0"}}],
                })
            if "/appStoreVersions?" in url and "include=appStoreVersionSubmission" in url:
                return _Response(200, {"data": editable})
            if "/appStoreVersions?" in url and "limit=5" in url:
                return _Response(200, {"data": probes.pop(0) if len(probes) > 1 else probes[0]})
            raise AssertionError(f"unexpected GET {url}")

        def post(url, headers=None, json=None, **_):
            calls.append(("POST", url))
            if bodies is not None:
                bodies.append(json)
            if url.endswith("/appStoreVersions"):
                return _Response(409, {"errors": [{"status": "409"}]})
            raise AssertionError(f"unexpected POST {url}")

        def patch(url, headers=None, json=None, **_):
            calls.append(("PATCH", url))
            raise AssertionError(f"unexpected PATCH {url}")

        fake_requests = types.SimpleNamespace(get=get, post=post, patch=patch)
        fake_jwt = types.SimpleNamespace(encode=lambda *a, **k: "token")
        with tempfile.TemporaryDirectory() as home:
            keys = pathlib.Path(home, ".private_keys")
            keys.mkdir()
            (keys / "AuthKey_KEY.p8").write_text("not a real key")
            summary = pathlib.Path(home, "step-summary.md")
            env = {"HOME": home, "ASC_KEY_ID": "KEY", "ASC_ISSUER_ID": "ISSUER",
                   "ASC_VERSION_STRING": version, "ASC_EXPECTED_BUILD": BUILD,
                   "ASC_SUPERSEDE": "false", "GITHUB_STEP_SUMMARY": str(summary)}
            if platform:
                env["ASC_PLATFORM"] = platform
            with mock.patch.dict(os.environ, env), \
                    mock.patch.dict(sys.modules, {"requests": fake_requests, "jwt": fake_jwt}), \
                    mock.patch("time.sleep"), \
                    self.assertRaises(SystemExit) as e:
                runpy.run_path(str(SCRIPT), run_name="__main__")
            summary_text = summary.read_text() if summary.exists() else ""
        return e.exception.code, calls, summary_text

    def test_never_renames_a_record_down(self):
        # The one editable record is 4.48.0: submitting 4.47.0 must not
        # rename it to 4.47.0.
        code, calls, _ = self.run_submit(
            editable=[_version("V48", "4.48.0", "DEVELOPER_REJECTED")], holders=[])
        self.assertEqual(code, 1)
        self.assertFalse([c for c in calls if c[0] in ("PATCH", "POST")], calls)

    def test_identified_holder_defers_with_75_without_a_409(self):
        # 4.48.0 waits for review: 4.49.0 is on TestFlight and defers, and no
        # version record is POSTed for Apple to refuse.
        code, calls, summary = self.run_submit(
            editable=[], holders=[_version("V48", "4.48.0", "WAITING_FOR_REVIEW")],
            version="v4.49.0")
        self.assertEqual(code, 75)
        self.assertFalse([c for c in calls if c[0] in ("PATCH", "POST")], calls)
        self.assertIn("4.49.0 deferred", summary)
        self.assertIn("4.48.0 is WAITING_FOR_REVIEW", summary)

    def test_release_already_in_review_defers_without_a_409(self):
        code, calls, summary = self.run_submit(
            editable=[], holders=[_version("V48", "4.48.0", "WAITING_FOR_REVIEW")],
            version="v4.48.0")
        self.assertEqual(code, 75)
        self.assertFalse([c for c in calls if c[0] in ("PATCH", "POST")], calls)
        self.assertIn("already WAITING_FOR_REVIEW", summary)

    def test_slot_filled_after_the_probe_still_defers(self):
        # Free when probed, taken by the time of the POST: the 409 handler
        # names the holder and defers.
        code, calls, _ = self.run_submit(
            editable=[], holders=([], [_version("V48", "4.48.0", "IN_REVIEW")]),
            version="v4.49.0")
        self.assertEqual(code, 75)
        self.assertIn(("POST", "https://api.appstoreconnect.apple.com/v1/appStoreVersions"),
                      calls)
        self.assertFalse([c for c in calls if c[0] == "PATCH"], calls)

    def test_unidentified_409_fails(self):
        code, _, summary = self.run_submit(editable=[], holders=[])
        self.assertEqual(code, 1)
        self.assertEqual(summary, "")

    def test_holder_without_a_state_is_not_identified(self):
        code, calls, _ = self.run_submit(
            editable=[], holders=[{"id": "V", "attributes": {"versionString": "4.42.0"}}])
        self.assertEqual(code, 1)
        # Not identified before the POST either, so the POST is what fails.
        self.assertIn(("POST", "https://api.appstoreconnect.apple.com/v1/appStoreVersions"),
                      calls)


    def test_mac_run_reads_and_creates_mac_records_only(self):
        # The macOS job sets ASC_PLATFORM=MAC_OS: the build, the version
        # records it probes and the record it creates are all the Mac ones,
        # never the iOS record of the same version.
        bodies = []
        code, calls, _ = self.run_submit(editable=[], holders=[], platform="MAC_OS",
                                         bodies=bodies)
        self.assertEqual(code, 1)
        probes = [u for m, u in calls if m == "GET" and "/appStoreVersions?" in u]
        self.assertTrue(probes, calls)
        for url in probes:
            self.assertIn("filter[platform]=MAC_OS", url)
        self.assertFalse([u for _, u in calls if "IOS" in u], calls)
        created = [b for b in bodies if b and b["data"]["type"] == "appStoreVersions"]
        self.assertEqual([b["data"]["attributes"]["platform"] for b in created], ["MAC_OS"])

    def test_unknown_platform_fails_before_any_call(self):
        code, calls, _ = self.run_submit(editable=[], holders=[], platform="TV_OS")
        self.assertEqual(code, 1)
        self.assertEqual(calls, [])

API = "https://api.appstoreconnect.apple.com/v1"


def _item_409(detail_tail):
    """Apple's 409 on a review item, with the reasons where Apple puts them."""
    return _Response(409, {"errors": [{
        "status": "409", "code": "STATE_ERROR.ENTITY_STATE_INVALID",
        "title": "appStoreVersions with id '892059197' is not in valid state.",
        "detail": "This resource cannot be reviewed, please check associated errors to see why.",
        "meta": {"associatedErrors": {"/v1/appStoreVersions/892059197": [{
            "code": "ENTITY_ERROR.ATTRIBUTE.INVALID",
            "detail": "x" * 400 + detail_tail,
        }]}},
    }]})


class ReviewSubmissionTest(unittest.TestCase):
    """Section 5: the review submission, run end to end against a fake App Store Connect.

    The record is the one 4.50.0 met on 2026-09-30: a version Apple rejected,
    reused for the new release, whose rejected submission is still open
    (UNRESOLVED_ISSUES) and holds that same version.
    """

    def run_submit(self, open_subs, sub_states=None, items=(), draft_items=()):
        """Run the whole program; return (exit code, [(method, url)], stdout).

        `open_subs` answers the open-reviewSubmissions probe. `sub_states`
        maps a submission id to the states its by-id reads return in turn (the
        last repeats). `items` is the sequence of answers to the review item
        CREATE (the last repeats). `draft_items` lists the appStoreVersion ids
        already in a reused draft.
        """
        calls = []
        sub_states = {k: list(v) for k, v in (sub_states or {}).items()}
        item_answers = list(items) or [_Response(201, {"data": {"id": "ITEM"}})]

        def get(url, headers=None, **_):
            calls.append(("GET", url))
            if "/apps?filter[bundleId]" in url:
                return _Response(200, {"data": [{"id": "APP"}]})
            if "/builds?" in url:
                return _Response(200, {
                    "data": [{"id": "B1", "attributes": {"version": BUILD},
                              "relationships": {"preReleaseVersion": {
                                  "data": {"type": "preReleaseVersions", "id": "P1"}}}}],
                    "included": [{"type": "preReleaseVersions", "id": "P1",
                                  "attributes": {"platform": "IOS", "version": "4.50.0"}}],
                })
            if "/appStoreVersions?" in url and "include=appStoreVersionSubmission" in url:
                return _Response(200, {"data": [_version("V", "4.48.0", "REJECTED")]})
            if "/apps/APP/reviewSubmissions?" in url:
                return _Response(200, {"data": open_subs})
            if "/items?include=appStoreVersion" in url:
                return _Response(200, {"data": [
                    {"id": f"I{n}", "relationships": {"appStoreVersion": {
                        "data": {"type": "appStoreVersions", "id": vid}}}}
                    for n, vid in enumerate(draft_items)]})
            if url.startswith(f"{API}/reviewSubmissions/"):
                states = sub_states[url.rsplit("/", 1)[1]]
                state = states.pop(0) if len(states) > 1 else states[0]
                return _Response(200, {"data": {"attributes": {"state": state}}})
            # Localization, app info: not what these cases are about.
            return _Response(404, {"errors": [{"status": "404"}]})

        def post(url, headers=None, json=None, **_):
            calls.append(("POST", url))
            if url == f"{API}/reviewSubmissions":
                return _Response(201, {"data": {"id": "NEW"}})
            if url == f"{API}/reviewSubmissionItems":
                return item_answers.pop(0) if len(item_answers) > 1 else item_answers[0]
            raise AssertionError(f"unexpected POST {url}")

        def patch(url, headers=None, json=None, **_):
            calls.append(("PATCH", url))
            attrs = (json or {}).get("data", {}).get("attributes", {})
            if attrs.get("canceled"):
                sid = url.rsplit("/", 1)[1]
                if sid == "NEW" or sub_states.get(sid) == ["READY_FOR_REVIEW"]:
                    return _Response(409, {"errors": [{
                        "status": "409", "code": "STATE_ERROR.ENTITY_STATE_INVALID",
                        "title": "Resource state is invalid."}]})
                return _Response(200, {"data": {"attributes": {"state": "CANCELING"}}})
            if attrs.get("submitted"):
                return _Response(200, {"data": {"attributes": {"state": "WAITING_FOR_REVIEW"}}})
            return _Response(200, {"data": {}})

        fake_requests = types.SimpleNamespace(get=get, post=post, patch=patch)
        fake_jwt = types.SimpleNamespace(encode=lambda *a, **k: "token")
        out = io.StringIO()
        with tempfile.TemporaryDirectory() as home:
            keys = pathlib.Path(home, ".private_keys")
            keys.mkdir()
            (keys / "AuthKey_KEY.p8").write_text("not a real key")
            env = {"HOME": home, "ASC_KEY_ID": "KEY", "ASC_ISSUER_ID": "ISSUER",
                   "ASC_VERSION_STRING": "v4.50.0", "ASC_EXPECTED_BUILD": BUILD,
                   "ASC_SUPERSEDE": "false", "GITHUB_WORKSPACE": home,
                   "GITHUB_STEP_SUMMARY": str(pathlib.Path(home, "summary.md"))}
            code = 0
            with mock.patch.dict(os.environ, env), \
                    mock.patch.dict(sys.modules, {"requests": fake_requests, "jwt": fake_jwt}), \
                    mock.patch("time.sleep"), contextlib.redirect_stdout(out):
                try:
                    runpy.run_path(str(SCRIPT), run_name="__main__")
                except SystemExit as e:
                    code = e.code
        return code, calls, out.getvalue()

    @staticmethod
    def _index(calls, call):
        return calls.index(call)

    def test_rejected_review_is_closed_before_its_version_is_added_again(self):
        # 4.50.0 on 2026-09-30: the rejection (UNRESOLVED_ISSUES) held the very
        # version being resubmitted. The item CREATE must wait for the cancel.
        code, calls, out = self.run_submit(
            open_subs=[{"id": "OLD", "attributes": {"state": "UNRESOLVED_ISSUES"}}],
            sub_states={"OLD": ["CANCELING", "CANCELING", "COMPLETE"]})
        self.assertEqual(code, 0, out)
        reads = [i for i, c in enumerate(calls) if c == ("GET", f"{API}/reviewSubmissions/OLD")]
        self.assertEqual(len(reads), 3, calls)
        self.assertLess(self._index(calls, ("PATCH", f"{API}/reviewSubmissions/OLD")), reads[0])
        self.assertLess(reads[-1], self._index(calls, ("POST", f"{API}/reviewSubmissionItems")))
        self.assertIn("Successfully submitted for App Store review! State: WAITING_FOR_REVIEW", out)

    def test_item_409_is_retried_and_apple_reasons_are_printed_in_full(self):
        code, calls, out = self.run_submit(
            open_subs=[],
            items=[_item_409("THE-REAL-REASON"), _Response(201, {"data": {"id": "ITEM"}})])
        self.assertEqual(code, 0, out)
        self.assertEqual(calls.count(("POST", f"{API}/reviewSubmissionItems")), 2)
        # Past the 300 characters the old `text[:300]` kept.
        self.assertIn("THE-REAL-REASON", out)
        self.assertIn("/v1/appStoreVersions/892059197: ENTITY_ERROR.ATTRIBUTE.INVALID", out)
        self.assertIn("State: WAITING_FOR_REVIEW", out)

    def test_persistent_item_409_fails_with_the_reasons_and_no_hand_cleanup(self):
        code, calls, out = self.run_submit(open_subs=[], items=[_item_409("STILL-INVALID")])
        self.assertEqual(code, 1)
        self.assertEqual(calls.count(("POST", f"{API}/reviewSubmissionItems")), 6)
        self.assertIn("STILL-INVALID", out)
        # One PATCH on the new submission: the cleanup cancel, never a submit.
        self.assertEqual(calls.count(("PATCH", f"{API}/reviewSubmissions/NEW")), 1)
        self.assertIn("the next run reuses it", out)
        self.assertNotIn("cancel it by hand", out)

    def test_other_refusals_are_not_retried(self):
        code, calls, out = self.run_submit(
            open_subs=[], items=[_Response(422, {"errors": [{"status": "422", "detail": "nope"}]})])
        self.assertEqual(code, 1)
        self.assertEqual(calls.count(("POST", f"{API}/reviewSubmissionItems")), 1)
        self.assertIn("nope", out)

    def test_open_draft_is_reused_not_canceled(self):
        # Apple refuses to cancel an unsubmitted draft, and a second CREATE is
        # not the way out of that: the draft is reused.
        code, calls, out = self.run_submit(
            open_subs=[{"id": "DRAFT", "attributes": {"state": "READY_FOR_REVIEW"}}],
            sub_states={"DRAFT": ["READY_FOR_REVIEW"]})
        self.assertEqual(code, 0, out)
        self.assertNotIn(("POST", f"{API}/reviewSubmissions"), calls)
        self.assertEqual([c for c in calls if c == ("PATCH", f"{API}/reviewSubmissions/DRAFT")],
                         [("PATCH", f"{API}/reviewSubmissions/DRAFT")])  # the submit, no cancel
        self.assertIn(("POST", f"{API}/reviewSubmissionItems"), calls)

    def test_draft_already_holding_this_version_is_submitted_as_is(self):
        code, calls, out = self.run_submit(
            open_subs=[{"id": "DRAFT", "attributes": {"state": "READY_FOR_REVIEW"}}],
            sub_states={"DRAFT": ["READY_FOR_REVIEW"]}, draft_items=["V"])
        self.assertEqual(code, 0, out)
        self.assertNotIn(("POST", f"{API}/reviewSubmissionItems"), calls)
        self.assertIn("State: WAITING_FOR_REVIEW", out)


if __name__ == "__main__":
    unittest.main()
