# Multi-LLM delegation — Codex · Gemini/Antigravity · Kimi (exploration 2026-07-23)

> Exploration session: how to delegate SceneView tasks to non-Claude LLMs, with
> Claude Code as the **single orchestrator**.
> Single entry point: [`llm-delegate.sh`](../scripts/llm-delegate.sh).
>
> ⚠️ **The status below is from 2026-07-23. Re-probe before relying on it — but note
> HOW to probe.** On 2026-08-15 (#3189) three separate "tool not installed" conclusions
> were drawn on this Mac — for `codex`, `node` and `wrangler` — and **all three were
> wrong**. Every one of them came from `command -v`, which sees only a non-interactive
> shell's `PATH`; nvm-managed and project-local binaries are invisible to it. `codex` was
> installed and authenticated the whole time, and its adversarial-review leg did run.
>
> `llm-delegate.sh:110` probes with `command -v`, so its SKIP contract fires **honestly on
> a false premise** — the exit code is right and the conclusion is not. Before recording
> "X is not installed", try invoking X, or source the interactive profile. A SKIP from
> this script means "not on this shell's PATH", never "absent from the machine".

## 1. Current state (measured on this machine, 2026-07-23 — see the decay warning above)

| Provider | CLI | Version | Headless | Sandbox | Auth | Measured trap |
|---|---|---|---|---|---|---|
| OpenAI Codex | `codex` (npm `@openai/codex`) | 0.145.0 | `codex exec "…"` + `codex exec review` | `--sandbox read-only\|workspace-write` | `codex login` (ChatGPT Plus/Pro) or API key; `codex login status` rc=1 when signed out | — |
| Google Gemini | `agy` (Antigravity CLI) | 1.1.5 | `agy -p "…"` (`--print-timeout` 5m default) | `--sandbox` (terminal restrictions) | Interactive Google sign-in (TTY required) | ⛔ rc=0 even signed out — detect "sign in" in output |
| Moonshot Kimi | `kimi` (uv `kimi-cli`) | 1.49.0 | `kimi --print --final-message-only -p "…"` | no sandbox; `--yolo` = auto-approve | Interactive setup or `MOONSHOT_API_KEY` | ⛔ rc=0 even signed out — "LLM not set" signature |

⚠️ **The `gemini` CLI no longer exists**: Google shut it down on 2026-06-18 (individual
accounts), replaced by Antigravity CLI (`agy`, Go binary, official curl installer). The
personal Google free tier goes through Antigravity.

### Smoke-test results (2026-07-23, `agy` + `codex` authenticated — `codex` since uninstalled)

- **codex** ✅ clean. `codex exec --sandbox read-only` reads workspace files natively
  (no permission prompt) — read `llms.txt`, answered, `codex exec review` returns a
  usable verdict. This is the least-friction provider for delegated review.
- **gemini/agy** ✅ but with a caveat. Auth: thomas.gorisse@gmail.com, **Antigravity
  Starter Quota, model Gemini 3.6 Flash**. In headless `-p` mode `agy` **cannot run
  shell tools** (`pwd`/`cat`/`ls` are auto-denied — "a tool required the command
  permission that headless mode cannot prompt for"), and `permissions.allow`
  allow-rules in `~/.gemini/antigravity-cli/settings.json` are unreliable because the
  model composes arbitrary compound commands. ⛔ **Do NOT let `agy` roam the
  filesystem in headless mode.** The working model — and the RIGHT one for review — is
  **inline context**: the orchestrator (Claude) reads the files / builds the diff and
  embeds the text in the prompt; `agy` only reasons. Verified: an inline-diff ADVISORY
  review returns a clean verdict. `--dangerously-skip-permissions` would enable
  roaming but is refused by policy (read-only intent, throwaway-tree only at most).
- **kimi** — not authenticated yet (Moonshot login / `MOONSHOT_API_KEY` pending).

## 2. Why delegate (by order of value)

1. **Cross-vendor second opinion** — a review by a model from another family does not
   share the blind spots of 4 Claude reviewers. `codex exec review` is even a dedicated
   subcommand. Always **ADVISORY**: never a merge gate.
2. **Independent quota pools** — the Claude Max quota is per model; Codex (ChatGPT
   subscription), Antigravity (Google free tier) and Kimi (API ~$0.60/M in) are
   separate reservoirs. Offloading mechanical work preserves fable/opus for hard
   reasoning (memory rule "model routing").
3. **Gemini's 1M context** — audits/syntheses over very large volumes (llms-full,
   doc sweeps) without chunking.
4. **Near-zero marginal cost for bulk mechanical work** — Kimi K2.x is ~10-20×
   cheaper than premium tiers.

## 3. Routing matrix (extends the existing memory rule)

| Task | Engine | Mode |
|---|---|---|
| Orchestration, architecture, hard debugging, decisions, anything that commits | **Claude (fable/opus)** — never delegated | — |
| Adversarial second-opinion review on a PR | `codex exec review` (native fs) + `agy -p` (inline diff) | read-only, ADVISORY in triptych/review-fanout |
| Large-context audit/synthesis (doc-drift, llms-full) | `codex exec` (native fs) or `agy -p` (**inline content**) | read-only |
| Scaffolds, test boilerplate, Maestro flows, mechanical conversions | `kimi` (or codex) | `--write` in a throwaway worktree/clone only |
| One-off research, cross-checked factual question | any (cheapest available) | read-only |

> **Filesystem access differs by provider.** `codex exec --sandbox read-only` reads the
> workspace natively — pass a task, it reads what it needs. `agy -p` **cannot** in
> headless mode (see §1 smoke-test caveat) → the orchestrator embeds file/diff content
> inline in the prompt. Plan the delegation accordingly.

## 4. Safety rules (non-negotiable)

- External LLMs **never commit, never push, never touch `gh`**. They return text
  (answer, diff, report); Claude reviews and applies.
- **Read-only by default**; `--write` refused outside a throwaway worktree / `/tmp`
  clone (guard coded in the wrapper). Never `danger-full-access` / approval bypass.
- **Honest SKIP** (#2343): missing or signed-out CLI → exit 3 + `SKIP:`, never a
  silent green. `agy` and `kimi` return rc=0 when signed out → output-signature
  detection.
- Pro/perso separation and zero secrets in delegated prompts — same rules as any
  outbound content.

## 5. Auth — Thomas's step (once per CLI)

```bash
codex login          # ChatGPT OAuth (Plus/Pro required) — or codex login --api-key
agy                  # bare launch → Google sign-in (personal free tier OK)
kimi                 # interactive setup — or export MOONSHOT_API_KEY (key → profile-private)
```

Costs: Codex included in ChatGPT Plus ($20/mo); Antigravity free tier with a personal
Google account; Kimi membership ~$19/mo or pay-as-you-go API. **No subscription needed
to start: Antigravity alone is enough to validate the pattern.**

## 5b. Daily-use wiring (live)

The cross-vendor voice is now automatic in the review paths:

- **`llm-external-review.sh`** — the reusable primitive. `--diff <ref>` | `--pr <n>`,
  runs codex (native fs) + gemini (inline diff, capped at `GEMINI_MAX_DIFF_BYTES`=40 KB
  because headless `agy` is unreliable on large inline diffs), prints one Markdown
  ADVISORY report. Always exit 0; a missing/unauth provider → honest `SKIPPED`.
- **`review-fanout.js`** — new non-gating `External advisory` phase; result carried as
  `externalAdvisory`, never folded into `merge_recommendation`.
- **`triptych.js`** — same advisory leg, never added to `blockers`.
- **`/review`** — documents the standalone command.

Both workflow call-sites validate `diffRef`/`pr`/`branch` against a safe pattern before
shell interpolation (no command injection). `llm-delegate.sh` caps the assembled prompt
at 256 KB and fails honestly rather than truncating (ARG_MAX).

**Trial (2026-07-23) — the external voice earned its keep immediately.** Reviewing this
very branch, Codex surfaced three real defects in the delegation code itself: (1) the
review wrapper was still uncommitted, (2) `--context` passed inline content via argv →
ARG_MAX blow-up on large diffs, (3) **command injection** via unvalidated `diffRef`/`pr`
interpolated into the agent's shell command. All three fixed; the follow-up trial shows
the injection finding gone and the ARG_MAX one downgraded to an accepted byte-cap.

> **Known enhancement (not a bug):** for genuine 1M-context Gemini use, feed context via
> stdin/temp-file instead of argv to lift the 256 KB cap. Today gemini is capped at 40 KB
> for reviews anyway, and codex reads the filesystem natively, so the cap is a safe
> resolution, not a limitation of the review path.

**Trial on 3 real open PRs (2026-07-23) — signal vs noise, measured.** Ran the review on
existing SceneView PRs authored by other sessions, then manually verified each finding
against source:

| PR | Codex | Gemini | Verified? |
|---|---|---|---|
| #2882 (docs: entity-id recycling + KDoc) | KDoc on `GeometryNode`/`MeshNode` describes a `borrow a manually-created entity` capability **no public constructor exposes** → AI would emit non-compiling code | LGTM (missed it) | ✅ REAL — confirmed no `entity` param on either ctor; only the parent `RenderableNode` has one, unreachable here |
| #2868 (iOS PBR demos → IBL path) | LGTM | LGTM | — clean, no false alarms |
| #2846 (Point & Ask P2, 44 KB) | `PixelCopy` callback can resurrect `askState`→Thinking + launch inference **after** a reset (the job is created inside the callback, so `askJob?.cancel()` on reset is a no-op for the in-flight request) | SKIPPED (>40 KB cap) | ✅ REAL — confirmed the reset guard only covers the settle-delay window, not request→callback |

Result: **2 real, confirmed, merge-relevant defects on 3 PRs, zero Codex false positives.**
Both are exactly what the authoring session missed (a truthfulness bug in AI-facing KDoc; an
async-lifecycle race). Codex ≫ Gemini for this job (native fs read, no size cap, caught both;
Gemini missed one and skips large diffs). Findings flagged for the PR authors — advisory,
never auto-merged. This is the value case: an outside family catches the author's blind spots.

## 6. Next steps

1. Thomas authenticates whatever he wants to enable (at minimum `agy`, free).
2. Smoke test: `bash .claude/scripts/llm-delegate.sh gemini "Summarize llms.txt in 5 points"`.
3. First real use: plug an external ADVISORY opinion into `review-fanout`
   (one `codex exec review` voice + one `agy` voice), compare value over 2-3 PRs.
4. If the value is there: promote to an optional triptych step + document it in
   `.claude/workflows/README.md`.
