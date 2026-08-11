#!/usr/bin/env python3
"""Gate: repo-hygiene's `always()` steps never run without their checkout.

`repo-hygiene` puts `if: always()` on every one of its 43 gate steps on purpose —
a PR author must see EVERY hygiene violation in one run, not fix them one check
at a time. But `always()` also fires when the job's own `actions/checkout` never
completed, and then every gate measures an empty working directory.

Measured on run 31516160366 / job 93861703920 (PR #3114, 2026-08-11): checkout
hung, consumed the full 10-minute timeout and was cancelled. All 40 downstream
steps ran against nothing and reported `failure` — "Check SceneView agent skill
is in sync with source", "Self-test sync-versions.sh", "Asset credits in sync
with catalog.json". None of them had measured anything. The job's conclusion
(`cancelled`) was correct and `CI Gate` correctly refused the merge, so this is
a DIAGNOSABILITY defect, not a correctness one: the single real cause was
visible only by listing the job's steps and noticing the first said `cancelled`
while the other forty said `failure`. Every future session pays several tool
calls to rediscover that a wall of red means "the runner lost the checkout".

So each gate carries `always() && steps.checkout.outcome == 'success'`.

Two properties are checked, and they pull in opposite directions — which is the
reason both are here. A gate that only checked "is the checkout mentioned" would
bless `success() && steps.checkout.outcome == 'success'`, restoring the
sequential-stop bug that `always()` exists to prevent.

  1. DISCOVERY, falsifiable. Every step of the job is parsed, and every `if:`
     line inside the job window must have been attributed to a parsed step. A
     step this parser fails to see is a FAILURE, never a silent pass — an
     unguarded gate that discovery skipped reads identically to no unguarded
     gate at all.

  2. SEMANTICS, not string equality. Each step's real `if:` expression is
     evaluated under simulated step outcomes (see CASES). A rewrite that is
     textually different but semantically right still passes; one that reads
     right and is wrong does not.

Plus the failure mode that would be worst of all, checked separately: every
`steps.<id>` an expression names must be the `id:` of an earlier step. Rename
the checkout's `id:` and `steps.checkout.outcome` resolves to null forever, so
all 43 gates skip and the job reports GREEN having measured nothing — a false
green strictly worse than the false red this gate was written to fix.

No third-party imports: this runs on a bare CI python3.

Usage:  python3 .claude/scripts/check-hygiene-checkout-guard.py [workflow] [job]
Exit 0 = every gate step is correctly guarded; 1 = at least one is not.
"""
import re
import sys
from pathlib import Path

# `repo-hygiene` had 44 gate steps when this gate was written. The floor exists
# for the same reason leg 19 of pre-push-check.sh has one: this parser is a set
# of regexes, and "found nothing" is a broken parser, never a clean bill of
# health (#3050). Lower it only if the job genuinely shrinks.
STEP_FLOOR = 20

GREEN, RED, OFF = "\033[0;32m", "\033[0;31m", "\033[0m"


# ---------------------------------------------------------------- expression eval
# The subset of GitHub Actions expression syntax step `if:` conditions use.
# Deliberately NOT shared with check-self-hosted-runner-routing.py's evaluator:
# that one is copied into synthetic trees by its own self-test and has no status
# functions, and the two grammars have no reason to agree.
TOKEN = re.compile(
    r"""\s*(?:
        (?P<str>'(?:[^']|'')*')
      | (?P<op>&&|\|\||==|!=|!|\(|\))
      | (?P<path>[A-Za-z_][A-Za-z0-9_.\-]*)
    )""",
    re.X,
)

# The four status-check functions. They take no arguments, so the tokenizer
# needs no `,` — any other function form dies unparsed, which is fail-closed.
STATUS_FUNCS = {"always", "success", "failure", "cancelled"}


def tokenize(src):
    pos, out = 0, []
    while pos < len(src):
        m = TOKEN.match(src, pos)
        if not m:
            if src[pos].isspace():
                pos += 1
                continue
            raise SyntaxError(f"cannot tokenize at offset {pos}: {src[pos:pos + 24]!r}")
        pos = m.end()
        if m.group("str"):
            out.append(("str", m.group("str")[1:-1].replace("''", "'")))
        elif m.group("op"):
            out.append(("op", m.group("op")))
        else:
            out.append(("path", m.group("path")))
    return out


def truthy(v):
    return not (v is None or v is False or v == 0 or v == "")


