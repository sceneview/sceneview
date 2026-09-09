#!/usr/bin/env python3
"""Turn AGP's instrumented-test XML into a truthful GitHub job summary.

Why this exists (#3551). `demo-render-goldens` runs its gradle call with
`|| true` inside a `continue-on-error: true` job, which is the repo's doctrine
for an emulator leg that is allowed to flake — but it also meant the job's only
verdict was "green", for months during which every single comparison was dying
on a size mismatch and only 3 of 15 cases ran at all. Advisory has to mean "not
a merge block", not "unreadable without downloading an artifact".

So this script states, in `$GITHUB_STEP_SUMMARY`:
  * how many cases the source file declares, vs how many the run executed —
    a shortfall is the signature of the emulator dying mid-suite;
  * passed / failed / skipped, and every failure's first message line;
  * whether any capture file came back at all.
It annotates a shortfall and a total absence of results as warnings, and exits
0 either way: the job's blocking-ness is the workflow's decision, not this
script's.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# `@Test` on its own line is how every case in these suites is declared. Counting
# them gives the denominator ("3 of 15 ran") that the XML alone cannot provide —
# the XML only knows about cases that started.
TEST_ANNOTATION = re.compile(r"^\s*@Test\b", re.MULTILINE)


def declared_cases(source: Path | None) -> int | None:
    if source is None or not source.is_file():
        return None
    return len(TEST_ANNOTATION.findall(source.read_text(encoding="utf-8", errors="replace")))


def first_line(text: str | None) -> str:
    if not text:
        return ""
    line = text.strip().splitlines()[0].strip() if text.strip() else ""
    # Keep the summary table one row per case; the artifact has the full trace.
    return (line[:220] + "…") if len(line) > 220 else line


def collect(results_dir: Path):
    """Parse every `TEST-*.xml` AGP wrote, wherever it nested them."""
    cases = []
    for xml in sorted(results_dir.rglob("*.xml")):
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError as exc:  # a truncated file is itself a finding
            cases.append(("<unparseable>", xml.name, "error", f"{xml}: {exc}"))
            continue
        suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
        for suite in suites:
            for case in suite.iter("testcase"):
                name = case.get("name", "?")
                classname = (case.get("classname") or "").rsplit(".", 1)[-1]
                failure = case.find("failure")
                error = case.find("error")
                skipped = case.find("skipped")
                if failure is not None or error is not None:
                    node = failure if failure is not None else error
                    detail = first_line(node.get("message") or node.text)
                    cases.append((classname, name, "failed", detail))
                elif skipped is not None:
                    cases.append((classname, name, "skipped", first_line(skipped.get("message") or skipped.text)))
                else:
                    cases.append((classname, name, "passed", ""))
    return cases


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--results-dir", required=True)
    ap.add_argument("--source", default=None, help="androidTest source file whose @Test count is the denominator")
    ap.add_argument("--title", default="Instrumented tests")
    ap.add_argument("--captures-dir", default=None)
    args = ap.parse_args()

    results_dir = Path(args.results_dir)
    cases = collect(results_dir) if results_dir.is_dir() else []
    expected = declared_cases(Path(args.source) if args.source else None)

    passed = sum(1 for c in cases if c[2] == "passed")
    failed = sum(1 for c in cases if c[2] in ("failed", "error"))
    skipped = sum(1 for c in cases if c[2] == "skipped")
    executed = len(cases)

    out = [f"## {args.title}", ""]
    if not cases:
        out += [
            "**No test results were produced at all.** No `*.xml` under "
            f"`{results_dir}` — the instrumentation never started, or the build "
            "failed before it. This is a worse outcome than a red suite, not a "
            "better one.",
            "",
        ]
        print(f"::warning::{args.title}: the run produced no instrumented-test results at all")
    else:
        denom = f" of {expected} declared" if expected else ""
        out += [
            f"| Executed{denom} | Passed | Failed | Skipped |",
            "| --- | --- | --- | --- |",
            f"| {executed} | {passed} | {failed} | {skipped} |",
            "",
        ]
        if expected and executed < expected:
            missing = expected - executed
            out += [
                f"⚠️ **{missing} of {expected} cases never ran.** A suite that stops "
                "part-way is usually the emulator process dying mid-run (`adb: "
                "device offline` in the step log), not a suite that had nothing to "
                "do. The cases that did not run are neither green nor red — they "
                "are untested.",
                "",
            ]
            print(f"::warning::{args.title}: only {executed} of {expected} cases ran — {missing} never executed")

        bad = [c for c in cases if c[2] in ("failed", "error")]
        if bad:
            out += ["### Failures", "", "| Case | Message |", "| --- | --- |"]
            for classname, name, _, detail in bad:
                cell = detail.replace("|", "\\|") or "(no message)"
                out.append(f"| `{classname}.{name}` | {cell} |")
            out.append("")
        skips = [c for c in cases if c[2] == "skipped"]
        if skips:
            out += ["### Skipped", "", "| Case | Reason |", "| --- | --- |"]
            for classname, name, _, detail in skips:
                cell = detail.replace("|", "\\|") or "(no reason given)"
                out.append(f"| `{classname}.{name}` | {cell} |")
            out.append("")

    if args.captures_dir:
        captures = Path(args.captures_dir)
        files = sorted(p for p in captures.rglob("*") if p.is_file()) if captures.is_dir() else []
        out += [
            f"**Captures pulled from the device:** {len(files)}"
            + (f" (artifact `{captures.name}`)" if files else " — the artifact will be empty"),
            "",
        ]
        if not files:
            print(f"::warning::{args.title}: no capture file was pulled from the device")

    summary = "\n".join(out)
    print(summary)
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as fh:
            fh.write(summary + "\n")
    # Advisory by contract: the workflow decides whether the job blocks.
    return 0


if __name__ == "__main__":
    sys.exit(main())
