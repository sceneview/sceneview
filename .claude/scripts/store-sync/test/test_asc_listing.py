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
