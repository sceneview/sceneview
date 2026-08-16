#!/usr/bin/env python3
"""Pin the release publishers' two anti-false-green contracts.

Usage: check-release-publish-verification.py [path/to/release.yml]

── What this gate is for ──────────────────────────────────────────────────
#3011: the pub.dev job carried `permissions: id-token: write` and a comment
saying OIDC was in use, and yet five consecutive releases (v4.25.0 → v4.29.0)
published nothing — the first two hung on the OAuth prompt until the run was
cancelled, the last three ended in `failure`.
The reason is that **nothing in the job ever exchanged the Actions id-token**.
`dart pub` does not do that exchange itself — `dart-lang/setup-dart` does, in
`createPubOIDCToken()`, and its own reusable publish workflow includes the
action for that single purpose while the Flutter SDK shadows its `dart`
binary. This job uses `subosito/flutter-action`, which does no such thing. So
`flutter pub publish` found no credential, fell back to interactive OAuth, and
waited on a browser that does not exist on a runner.

Every part of that is invisible to a reader: the permission is present, the
comment says OIDC, the audience is right. Only the *absence* of an exchange
step tells the truth, and an absence is exactly what a human review misses and
a gate does not.

#3021: the same class one level up — a publish step's exit code is a claim,
the registry is the fact, and only pub.dev was asking the registry.

── What it checks ─────────────────────────────────────────────────────────
Per publisher job:
  A. it calls .claude/scripts/verify-published-version.sh, AFTER its publish
     step (an order swap would verify the PREVIOUS release and pass);
and for the pub.dev job specifically:
  B. some step performs the OIDC exchange (ACTIONS_ID_TOKEN_REQUEST_URL +
     `pub token add`);
  C. the interactive-OAuth banner is a hard failure;
  D. the publish command is bounded by a `timeout` well under the job's own
     `timeout-minutes`, so a hang is attributable to the step;
  E. the publish log is not tee'd into the package directory — at v4.29.0
     `publish.log` appears in the archive listing pub was about to upload.

Plus contracts with no job of their own:
  F. release.yml does not repeat the false claim that flutter_sceneview's
     first version must still be published manually. It is on pub.dev at
     4.24.0 since 2026-07-20; a workflow that prints that sends the reader
     to fix something that is not broken.
  G. #3061 — nothing publishes without the breaking-change/patch guard having
     read real content first. Four parts, because three of them were live
     holes and the fourth is the one a later edit reopens:
       G1 the guard job invokes check-breaking-change-bump.sh with
          `--from-changelog`. WITHOUT that flag the script iterates
          `changelog.d/*.md`, which collate-changelog.sh emptied days before
          the tag exists — zero fragments, zero breaking, a green `✓` over an
          unread release. A job that cannot fail is not a gate, so a bare
          invocation is a FAILURE here, not a pass;
       G2 it resolves the version from BOTH trigger paths (tag and dispatch),
          the way publish-rn does — guarding a different version from the one
          being published is a green stamp on the wrong release;
       G3 every publishing job reaches that guard through `needs:`. Publishers
          are DISCOVERED from their commands, not read off a list, so a sixth
          one added later is covered on the day it lands;
       G4 a job that depends on the guard and carries `always()` must test the
          guard's result explicitly. `always()` runs on a SKIPPED dependency,
          and a failed guard skips every publisher — which is how create-release
          could cut a public GitHub Release for a version the guard refused.

Exit 0 = all contracts hold. Exit 1 = a contract is broken. Exit 2 = the gate
could not measure (missing file, unparseable YAML, a job it cannot find) —
never conflated with a pass, because "found nothing" must not read as a clean
bill of health.
"""

import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - CI installs it explicitly
    print("MEASUREMENT FAILED: PyYAML is not available", file=sys.stderr)
    sys.exit(2)

VERIFIER = ".claude/scripts/verify-published-version.sh"

