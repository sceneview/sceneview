#!/usr/bin/env python3
"""Generate the marketing sitemap; --check reports drift without writing files."""

import argparse
import difflib
from html.parser import HTMLParser
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import quote, urljoin, urlsplit
import xml.etree.ElementTree as ET


ORIGIN = "https://sceneview.github.io/"
NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9"
JS_REDIRECT = re.compile(
    r"\b(?:(?:window|document|self|top)\s*\.\s*)?location"
    r"(?:\s*\.\s*href)?\s*=(?!=)|"
    r"\blocation\s*\.\s*(?:replace|assign)\s*\("
)


class Page(HTMLParser):
    """Read indexing metadata and distinguish redirect-only scripts from viewers."""

    def __init__(self, html):
        super().__init__(convert_charrefs=True)
        self.canonical = None
        self.noindex = False
        self.refresh = False
        self.in_body = False
        self.ignored = 0
        self.in_script = False
        self.scripts = []
        self.has_content = False
        self.feed(html)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "link" and "canonical" in (attrs.get("rel") or "").lower().split():
            self.canonical = attrs.get("href")
        if tag == "meta":
            if (attrs.get("http-equiv") or "").lower() == "refresh":
                self.refresh = True
            if (attrs.get("name") or "").lower() == "robots":
                directives = re.split(r"[\s,]+", (attrs.get("content") or "").lower())
                self.noindex |= bool({"noindex", "none"}.intersection(directives))
        if tag == "body":
            self.in_body = True
        if tag in {"script", "style", "noscript", "template"}:
            self.ignored += 1
        if tag == "script":
            self.in_script = True
        if self.in_body and not self.ignored and tag in {
            "img", "svg", "canvas", "video", "audio", "iframe", "object",
            "embed", "input", "button", "select", "textarea",
        }:
            self.has_content = True

    def handle_endtag(self, tag):
        if tag == "body":
            self.in_body = False
        if tag in {"script", "style", "noscript", "template"}:
            self.ignored = max(0, self.ignored - 1)
        if tag == "script":
            self.in_script = False

    def handle_data(self, data):
        if self.in_script:
            self.scripts.append(data)
        elif self.in_body and not self.ignored and data.strip():
            self.has_content = True

    @property
    def excluded(self):
        return self.noindex or self.refresh or (
            not self.has_content and JS_REDIRECT.search("\n".join(self.scripts))
        )


def git(root, *args):
    return subprocess.check_output(
        ["git", *args], cwd=root, text=True, stderr=subprocess.PIPE
    ).strip()


def generate(root):
    if git(root, "rev-parse", "--is-shallow-repository") == "true":
        raise ValueError("Sitemap dates require full Git history (checkout fetch-depth: 0).")
    site = root / "website-static"
    entries = {}
    for path in sorted(site.rglob("*.html")):
        relative = path.relative_to(site)
        if relative.parts[0] == "go" or path.name == "404.html":
            continue
        page = Page(path.read_text(encoding="utf-8"))
        if page.excluded:
            continue
        route = relative.as_posix()
        if path.name == "index.html":
            route = route[:-len("index.html")]
        url = urljoin(ORIGIN + quote(route), page.canonical or "")
        if urlsplit(url).netloc != urlsplit(ORIGIN).netloc:
            continue  # An external canonical belongs in that site's sitemap.
        lastmod = git(root, "log", "-1", "--format=%cs", "--", path.relative_to(root).as_posix())
        if not lastmod:
            raise ValueError(f"No Git history for {path.relative_to(root)}; commit the HTML first.")
        entries[url] = max(entries.get(url, ""), lastmod)
    if not entries:
        raise ValueError("No indexable HTML pages found in website-static.")
    ET.register_namespace("", NAMESPACE)
    sitemap = ET.Element(f"{{{NAMESPACE}}}urlset")
    for url, lastmod in sorted(entries.items()):
        entry = ET.SubElement(sitemap, "url")
        ET.SubElement(entry, "loc").text = url
        ET.SubElement(entry, "lastmod").text = lastmod
    ET.indent(sitemap, space="  ")
    return ET.tostring(sitemap, encoding="utf-8", xml_declaration=True).decode() + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if the committed sitemap is stale")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    target = root / "website-static/sitemap.xml"
    try:
        expected = generate(root)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Sitemap error: {error}", file=sys.stderr)
        return 1
    if args.check:
        actual = target.read_text(encoding="utf-8") if target.exists() else ""
        if actual != expected:
            sys.stderr.writelines(difflib.unified_diff(
                actual.splitlines(keepends=True), expected.splitlines(keepends=True),
                fromfile=str(target.relative_to(root)), tofile="generated sitemap",
            ))
            print("Run python3 tools/generate-sitemap.py and commit the sitemap.", file=sys.stderr)
            return 1
        print("Sitemap is up to date.")
    else:
        target.write_text(expected, encoding="utf-8")
        print("Generated website-static/sitemap.xml.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
