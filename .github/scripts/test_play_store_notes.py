"""Offline extraction tests, including the exact file/output contract used by CI.

Run: python3 -m unittest discover -s .github/scripts -p 'test_play_store_notes.py' -v
"""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from play_store_notes import release_notes  # noqa: E402


SCRIPT = Path(__file__).with_name("play_store_notes.py")
REPO_ROOT = Path(__file__).resolve().parents[2]


class PlayStoreNotesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "samples/android-demo/whatsnew/4.69.7/en-US.txt"

    def curated(self, text):
        self.source.parent.mkdir(parents=True, exist_ok=True)
        self.source.write_text(text, encoding="utf-8")

    def changelog(self, text):
        (self.root / "CHANGELOG.md").write_text(text, encoding="utf-8")

    def run_script(self, version):
        return subprocess.run(
            [sys.executable, str(SCRIPT), version, "--root", str(self.root)],
            cwd=self.root,
            env={**os.environ, "GITHUB_OUTPUT": str(self.root / "outputs")},
            capture_output=True, text=True, check=True,
        )

    def test_curated_notes_take_precedence_and_need_no_changelog(self):
        self.curated("  Brighter steel.\nSmoother switching.\n")
        for changelog in (None, "## v4.69.7\n- SDK internals.\n"):
            with self.subTest(changelog=changelog):
                if changelog:
                    self.changelog(changelog)
                self.assertEqual(
                    release_notes(self.root, "4.69.7"),
                    "Brighter steel.\nSmoother switching.",
                )

    def test_snapshots_use_only_their_base_versions_notes(self):
        self.curated("Smoother material changes.")
        for version in ("4.69.7-abcd1234", "4.69.7-rc-1"):
            with self.subTest(version=version):
                self.assertEqual(release_notes(self.root, version), "Smoother material changes.")
        self.changelog("## v4.69.8\n- Newer release.\n")
        self.assertEqual(release_notes(self.root, "4.69.8"), "- Newer release.")

    def test_missing_curated_file_uses_only_exact_changelog_section(self):
        self.changelog(
            "# Changelog\n## Unreleased\n- Future work.\n"
            "## v4x69x7\n- Wrong dots.\n## v4.69.70\n- Wrong patch.\n"
            "## v4.69.7 — 2026-11-18\n### Fixed\n\n"
            "- Try **brighter** [models](https://example.com) with `touch`.\n\n\n\n"
            "- Clearer instructions.\n## v4.69.6\n- Older release.\n"
        )
        expected = "Fixed\n\n- Try brighter models with touch.\n\n- Clearer instructions."
        self.assertEqual(release_notes(self.root, "4.69.7"), expected)
        self.assertEqual(release_notes(self.root, "4.69.7-abcd1234"), expected)

    def test_missing_or_empty_section_leaves_notes_empty(self):
        for changelog in (None, "## Unreleased\n- Future.\n", "## v4.69.7\n\n## v4.69.6\n- Older.\n"):
            with self.subTest(changelog=changelog):
                if changelog:
                    self.changelog(changelog)
                self.assertEqual(release_notes(self.root, "4.69.7"), "")

    def test_empty_curated_file_does_not_fall_back_to_sdk_notes(self):
        self.curated(" \n\t")
        self.changelog("## v4.69.7\n- SDK internals.\n")
        self.assertEqual(release_notes(self.root, "4.69.7"), "")

    def test_character_limit_for_both_sources_including_unicode(self):
        for character in ("a", "é", "🧊"):
            for length in (499, 500, 501, 1000):
                for source in ("curated", "changelog"):
                    with self.subTest(character=character, length=length, source=source):
                        text = character * length
                        if source == "curated":
                            self.curated(text)
                        else:
                            self.source.unlink(missing_ok=True)
                            self.changelog(f"## v4.69.7\n{text}\n")
                        expected = text if length <= 500 else text[:499] + "…"
                        self.assertEqual(release_notes(self.root, "4.69.7"), expected)

    def test_truncation_trims_whitespace_before_ellipsis(self):
        self.curated("a" * 497 + "\n\n" + "b" * 10)
        self.assertEqual(release_notes(self.root, "4.69.7"), "a" * 497 + "…")

    def test_invalid_version_is_rejected(self):
        for version in ("", "../4.69.7", "4x69x7", "4.69.7/other"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                release_notes(self.root, version)

    def test_cli_writes_play_filename_and_github_output(self):
        self.curated("Clearer AR instructions.\n")
        (self.root / "outputs").write_text("earlier=value\n", encoding="utf-8")
        result = self.run_script("4.69.7-abcd1234")
        self.assertEqual(
            (self.root / "whatsnew/whatsnew-en-US").read_text(encoding="utf-8"),
            "Clearer AR instructions.",
        )
        self.assertEqual(
            (self.root / "outputs").read_text(encoding="utf-8"),
            "earlier=value\nextracted=true\n",
        )
        self.assertIn("24 characters", result.stdout)

    def test_cli_missing_notes_removes_stale_payload_and_reports_false(self):
        payload = self.root / "whatsnew/whatsnew-en-US"
        payload.parent.mkdir()
        payload.write_text("Stale notes.", encoding="utf-8")
        result = self.run_script("4.69.8")
        self.assertFalse(payload.exists())
        self.assertEqual((self.root / "outputs").read_text(), "extracted=false\n")
        self.assertIn("::warning::No release notes", result.stdout)

    def test_current_release_copy_is_short_and_has_no_issue_references(self):
        source = REPO_ROOT / "samples/android-demo/whatsnew/unreleased/en-US.txt"
        text = source.read_text(encoding="utf-8")
        self.assertTrue(text.strip())
        self.assertLessEqual(len(text), 500)
        self.assertNotRegex(text, r"#\d+|https?://|`")
        self.assertEqual(release_notes(REPO_ROOT, "4.69.7-abcd1234"), text.strip())

    def test_snapshots_prefer_unreleased_notes_and_tags_never_read_them(self):
        self.curated("Released copy.")
        unreleased = self.root / "samples/android-demo/whatsnew/unreleased/en-US.txt"
        unreleased.parent.mkdir(parents=True, exist_ok=True)
        unreleased.write_text("Next release copy.", encoding="utf-8")
        self.assertEqual(release_notes(self.root, "4.69.7-abcd1234"), "Next release copy.")
        self.assertEqual(release_notes(self.root, "4.69.7"), "Released copy.")


if __name__ == "__main__":
    unittest.main()