def loose_eq(a, b):
    """GitHub compares strings case-insensitively and coerces otherwise.

    Only the string/null cases can arise here (`steps.<id>.outcome` is a string
    or null, and the literals are strings), so the numeric coercion the routing
    evaluator needs is deliberately absent rather than half-implemented.
    """
    if isinstance(a, str) and isinstance(b, str):
        return a.lower() == b.lower()
    return a == b


class Ev:
    def __init__(self, toks, ctx):
        self.t, self.i, self.ctx = toks, 0, ctx

    def peek(self):
        return self.t[self.i] if self.i < len(self.t) else (None, None)

    def eat(self, val):
        if self.peek() == ("op", val):
            self.i += 1
            return True
        return False

    def resolve(self, path):
        cur = self.ctx
        for part in path.split("."):
            if not isinstance(cur, dict) or part not in cur:
                return None
            cur = cur[part]
        return cur

    def primary(self):
        if self.eat("("):
            v = self.or_()
            if not self.eat(")"):
                raise SyntaxError("unbalanced parenthesis")
            return v
        kind, val = self.peek()
        self.i += 1
        if kind == "str":
            return val
        if kind == "path":
            # `always()` and friends: a bare name immediately followed by `()`.
            if self.peek() == ("op", "("):
                if val not in STATUS_FUNCS:
                    raise SyntaxError(f"unsupported function {val}()")
                self.i += 1
                if not self.eat(")"):
                    raise SyntaxError(f"{val}() takes no arguments")
                return self.ctx["__status__"][val]
            return self.resolve(val)
        raise SyntaxError(f"unexpected token {kind}:{val}")

    def unary(self):
        if self.eat("!"):
            return not truthy(self.unary())
        return self.primary()

    def cmp_(self):
        left = self.unary()
        while True:
            if self.eat("=="):
                left = loose_eq(left, self.unary())
            elif self.eat("!="):
                left = not loose_eq(left, self.unary())
            else:
                return left

    def and_(self):
        left = self.cmp_()
        while self.eat("&&"):
            right = self.cmp_()
            left = right if truthy(left) else left
        return left

    def or_(self):
        left = self.and_()
        while self.eat("||"):
            right = self.and_()
            left = left if truthy(left) else right
        return left


def evaluate(expr, ctx):
    ev = Ev(tokenize(expr), ctx)
    value = ev.or_()
    # Reject leftovers rather than ignoring them: without this a stray closing
    # paren parses "successfully" with the tail dropped, so a malformed
    # expression would be judged on a prefix of itself.
    if ev.i != len(ev.t):
        raise SyntaxError(f"trailing tokens after a complete expression: {ev.t[ev.i:]!r}")
    return truthy(value)


# ---------------------------------------------------------------- the contract
def ctx_for(checkout_outcome, prior_gate_failed=False, run_cancelled=False,
            steps_context=True):
    """Build the expression context for one simulated job state.

    `steps_context=False` models the state GitHub does not document either way:
    a step that never completed may simply be absent from the `steps` context.
    The guard has to be right whether `steps.checkout.outcome` is `'cancelled'`
    or null, so both are cases rather than an assumption.
    """
    steps = {}
    if steps_context and checkout_outcome is not None:
        # `conclusion` equals `outcome` here: no step in this job sets
        # `continue-on-error`, so accepting either spelling is correct rather
        # than lax — a gate that refused the other one would block a rewrite
        # that is not wrong.
        steps["checkout"] = {"outcome": checkout_outcome, "conclusion": checkout_outcome}
    lost = checkout_outcome != "success"
    return {
        "steps": steps,
        "__status__": {
            "always": True,
            # "none of the previous steps have failed or been cancelled" —
            # the checkout counts as a previous step.
            "success": not lost and not prior_gate_failed,
            "failure": lost or prior_gate_failed,
            "cancelled": run_cancelled,
        },
    }


RUN, SKIP = "run", "skip"

