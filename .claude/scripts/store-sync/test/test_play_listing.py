"""Offline unit tests for play_listing.py's pure helpers (#2612 P2 Phase A).

No network, no google-auth/requests — run via test-store-sync.sh or
`python3 -m unittest discover .claude/scripts/store-sync/test`.
"""

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import play_listing as pl  # noqa: E402


class ReadListingTextTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.ldir = pathlib.Path(self.tmp.name) / "en-GB"
        self.ldir.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def test_reads_existing_fields_and_skips_missing(self):
        (self.ldir / "title.txt").write_text("SceneView Demo\n")
        body, notes = pl.read_listing_text(self.ldir)
        self.assertEqual(body, {"title": "SceneView Demo"})
        self.assertEqual(notes, [])

    def test_truncates_over_cap_with_ellipsis(self):
        (self.ldir / "title.txt").write_text("x" * 45)
        body, notes = pl.read_listing_text(self.ldir)
        self.assertEqual(len(body["title"]), 30)
        self.assertTrue(body["title"].endswith("…"))
        self.assertEqual(len(notes), 1)
        self.assertIn("truncated", notes[0])

    def test_exact_cap_untouched(self):
        (self.ldir / "short_description.txt").write_text("y" * 80)
        body, notes = pl.read_listing_text(self.ldir)
        self.assertEqual(body["shortDescription"], "y" * 80)
        self.assertEqual(notes, [])


class GraphicsForTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.ldir = pathlib.Path(self.tmp.name) / "en-GB"
        (self.ldir / "graphics").mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def test_glob_is_sorted(self):
        g = self.ldir / "graphics"
        for n in ["phone-screenshot-2.png", "phone-screenshot-1.png"]:
            (g / n).write_bytes(b"png")
        files = pl.graphics_for(self.ldir, "phone-screenshot-*.png")
        self.assertEqual([f.name for f in files],
                         ["phone-screenshot-1.png", "phone-screenshot-2.png"])

    def test_list_pattern_keeps_only_existing(self):
        (self.ldir / "graphics" / "icon-512.png").write_bytes(b"png")
        files = pl.graphics_for(self.ldir, ["icon-512.png", "feature-graphic.png"])
        self.assertEqual([f.name for f in files], ["icon-512.png"])

    def test_no_graphics_dir(self):
        self.assertEqual(pl.graphics_for(pathlib.Path(self.tmp.name), "*.png"), [])


class DiffTextTest(unittest.TestCase):
    def test_no_drift(self):
        self.assertEqual(pl.diff_text("en-GB", {"title": "A"}, {"title": "A"}), [])

    def test_field_differs(self):
        drift = pl.diff_text("en-GB", {"title": "A"}, {"title": "B"})
        self.assertEqual(len(drift), 1)
        self.assertIn("title differs", drift[0])

    def test_field_missing_remotely(self):
        drift = pl.diff_text("en-GB", {"title": "A"}, {})
        self.assertEqual(len(drift), 1)
        self.assertIn("missing on the live listing", drift[0])

    def test_unmanaged_remote_field_is_not_drift(self):
        # No local file for fullDescription → the live value is unmanaged.
        self.assertEqual(
            pl.diff_text("en-GB", {"title": "A"},
                         {"title": "A", "fullDescription": "live-only"}),
            [])


class DiffImagesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)
        self.f1 = self.dir / "phone-screenshot-1.png"
        self.f2 = self.dir / "phone-screenshot-2.png"
        self.f1.write_bytes(b"one")
        self.f2.write_bytes(b"two")

    def tearDown(self):
        self.tmp.cleanup()

    def test_identical_sequence_no_drift(self):
        shas = [pl.sha256_of(self.f1), pl.sha256_of(self.f2)]
        self.assertEqual(
            pl.diff_images("en-GB", "phoneScreenshots", [self.f1, self.f2], shas), [])

    def test_order_mismatch_is_drift(self):
        shas = [pl.sha256_of(self.f2), pl.sha256_of(self.f1)]
        drift = pl.diff_images("en-GB", "phoneScreenshots", [self.f1, self.f2], shas)
        self.assertEqual(len(drift), 1)
        self.assertIn("ORDER", drift[0])

    def test_missing_and_extra(self):
        drift = pl.diff_images("en-GB", "phoneScreenshots", [self.f1, self.f2],
                               [pl.sha256_of(self.f1), "0" * 64])
        self.assertEqual(len(drift), 1)
        self.assertIn("phone-screenshot-2.png", drift[0])
        self.assertIn("1 live image(s) not in the repo", drift[0])


class ResolveCredentialsTest(unittest.TestCase):
    def test_content_env(self):
        creds = pl.resolve_credentials({"SERVICE_ACCOUNT_JSON": '{"type": "sa"}'})
        self.assertEqual(creds, {"type": "sa"})

    def test_alias_env(self):
        creds = pl.resolve_credentials({"PLAY_STORE_SERVICE_ACCOUNT_JSON": '{"a": 1}'})
        self.assertEqual(creds, {"a": 1})

    def test_path_env(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json") as f:
            f.write('{"from": "path"}')
            f.flush()
            creds = pl.resolve_credentials(
                {"PLAY_STORE_SERVICE_ACCOUNT_JSON_PATH": f.name})
        self.assertEqual(creds, {"from": "path"})

    def test_absent(self):
        self.assertIsNone(pl.resolve_credentials({}))


class SsotDefaultsTest(unittest.TestCase):
    """The defaults must keep pointing at the real repo SSOT dirs."""

    def repo_root(self):
        return pathlib.Path(__file__).resolve().parents[4]

    def test_listing_dir_exists_with_locales(self):
        root = self.repo_root() / pl.DEFAULT_LISTING_DIR
        self.assertTrue(root.is_dir(), f"missing {root}")
        self.assertTrue(pl.locales_under(root), "no locale dirs under the SSOT")

    def test_every_graphics_pattern_matches_something_in_en_gb(self):
        ldir = self.repo_root() / pl.DEFAULT_LISTING_DIR / "en-GB"
        for image_type, pattern in pl.GRAPHICS:
            self.assertTrue(pl.graphics_for(ldir, pattern),
                            f"{image_type} pattern matches no committed file")


if __name__ == "__main__":
    unittest.main()
