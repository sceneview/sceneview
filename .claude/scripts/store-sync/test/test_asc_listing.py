"""Offline unit tests for asc_listing.py's pure helpers (#2612 P2 Phase A).

No network, no PyJWT/requests — run via test-store-sync.sh or
`python3 -m unittest discover .claude/scripts/store-sync/test`.
"""

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import asc_listing as al  # noqa: E402


class ReadMetadataFieldsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.meta = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_reads_and_caps(self):
        (self.meta / "promotional_text.txt").write_text("p" * 200)
        (self.meta / "keywords.txt").write_text("ar,3d\n")
        fields, notes = al.read_metadata_fields(self.meta)
        self.assertEqual(fields["keywords"], "ar,3d")
        self.assertEqual(len(fields["promotionalText"]), 170)
        self.assertTrue(fields["promotionalText"].endswith("…"))
        self.assertEqual(len(notes), 1)

    def test_missing_files_skipped(self):
        fields, notes = al.read_metadata_fields(self.meta)
        self.assertEqual(fields, {})
        self.assertEqual(notes, [])


class DiffFieldsTest(unittest.TestCase):
    def test_no_drift_with_remote_whitespace(self):
        self.assertEqual(
            al.diff_fields({"description": "Hello"}, {"description": "Hello\n"}), [])

    def test_differs(self):
        drift = al.diff_fields({"keywords": "a,b"}, {"keywords": "a,c"})
        self.assertEqual(len(drift), 1)
        self.assertIn("keywords differs", drift[0])

    def test_empty_remote_is_drift(self):
        drift = al.diff_fields({"promotionalText": "x"}, {"promotionalText": ""})
        self.assertEqual(len(drift), 1)
        self.assertIn("empty on the live listing", drift[0])

    def test_unmanaged_field_ignored(self):
        self.assertEqual(al.diff_fields({}, {"description": "live-only"}), [])


class DiffScreenshotsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        d = pathlib.Path(self.tmp.name)
        self.f1 = d / "01-explore.png"
        self.f2 = d / "02-samples.png"
        self.f1.write_bytes(b"one")
        self.f2.write_bytes(b"two")

    def tearDown(self):
        self.tmp.cleanup()

    def test_identical_no_drift(self):
        sums = [al.md5_of(self.f1), al.md5_of(self.f2)]
        self.assertEqual(
            al.diff_screenshots("iphone-6.9", "APP_IPHONE_67", [self.f1, self.f2], sums), [])

    def test_never_uploaded_baseline(self):
        drift = al.diff_screenshots("iphone-6.9", "APP_IPHONE_67", [self.f1, self.f2], [])
        self.assertEqual(len(drift), 1)
        self.assertIn("never uploaded", drift[0])

    def test_order_mismatch(self):
        sums = [al.md5_of(self.f2), al.md5_of(self.f1)]
        drift = al.diff_screenshots("ipad-13", "APP_IPAD_PRO_3GEN_129",
                                    [self.f1, self.f2], sums)
        self.assertEqual(len(drift), 1)
        self.assertIn("ORDER", drift[0])

    def test_missing_and_extra(self):
        drift = al.diff_screenshots("iphone-6.9", "APP_IPHONE_67",
                                    [self.f1], ["f" * 32])
        self.assertEqual(len(drift), 1)
        self.assertIn("01-explore.png", drift[0])
        self.assertIn("1 live screenshot(s) not in the repo", drift[0])