# job name -> (human label, regex matching that job's publish command)
PUBLISHERS = {
    "publish-library": ("Maven Central", re.compile(r"publishAndReleaseToMavenCentral")),
    "publish-mcp": ("npm (sceneview-mcp)", re.compile(r"npm publish")),
    "publish-web": ("npm (sceneview-web)", re.compile(r"npm publish")),
    "publish-rn": ("npm (@sceneview-sdk/react-native)", re.compile(r"npm publish")),
    "pub-publish": ("pub.dev (flutter_sceneview)", re.compile(r"pub publish\s+--force")),
}

PUB_JOB = "pub-publish"

FALSE_CLAIM = re.compile(
    r"first version must be published manually", re.IGNORECASE
)

# ── Contract G (#3061) ────────────────────────────────────────────────────
GUARD = ".claude/scripts/check-breaking-change-bump.sh"

# A COMMAND, not a mention — same rule as every other contract here. `commands()`
# has already dropped shell comments; this also rules out an `echo` of the path.
GUARD_CALL = re.compile(
    r"^\s*(?:\w+=\S+\s+)*(?:bash|sh)\s+\S*check-breaking-change-bump\.sh\s",
    re.MULTILINE,
)

# The flag that makes the call mean anything after collation. See G1.
GUARD_CHANGELOG_MODE = re.compile(r"--from-changelog\b")

# G2: the two trigger paths, exactly as publish-rn derives its own version.
GUARD_TAG_PATH = re.compile(r"GITHUB_REF_NAME")
GUARD_DISPATCH_PATH = re.compile(r"gradle\.properties")

# G3's discovery. Commands and actions that make a release PUBLIC, most of them
# irreversibly. Deliberately a small, high-signal set: a broad net that flags
# innocent jobs is a gate someone deletes. The floor below turns a net that has
# stopped matching into a MEASUREMENT FAILURE rather than a clean bill.
PUBLISH_SHAPES = re.compile(
    r"npm\s+publish\b"
    r"|publishAndReleaseToMavenCentral"
    r"|(?:dart|flutter)\s+pub\s+publish\b"
    r"|softprops/action-gh-release"
    r"|peaceiris/actions-gh-pages"
    r"|gh\s+release\s+create\b"
    r"|pod\s+trunk\s+push\b"
    r"|twine\s+upload\b"
    r"|cargo\s+publish\b"
    r"|mvn\s+deploy\b"
)

# G4: `always()` re-enables a job whose dependencies were skipped.
ALWAYS = re.compile(r"\balways\s*\(\s*\)")


def needs_of(job):
    """A job's `needs:`, in either of YAML's two spellings."""
    if not isinstance(job, dict):
        return []
    needs = job.get("needs")
    if isinstance(needs, str):
        return [needs]
    if isinstance(needs, list):
        return [str(n) for n in needs]
    return []


def needs_closure(jobs, start):
    """Every job `start` waits on, transitively. Cycle-safe."""
    seen = set()
    stack = list(needs_of(jobs.get(start)))
    while stack:
        name = stack.pop()
        if name in seen:
            continue
        seen.add(name)
        stack.extend(needs_of(jobs.get(name)))
    return seen

failures = []


def fail(msg):
    failures.append(msg)
    print(f"  FAIL: {msg}")


def step_text(step):
    """Everything a step can carry a command in, flattened to one string."""
    if not isinstance(step, dict):
        return ""
    parts = [str(step.get("run", "")), str(step.get("uses", ""))]
    with_block = step.get("with")
    if isinstance(with_block, dict):
        parts.extend(str(v) for v in with_block.values())
    return "\n".join(parts)


def step_env(step):
    """`{VAR: value}` for a step's `env:` block, as strings.

    Separate from step_text on purpose. A step's environment is where a
    `${{ steps.*.outputs.* }}` expression BELONGS (it is data there, code
    inside `run:`), so contract A has to look for the version here — but
    folding it into step_text would let an env value satisfy contracts B-E,
    which are statements about commands.
    """
    env = step.get("env") if isinstance(step, dict) else None
    if not isinstance(env, dict):
        return {}
    return {str(k): str(v) for k, v in env.items()}


