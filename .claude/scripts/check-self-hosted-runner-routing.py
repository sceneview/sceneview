#!/usr/bin/env python3
"""Gate: every `runs-on` that can reach the self-hosted Mac routes correctly.

Three workflows carry the same `runs-on` expression (ci.yml, bridge-ios-compile.yml,
device-qa.yml). Keeping them identical was, until this script existed, enforced by a
comment saying "keep the three copies identical" — which is prose, not a gate.

Two independent properties are checked:

  1. DISCOVERY, not a hardcoded list. Every job in .github/workflows/ whose `runs-on`
     mentions `sceneview-mac` must be found and checked. A fourth workflow opted in by
     pasting the OLD two-term expression is caught here, which a fixed list would miss.

  2. SEMANTICS, not string equality. The extracted expression is evaluated under
     simulated event payloads: it must select the self-hosted runner for push /
     workflow_dispatch / schedule / same-repo PR, and the hosted runner for any fork PR
     or whenever the heartbeat variable is not "true".

Property 2 is what makes the `github.event_name != 'pull_request'` term non-negotiable:
`github.event.pull_request` is null on push, dispatch, schedule and workflow_call, so
dropping that term silently sends EVERY non-PR run to the paid hosted runner.

Reminder, deliberately restated where the check lives: this routing is defence in depth,
not a trust boundary. A fork PR runs the workflow file from the merge ref — the
contributor's own copy — so a hostile PR can simply delete the clause. The boundary is
the repo's fork-PR approval policy; see the `self-hosted-runner` skill.

No third-party imports: this runs on a bare CI python3.

Usage:  python3 .claude/scripts/check-self-hosted-runner-routing.py
Exit 0 = all routing expressions correct; 1 = at least one wrong.
"""
import re
import sys
from pathlib import Path

SELF, HOSTED = "sceneview-mac", "macos-15"
REPO = "sceneview/sceneview"

# ---------------------------------------------------------------- expression eval
# The subset of GitHub Actions expression syntax these lines use. `&&` and `||` return
# an OPERAND rather than a boolean (same as JS), and a property lookup that walks
# through a missing key yields null — that null is exactly what a push event produces
# for `github.event.pull_request.head.repo.full_name`.
TOKEN = re.compile(
    r"""\s*(?:
        (?P<str>'(?:[^']|'')*')
      | (?P<op>&&|\|\||==|!=|\(|\))
      | (?P<path>[A-Za-z_][A-Za-z0-9_.\-]*)
    )""",
    re.X,
)


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


def _num(v):
    if v is None or v is False:
        return 0
    if v is True:
        return 1
    if isinstance(v, str):
        if v == "":
            return 0
        try:
            return float(v)
        except ValueError:
            return float("nan")
    return v


def loose_eq(a, b):
    if isinstance(a, str) and isinstance(b, str):
        return a.lower() == b.lower()
    return _num(a) == _num(b)


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
            return self.resolve(val)
        raise SyntaxError(f"unexpected token {kind}:{val}")

    def cmp_(self):
        left = self.primary()
        while True:
            if self.eat("=="):
                left = loose_eq(left, self.primary())
            elif self.eat("!="):
                left = not loose_eq(left, self.primary())
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
    # Reject leftovers rather than ignoring them. Without this, a stray closing
    # paren — `vars.X == 'true')` — parses "successfully" with the tail dropped,
    # so a malformed expression could be judged on a prefix of itself. The
    # opposite typo, a missing close, already raised.
    if ev.i != len(ev.t):
        raise SyntaxError(f"trailing tokens after a complete expression: {ev.t[ev.i:]!r}")
    return value


# ---------------------------------------------------------------- discovery
# Read the raw text rather than parsing YAML: no PyYAML dependency, and the check sees
# exactly the bytes a reviewer sees. `jobs:` keys are 2-space indented, `runs-on:` 4.
JOB_RE = re.compile(r"^  ([A-Za-z_][\w-]*):\s*$")
RUNSON_RE = re.compile(r"^    runs-on:\s*(.*?)\s*$")
SEQ_ITEM_RE = re.compile(r"^\s+-\s*(.+?)\s*$")


