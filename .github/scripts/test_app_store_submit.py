"""Offline tests for app_store_submit.py's version-record guards — no network, no secrets.

Run: python3 -m unittest discover -s .github/scripts -p 'test_app_store_*.py'

app_store_submit.py is a top-level program, not a library, so each case runs
the whole file with runpy against stub `jwt` and `requests` modules that answer
App Store Connect's calls from a table. Every request is recorded, which is how
a case proves that nothing was renamed, cancelled or created.
"""
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
    def run_submit(self, editable, holders, version="v4.47.0"):
        """Run app_store_submit.py; return (exit code, [(method, url)])."""
        calls = []

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
                                  "attributes": {"platform": "IOS", "version": "4.47.0"}}],
                })
            if "/appStoreVersions?" in url and "include=appStoreVersionSubmission" in url:
                return _Response(200, {"data": editable})
            if "/appStoreVersions?" in url and "limit=5" in url:
                return _Response(200, {"data": holders})
            raise AssertionError(f"unexpected GET {url}")

        def post(url, headers=None, json=None, **_):
            calls.append(("POST", url))
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
            env = {"HOME": home, "ASC_KEY_ID": "KEY", "ASC_ISSUER_ID": "ISSUER",
                   "ASC_VERSION_STRING": version, "ASC_EXPECTED_BUILD": BUILD,
                   "ASC_SUPERSEDE": "false"}
            with mock.patch.dict(os.environ, env), \
                    mock.patch.dict(sys.modules, {"requests": fake_requests, "jwt": fake_jwt}), \
                    mock.patch("time.sleep"), \
                    self.assertRaises(SystemExit) as e:
                runpy.run_path(str(SCRIPT), run_name="__main__")
        return e.exception.code, calls

    def test_never_renames_a_record_down(self):
        # The one editable record is 4.48.0: submitting 4.47.0 must not
        # rename it to 4.47.0.
        code, calls = self.run_submit(
            editable=[_version("V48", "4.48.0", "DEVELOPER_REJECTED")], holders=[])
        self.assertEqual(code, 1)
        self.assertFalse([c for c in calls if c[0] in ("PATCH", "POST")], calls)

    def test_identified_holder_defers_with_75(self):
        code, calls = self.run_submit(
            editable=[], holders=[_version("V42", "4.42.0", "WAITING_FOR_REVIEW")])
        self.assertEqual(code, 75)
        self.assertIn(("POST", "https://api.appstoreconnect.apple.com/v1/appStoreVersions"),
                      calls)
        self.assertFalse([c for c in calls if c[0] == "PATCH"], calls)

    def test_unidentified_409_fails(self):
        code, _ = self.run_submit(editable=[], holders=[])
        self.assertEqual(code, 1)

    def test_holder_without_a_state_is_not_identified(self):
        code, _ = self.run_submit(
            editable=[], holders=[{"id": "V", "attributes": {"versionString": "4.42.0"}}])
        self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()
