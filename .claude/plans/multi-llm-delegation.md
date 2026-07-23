# Multi-LLM delegation — Codex · Gemini/Antigravity · Kimi (exploration 2026-07-23)

> Exploration session: how to delegate SceneView tasks to non-Claude LLMs, with
> Claude Code as the **single orchestrator**. Status: CLIs installed and probed on
> the Mac; **none authenticated** (Thomas's step, §Auth).
> Single entry point: [`llm-delegate.sh`](../scripts/llm-delegate.sh).

## 1. Current state (measured on this machine, 2026-07-23)

| Provider | CLI | Version | Headless | Sandbox | Auth | Measured trap |
|---|---|---|---|---|---|---|
| OpenAI Codex | `codex` (npm `@openai/codex`) | 0.145.0 | `codex exec "…"` + `codex exec review` | `--sandbox read-only\|workspace-write` | `codex login` (ChatGPT Plus/Pro) or API key; `codex login status` rc=1 when signed out | — |
| Google Gemini | `agy` (Antigravity CLI) | 1.1.5 | `agy -p "…"` (`--print-timeout` 5m default) | `--sandbox` (terminal restrictions) | Interactive Google sign-in (TTY required) | ⛔ rc=0 even signed out — detect "sign in" in output |
| Moonshot Kimi | `kimi` (uv `kimi-cli`) | 1.49.0 | `kimi --print --final-message-only -p "…"` | no sandbox; `--yolo` = auto-approve | Interactive setup or `MOONSHOT_API_KEY` | ⛔ rc=0 even signed out — "LLM not set" signature |

⚠️ **The `gemini` CLI no longer exists**: Google shut it down on 2026-06-18 (individual
accounts), replaced by Antigravity CLI (`agy`, Go binary, official curl installer). The
personal Google free tier goes through Antigravity.

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
| Adversarial second-opinion review on a PR | `codex exec review` + `agy -p` | read-only, ADVISORY in triptych/review-fanout |
| Large-context audit/synthesis (doc-drift, llms-full) | `agy -p` (Gemini, 1M ctx) | read-only |
| Scaffolds, test boilerplate, Maestro flows, mechanical conversions | `kimi` (or codex) | `--write` in a throwaway worktree/clone only |
| One-off research, cross-checked factual question | any (cheapest available) | read-only |

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

## 6. Next steps

1. Thomas authenticates whatever he wants to enable (at minimum `agy`, free).
2. Smoke test: `bash .claude/scripts/llm-delegate.sh gemini "Summarize llms.txt in 5 points"`.
3. First real use: plug an external ADVISORY opinion into `review-fanout`
   (one `codex exec review` voice + one `agy` voice), compare value over 2-3 PRs.
4. If the value is there: promote to an optional triptych step + document it in
   `.claude/workflows/README.md`.
