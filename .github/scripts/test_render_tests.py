"""Offline regression tests for the PR-time Playwright verdict (#4548).

Run: python3 -m unittest discover -s .github/scripts -p 'test_render_tests.py' -v
Requires PyYAML (pinned in .github/workflows/requirements/ci.txt).
"""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/render-tests.yml"


class PlaywrightVerdictTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = yaml.safe_load(WORKFLOW.read_text())
        cls.job = cls.workflow["jobs"]["web-render"]
        cls.step = next(
            step for step in cls.job["steps"]
            if step.get("name") == "Run Playwright tests"
        )

    def test_playwright_exit_code_reaches_the_job(self):
        # Execute the actual workflow command with a stand-in for npx. A
        # failing assertion, browser crash or missing executable must stay red.
        with tempfile.TemporaryDirectory() as directory:
            npx = Path(directory) / "npx"
            npx.write_text(
                '#!/bin/sh\n'
                '[ "$1" = playwright ] && [ "$2" = test ] || exit 99\n'
                'exit "$PLAYWRIGHT_EXIT_CODE"\n'
            )
            npx.chmod(0o755)
            for exit_code in (0, 1, 127):
                with self.subTest(exit_code=exit_code):
                    result = subprocess.run(
                        ["bash", "--noprofile", "--norc", "-eo", "pipefail",
                         "-c", self.step["run"]],
                        cwd=ROOT / self.step["working-directory"],
                        env={
                            **os.environ,
                            "PATH": directory + os.pathsep + os.environ["PATH"],
                            "PLAYWRIGHT_EXIT_CODE": str(exit_code),
                        },
                        capture_output=True,
                        text=True,
                        timeout=10,
                    )
                    self.assertEqual(result.returncode, exit_code, result.stderr)

    def test_failures_are_not_allowed_at_job_or_step_level(self):
        self.assertFalse(self.job.get("continue-on-error", False))
        self.assertFalse(self.step.get("continue-on-error", False))

    def test_failure_artifacts_are_always_uploaded(self):
        for name, path in (
            ("Upload Playwright report", "samples/web-demo/playwright-report/"),
            ("Upload test screenshots", "samples/web-demo/test-results/"),
        ):
            with self.subTest(step=name):
                step = next(s for s in self.job["steps"] if s.get("name") == name)
                self.assertEqual(step["if"], "always()")
                self.assertTrue(step["uses"].startswith("actions/upload-artifact@"))
                self.assertEqual(step["with"]["path"], path)

    def test_browser_fixtures_are_built_before_both_full_suites(self):
        for path, job, runner in (
            (WORKFLOW, "web-render", "Run Playwright tests"),
            (ROOT / ".github/workflows/device-qa.yml", "web", "Run device-qa.sh (web leg)"),
        ):
            with self.subTest(workflow=path.name):
                steps = yaml.safe_load(path.read_text())["jobs"][job]["steps"]
                setup = next(i for i, s in enumerate(steps) if s.get("uses") == "./.github/actions/setup-gradle")
                build = next(i for i, s in enumerate(steps) if ":sceneview-web:jsBrowserProductionWebpack" in s.get("run", ""))
                run = next(i for i, s in enumerate(steps) if s.get("name") == runner)
                self.assertLess(setup, build)
                self.assertLess(build, run)
                self.assertFalse(steps[build].get("continue-on-error", False))


if __name__ == "__main__":
    unittest.main()