def uses_var(text, name):
    """True if `text` dereferences shell variable `name`."""
    return re.search(r"\$\{?" + re.escape(name) + r"\b", text) is not None


def commands(text):
    """`text` with shell comment lines removed.

    Load-bearing, and this suite found out the hard way: contracts B-E ask
    whether the job DOES something, and every one of them was satisfiable by a
    comment or an error string that merely mentions it. Deleting the real
    `dart pub token add` line left the gate green because the step's own
    explanatory comment two lines below still said the words. A gate that a
    comment can satisfy is a gate that describes documentation, not behaviour.
    """
    return "\n".join(
        line for line in text.splitlines() if not line.lstrip().startswith("#")
    )


# A command, not a mention: optional `VAR=value` prefixes, then dart/flutter.
TOKEN_ADD = re.compile(
    r"^\s*(?:\w+=\S+\s+)*(?:dart|flutter)\s+pub\s+token\s+add\s+https://pub\.dev",
    re.MULTILINE,
)

# Same rule for contract A. `VERIFIER in text` was a substring test, so
# `# TODO re-enable .claude/scripts/verify-published-version.sh` satisfied it —
# the headline contract of #3021, falsifiable by a comment. Reported in review
# of PR #3130.
VERIFY_CALL = re.compile(
    r"^\s*(?:\w+=\S+\s+)*(?:bash|sh)\s+\S*verify-published-version\.sh\s",
    re.MULTILINE,
)

# The version handed to the verifier must be the one THIS job published, read
# from its own check step's output. A literal would verify a release that is
# not the one running.
VERIFY_VERSION = re.compile(r"steps\.[\w-]+\.outputs\.version")

# Contract B's other half. The variable name alone is not the exchange: it
# appears verbatim inside this step's own `::error::` string, so a job that
# only echoes it — and never calls the endpoint — used to read as compliant.
# That mutant is #3011 exactly: `--env-var PUB_TOKEN` pointing at a variable
# nothing sets. Reported in review of PR #3130.
OIDC_REQUEST = re.compile(r"\$\{?ACTIONS_ID_TOKEN_REQUEST_URL")


# Contract C, in two halves so the two failures read differently: the banner
# has to be TESTED, and the test has to LEAD to a non-zero exit. The second
# regex walks from the grep line down to the first `exit`, refusing to cross
# the `fi` that closes the branch (an `exit 1` further down the step belongs to
# another condition) and refusing a literal `exit 0`.
BANNER_GREP = re.compile(r"grep[^\n]*Waiting for your authorization")
BANNER_HARD_FAIL = re.compile(
    r"grep[^\n]*Waiting for your authorization[^\n]*\n"
    r"(?:(?!\s*fi\s*$)[^\n]*\n){0,12}?"
    r"\s*exit\s+(?!0\s*$)\S+",
    re.MULTILINE,
)


def verify_invocations(text):
    """The verifier's own command lines out of a step, continuations included.

    Scoped, not step-wide: `publish-library` calls the verifier inside a loop
    whose step also carries a `VERSION=` line elsewhere, so a step-wide search
    stayed satisfied when the verifier's own argument was swapped for a
    literal. The single-line npm/pub jobs were covered; maven was not.
    Reported in review of PR #3130.
    """
    lines = text.splitlines()
    calls = []
    for i, line in enumerate(lines):
        if not VERIFY_CALL.search(line + "\n"):
            continue
        chunk = [line]
        j = i
        while lines[j].rstrip().endswith("\\") and j + 1 < len(lines):
            j += 1
            chunk.append(lines[j])
        calls.append("\n".join(chunk))
    return calls


def carries_run_version(text, env):
    """True if the verifier CALL is handed the version THIS run published.

    Two accepted shapes, and the second is the one the workflow uses since
    review of PR #3130 asked for it: the expression lives in `env:` (data)
    and the command reads `"$VERSION"` (never code). An env var that holds the
    version but is never dereferenced BY THE CALL is not accepted — that is a
    step verifying a literal with a decorative binding above it.
    """
    return any(
        VERIFY_VERSION.search(call)
        or any(
            VERIFY_VERSION.search(value) and uses_var(call, name)
            for name, value in env.items()
        )
        for call in verify_invocations(text)
    )


