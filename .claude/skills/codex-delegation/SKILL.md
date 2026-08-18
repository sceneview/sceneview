---
name: codex-delegation
description: Delegate bounded work to Codex CLI as a second developer under Claude's control — implementation in an isolated worktree, independent bug investigation, adversarial code review, second opinion on a design decision. Covers the codex-delegate.sh wrapper, the ChatGPT-only billing guarantee, worktree isolation, quota policy, and when NOT to delegate. Use when deciding whether to hand a task to Codex, when a call to Codex fails or reports a quota limit, or when verifying that no OpenAI API billing can occur.
---

## What this is

Codex CLI (OpenAI) is available on this machine as a **delegated developer**.
Claude Code stays the Lead Developer: architecture, planning, decisions, Git and
final verification. Codex implements, investigates and critiques.

Every call goes through one wrapper — never a raw `codex` command:

```
bash .claude/scripts/codex-delegate.sh <check|ask|review|implement> [opts]
```

The wrapper exists because the billing and isolation guarantees live in it. A
raw `codex` call bypasses all of them.

## The billing invariant

**Codex must bill Thomas's ChatGPT subscription, never the OpenAI pay-per-token
API.** This is an absolute constraint, not a preference.

Two independent mechanisms enforce it, both inside the wrapper:

1. **Preflight** — `codex login status` must report ChatGPT, *and*
   `${CODEX_HOME:-~/.codex}/auth.json` must carry `auth_mode=chatgpt` with a
   null `OPENAI_API_KEY`. Anything else exits 2 before any call is made.
   Preflight runs again *after* every call, so a mid-run switch cannot pass.
2. **Environment scrub** — `OPENAI_API_KEY`, `CODEX_API_KEY`, `OPENAI_BASE_URL`
   and eight siblings are stripped from Codex's environment with `env -u`. Even
   if a key is exported later, Codex cannot see it.

Banned outright, rejected before exec: `--with-api-key`, `--oss`,
`--dangerously-bypass-approvals-and-sandbox`, `--dangerously-bypass-hook-trust`.

**Never** run `codex login --with-api-key`, never create an OpenAI API key,
never buy credits, never enable auto top-up. If the auth mode is ever anything
other than ChatGPT, or cannot be determined, **stop and tell Thomas** — do not
work around it.

Codex is installed under nvm and is **not on the default PATH** of a Claude
session. `command -v codex` returning nothing does **not** mean it is missing;
the wrapper resolves the real path itself.

## The four modes

| Command | Sandbox | Use it for |
|---|---|---|
| `check` | — | Verify the integration. Free, no quota. Run it first when in doubt. |
| `ask` | `read-only` | Second opinion, design critique, bug investigation, error analysis. Codex **cannot** modify anything. |
| `review` | read-only | Independent adversarial review of a diff: `--base main`, `--uncommitted`, `--commit SHA`. |
| `implement` | `workspace-write` | Bounded implementation. **Refuses to write in the caller's own worktree** unless `--here`. |

Common options: `--label NAME` (names the log), `--model M`, `--timeout SECS`,
`--dir PATH`, `--new-worktree BRANCH`, `--file F`, `--schema F` (JSON Schema for
a structured answer), `-` to read the prompt from stdin.

Results land in `.claude/data/codex/<stamp>-<label>.{log,out}` — gitignored.
`.out` is the final answer alone; `.log` is the full transcript.

### Isolation

`--new-worktree fix-foo` creates branch `codex/fix-foo` in
`.claude/worktrees/codex-fix-foo/` and runs Codex there. Use it for anything
non-trivial: Claude and Codex must never edit the same files concurrently.

After Codex returns: **inspect the diff, check coherence, run the relevant
tests, then integrate.** Codex never commits, pushes or merges — `AGENTS.md`
tells it so, and Git stays Claude's responsibility.

## Cost policy — Claude quota is the scarce resource (2026-08-18)

The weekly Claude 20x allowance was at **66% by Tuesday 18 August**, resetting
**Saturday 14:00**. The week has to be finished on the remaining third, so the
default balance is shifted: **Codex first, Claude for what only Claude can do.**

Order of preference for any unit of work, cheapest first:

1. **A deterministic script** — `.claude/scripts/*` is bash: it costs **zero
   tokens** on either side. Before delegating anything, check `automation-map`
   for a script that already does it. This is the single biggest lever.
2. **Codex** (`ask` / `implement` / `review`) — bills the ChatGPT plan, a
   separate budget from Claude's. Default executor while Claude is constrained.
3. **Claude** — architecture, the final call, integration, Git, and anything
   needing the conversation's context. Not implementation grunt work.

**QA especially.** The QA harnesses (`device-qa.sh`, `ar-replay-qa.sh`, the
gates) are scripts — run them directly, never re-implement their reasoning in a
Claude turn. Screenshots are the most expensive thing a Claude session can do
(~300 kB each, re-sent on every later turn: 41% of injected volume for 0.5% of
calls). Any visual loop belongs in a subagent whose context dies with it, or in
Codex, or in a script — never in the main session.

While this policy holds, Claude should also **batch tool calls** (each call
re-bills the whole context, ~15k tokens) and avoid courtesy commands.

Revisit after the Saturday reset — this is a constraint of the moment, not a
permanent architecture.

## When to delegate

**Delegate** when the task is genuinely autonomous: a bounded implementation, a
relatively independent module, a large batch of repetitive code, a big test
suite, an independent bug hunt, a bounded refactor, a contradictory review, or a
second analysis whose independence is the point.

**Keep it** when the task leans on the conversation's global context, when it is
a central architecture decision, when it is trivial, when explaining it costs
more than doing it, when you can do it quickly and reliably, or when Codex just
produced the work and it only needs reviewing.

Do not delegate mechanically. Optimise, in this order: **quality, reliability,
speed, reasonable consumption.**

### Important decisions

Claude analyses and proposes → Codex analyses independently and hunts for flaws
→ Claude compares, decides, explains the call briefly, then implements or
delegates. Claude is always the final decision-maker.

## Prompting Codex

Send the **minimum useful context**, never the conversation transcript: the
precise goal, the paths involved, the architectural constraints that apply, the
acceptance criteria, and the tests to run. Let Codex inspect the repo itself —
it reads `AGENTS.md` at the repo root automatically, which already carries the
Git, secrets, threading, changelog and verification rules.

The repo is large: tell Codex what *not* to explore when the task is narrow, or
it will burn quota wandering.

## Quota policy

Codex consumption is a limited resource on the ChatGPT plan.

- Do not fire several large calls just to collect opinions.
- Do not re-send a whole problem when a targeted follow-up suffices.
- Reuse results already produced — they are on disk in `.claude/data/codex/`.

If the wrapper exits **3**, Codex hit a quota or rate limit. Then: never work
around it, never switch account, never create another account, never fall back
to the API. **Tell Thomas**, and carry on yourself if that is reasonable.

## Verifying and disabling

```
bash .claude/scripts/codex-delegate.sh check     # binary, auth mode, env scrub
codex login status                               # authoritative: "Logged in using ChatGPT"
```

To disable delegation, simply stop calling the wrapper — nothing runs on a hook
or a schedule. To disable it hard, `chmod -x .claude/scripts/codex-delegate.sh`,
or remove Codex's credentials with `codex logout`. Nothing in this integration
alters Claude Code's own behaviour when unused.

## Exit codes

`0` ok · `1` Codex failed · `2` preflight refused (auth, binary, banned flag,
missing isolation) · `3` quota — tell Thomas · `4` timed out.
