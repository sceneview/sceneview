#!/usr/bin/env bash
# Grade an agent PR review into a merge verdict — DETERMINISTIC, no LLM.
#
# WHY THIS IS A SCRIPT AND NOT PART OF THE PROMPT
#   `pr-review.yml` runs four independent reviewer agents and asks them to
#   write their findings to a JSON file. The agents FIND; this script DECIDES.
#   Letting the model also grade its own findings means a single confused
#   generation can turn a confirmed breaking-API error into "looks fine" — the
#   generator/evaluator separation the repo mandates (CLAUDE.md "Separate
#   generator from evaluator") applies to the gate itself, not just to code.
#
#   It mirrors the grading block of `.claude/workflows/review-fanout.js` so the
#   in-session and in-CI paths reach the SAME verdict for the same findings.
#   Change one, change the other.
#
# HARD INVARIANT — absence of evidence is never evidence of safety.
#   A missing file, unparsable JSON, or a reviewer that did not report is
#   REVIEW_INCOMPLETE (blocking), never a pass. This is the mutation the
#   self-test (`test-grade-pr-review.sh`) exists to keep true.
#
# USAGE
#   grade-pr-review.sh <verdict.json> [--comment-out <file.md>] [--pr <n>]
#
# STDOUT   `key=value` lines, safe to append to $GITHUB_OUTPUT.
# EXIT     0 = mergeable (MERGE / MERGE_AFTER_WARNINGS)
#          1 = blocked   (DO_NOT_MERGE / REVIEW_INCOMPLETE)
#          2 = usage error

set -uo pipefail

VERDICT_FILE=""
COMMENT_OUT=""
PR_NUM=""

while [ $# -gt 0 ]; do
  case "$1" in
    --comment-out) COMMENT_OUT="${2:-}"; shift 2 ;;
    --pr)          PR_NUM="${2:-}"; shift 2 ;;
    -h|--help)     sed -n '2,30p' "$0"; exit 0 ;;
    -*)            echo "grade-pr-review.sh: unknown flag $1" >&2; exit 2 ;;
    *)             VERDICT_FILE="$1"; shift ;;
  esac
done

if [ -z "$VERDICT_FILE" ]; then
  echo "usage: grade-pr-review.sh <verdict.json> [--comment-out <file.md>] [--pr <n>]" >&2
  exit 2
fi

# The whole decision lives in python3 (present on every GitHub runner and on
# macOS) because bash cannot parse JSON safely. It prints the key=value lines
# and the markdown body, then exits with the gate status.
COMMENT_OUT="$COMMENT_OUT" PR_NUM="$PR_NUM" VERDICT_FILE="$VERDICT_FILE" python3 <<'PY'
import json, os, sys

path = os.environ["VERDICT_FILE"]
comment_out = os.environ.get("COMMENT_OUT") or ""
pr = os.environ.get("PR_NUM") or ""

REVIEWERS = ("code", "security", "impact", "doc")


def emit(verdict, action, reason, errors=None, warnings=None, propagation=None,
         per_reviewer=None, ran=None, expected=len(REVIEWERS)):
    errors = errors or []
    warnings = warnings or []
    propagation = propagation or []
    per_reviewer = per_reviewer or []
    blocking = verdict in ("DO_NOT_MERGE", "REVIEW_INCOMPLETE")
    breaking = any((e.get("reviewer") == "impact") for e in errors)

    print("verdict=%s" % verdict)
    print("blocking=%s" % ("true" if blocking else "false"))
    print("breaking_api=%s" % ("true" if breaking else "false"))
    print("error_count=%d" % len(errors))
    print("warning_count=%d" % len(warnings))
    print("reviewers_ran=%s" % ("" if ran is None else ran))
    print("action=%s" % action)

    if comment_out:
        icon = {"MERGE": "✅", "MERGE_AFTER_WARNINGS": "🟡",
                "DO_NOT_MERGE": "⛔", "REVIEW_INCOMPLETE": "⚠️"}.get(verdict, "•")
        L = []
        # Stable marker so the workflow updates ONE comment instead of spamming.
        L.append("<!-- sceneview-agent-review -->")
        L.append("## %s Agent review — `%s`" % (icon, verdict))
        L.append("")
        L.append("**%s**" % reason)
        L.append("")
        if breaking:
            L.append("> ⛔ **Breaking public-API / cross-platform divergence confirmed by the "
                     "impact reviewer.** This is the autonomous-merge safety gate: it stops for "
                     "the maintainer, it is not an auto-fixable warning.")
            L.append("")
        if per_reviewer:
            L.append("| Reviewer | Verdict | Errors |")
            L.append("|---|---|---|")
            for r in per_reviewer:
                L.append("| `%s` | %s | %s |" % (r.get("reviewer", "?"),
                                                 r.get("verdict", "?"),
                                                 r.get("errorCount", "?")))
            L.append("")
        if errors:
            L.append("### Confirmed errors (survived adversarial verification)")
            for e in errors:
                loc = e.get("file") or "?"
                if e.get("line"):
                    loc = "%s:%s" % (loc, e["line"])
                L.append("- **[%s]** `%s` — %s" % (e.get("reviewer", "?"), loc,
                                                   e.get("problem", "(no description)")))
                if e.get("fix"):
                    L.append("  - _fix_: %s" % e["fix"])
            L.append("")
        if warnings:
            L.append("### Warnings (%d)" % len(warnings))
            for w in warnings[:20]:
                loc = w.get("file") or "?"
                if w.get("line"):
                    loc = "%s:%s" % (loc, w["line"])
                L.append("- **[%s]** `%s` — %s" % (w.get("reviewer", "?"), loc,
                                                   w.get("problem", "(no description)")))
            if len(warnings) > 20:
                L.append("- _(+%d more)_" % (len(warnings) - 20))
            L.append("")
        if propagation:
            L.append("### Must also reach")
            for p in propagation:
                L.append("- %s" % p)
            L.append("")
        L.append("### Next step")
        L.append(action)
        L.append("")
        L.append("<sub>Posted by `pr-review.yml` — four independent reviewers "
                 "(`sv-code-reviewer`, `sv-security-reviewer`, `sv-impact-reviewer`, "
                 "`sv-doc-freshness`), every ERROR adversarially verified before it counts. "
                 "Graded by `grade-pr-review.sh`, not by the model.</sub>")
        with open(comment_out, "w") as fh:
            fh.write("\n".join(L) + "\n")

    sys.exit(1 if blocking else 0)