class PlanScreenshotSyncTest(unittest.TestCase):
    """Phase B: what one display type needs before any network call."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        d = pathlib.Path(self.tmp.name)
        self.f1 = d / "01-a.png"
        self.f2 = d / "02-b.png"
        self.f1.write_bytes(b"one")
        self.f2.write_bytes(b"two")
        self.m1 = al.md5_of(self.f1)
        self.m2 = al.md5_of(self.f2)

    def tearDown(self):
        self.tmp.cleanup()

    def test_identical_in_order_skips(self):
        action, delete, uploads = al.plan_screenshot_sync(
            [self.f1, self.f2],
            [{"id": "A", "checksum": self.m1}, {"id": "B", "checksum": self.m2}])
        self.assertEqual(action, "skip")
        self.assertEqual((delete, uploads), ([], []))

    def test_same_content_wrong_order_replaces(self):
        # Order is user-visible on the App Store, so a reorder is a change.
        action, delete, uploads = al.plan_screenshot_sync(
            [self.f1, self.f2],
            [{"id": "A", "checksum": self.m2}, {"id": "B", "checksum": self.m1}])
        self.assertEqual(action, "replace")
        self.assertEqual(delete, ["A", "B"])
        self.assertEqual([u.name for u in uploads], ["01-a.png", "02-b.png"])

    def test_empty_live_set_uploads_everything(self):
        action, delete, uploads = al.plan_screenshot_sync([self.f1, self.f2], [])
        self.assertEqual(action, "replace")
        self.assertEqual(delete, [])
        self.assertEqual(len(uploads), 2)

    def test_extra_live_screenshot_is_deleted(self):
        action, delete, uploads = al.plan_screenshot_sync(
            [self.f1],
            [{"id": "A", "checksum": self.m1}, {"id": "STALE", "checksum": "f" * 32}])
        self.assertEqual(action, "replace")
        self.assertIn("STALE", delete)


class UploadOperationsTest(unittest.TestCase):
    """Chunk slicing is pure arithmetic — pin it offline, not against Apple."""

    def test_chunks_reassemble_to_the_original_bytes(self):
        blob = bytes(range(256))
        ops = [{"method": "PUT", "url": "u1", "offset": 0, "length": 100,
                "requestHeaders": [{"name": "Content-Type", "value": "image/png"}]},
               {"method": "PUT", "url": "u2", "offset": 100, "length": 156,
                "requestHeaders": []}]
        reqs = al.upload_operations_to_requests(blob, ops)
        self.assertEqual(b"".join(r["body"] for r in reqs), blob)
        self.assertEqual(reqs[0]["headers"], {"Content-Type": "image/png"})
        self.assertEqual(reqs[1]["url"], "u2")

    def test_missing_offset_and_length_default_to_whole_file(self):
        blob = b"abcdef"
        reqs = al.upload_operations_to_requests(blob, [{"url": "u"}])
        self.assertEqual(reqs[0]["body"], blob)
        self.assertEqual(reqs[0]["method"], "PUT")

    def test_no_operations_is_empty_not_a_crash(self):
        self.assertEqual(al.upload_operations_to_requests(b"x", None), [])
        self.assertEqual(al.upload_operations_to_requests(b"x", []), [])

    def test_headers_without_a_name_are_dropped(self):
        reqs = al.upload_operations_to_requests(
            b"x", [{"url": "u", "requestHeaders": [{"value": "orphan"}]}])
        self.assertEqual(reqs[0]["headers"], {})


class _StubResponse:
    def __init__(self, payload, status_code=200):
        self._payload = payload
        self.status_code = status_code

    def json(self):
        return self._payload


class _StubRequests:
    """Minimal stand-in for the `requests` module: replays queued responses."""

    def __init__(self, responses):
        self._responses = list(responses)
        self.calls = 0

    def get(self, url, headers=None):
        self.calls += 1
        return self._responses.pop(0)


def _delivery(state, errors=None):
    attrs = {"assetDeliveryState": {"state": state}}
    if errors is not None:
        attrs["assetDeliveryState"]["errors"] = errors
    return _StubResponse({"data": {"attributes": attrs}})


class AwaitDeliveryTest(unittest.TestCase):
    """Apple's verdict must never be reported as a successful upload.

    The first implementation returned one overloaded string and the caller
    tested `state == "FAILED"`. A REJECTED asset comes back as FAILED *with*
    an errors payload — rendered "FAILED errors=[…]", never equal to
    "FAILED" — so a wrong-dimension PNG was reported as uploaded and the job
    exited 0, with the previous set already deleted (PR #2781 review)."""

    def test_complete_is_ok(self):
        req = _StubRequests([_delivery("COMPLETE")])
        ok, detail = al._await_delivery(req, {}, "id", "01.png")
        self.assertTrue(ok)
        self.assertEqual(detail, "COMPLETE")

    def test_bare_failed_is_fatal(self):
        ok, detail = al._await_delivery(_StubRequests([_delivery("FAILED")]), {}, "id", "01.png")
        self.assertFalse(ok)
        self.assertEqual(detail, "FAILED")

    def test_failed_with_errors_payload_is_fatal(self):
        # THE regression: the realistic rejection shape.
        req = _StubRequests([_delivery("FAILED", [{"code": "IMAGE_WRONG_DIMENSIONS"}])])
        ok, detail = al._await_delivery(req, {}, "id", "01.png")
        self.assertFalse(ok, "a rejected screenshot must not count as uploaded")
        self.assertIn("IMAGE_WRONG_DIMENSIONS", detail)

    def test_errors_on_a_non_failed_state_is_still_fatal(self):
        req = _StubRequests([_delivery("COMPLETE", [{"code": "SOMETHING"}])])
        ok, _ = al._await_delivery(req, {}, "id", "01.png")
        self.assertFalse(ok)

    def test_poll_http_error_is_fatal(self):
        # e.g. the JWT expiring mid-run — not evidence of success.
        req = _StubRequests([_StubResponse({}, status_code=401)])
        ok, detail = al._await_delivery(req, {}, "id", "01.png")
        self.assertFalse(ok)
        self.assertIn("401", detail)

    def test_still_processing_times_out_as_non_fatal(self):
        req = _StubRequests([_delivery("UPLOAD_COMPLETE") for _ in range(3)])
        ok, detail = al._await_delivery(req, {}, "id", "01.png", attempts=3, delay=0)
        self.assertTrue(ok, "still processing is not evidence of failure")
        self.assertIn("still processing", detail)
        self.assertEqual(req.calls, 3)

    def test_polls_until_terminal(self):
        req = _StubRequests([_delivery("AWAITING_UPLOAD"), _delivery("COMPLETE")])
        ok, _ = al._await_delivery(req, {}, "id", "01.png", attempts=5, delay=0)
        self.assertTrue(ok)
        self.assertEqual(req.calls, 2)


class WriteFlagSafetyTest(unittest.TestCase):
    """A near-miss flag must never resolve to the App Store write path.

    argparse expands unambiguous prefixes by default, so without
    allow_abbrev=False `--apply` (the sibling play_listing.py's real flag)
    would upload screenshots."""

    def test_abbreviations_are_rejected(self):
        for flag in ("--apply", "--appl", "--apply-screenshot"):
            with self.subTest(flag=flag):
                self.assertEqual(al.main([flag]), 2)

    def test_read_and_write_modes_are_mutually_exclusive(self):
        self.assertEqual(al.main(["--dry-run", "--apply-screenshots"]), 2)

    def test_fail_on_drift_is_refused_on_the_write_path(self):
        # Silently ignoring a requested flag is the same class of defect as
        # expanding an abbreviation into a write.
        self.assertEqual(al.main(["--apply-screenshots", "--fail-on-drift"]), 2)


class ResolveAscCredentialsTest(unittest.TestCase):
    def test_content_env_with_canonical_names(self):
        creds = al.resolve_asc_credentials({
            "APP_STORE_CONNECT_KEY_ID": "K1",
            "APP_STORE_CONNECT_ISSUER_ID": "I1",
            "APP_STORE_CONNECT_API_KEY": "PEM",
        })
        self.assertEqual(creds, ("K1", "I1", "PEM"))

    def test_store_preflight_alias_set(self):
        creds = al.resolve_asc_credentials({
            "ASC_KEY_ID": "K2", "ASC_ISSUER_ID": "I2", "ASC_API_KEY": "PEM2",
        })
        self.assertEqual(creds, ("K2", "I2", "PEM2"))

    def test_path_env(self):
        with tempfile.NamedTemporaryFile("w", suffix=".p8") as f:
            f.write("PEM-FROM-PATH")
            f.flush()
            creds = al.resolve_asc_credentials({
                "APP_STORE_CONNECT_KEY_ID": "K",
                "APP_STORE_CONNECT_ISSUER_ID": "I",
                "APP_STORE_CONNECT_API_KEY_PATH": f.name,
            })
        self.assertEqual(creds, ("K", "I", "PEM-FROM-PATH"))

    def test_private_keys_home_fallback(self):
        with tempfile.TemporaryDirectory() as home:
            keys = pathlib.Path(home) / ".private_keys"
            keys.mkdir()
            (keys / "AuthKey_KH.p8").write_text("PEM-HOME")
            creds = al.resolve_asc_credentials(
                {"APP_STORE_CONNECT_KEY_ID": "KH",
                 "APP_STORE_CONNECT_ISSUER_ID": "I"},
                home=home)
        self.assertEqual(creds, ("KH", "I", "PEM-HOME"))

    def test_missing_ids_or_key(self):
        self.assertIsNone(al.resolve_asc_credentials({}))
        with tempfile.TemporaryDirectory() as home:
            self.assertIsNone(al.resolve_asc_credentials(
                {"APP_STORE_CONNECT_KEY_ID": "K",
                 "APP_STORE_CONNECT_ISSUER_ID": "I"},
                home=home))


class DisplayTypeMapTest(unittest.TestCase):
    """Every committed screenshot dir must have an ASC display-type mapping —
    a new capture-script device class can't silently escape the drift check."""

    def test_committed_dirs_are_all_mapped(self):
        repo_root = pathlib.Path(__file__).resolve().parents[4]
        shots = repo_root / al.DEFAULT_SCREENSHOTS_DIR
        self.assertTrue(shots.is_dir(), f"missing {shots}")
        committed = {d.name for d in shots.iterdir()
                     if d.is_dir() and list(d.glob("*.png"))}
        unmapped = committed - set(al.DISPLAY_TYPE_MAP)
        self.assertFalse(
            unmapped,
            f"screenshot dir(s) {sorted(unmapped)} have no DISPLAY_TYPE_MAP entry")

    def test_metadata_dir_exists(self):
        repo_root = pathlib.Path(__file__).resolve().parents[4]
        self.assertTrue((repo_root / al.DEFAULT_METADATA_DIR).is_dir())


if __name__ == "__main__":
    unittest.main()