# Each row states one thing the guard must do. The four checkout-lost rows are
# the bug this gate was written for; the two run-anyway rows are the properties
# that must NOT regress while fixing it.
#
# The rows OVERLAP on purpose — a bare `always()` is refused by all four
# checkout-lost rows at once, because it runs whatever the outcome was. Overlap
# is not redundancy: each row names one concrete state the runner really
# produces, and section 5 of test-check-hygiene-checkout-guard.sh pins every row
# but the first with a near-miss guard that ONLY that row refuses, deletes the
# row, and requires the fixture to go green. A row no fixture uniquely exercises
# could be dropped with the suite still green, so "load-bearing" here is
# measured, not asserted. An earlier draft of this comment claimed the row below
# was the only one refusing a bare `always()`; that mutation run is what
# disproved it.
CASES = [
    # The readable baseline: the gates must actually run. Deliberately NOT
    # claimed unique — any guard that breaks this also breaks rows 2 and 3, so
    # no fixture can isolate it. It stays because a contract whose happy path is
    # only implied is one the reader has to reconstruct.
    ("checkout ok, nothing failed yet",
     ctx_for("success"), RUN),
    # The reason the guard is `always() && …` and never `success() && …`.
    # `success()` is false the moment any earlier gate fails, which is exactly
    # the fix-them-one-at-a-time behaviour this job exists to avoid.
    ("checkout ok, an earlier gate failed",
     ctx_for("success", prior_gate_failed=True), RUN),
    # Unchanged behaviour, pinned so the fix cannot quietly narrow it: a bare
    # `always()` runs during a cancelled run, and the guarded form still does.
    ("checkout ok, the run was cancelled",
     ctx_for("success", run_cancelled=True), RUN),
    # The measured incident: checkout ate the job timeout and was cancelled.
    ("checkout cancelled by the job timeout",
     ctx_for("cancelled"), SKIP),
    ("checkout failed outright",
     ctx_for("failure"), SKIP),
    ("checkout was skipped",
     ctx_for("skipped"), SKIP),
    # The row that refuses a DENYLIST guard
    # (`outcome != 'cancelled' && outcome != 'failure' && …`): right for every
    # outcome it names, wrong for the one it cannot name. GitHub does not
    # document whether a step that never completed appears in the `steps`
    # context at all, so the guard has to hold for a null outcome too.
    ("checkout absent from the steps context",
     ctx_for("cancelled", steps_context=False), SKIP),
]


# ---------------------------------------------------------------- discovery
# Read the raw text rather than parsing YAML: no PyYAML dependency, and the
# check sees exactly the bytes a reviewer sees.
JOB_RE = re.compile(r"^  ([A-Za-z_][\w-]*):\s*$")
STEP_RE = re.compile(r"^      - (.*)$")
KEY_RE = re.compile(r"^        ([A-Za-z_][\w-]*):\s?(.*)$")
IF_RE = re.compile(r"^\s+if:\s*(.*)$")


class Step:
    def __init__(self, line_no):
        self.line = line_no
        self.keys = {}
        self.if_line = None

    @property
    def label(self):
        return self.keys.get("name") or self.keys.get("uses") or "(unnamed)"


def job_window(lines, job):
    """Return (start, end) line indices of the job's body, or None."""
    start = None
    for i, line in enumerate(lines):
        m = JOB_RE.match(line)
        if m and m.group(1) == job:
            start = i + 1
            continue
        if start is not None and m:
            return start, i
    return (start, len(lines)) if start is not None else None


def parse_steps(lines, start, end):
    """Parse the job's steps, and report every `if:` the parse did not claim.

    The second return value is what makes discovery falsifiable. A parser that
    silently loses a step reports "all guarded" over an unguarded gate, which is
    the same green as a correct tree.

    Block scalars (`run: |`) are consumed as literal text rather than scanned.
    A multi-line `run:` is shell, not YAML, and a line inside one that merely
    LOOKS like a step key is not a step key — without this, a step whose script
    happened to print or write `if:` would be reported as an unattributed
    condition, i.e. this gate failing CI over a false positive of its own making.
    """
    steps, cur = [], None
    claimed, literal = set(), set()
    block_indent = None
    for i in range(start, end):
        line = lines[i].rstrip("\n")
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        # Inside a block scalar: everything more-indented than the key belongs
        # to the scalar, whatever it looks like.
        if block_indent is not None:
            if indent > block_indent:
                literal.add(i + 1)
                continue
            block_indent = None
        if line.lstrip().startswith("#"):
            continue

        m = STEP_RE.match(line)
        if m:
            cur = Step(i + 1)
            steps.append(cur)
            # A step's first key may sit on the `- ` line itself. Re-indent it
            # to the 8 columns KEY_RE expects rather than writing a second regex.
            m, indent = KEY_RE.match("        " + m.group(1)), 8
            if m is None:
                continue
        else:
            m = KEY_RE.match(line)
            if m is None or cur is None:
                continue

        key, value = m.group(1), m.group(2).strip()
        cur.keys[key] = value
        if key == "if":
            cur.if_line = i + 1
            claimed.add(i + 1)
        if value[:1] in ("|", ">"):
            block_indent = indent
    unattributed = [
        (i + 1, lines[i].strip())
        for i in range(start, end)
        if IF_RE.match(lines[i].rstrip("\n"))
        and not lines[i].lstrip().startswith("#")
        and i + 1 not in claimed
        and i + 1 not in literal
    ]
    return steps, unattributed


