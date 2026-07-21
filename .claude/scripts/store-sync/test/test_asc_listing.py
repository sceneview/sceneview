"""Offline unit tests for asc_listing.py (#2612 P2, Phases A+B).

No network, no PyJWT/requests: the pure helpers are imported directly, and
the parts that do talk to Apple (_await_delivery, _upload_one) are driven
through stubbed request objects. Run via test-store-sync.sh or
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

    def get(self, url, headers=None, timeout=None):
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


class UploadWithoutOperationsTest(unittest.TestCase):
    """A reserve that returns no uploadOperations must not be committed.

    Reproduced in review (PR #2781): zero chunk PUTs, yet the commit PATCH
    marked the asset uploaded and the run reported "6 screenshot(s) uploaded"
    — with the previous set already deleted, leaving the display type empty."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.png = pathlib.Path(self.tmp.name) / "01.png"
        self.png.write_bytes(b"not-actually-a-png")

    def tearDown(self):
        self.tmp.cleanup()

    def test_reserve_without_operations_raises(self):
        class _Req:
            def post(self, url, headers=None, json=None, timeout=None):
                return _StubResponse({"data": {"id": "NEW1", "attributes": {}}}, 201)

        with self.assertRaises(RuntimeError) as ctx:
            al._upload_one(_Req(), {}, "SET1", self.png)
        self.assertIn("no uploadOperations", str(ctx.exception))

    def test_empty_file_is_not_forced_to_raise(self):
        # A zero-byte file has nothing to send; the guard keys on `blob`.
        empty = pathlib.Path(self.tmp.name) / "empty.png"
        empty.write_bytes(b"")

        class _Req:
            def post(self, url, headers=None, json=None, timeout=None):
                return _StubResponse({"data": {"id": "N", "attributes": {}}}, 201)

            def patch(self, url, headers=None, json=None, timeout=None):
                return _StubResponse({"data": {"attributes": {"sourceFileChecksum": al.md5_of(empty)}}}, 200)

        shot_id, local_md5, echoed = al._upload_one(_Req(), {}, "SET1", empty)
        self.assertEqual(shot_id, "N")
        self.assertEqual(local_md5, echoed)


class ScreenshotCapTest(unittest.TestCase):
    """Apple caps a set at 10; the destructive step must not start otherwise."""

    def test_cap_constant_matches_apples_limit(self):
        self.assertEqual(al.MAX_SCREENSHOTS_PER_SET, 10)

    def test_committed_dirs_are_within_the_cap(self):
        repo_root = pathlib.Path(__file__).resolve().parents[4]
        shots = repo_root / al.DEFAULT_SCREENSHOTS_DIR
        for device_dir in al.DISPLAY_TYPE_MAP:
            d = shots / device_dir
            if not d.is_dir():
                continue
            count = len(list(d.glob("*.png")))
            self.assertLessEqual(
                count, al.MAX_SCREENSHOTS_PER_SET,
                f"{device_dir} has {count} screenshots — an upload would delete the "
                "live set and then fail partway")


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


class ClassifyChecksumTest(unittest.TestCase):
    """Shape classifier for one live `sourceFileChecksum` (#2612 Phase C step 0)."""

    def test_md5_shaped_is_32_lowercase_hex(self):
        self.assertEqual(al.classify_checksum("d41d8cd98f00b204e9800998ecf8427e"),
                         "md5-shaped")

    def test_none_and_empty_are_absent(self):
        self.assertEqual(al.classify_checksum(None), "absent")
        self.assertEqual(al.classify_checksum(""), "absent")

    def test_uppercase_hex_is_not_md5_shaped(self):
        # Apple's field is lowercase; an uppercase digest is a different shape,
        # and mislabelling it "md5" would hide that the convention changed.
        self.assertEqual(al.classify_checksum("D41D8CD98F00B204E9800998ECF8427E"),
                         "other")

    def test_sha256_length_is_other(self):
        self.assertEqual(al.classify_checksum("a" * 64), "other")

    def test_wrong_length_hex_is_other(self):
        self.assertEqual(al.classify_checksum("abc123"), "other")

    def test_base64ish_is_other(self):
        self.assertEqual(al.classify_checksum("Zm9vYmFyYmF6cXV4=="), "other")


class ClassifyLiveChecksumsTest(unittest.TestCase):
    """The measurement Phase C is blocked on: what does Apple actually store?"""

    def test_no_live_assets(self):
        v = al.classify_live_checksums({})
        self.assertEqual(v["overall"], "no-live-assets")

    def test_empty_sets_still_no_live_assets(self):
        # A display type present with an empty list is still nothing to measure.
        v = al.classify_live_checksums({"APP_IPHONE_67": []})
        self.assertEqual(v["overall"], "no-live-assets")

    def test_all_md5_shaped_is_not_confirmed(self):
        # The crux: 32-hex is CONSISTENT with MD5 but is not proof. It must not
        # silently become a green light for the checksum diff.
        v = al.classify_live_checksums(
            {"APP_IPHONE_67": ["d41d8cd98f00b204e9800998ecf8427e",
                               "0cc175b9c0f1b6a831c399e269772661"]})
        self.assertEqual(v["overall"], "md5-shaped")
        self.assertEqual(v["matched_local"], [])

    def test_draft_match_is_attributed_to_the_draft_set(self):
        # E3 (correctness re-review, PR #2811): the probe samples the EDITABLE
        # draft so a console upload is visible — but that draft is the exact
        # version apply_screenshots() writes to. If a match were reported
        # without saying WHERE, an operator could truthfully verify "the script
        # never wrote the live set" (true by construction) and attest, turning
        # our own echo in the draft into CONFIRMED.
        digest = "900150983cd24fb0d6963f7d28e17f72"
        v = al.classify_live_checksums(
            {"APP_IPHONE_67": ["a" * 32],
             "APP_IPHONE_67 (draft)": [digest]}, local_md5s=[digest])
        self.assertEqual(v["matched_by_display_type"],
                         {"APP_IPHONE_67 (draft)": [digest]})
        text = "\n".join(al.checksum_provenance_report(v))
        self.assertIn("matched in APP_IPHONE_67 (draft)", text)
        # And the report must kill the "live set was untouched" reasoning.
        self.assertIn("true by construction", text)

    def test_non_str_checksum_does_not_crash_the_entry_point(self):
        # classify_checksum() is advertised as total; the caller must be too.
        # `len()` on a non-str bucketed as "other" used to raise TypeError.
        v = al.classify_live_checksums({"APP_IPHONE_67": [12345]})
        self.assertEqual(v["overall"], "other")
        self.assertIn("len=5", v["unknown_shapes"])

    def test_match_alone_is_only_unattested_not_confirmed(self):
        # THE defect this guard exists for (correctness review, PR #2811):
        # once --apply-screenshots has run, the live set holds the very MD5s we
        # declared, so a repo match is our own echo. Unattested, it must never
        # read CONFIRMED — that would be a permanently-green Phase C unblocker
        # built on the one thing the write path structurally cannot test.
        digest = "900150983cd24fb0d6963f7d28e17f72"  # md5("abc")
        v = al.classify_live_checksums(
            {"APP_IPHONE_67": [digest]}, local_md5s=[digest])
        self.assertEqual(v["overall"], "unattested-match")
        self.assertEqual(v["matched_local"], [digest])

    def test_match_confirms_only_when_provenance_is_attested(self):
        digest = "900150983cd24fb0d6963f7d28e17f72"
        v = al.classify_live_checksums(
            {"APP_IPHONE_67": [digest]}, local_md5s=[digest],
            console_sourced=True)
        self.assertEqual(v["overall"], "confirmed")

    def test_attestation_alone_confirms_nothing_without_a_match(self):
        # Attesting provenance must not manufacture a verdict out of thin air.
        v = al.classify_live_checksums(
            {"APP_IPHONE_67": ["d41d8cd98f00b204e9800998ecf8427e"]},
            local_md5s=[], console_sourced=True)
        self.assertEqual(v["overall"], "md5-shaped")

    def test_none_display_type_does_not_crash_the_sort(self):
        # A live set missing `screenshotDisplayType` keys this dict on None;
        # sorting None against str is a TypeError that would take the whole
        # read-only run down and surface as an empty, clean-looking read.
        v = al.classify_live_checksums(
            {None: ["d41d8cd98f00b204e9800998ecf8427e"],
             "APP_IPHONE_67": ["d41d8cd98f00b204e9800998ecf8427e"]})
        self.assertEqual(v["overall"], "md5-shaped")

    def test_absent_checksums_refute(self):
        v = al.classify_live_checksums({"APP_IPHONE_67": [None, None]})
        self.assertEqual(v["overall"], "absent")

    def test_non_md5_shape_refutes_and_reports_length(self):
        v = al.classify_live_checksums({"APP_IPHONE_67": ["a" * 64]})
        self.assertEqual(v["overall"], "other")
        self.assertIn("len=64", v["unknown_shapes"])

    def test_mixed_buckets_are_not_averaged(self):
        v = al.classify_live_checksums(
            {"APP_IPHONE_67": ["d41d8cd98f00b204e9800998ecf8427e"],
             "APP_IPAD_PRO_3GEN_129": [None]})
        self.assertEqual(v["overall"], "mixed")

    def test_a_match_wins_over_shape_when_a_match_exists(self):
        # One matching MD5 outranks the shape bucket even if other assets are
        # only md5-shaped — still gated on the provenance attestation.
        digest = "900150983cd24fb0d6963f7d28e17f72"
        live = {"APP_IPHONE_67": [digest, "0cc175b9c0f1b6a831c399e269772661"]}
        self.assertEqual(
            al.classify_live_checksums(live, local_md5s=[digest])["overall"],
            "unattested-match")
        self.assertEqual(
            al.classify_live_checksums(live, local_md5s=[digest],
                                       console_sourced=True)["overall"],
            "confirmed")


class ChecksumProvenanceReportTest(unittest.TestCase):
    """The verdict must SAY what it licenses, not just what it observed."""

    def _report(self, live, local=(), console_sourced=False):
        return "\n".join(al.checksum_provenance_report(
            al.classify_live_checksums(live, local, console_sourced)))

    def test_md5_shaped_report_says_not_proof_and_how_to_close(self):
        text = self._report({"APP_IPHONE_67": ["d41d8cd98f00b204e9800998ecf8427e"]})
        self.assertIn("MD5-SHAPED", text.upper())
        self.assertIn("console", text)  # names the action that would confirm it

    def test_md5_shaped_report_warns_the_upload_lands_on_the_draft(self):
        # The prescribed action must be actionable: console uploads go to the
        # EDITABLE draft, so an operator told to "upload and re-run" would
        # otherwise watch the verdict never move and conclude the probe is broken.
        text = self._report({"APP_IPHONE_67": ["d41d8cd98f00b204e9800998ecf8427e"]})
        self.assertIn("draft", text.lower())

    def test_confirmed_report_unblocks_phase_c(self):
        digest = "900150983cd24fb0d6963f7d28e17f72"
        text = self._report({"APP_IPHONE_67": [digest]}, local=[digest],
                            console_sourced=True)
        self.assertIn("CONFIRMED", text.upper())
        self.assertIn("Phase C", text)

    def test_unattested_match_report_refuses_to_unblock_and_says_why(self):
        digest = "900150983cd24fb0d6963f7d28e17f72"
        text = self._report({"APP_IPHONE_67": [digest]}, local=[digest])
        self.assertIn("UNATTESTED-MATCH", text.upper())
        self.assertIn("echo", text.lower())          # names the failure mode
        self.assertIn("NOT a Phase C unblocker", text)
        # Must not read as a confirmation to a skimming eye.
        self.assertNotIn("CONFIRMED\n", text.upper().replace("[PROBE] ", ""))

    def test_absent_report_says_rekey(self):
        text = self._report({"APP_IPHONE_67": [None]})
        self.assertIn("re-key", text.lower())

    def test_every_verdict_produces_lines(self):
        # No verdict may silently emit nothing — that would read as "no drift".
        digest = "900150983cd24fb0d6963f7d28e17f72"
        for live, local in [
            ({}, ()),
            ({"APP_IPHONE_67": ["d41d8cd98f00b204e9800998ecf8427e"]}, ()),
            ({"APP_IPHONE_67": [None]}, ()),
            ({"APP_IPHONE_67": ["a" * 64]}, ()),
            ({"APP_IPHONE_67": [digest]}, [digest]),          # unattested-match
            ({"APP_IPHONE_67": ["d41d8cd98f00b204e9800998ecf8427e"],
              "APP_IPAD_PRO_3GEN_129": [None]}, ()),
        ]:
            for attested in (False, True):
                lines = al.checksum_provenance_report(
                    al.classify_live_checksums(live, local, attested))
                self.assertTrue(lines and all(l.startswith("[probe]") for l in lines))


class DisplayTypeEnumTest(unittest.TestCase):
    """#2794 follow-up (impact-review asymmetry): the ASC display-type VALUES
    had no enum guard, only a dir↔map coverage test. A DISPLAY_TYPE_MAP row
    with a bogus screenshotDisplayType would pass every offline check and fail
    only on the first live ASC call — on the write path, possibly after an
    earlier display type's live set was already replaced. Symmetric with
    play_listing.py's ImageTypeTest, which pinned the Play `AppImageType` enum."""

    def test_shipped_display_types_are_all_valid(self):
        self.assertEqual(al.unknown_display_types(), [])

    def test_allowlist_mirrors_apples_enum_exactly(self):
        """Transcribed verbatim from the App Store Connect API OpenAPI spec v4.3
        (components.schemas.ScreenshotDisplayType), cross-checked against
        fastlane spaceship's AppScreenshotSet::DisplayType — 33 values, no
        omissions or extras. A guard that exists to be exhaustive is worthless
        if it ALLOWS a value Apple rejects (an extra entry lets a future map row
        400 against the live store) or REJECTS one Apple accepts (a false
        positive blocking a legitimate device class). Unlike Play's AppImageType
        this enum has no proto-zero sentinel to exclude, so every value is kept.
        Regenerate from the spec — never a web summary — if Apple grows it."""
        self.assertEqual(al.VALID_DISPLAY_TYPES, frozenset({
            "APP_IPHONE_67", "APP_IPHONE_65", "APP_IPHONE_61", "APP_IPHONE_58",
            "APP_IPHONE_55", "APP_IPHONE_47", "APP_IPHONE_40", "APP_IPHONE_35",
            "APP_IPAD_PRO_3GEN_129", "APP_IPAD_PRO_3GEN_11", "APP_IPAD_PRO_129",
            "APP_IPAD_105", "APP_IPAD_97",
            "APP_DESKTOP", "APP_APPLE_TV", "APP_APPLE_VISION_PRO",
            "APP_WATCH_ULTRA", "APP_WATCH_SERIES_10", "APP_WATCH_SERIES_7",
            "APP_WATCH_SERIES_4", "APP_WATCH_SERIES_3",
            "IMESSAGE_APP_IPHONE_67", "IMESSAGE_APP_IPHONE_65",
            "IMESSAGE_APP_IPHONE_61", "IMESSAGE_APP_IPHONE_58",
            "IMESSAGE_APP_IPHONE_55", "IMESSAGE_APP_IPHONE_47",
            "IMESSAGE_APP_IPHONE_40",
            "IMESSAGE_APP_IPAD_PRO_3GEN_129", "IMESSAGE_APP_IPAD_PRO_3GEN_11",
            "IMESSAGE_APP_IPAD_PRO_129", "IMESSAGE_APP_IPAD_105",
            "IMESSAGE_APP_IPAD_97",
        }))

    def test_detects_an_unknown_type(self):
        # APP_IPHONE_69 / APP_IPAD_13 are the plausible typos — Apple never
        # created them, so a naming-by-form-factor mistake lands here.
        self.assertEqual(
            al.unknown_display_types({"iphone-6.9": "APP_IPHONE_69"}),
            ["APP_IPHONE_69"])
        self.assertEqual(al.unknown_display_types({"ipad-13": "APP_IPAD_13"}),
                         ["APP_IPAD_13"])

    def test_shipped_map_routes_to_the_67_and_129_slots(self):
        # Pins the deliberate routing the guard protects: no APP_IPHONE_69 or
        # APP_IPAD_13 exists, so 6.9" iPhone captures use the 6.7" slot and 13"
        # iPad captures the 12.9" slot — both real enum members.
        self.assertEqual(al.DISPLAY_TYPE_MAP["iphone-6.9"], "APP_IPHONE_67")
        self.assertEqual(al.DISPLAY_TYPE_MAP["ipad-13"], "APP_IPAD_PRO_3GEN_129")
        self.assertIn("APP_IPHONE_67", al.VALID_DISPLAY_TYPES)
        self.assertIn("APP_IPAD_PRO_3GEN_129", al.VALID_DISPLAY_TYPES)
        self.assertNotIn("APP_IPHONE_69", al.VALID_DISPLAY_TYPES)
        self.assertNotIn("APP_IPAD_13", al.VALID_DISPLAY_TYPES)

    def test_apply_screenshots_refuses_a_bad_type_at_the_write_boundary(self):
        """main() guards the CLI; this guards a direct importer, before any
        network object is built or any live set deleted. Patch DISPLAY_TYPE_MAP
        so nothing ever touches Apple."""
        original = al.DISPLAY_TYPE_MAP
        al.DISPLAY_TYPE_MAP = {"iphone-6.9": "APP_IPHONE_69"}
        try:
            with self.assertRaises(ValueError) as ctx:
                al.apply_screenshots(headers={}, bundle_id="x",
                                     shots_dir=pathlib.Path("."))
            self.assertIn("APP_IPHONE_69", str(ctx.exception))
        finally:
            al.DISPLAY_TYPE_MAP = original

    def test_main_rejects_a_bad_display_type_before_network(self):
        """CLI-level guard: a bogus map value returns 2 before any creds are
        resolved or any request is made. Explicit --metadata-dir keeps it
        cwd-independent (the guard runs after the dir check)."""
        original = al.DISPLAY_TYPE_MAP
        al.DISPLAY_TYPE_MAP = {"iphone-6.9": "APP_IPHONE_69"}
        try:
            with tempfile.TemporaryDirectory() as d:
                self.assertEqual(al.main(["--dry-run", "--metadata-dir", d]), 2)
        finally:
            al.DISPLAY_TYPE_MAP = original


if __name__ == "__main__":
    unittest.main()