def find_self_hosted_jobs(workflow_dir):
    """Yield (path, job, raw_runs_on) for every job whose runs-on names the Mac.

    Handles BOTH spellings of the value. `runs-on:` with an empty value opens a
    YAML block sequence:

        runs-on:
          - self-hosted
          - sceneview-mac

    which is the canonical way to write a label list — and which an inline-only
    regex skips silently. Skipping it is worse than missing a job: with no other
    self-hosted job in the tree the gate would take its "nothing routes here"
    branch and exit 0, reporting green over a job pinned to the persistent Mac
    with no heartbeat fallback and no fork clause at all.
    """
    for path in workflow_files(workflow_dir):
        job = None
        lines = path.read_text().splitlines()
        i = 0
        while i < len(lines):
            line = lines[i]
            i += 1
            m = JOB_RE.match(line)
            if m:
                job = m.group(1)
                continue
            m = RUNSON_RE.match(line)
            if not m:
                continue
            start = i  # 1-indexed line number of the `runs-on:` line
            value = m.group(1)
            if value:
                if SELF in value:
                    yield path, job or "?", value, {start}
                continue
            # Empty value: gather the block sequence that follows.
            items, used = [], {start}
            while i < len(lines) and SEQ_ITEM_RE.match(lines[i]):
                items.append(SEQ_ITEM_RE.match(lines[i]).group(1))
                used.add(i + 1)
                i += 1
            if items and any(SELF in it for it in items):
                yield path, job or "?", "[" + ", ".join(items) + "]", used


def workflow_files(workflow_dir):
    return sorted(workflow_dir.glob("*.yml")) + sorted(workflow_dir.glob("*.yaml"))


def unattributed_mentions(workflow_dir, attributed):
    """Lines naming the runner that discovery did NOT pick up.

    Discovery above is a set of regexes, and a regex is a claim about formatting,
    not about meaning. It sees `runs-on` only on one line, at one indentation.
    A folded scalar (`runs-on: >-` with the expression on the next line), a
    matrix indirection (`runs-on: ${{ matrix.runner }}`), or 4-space job keys
    all make it find NOTHING — and "nothing found" takes the legitimate
    opt-out branch and exits 0. The expression is 200+ characters, which is
    exactly what a human or a formatter folds, so this is not a hypothetical.

    So the gate does not get to trust its own regex. Every non-comment line in
    the workflow directory that names the runner must have been attributed to a
    job; one that was not means discovery drifted, and that is a failure rather
    than a silent green.
    """
    stray = []
    for path in workflow_files(workflow_dir):
        for n, line in enumerate(path.read_text().splitlines(), 1):
            if SELF not in line or line.lstrip().startswith("#"):
                continue
            if (path, n) not in attributed:
                stray.append((path, n, line.strip()))
    return stray


def ctx_for(event_name, head_repo=None, online="true"):
    event = {}
    if head_repo is not None:
        event["pull_request"] = {"head": {"repo": {"full_name": head_repo}}}
    variables = {} if online is None else {"SELF_HOSTED_MACOS_ONLINE": online}
    return {
        "vars": variables,
        "github": {"event_name": event_name, "repository": REPO, "event": event},
    }


