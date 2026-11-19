#!/usr/bin/env python3
"""Exercise sitemap URLs, exclusions and dates against a small real Git history."""

import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET


SCRIPT = Path(__file__).with_name("generate-sitemap.py")
SPEC = importlib.util.spec_from_file_location("sitemap", SCRIPT)
SITEMAP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SITEMAP)


class SitemapTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.site = self.root / "website-static"
        self.site.mkdir()
        self.git("init", "-q")
        pages = {
            "index.html": '<link rel="canonical" href="https://sceneview.github.io/">'
                          '<body><h1>Home</h1></body>',
            "view.html": '<link href="/view" rel="canonical"><body>Viewer</body>',
            "alias.html": '<link rel="canonical" href="/view"><body>Same viewer</body>',
            "nested/index.html": '<body>Nested page</body>',
            "viewer.html": '<body><canvas></canvas><script>'
                           'button.onclick = () => { location.href = "/open/"; };'
                           '</script></body>',
            "go/index.html": '<meta name="robots" content="index, follow">'
                             '<link rel="canonical" href="/go/"><body>Download links</body>',
            "go/android/index.html": '<body>Redirect</body>',
            "go/ios/index.html": '<body>Redirect</body>',
            "go/codex/index.html": '<body>Redirect</body>',
            "playground.html": '<link rel="canonical" href="/web-demo/">'
                               '<meta http-equiv="refresh" content="0; url=/web-demo/">',
            "404.html": '<body>Not found</body>',
            "moved.html": '<META content="0; url=/view" HTTP-EQUIV="Refresh">'
                          '<body><a href="/view">Moved</a></body>',
            "private.html": '<meta name="robots" content="NOINDEX, follow"><body>Private</body>',
            "external.html": '<link rel="canonical" href="https://example.com/"><body>Copy</body>',
            "redirect.html": '<body><script>window.location.replace("/view")</script>'
                             '<noscript><a href="/view">Continue</a></noscript></body>',
        }
        for name, html in pages.items():
            path = self.site / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(html, encoding="utf-8")
        sources = {
            "samples/web-demo/site/index.html": '<body><canvas></canvas></body>',
            "samples/web-demo/site/js/app.js": '// Demo code\n',
            "website-static/js/sceneview.js": '// Shared viewer\n',
            "llms.txt": "Full API reference\n",
            "docs/docs/llms-full.txt": "Compact overview\n",
        }
        for name, content in sources.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        self.commit("2026-04-11T12:00:00+00:00")
        with (self.site / "view.html").open("a", encoding="utf-8") as page:
            page.write("<!-- Updated viewer -->")
        (self.root / "llms.txt").write_text("Updated API reference\n", encoding="utf-8")
        self.commit("2026-11-18T12:00:00+00:00")

    def git(self, *args, **kwargs):
        return subprocess.check_output(
            ["git", *args], cwd=self.root, text=True, stderr=subprocess.PIPE, **kwargs
        )

    def commit(self, date):
        self.git("add", ".")
        self.git(
            "-c", "user.name=Sitemap Test", "-c", "user.email=thomas.gorisse@gmail.com",
            "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
            "commit", "-qm", "Update fixture pages",
            env={**os.environ, "GIT_AUTHOR_DATE": date, "GIT_COMMITTER_DATE": date},
        )

    def test_indexable_canonical_urls_and_per_file_git_dates(self):
        namespace = {"s": SITEMAP.NAMESPACE}
        xml = ET.fromstring(SITEMAP.generate(self.root))
        entries = [
            (entry.findtext("s:loc", namespaces=namespace),
             entry.findtext("s:lastmod", namespaces=namespace))
            for entry in xml.findall("s:url", namespace)
        ]
        self.assertEqual([
            ("https://sceneview.github.io/", "2026-04-11"),
            ("https://sceneview.github.io/go/", "2026-04-11"),
            ("https://sceneview.github.io/llms-full.txt", "2026-04-11"),
            ("https://sceneview.github.io/llms.txt", "2026-11-18"),
            ("https://sceneview.github.io/nested/", "2026-04-11"),
            ("https://sceneview.github.io/view", "2026-11-18"),
            ("https://sceneview.github.io/viewer.html", "2026-04-11"),
            ("https://sceneview.github.io/web-demo/", "2026-04-11"),
        ], entries)

    def test_external_urls_follow_their_deployed_source_history(self):
        sources = (
            ("web-demo/", "samples/web-demo/site/index.html"),
            ("web-demo/", "samples/web-demo/site/js/app.js"),
            ("web-demo/", "website-static/js/sceneview.js"),
            ("llms.txt", "llms.txt"),
            ("llms-full.txt", "docs/docs/llms-full.txt"),
        )
        for day, (route, source) in enumerate(sources, start=19):
            with self.subTest(source=source):
                with (self.root / source).open("a", encoding="utf-8") as content:
                    content.write("\nUpdated source\n")
                date = f"2026-11-{day}"
                self.commit(f"{date}T12:00:00+00:00")
                xml = ET.fromstring(SITEMAP.generate(self.root))
                entries = {
                    entry.findtext(f"{{{SITEMAP.NAMESPACE}}}loc"):
                    entry.findtext(f"{{{SITEMAP.NAMESPACE}}}lastmod")
                    for entry in xml
                }
                self.assertEqual(date, entries[SITEMAP.ORIGIN + route])

    def test_missing_external_source_is_rejected(self):
        (self.root / "docs/docs/llms-full.txt").unlink()
        with self.assertRaisesRegex(ValueError, "Missing sitemap source: docs/docs/llms-full.txt"):
            SITEMAP.generate(self.root)

    def test_javascript_only_redirect_forms(self):
        for script in (
            'location = "/view"', 'window.location.href = "/view"',
            'document.location.assign("/view")', 'top.location.replace("/view")',
        ):
            with self.subTest(script=script):
                self.assertTrue(SITEMAP.Page(f"<body><script>{script}</script></body>").excluded)

    def test_redirect_navigation_does_not_exclude_a_real_page(self):
        page = SITEMAP.Page('<body><h1>Viewer</h1><script>'
                            'button.onclick = () => location.replace("/open/");'
                            '</script></body>')
        self.assertFalse(page.excluded)

    def test_check_accepts_generated_output_and_rejects_drift_without_writing(self):
        tools = self.root / "tools"
        tools.mkdir()
        script = tools / SCRIPT.name
        shutil.copyfile(SCRIPT, script)
        command = [sys.executable, str(script)]
        generated = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, generated.returncode, generated.stderr)
        checked = subprocess.run(command + ["--check"], capture_output=True, text=True)
        self.assertEqual(0, checked.returncode, checked.stderr)
        target = self.site / "sitemap.xml"
        target.write_text("stale sitemap\n", encoding="utf-8")
        stale = subprocess.run(command + ["--check"], capture_output=True, text=True)
        self.assertEqual(1, stale.returncode)
        self.assertIn("Run python3 tools/generate-sitemap.py", stale.stderr)
        self.assertEqual("stale sitemap\n", target.read_text(encoding="utf-8"))
        output = self.root / "temporary-sitemap.xml"
        exported = subprocess.run(command + ["--output", str(output)], capture_output=True, text=True)
        self.assertEqual(0, exported.returncode, exported.stderr)
        self.assertEqual(SITEMAP.generate(self.root), output.read_text(encoding="utf-8"))
        self.assertEqual("stale sitemap\n", target.read_text(encoding="utf-8"))
        # The real-tree CI command must surface parse errors in a new page.
        (self.site / "broken.html").write_text(
            '<link rel="canonical" href="https://[invalid"><body>Broken canonical</body>',
            encoding="utf-8",
        )
        broken = subprocess.run(command + ["--output", str(output)], capture_output=True, text=True)
        self.assertEqual(1, broken.returncode)
        self.assertIn("Sitemap error:", broken.stderr)
        self.assertEqual("stale sitemap\n", target.read_text(encoding="utf-8"))
        (self.site / "broken.html").unlink()
        target.unlink()
        missing = subprocess.run(command + ["--check"], capture_output=True, text=True)
        self.assertEqual(1, missing.returncode)
        self.assertFalse(target.exists())

    def test_shallow_history_is_rejected(self):
        shallow = self.root / "shallow"
        self.git("clone", "-q", "--depth=1", self.root.as_uri(), str(shallow))
        with self.assertRaisesRegex(ValueError, "full Git history"):
            SITEMAP.generate(shallow)

    def test_untracked_html_has_no_invented_date(self):
        (self.site / "new.html").write_text("<body>New page</body>", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "No Git history"):
            SITEMAP.generate(self.root)


if __name__ == "__main__":
    unittest.main()