def main():
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".github/workflows/release.yml")
    if not path.is_file():
        print(f"MEASUREMENT FAILED: {path} does not exist", file=sys.stderr)
        return 2

    raw = path.read_text(encoding="utf-8")
    try:
        doc = yaml.safe_load(raw)
    except yaml.YAMLError as exc:
        print(f"MEASUREMENT FAILED: {path} is not parseable YAML: {exc}", file=sys.stderr)
        return 2

    jobs = (doc or {}).get("jobs")
    if not isinstance(jobs, dict) or not jobs:
        print(f"MEASUREMENT FAILED: {path} declares no jobs", file=sys.stderr)
        return 2

    # Discovery floor. A gate that silently measures nothing is worse than no
    # gate: it reports green over the exact tree it was written to catch.
    missing = [j for j in PUBLISHERS if j not in jobs]
    if missing:
        print(
            "MEASUREMENT FAILED: publisher job(s) not found in "
            f"{path}: {', '.join(sorted(missing))}. Either they were renamed — "
            "update this gate — or a publisher was deleted.",
            file=sys.stderr,
        )
        return 2

    print(f"Checking {path} ({len(PUBLISHERS)} publisher jobs)")

    # ── Contract F: no false claim anywhere in the file ────────────────────
    if FALSE_CLAIM.search(raw):
        fail(
            "release.yml still claims flutter_sceneview's first version must be "
            "published manually — pub.dev has served it since 4.24.0 (2026-07-20). "
            "An error message that sends the reader after a non-existent problem "
            "is part of the bug (#3011)."
        )

    # ── Contract G: nothing publishes without the guard (#3061) ────────────
    # Every step's commands, per job, comments already stripped — the same
    # `commands()` rule the rest of this gate runs on.
    job_cmds = {}
    for name, job in jobs.items():
        steps = job.get("steps") if isinstance(job, dict) else None
        steps = steps if isinstance(steps, list) else []
        job_cmds[name] = "\n".join(commands(step_text(s)) for s in steps)

    guard_jobs = [n for n, text in job_cmds.items() if GUARD_CALL.search(text)]
    if not guard_jobs:
        fail(
            f"no job in {path} calls {GUARD}. Five irreversible publications "
            "(Maven Central, three npm packages, pub.dev) run off a `v*` tag "
            "with nothing checking that a source-breaking change is not "
            "shipping as a semver PATCH (#3061)."
        )
    for name in sorted(guard_jobs):
        text = job_cmds[name]
        # ── G1 ────────────────────────────────────────────────────────────
        if not GUARD_CHANGELOG_MODE.search(text):
            fail(
                f"{name} calls {GUARD} without `--from-changelog`. That mode "
                "reads `changelog.d/*.md`, which collate-changelog.sh DELETED "
                "before this tag existed — so the job would count zero "
                "fragments, find zero breaking changes and print a green tick "
                "having examined nothing. A job that cannot fail is not a gate "
                "(#3061)."
            )
        # ── G2 ────────────────────────────────────────────────────────────
        if not (GUARD_TAG_PATH.search(text) and GUARD_DISPATCH_PATH.search(text)):
            fail(
                f"{name} does not resolve the release version from BOTH "
                "trigger paths ($GITHUB_REF_NAME on a tag push, "
                "gradle.properties on a workflow_dispatch) the way publish-rn "
                "does. A guard that judges a different version from the one "
                "being published is a green stamp on the wrong release (#3061)."
            )

    # ── G3: discovery, with a floor ───────────────────────────────────────
    publishing_jobs = {
        n for n, text in job_cmds.items() if PUBLISH_SHAPES.search(text)
    }
    undiscovered = set(PUBLISHERS) - publishing_jobs
    if undiscovered:
        print(
            "MEASUREMENT FAILED: the publish-command discovery missed known "
            f"publisher job(s): {', '.join(sorted(undiscovered))}. The pattern "
            "is broken, not the tree — a discovery that finds nothing must "
            "never report a pass (#3050).",
            file=sys.stderr,
        )
        return 2
    if guard_jobs:
        for name in sorted(publishing_jobs):
            if name in guard_jobs:
                continue
            if not (needs_closure(jobs, name) & set(guard_jobs)):
                fail(
                    f"{name} publishes but does not wait on the "
                    f"breaking-change guard ({', '.join(sorted(guard_jobs))}) "
                    "through `needs:`. One `needs:` edge is the whole "
                    "enforcement, so a publisher added without it ships "
                    "unguarded (#3061)."
                )

    # ── G4: `always()` must not resurrect a job behind a failed guard ─────
    for name, job in sorted(jobs.items()):
        if name in guard_jobs or not isinstance(job, dict):
            continue
        cond = str(job.get("if", ""))
        if not ALWAYS.search(cond):
            continue
        reached = needs_closure(jobs, name) & set(guard_jobs)
        for guard in sorted(reached):
            if not re.search(
                r"needs\." + re.escape(guard) + r"\.result\s*==\s*'success'", cond
            ):
                fail(
                    f"{name} carries `always()` and depends on {guard}, but its "
                    f"`if:` never requires needs.{guard}.result == 'success'. A "
                    "failed guard leaves every publisher `skipped`, which is "
                    "neither 'failure' nor 'cancelled' — so this job would run "
                    "for a version the guard just refused, with nothing "
                    "published behind it (#3061)."
                )

    for job_name, (label, publish_re) in sorted(PUBLISHERS.items()):
        job = jobs[job_name]
        steps = job.get("steps") if isinstance(job, dict) else None
        if not isinstance(steps, list) or not steps:
            print(
                f"MEASUREMENT FAILED: job {job_name} has no steps to measure",
                file=sys.stderr,
            )
            return 2

        # Comments stripped BEFORE any contract is evaluated: see commands().
        # Doing this only for the pub job left contract A — the one #3021 is
        # about — satisfiable by a commented-out call in all five jobs.
        texts = [commands(step_text(s)) for s in steps]
        envs = [step_env(s) for s in steps]
        publish_at = [i for i, t in enumerate(texts) if publish_re.search(t)]
        verify_at = [i for i, t in enumerate(texts) if VERIFY_CALL.search(t)]

        # ── Contract A ────────────────────────────────────────────────────
        if not publish_at:
            print(
                f"MEASUREMENT FAILED: job {job_name} has no step matching its "
                f"publish command /{publish_re.pattern}/",
                file=sys.stderr,
            )
            return 2
        if not verify_at:
            fail(
                f"{job_name} ({label}) never calls {VERIFIER} — it trusts the "
                "publish command's exit code, which is a claim, not the registry "
                "(#3021)."
            )
        elif max(verify_at) < max(publish_at):
            fail(
                f"{job_name} ({label}) calls {VERIFIER} BEFORE it publishes — "
                "that verifies the previous release and passes over a publish "
                "that landed nothing (#3021)."
            )
        elif not any(carries_run_version(texts[i], envs[i]) for i in verify_at):
            fail(
                f"{job_name} ({label}) does not pass a "
                "${{ steps.<check>.outputs.version }} to " + VERIFIER + " — a "
                "literal or an unrelated variable verifies some version, not the "
                "one this run published (#3021)."
            )

        if job_name != PUB_JOB:
            continue

        cmd_texts = texts
        pub_text = "\n".join(cmd_texts)

        # ── Contract B ────────────────────────────────────────────────────
        # All three in ONE step: the endpoint has to be EXPANDED (not merely
        # named in an error message), an audience has to be asked for, and the
        # result has to reach pub's token store.
        has_exchange = any(
            OIDC_REQUEST.search(t) and "audience=" in t and TOKEN_ADD.search(t)
            for t in cmd_texts
        )
        if not has_exchange:
            fail(
                f"{job_name} never exchanges the Actions id-token for a pub.dev "
                "credential. `id-token: write` alone provisions nothing — the pub "
                "client does not do the exchange itself, `dart-lang/setup-dart` "
                "does (createPubOIDCToken: getIDToken('https://pub.dev') then "
                "`dart pub token add https://pub.dev --env-var PUB_TOKEN`), and "
                "subosito/flutter-action does not. Without it `flutter pub "
                "publish` drops to interactive OAuth (#3011)."
            )

        # ── Contract C ────────────────────────────────────────────────────
        # A `grep` over the captured log, not a mention of the banner: the
        # step has to ACT on it. And "acting on it" means EXITING NON-ZERO —
        # asserting only that a grep exists let the branch degrade to a
        # `echo "::warning::"` while this gate stayed green, which is the
        # #3011 failure verbatim (three green-looking runs, nothing
        # published). Reported in review of PR #3130.
        if not BANNER_GREP.search(pub_text):
            fail(
                f"{job_name} does not treat the interactive-OAuth banner "
                '("Waiting for your authorization") as a hard failure — the exit '
                "code alone did not catch it on five releases (#3011)."
            )
        elif not BANNER_HARD_FAIL.search(pub_text):
            fail(
                f"{job_name} greps for the interactive-OAuth banner but never "
                "exits non-zero on it — a warning lets a publish that reached "
                "interactive OAuth (i.e. published nothing) finish green, which "
                "is #3011 exactly. The branch must `exit` with a non-zero status."
            )

        # ── Contract D ────────────────────────────────────────────────────
        job_timeout = job.get("timeout-minutes")
        if not isinstance(job_timeout, int):
            print(
                f"MEASUREMENT FAILED: job {job_name} has no integer "
                "timeout-minutes to compare a step timeout against",
                file=sys.stderr,
            )
            return 2
        # Scoped to the ONE step that publishes. Over the whole job's text a
        # `timeout 30 echo hi` in the --dry-run step three steps earlier
        # satisfied this, while the real publish ran unbounded. Reported in
        # review of PR #3130.
        publish_step_text = "\n".join(
            t for t in cmd_texts if re.search(r"pub publish\s+--force", t)
        )
        m = re.search(
            r"\btimeout\s+(\d+)\s+[^\n]*pub publish", publish_step_text
        )
        if not m:
            fail(
                f"{job_name}'s publish command is not wrapped in `timeout <s>` — a "
                "hang then burns the JOB timeout and lands as `cancelled`, which "
                "reads as 'someone stopped it' rather than 'the publish failed' "
                "(#3011)."
            )
        else:
            step_seconds = int(m.group(1))
            budget = job_timeout * 60 * 0.6
            if step_seconds > budget:
                fail(
                    f"{job_name}'s publish timeout ({step_seconds}s) is not well "
                    f"under the job timeout ({job_timeout}min) — cap it at or "
                    f"below {int(budget)}s so a hang is attributable to the step, "
                    "not to the job (#3011)."
                )

        # ── Contract E ────────────────────────────────────────────────────
        # A relative tee target lands in `working-directory`, i.e. inside the
        # package pub is about to archive. At v4.29.0 the run log shows
        # `├── publish.log (<1 KB)` in the file list pub was uploading.
        # The optional quote is load-bearing: `tee "publish.log"` — the v4.29.0
        # defect, quoted — slipped through a class that had no `"` in it. So
        # are the optional flags: `tee -a publish.log` walks the same relative
        # path past a pattern that expects the target first (review of #3130).
        if re.search(r"""\btee\s+(?:-\S+\s+)*["']?(?!/)(?!\$)[\w.][\w./-]*""", pub_text):
            fail(
                f"{job_name} tees the publish log to a RELATIVE path — that lands "
                "inside working-directory and ships in the published archive "
                "(observed at v4.29.0). Write it under $RUNNER_TEMP."
            )

    print()
    if failures:
        print(f"BROKEN: {len(failures)} release-publisher contract(s) violated")
        return 1
    print(
        "OK: every publisher verifies against its registry; pub.dev provisions "
        "OIDC; every publisher waits on the breaking-change guard"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