def strip_expr(raw):
    """Normalise an `if:` value to a bare expression, or raise."""
    raw = raw.strip()
    if not raw:
        raise SyntaxError("empty or block-scalar `if:` — this gate cannot read it")
    if raw[0] in "|>":
        raise SyntaxError(f"block scalar `if: {raw}` — write it on one line")
    if len(raw) >= 2 and raw[0] == raw[-1] and raw[0] in "\"'":
        raw = raw[1:-1].strip()
    m = re.fullmatch(r"\$\{\{(.*)\}\}", raw, re.S)
    if m:
        raw = m.group(1).strip()
    return raw


def main():
    argv = sys.argv[1:]
    root = Path(__file__).resolve().parents[2]
    workflow = Path(argv[0]) if argv else root / ".github" / "workflows" / "ci.yml"
    job = argv[1] if len(argv) > 1 else "repo-hygiene"

    if not workflow.is_file():
        print(f"{RED}  ✗ {workflow} not found{OFF}")
        return 1

    lines = workflow.read_text().splitlines(keepends=True)
    window = job_window(lines, job)
    if window is None:
        print(f"{RED}  ✗ no `{job}:` job in {workflow}{OFF}")
        return 1
    start, end = window

    steps, unattributed = parse_steps(lines, start, end)
    failures = []

    for n, text in unattributed:
        failures.append(
            f"line {n}: an `if:` this gate could not attribute to a step — the "
            f"step parser has drifted, so the guard is not being checked here:"
            f"\n      {text}"
        )

    # Every step must resolve to exactly one body. A step with neither `run:`
    # nor `uses:` means the parser merged two steps together and is now judging
    # a body it cannot see.
    for st in steps:
        if ("run" in st.keys) == ("uses" in st.keys):
            failures.append(
                f"line {st.line}: step {st.label!r} has "
                f"{'both' if 'run' in st.keys else 'neither'} `run:` and `uses:` "
                "— the step parser has drifted"
            )

    if len(steps) < STEP_FLOOR:
        failures.append(
            f"only {len(steps)} step(s) parsed in `{job}` (floor {STEP_FLOOR}) — "
            "that is a broken parser, not a small job"
        )

    # `id:`s available to an expression: only steps that already ran.
    seen_ids, gates = set(), []
    for st in steps:
        if "if" in st.keys:
            gates.append((st, set(seen_ids)))
        if "id" in st.keys:
            seen_ids.add(st.keys["id"])

    # The false-green case. `steps.typo.outcome` is null under EVERY simulated
    # state, so a renamed id makes every gate skip and the job go green having
    # measured nothing — which no amount of semantic evaluation would notice,
    # because "skip on a lost checkout" is satisfied vacuously.
    for st, available in gates:
        for ref in re.findall(r"\bsteps\.([A-Za-z_][\w-]*)\.", st.keys["if"]):
            if ref not in available:
                failures.append(
                    f"line {st.if_line}: step {st.label!r} reads `steps.{ref}.` but no "
                    f"earlier step in `{job}` has `id: {ref}` — it resolves to null, so "
                    "this gate would skip on every run and the job would go GREEN "
                    "having measured nothing"
                )

    for st, _ in gates:
        try:
            expr = strip_expr(st.keys["if"])
        except SyntaxError as exc:
            failures.append(f"line {st.if_line}: step {st.label!r} — {exc}")
            continue
        for name, ctx, want in CASES:
            try:
                got = RUN if evaluate(expr, ctx) else SKIP
            except SyntaxError as exc:
                failures.append(
                    f"line {st.if_line}: step {st.label!r} — cannot parse `if:` ({exc})"
                )
                break
            if got != want:
                failures.append(
                    f"line {st.if_line}: step {st.label!r} — on '{name}' it would "
                    f"{got}, must {want}\n      if: {expr}"
                )
                break

    if failures:
        print(f"{RED}  ✗ `{job}` gate steps are not correctly guarded on the checkout{OFF}")
        for f in failures:
            print(f"    - {f}")
        print("      Every gate step needs: "
              "if: always() && steps.checkout.outcome == 'success'")
        return 1

    print(f"{GREEN}  ✓ {len(gates)}/{len(steps)} `{job}` steps guarded on the checkout, "
          f"{len(CASES)} job states each{OFF}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
