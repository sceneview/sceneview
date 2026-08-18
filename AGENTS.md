# AGENTS.md — conventions for delegated coding agents

This file is read automatically by Codex CLI (and other agents that honour the
AGENTS.md convention) when they work in this repository. It carries the rules a
delegated developer must respect. It is **not** a project overview — read
`CLAUDE.md` and `llms.txt` for that.

**SceneView is an AI-first SDK.** Its purpose is to let an AI generate correct
3D/AR Compose code on the first try. Every API, doc and sample is judged by:
*can an AI read this and emit working code?*

## Your role

You are a **delegated developer**. A Claude Code session is the lead developer
and owns architecture, integration and Git. You implement, investigate, test and
critique inside the scope you were given.

## Git — you do not own it

- **Never `git commit`, `git push`, `git merge`, `git rebase` or `git checkout`
  another branch.** Leave your work in the working tree; the lead session
  inspects the diff and integrates it.
- Never touch `.git/config`, remotes, tags or worktrees.
- Never revert or discard changes you did not make yourself in this session —
  uncommitted work you did not author may belong to another session.
- Stay inside the directory you were given. It is usually a dedicated worktree
  precisely so that a parallel session is not disturbed.

## Secrets

Never read, print, copy or commit credentials. Specifically off limits:
`~/.codex/auth.json`, `~/.claude/`, `~/.ssh/`, `.env*`, keystores, `*.jks`,
`*.p12`, `*.mobileprovision`, anything under a password manager path. If a task
seems to require a secret, stop and say so instead of improvising.

## Hard technical rules

- **Filament JNI calls run on the main thread.** Never call
  `modelLoader.createModel*` or `materialLoader.*` from a background coroutine.
  In composables, `rememberModelInstance` already handles it.
- **Never hand-edit a generated file** — `gpt/knowledge-*.md`, every
  `CREDITS.md`, and `CHANGELOG.md` are generated and gated. Edit the source or
  the generator instead.
- **Never hand-edit `CHANGELOG.md`.** One fragment per change:
  `changelog.d/<issue-or-pr>-<slug>.md`, starting with a
  `<!-- category: Fixed -->` tag. See `changelog.d/README.md`.
- If `gradle/libs.versions.toml` bumps `filament`, `filamentWebsite` or
  `filamentWeb`, the matching `.filamat` blobs must be recompiled in the same
  change with the matching `matc` — a split version pair crashed 10 demos at
  runtime in v4.1.0. See `CONTRIBUTING.md`.
- **Public surfaces are English only** — code, comments, KDoc, commit messages
  and PR bodies included.
- A public API change is expected to reach every platform and the docs, or to
  state why not.

## Modules

| Module | Purpose |
|---|---|
| `sceneview-core/` | KMP — collision, math, geometry, animation, physics |
| `sceneview/` | Android 3D — `Scene`, `SceneScope`, node types (Filament) |
| `arsceneview/` | Android AR — `ARScene`, ARCore |
| `sceneview-compose/` | Compose Multiplatform façade — viewer subset, no AR |
| `sceneview-web/` | Kotlin/JS + Filament.js (WebGL2/WASM) |
| `SceneViewSwift/` | Apple 3D+AR — RealityKit (iOS/macOS/visionOS) |
| `flutter/` · `react-native/` | Native bridges (Android + iOS) |
| `samples/` | One demo app per platform |
| `mcp/` | `sceneview-mcp` server + packages |

Design tokens live in `DESIGN.md` — read it before generating any UI, never
hardcode colors or spacing, and support light and dark.

## Verification

Run the checks relevant to what you touched and **quote the real output**. Do
not report a test as passing without having run it. If a check cannot run in
your environment (no device, no network, no Gradle daemon), say so explicitly
rather than assuming it would pass.

Gradle builds are heavy and this machine is often near-full on disk; prefer
targeted module tasks over full builds, and mention it if you skip one.

## Reporting back

End with a short, factual summary: what you changed (file by file), what you
ran and its result, what you could not verify, and any decision you had to make
that the lead session should re-examine. Flag uncertainty rather than smoothing
it over — an unflagged wrong assumption costs far more than a question.