# The contract, stated once. Fork cases include two near-misses that a sloppy
# `startsWith`/`contains` rewrite would wave through.
CASES = [
    ("push",                        ctx_for("push"),                                     SELF),
    ("workflow_dispatch",           ctx_for("workflow_dispatch"),                        SELF),
    ("schedule (nightly caller)",   ctx_for("schedule"),                                 SELF),
    ("PR from this repo",           ctx_for("pull_request", REPO),                       SELF),
    ("PR from a fork",              ctx_for("pull_request", "attacker/sceneview"),       HOSTED),
    ("fork w/ repo as name prefix", ctx_for("pull_request", "sceneview/sceneview-evil"), HOSTED),
    ("fork w/ repo as path suffix", ctx_for("pull_request", "evil/sceneview/sceneview"), HOSTED),
    # `pull_request_target` is the trap. It carries a FULLY POPULATED
    # `github.event.pull_request` from the fork, but its event_name is not
    # `pull_request` — so a guard that only excludes `pull_request` short-
    # circuits straight to the self-hosted branch and hands the fork the Mac.
    # No workflow here uses the trigger today (only comments in pr-review.yml
    # explaining why it is avoided), which is exactly why it needs a case: it
    # is the trigger someone reaches for when they WANT a fork PR to see
    # secrets, and by then nothing would have complained.
    ("PR_TARGET from a fork",       ctx_for("pull_request_target", "attacker/sceneview"), HOSTED),
    ("PR_TARGET from this repo",    ctx_for("pull_request_target", REPO),                 SELF),
    ("heartbeat says offline",      ctx_for("push", online="false"),                     HOSTED),
    ("heartbeat variable unset",    ctx_for("push", online=None),                        HOSTED),
]

GREEN, YELLOW, RED, OFF = "\033[0;32m", "\033[1;33m", "\033[0;31m", "\033[0m"


def main():
    root = Path(__file__).resolve().parents[2]
    workflow_dir = root / ".github" / "workflows"
    if not workflow_dir.is_dir():
        print(f"{RED}  ✗ {workflow_dir} not found{OFF}")
        return 1

    jobs = list(find_self_hosted_jobs(workflow_dir))
    failures, expressions = [], set()

    # Falsify discovery BEFORE trusting its result — including when it found
    # nothing, which is the case that used to exit 0 over a reformatted tree.
    attributed = {(path, n) for path, _, _, used in jobs for n in used}
    for path, n, text in unattributed_mentions(workflow_dir, attributed):
        failures.append(
            f"{path.relative_to(root)}:{n} names `{SELF}` but no job's `runs-on` "
            f"was attributed to it — the discovery regex has drifted, so this "
            f"gate is not seeing what it claims to check:\n      {text}"
        )

    if not jobs and not failures:
        # A genuine full opt-out (runner decommissioned). Legitimate, but never
        # silent: the routing is supposed to exist, so say the coverage is zero.
        print(f"{YELLOW}  ⚠ no job routes to `{SELF}` — self-hosted opt-in removed?{OFF}")
        return 0

    for path, job, raw, _ in jobs:
        rel = path.relative_to(root)
        # Normalise the two spellings that are stylistic, not semantic: a
        # trailing YAML comment, and a quoted scalar (several YAML linters
        # prefer quotes). Refusing those would make the gate reject a correct
        # expression — a gate that blocks legitimate work gets deleted.
        raw = re.sub(r"\s+#.*$", "", raw).strip()
        if len(raw) >= 2 and raw[0] == raw[-1] and raw[0] in "\"'":
            raw = raw[1:-1].strip()
        m = re.fullmatch(r"\$\{\{(.*)\}\}", raw, re.S)
        if not m:
            failures.append(f"{rel} :: {job} — runs-on is not a bare ${{{{ }}}} expression: {raw}")
            continue
        expr = m.group(1).strip()
        expressions.add(expr)
        for name, ctx, want in CASES:
            try:
                got = evaluate(expr, ctx)
            except SyntaxError as exc:
                failures.append(f"{rel} :: {job} — cannot parse runs-on ({exc})")
                break
            if got != want:
                failures.append(
                    f"{rel} :: {job} — on {name}: routes to {got!r}, must be {want!r}"
                )

    if len(expressions) > 1:
        failures.append(
            f"{len(expressions)} different runs-on expressions across {len(jobs)} "
            "self-hosted jobs — they must be byte-identical:\n      "
            + "\n      ".join(sorted(expressions))
        )

    if failures:
        print(f"{RED}  ✗ self-hosted runner routing is wrong{OFF}")
        for f in failures:
            print(f"    - {f}")
        print("    See the `self-hosted-runner` skill for the expression to copy.")
        return 1

    print(f"{GREEN}  ✓ self-hosted routing correct in {len(jobs)} job(s), "
          f"{len(CASES)} event cases each{OFF}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
