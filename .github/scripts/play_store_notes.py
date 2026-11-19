"""Build the Play Store notes, preferring versioned copy for demo users."""

import argparse
import os
from pathlib import Path
import re


def release_notes(root: Path, version: str) -> str:
    """Read curated notes or the legacy changelog section, capped at 500 chars."""
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", version):
        raise ValueError(f"Invalid release version: {version}")
    base_version, _, suffix = version.partition("-")
    whatsnew = root / "samples/android-demo/whatsnew"
    # A tagged release reads its own directory, which collate-changelog.sh
    # creates by renaming `unreleased/` in the release PR. A snapshot
    # (`4.69.7-abcd1234`) is built from commits AFTER that tag, so it reads the
    # notes being written for the next release first.
    candidates = [whatsnew / base_version / "en-US.txt"]
    if suffix:
        candidates.insert(0, whatsnew / "unreleased" / "en-US.txt")
    source = next((c for c in candidates if c.is_file()), None)
    if source is not None:
        text = source.read_text(encoding="utf-8")
    else:
        changelog = root / "CHANGELOG.md"
        if not changelog.is_file():
            return ""
        lines = []
        found = False
        heading = re.compile(rf"^## v{re.escape(base_version)}(?: |$)")
        for line in changelog.read_text(encoding="utf-8").splitlines():
            if not found:
                found = bool(heading.match(line))
            elif re.match(r"^## v[0-9]", line):
                break
            else:
                lines.append(line)
        text = "\n".join(lines)
        # Preserve the previous workflow's Markdown-to-plain-text fallback.
        text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
        text = re.sub(r"\*\*([^*]+)\*\*", r"\1", text)
        text = re.sub(r"`([^`]+)`", r"\1", text)
        text = re.sub(r"^#{1,6}\s*", "", text, flags=re.M)
        text = re.sub(r"\n{3,}", "\n\n", text)
    text = text.strip()
    if len(text) > 500:
        text = text[:499].rstrip() + "…"
    return text


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output-dir", type=Path, default=Path("whatsnew"))
    args = parser.parse_args()
    text = release_notes(args.root, args.version)
    output = args.output_dir / "whatsnew-en-US"
    if text:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")
        print(f"What's new payload ({len(text)} characters):\n{text}")
    else:
        output.unlink(missing_ok=True)
        print(f"::warning::No release notes for {args.version} — Play Store 'What's new' will be left empty")
    if github_output := os.environ.get("GITHUB_OUTPUT"):
        with open(github_output, "a", encoding="utf-8") as stream:
            stream.write(f"extracted={str(bool(text)).lower()}\n")


if __name__ == "__main__":
    main()
