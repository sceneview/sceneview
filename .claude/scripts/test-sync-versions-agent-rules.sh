#!/usr/bin/env bash
# Check the real agent-rule Maven pins, then exercise sync-versions.sh against
# throwaway fixtures. No network, Gradle, or edits to the working tree.
# Usage: bash .claude/scripts/test-sync-versions-agent-rules.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 - "$REPO_ROOT" "$BASH" <<'PY'
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(sys.argv[1])
BASH = sys.argv[2]
del sys.argv[1:]
FILES = (
    ".cursorrules",
    ".windsurfrules",
    ".github/copilot-instructions.md",
    "pro/gpt-store/gpt-instructions.md",
    "agents/OPENAI-PLUGIN.md",
)
COORDINATE = re.compile(r"(io\.github\.sceneview:[\w.-]+:)([0-9][\w.+-]*)")
VERSION = re.search(r"^VERSION_NAME=(.+)$", (REPO / "gradle.properties").read_text(), re.M)[1]


class AgentRuleVersionsTest(unittest.TestCase):
    def test_repository_maven_versions(self):
        for name in FILES:
            with self.subTest(file=name):
                text = (REPO / name).read_text()
                matches = list(COORDINATE.finditer(text))
                self.assertTrue(matches, f"{name}: no Maven coordinates checked")
                for match in matches:
                    line = text.count("\n", 0, match.start()) + 1
                    self.assertEqual(match[2], VERSION, f"{name}:{line}: {match[0]}")

    def test_check_fix_and_future_release(self):
        with tempfile.TemporaryDirectory(prefix="sceneview-agent-versions-") as tmp:
            root = Path(tmp)
            script = root / ".claude/scripts/sync-versions.sh"
            script.parent.mkdir(parents=True)
            shutil.copyfile(REPO / ".claude/scripts/sync-versions.sh", script)
            # Empty directories keep unrelated optional scans quiet.
            for directory in ("website-static", "sceneview-web", "gradle"):
                (root / directory).mkdir()
            (root / "website-static/fixture.js").write_text("// Stream ID: 14357002837\n")
            props = root / "gradle.properties"
            props.write_text(f"VERSION_NAME={VERSION}\n")
            clean = (
                f'implementation("io.github.sceneview:sceneview:{VERSION}")\n'
                f"implementation('io.github.sceneview:arsceneview:{VERSION}')\n"
                f"Maven: `io.github.sceneview:sceneview:{VERSION}`\n"
                f"`io.github.sceneview:sceneview-core:{VERSION}` "
                f"`io.github.sceneview:sceneview-compose:{VERSION}`\n"
                'SPM tag: "4.68.0"; Flutter: ^4.68.0; React Native: 4.68.0\n'
                'implementation("io.github.other:sceneview:4.51.0")\n'
            )
            for name in FILES:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(clean)

            def run(expected, *args):
                result = subprocess.run(
                    [BASH, str(script), *args], cwd=root,
                    text=True, capture_output=True, timeout=30,
                )
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
                return result.stdout

            run(0)
            # Isolate each file: an aligned first coordinate must not hide
            # stale AR, repeated 3D, core or Compose coordinates later on.
            stale = ("9.8.7-rc.2", "1.2.3", "2.3.4-alpha-1", "3.4.5+build.7")
            for name in FILES:
                with self.subTest(file=name):
                    path = root / name
                    versions = iter((VERSION, *stale))
                    drifted = COORDINATE.sub(lambda m: m[1] + next(versions), clean)
                    path.write_text(drifted)
                    out = run(1)
                    self.assertIn(name, out)
                    for version in stale:
                        self.assertRegex(out, re.escape(version) + r"[^\n]*MISMATCH")
                    self.assertEqual(path.read_text(), drifted, "check mode wrote to a file")
                    run(1, "--fix")  # Existing contract: reports pre-fix mismatches.
                    self.assertEqual(path.read_text(), clean, "fix changed non-Maven text")
                    run(0)

            # VERSION_NAME is authoritative on the next release too, rather
            # than a special case for today's version or the old 4.51.0 pin.
            future = "99.12.3-rc.1"
            props.write_text(f"VERSION_NAME={future}\n")
            run(1)
            run(1, "--fix")
            for name in FILES:
                expected = COORDINATE.sub(lambda m: m[1] + future, clean)
                self.assertEqual((root / name).read_text(), expected, name)
            run(0)
            run(0, "--fix")
            for name in FILES:
                self.assertEqual((root / name).read_text(), expected, name)


unittest.main(verbosity=2)
PY