# ── Load ─────────────────────────────────────────────────────────────────────
# Any failure to read the reviewers' own output is REVIEW_INCOMPLETE. It must
# never degrade into a pass: a crashed agent produces no findings, and "no
# findings" would otherwise read exactly like a clean review.
if not os.path.exists(path):
    emit("REVIEW_INCOMPLETE",
         "Re-run the review (`gh workflow run pr-review.yml -f pr=%s`) and investigate why the "
         "agent produced no verdict file. Do NOT merge on a missing review." % (pr or "<n>"),
         "The reviewer agents did not produce `%s`. No review happened — this is not a pass."
         % os.path.basename(path),
         ran=0)

try:
    with open(path) as fh:
        data = json.load(fh)
    if not isinstance(data, dict):
        raise ValueError("top level is %s, expected object" % type(data).__name__)
except Exception as exc:  # noqa: BLE001 — any parse failure is the same verdict
    emit("REVIEW_INCOMPLETE",
         "Re-run the review. The verdict file exists but could not be read, so no finding can be "
         "trusted. Do NOT merge.",
         "Could not parse `%s`: %s" % (os.path.basename(path), exc),
         ran=0)

expected = data.get("reviewersExpected")
if not isinstance(expected, int) or expected <= 0:
    expected = len(REVIEWERS)

per_reviewer = data.get("perReviewer")
if not isinstance(per_reviewer, list):
    per_reviewer = []

ran = data.get("reviewersRan")
if not isinstance(ran, int):
    # Never infer a full run from a missing count — fall back to what the
    # per-reviewer table actually proves, which is the conservative direction.
    ran = len(per_reviewer)

errors = data.get("confirmedErrors")
errors = [e for e in errors if isinstance(e, dict)] if isinstance(errors, list) else []
warnings = data.get("warnings")
warnings = [w for w in warnings if isinstance(w, dict)] if isinstance(warnings, list) else []
propagation = data.get("propagation")
propagation = [str(p) for p in propagation] if isinstance(propagation, list) else []

# ── Grade (mirrors review-fanout.js) ─────────────────────────────────────────
if ran < expected:
    emit("REVIEW_INCOMPLETE",
         "NEVER merge on an incomplete review — re-run and investigate why a reviewer dropped.",
         "Only %d of %d reviewers reported. A dropped reviewer makes \"no findings\" meaningless."
         % (ran, expected),
         errors, warnings, propagation, per_reviewer, ran, expected)

if errors:
    breaking = any(e.get("reviewer") == "impact" for e in errors)
    action = ("Convert to a DRAFT PR and stop for the maintainer — a breaking public-API change "
              "or an unmirrored cross-platform divergence is never auto-fixable."
              if breaking else
              "Fix the confirmed root cause, push, and let this check re-run. Do not merge.")
    emit("DO_NOT_MERGE", action,
         "%d confirmed error(s) survived adversarial verification." % len(errors),
         errors, warnings, propagation, per_reviewer, ran, expected)

if warnings:
    emit("MERGE_AFTER_WARNINGS",
         "Address the warnings in this same PR, then merge. The gate is green — this is advice, "
         "not a block.",
         "No confirmed errors. %d warning(s) to address." % len(warnings),
         errors, warnings, propagation, per_reviewer, ran, expected)

emit("MERGE",
     "Mergeable. All four reviewers reported and none found a blocking problem.",
     "Clean pass across all %d reviewers." % expected,
     errors, warnings, propagation, per_reviewer, ran, expected)
PY
