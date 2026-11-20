#!/usr/bin/env bash
# Exercise retired package versions and exact SwiftPM rules in temporary fixtures.
# No Gradle, network, credentials, or writes to the real checkout.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 - "$REPO_ROOT" "$BASH" <<'PY'
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(sys.argv[1])
BASH = sys.argv[2]
del sys.argv[1:]
EOL = "4.68.0"
CURRENT = "4.69.7"
FUTURE = "99.12.3-rc.1"
URL = "https://github.com/sceneview/sceneview.git"
PIN = f'.package(url: "{URL}", exact: "{EOL}")\n'
XCODE_RULE = f'Set version rule to **"Exact Version {EOL}"**.\n'


class RetiredVersionsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="sceneview-eol-versions-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.script = self.root / ".claude/scripts/sync-versions.sh"
        self.script.parent.mkdir(parents=True)
        shutil.copyfile(REPO / ".claude/scripts/sync-versions.sh", self.script)
        for directory in ("website-static", "sceneview-web", "gradle"):
            (self.root / directory).mkdir()
        self.write("website-static/fixture.js", "// Stream ID: 14357002837\n")
        self.write("gradle.properties", f"VERSION_NAME={CURRENT}\n")
        self.write("sceneview-web/package.json", json.dumps({"version": CURRENT}))
        self.frozen = {
            "flutter/sceneview_flutter/pubspec.yaml": f"version: {EOL}\n",
            "flutter/sceneview_flutter/android/build.gradle": f"version '{EOL}'\n",
            "flutter/sceneview_flutter/ios/flutter_sceneview.podspec":
                f"s.version          = '{EOL}'\ns.dependency 'SceneViewSwift', '~> 4.68'\n",
            "flutter/sceneview_flutter/CHANGELOG.md": f"## {EOL}\n\n- Last release.\n",
            "react-native/react-native-sceneview/package.json":
                json.dumps({"version": EOL}, indent=2) + "\n",
            "react-native/react-native-sceneview/package-lock.json":
                json.dumps({"version": EOL, "packages": {"": {"version": EOL},
                           "node_modules/other": {"version": "1.2.3"}}}, indent=2) + "\n",
            "samples/react-native-demo/package.json":
                json.dumps({"version": EOL}, indent=2) + "\n",
            "samples/react-native-demo/src/App.tsx": f"const VERSION = '{EOL}';\n",
            "SceneViewSwift.podspec": f"s.version = '{EOL}'\n",
            "Package.swift": PIN,
            "mcp/src/guides.ts": PIN + f'SceneViewSwift SPM exact: "{EOL}"\n',
            "mcp/src/platform-setup.ts": PIN + XCODE_RULE,
            "mcp/src/samples.ts": f'dependency: `{URL} — exact: "{EOL}"`,\n',
            "mcp/src/tools/handler.ts": PIN + XCODE_RULE + '`.package(url: "${sample.spmDependency ?? sample.dependency}", exact: "4.68.0")`\n',
            "SceneViewSwift/README.md": PIN,
            ".cursorrules": f'// SPM: {URL} exact: "{EOL}"\n',
            ".windsurfrules": f'// SPM: {URL} exact: "{EOL}"\n',
            "pro/gpt-store/gpt-instructions.md": f'SPM: `{URL}` exact: "{EOL}"\n',
            "docs/docs/quickstart-ios.md":
                f'3. Set the version rule to **Exact Version** `{EOL}`.\n' + PIN,
            "docs/docs/codelabs/codelab-3d-swiftui.md":
                f'3. Set the version rule to **Exact Version** `{EOL}`.\n',
            "docs/docs/codelabs/codelab-ar-swiftui.md":
                f'3. Version: **Exact Version** `{EOL}`\n',
        }
        for name, text in self.frozen.items():
            self.write(name, text)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def run_sync(self, expected, *args):
        result = subprocess.run([BASH, str(self.script), *args], cwd=self.root,
                                text=True, capture_output=True, timeout=45)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        self.assertIn("=== Summary ===", result.stdout)
        return result.stdout

    def assert_frozen(self):
        for name, text in self.frozen.items():
            self.assertEqual((self.root / name).read_text(), text, name)

    def test_future_android_release_leaves_retired_files_unchanged(self):
        self.run_sync(0)
        self.write("gradle.properties", f"VERSION_NAME={FUTURE}\n")
        self.run_sync(1)
        self.assert_frozen()  # Check mode must also be read-only.
        self.run_sync(1, "--fix")  # Reports pre-fix mismatches by contract.
        self.assert_frozen()
        web = json.loads((self.root / "sceneview-web/package.json").read_text())
        self.assertEqual(web["version"], FUTURE)
        self.run_sync(0)
        self.run_sync(0, "--fix")
        self.assert_frozen()  # Also catches an unconditional Flutter changelog stub.

    def test_retired_package_drift_is_reported_and_fixed_to_eol(self):
        names = [name for name in self.frozen
                 if name.startswith(("flutter/", "react-native/", "samples/react-native-demo/"))
                 and not name.endswith("CHANGELOG.md")]
        for name in names:
            self.write(name, self.frozen[name].replace(EOL, CURRENT))
        report = self.run_sync(1)
        for label in ("flutter/sceneview_flutter/pubspec.yaml", "flutter/.../android/build.gradle",
                      "flutter/.../ios/flutter_sceneview.podspec", "react-native/.../package-lock.json",
                      "samples/react-native-demo/package.json", "samples/react-native-demo App.tsx VERSION"):
            self.assertRegex(report, label.replace(".", r"\.") + r"[^\n]*MISMATCH")
        self.run_sync(1, "--fix")
        self.assert_frozen()
        self.run_sync(0)

    def test_range_and_stale_exact_rules_are_rejected_and_repaired(self):
        for name, text in self.frozen.items():
            if "exact:" in text:
                # Include the dangerous case: correct lower bound, wrong requirement.
                self.write(name, text.replace('exact:', 'from:'))
            elif "**Exact Version**" in text:
                self.write(name, text.replace("**Exact Version**", "**Up to Next Major** from"))
        self.run_sync(1)
        self.run_sync(1, "--fix")
        self.assert_frozen()
        self.write("Package.swift", PIN.replace(EOL, CURRENT))
        self.run_sync(1)
        self.run_sync(1, "--fix")
        self.assert_frozen()
        self.run_sync(0)

    def test_mcp_xcode_rules_are_checked_and_repaired_independently_of_snippets(self):
        for name in ("mcp/src/platform-setup.ts", "mcp/src/tools/handler.ts"):
            for rule in (f"Exact Version {CURRENT}", f"from: {EOL}",
                         "from: ${LATEST_SCENEVIEW_RELEASE}"):
                with self.subTest(file=name, rule=rule):
                    self.write(name, self.frozen[name].replace(f"Exact Version {EOL}", rule))
                    report = self.run_sync(1)
                    self.assertIn(f"{name} (SPM exact:)", report)
                    self.run_sync(1, "--fix")
                    self.assert_frozen()
                    self.run_sync(0)


unittest.main(verbosity=2)
PY
