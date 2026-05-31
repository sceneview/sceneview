---
description: Review current changes at an effort level — checklist (low) → adversarial multi-agent triptych (high) → weighted scorecard (--score) → coverage (--coverage). Generator ≠ evaluator.
---

# /review — one review skill, four depths

Reviews the staged/unstaged changes (or a PR/branch) as an **independent**
evaluator — you did NOT write this code; do not defend it, be skeptical.
This skill **absorbs the former `/evaluate` and `/test`** — use the flags below.

```
/review                 # default: low — the deterministic checklist
/review low|high        # depth (see below)
/review --score         # the weighted 5-criteria scorecard (was /evaluate)
/review --coverage      # the test-coverage audit (was /test)
/review high            # adversarial multi-agent triptych via the saved workflow
/review <pr#|branch>    # target a PR number or branch instead of the working tree
```

Combine freely: `/review high --score`, `/review --coverage`, etc. Default target
is the working tree (`git diff HEAD`); a numeric/branch arg targets that instead.

---

## Depth: `low` (default) — the checklist

1. `git diff HEAD` + `git diff --name-only HEAD`; read each changed source file in full.
2. Read `CLAUDE.md` + `llms.txt` for the API/threading contract.
3. Run the checklist, output PASS/FAIL/WARN per applicable item + an overall verdict
   (✅ Ready to merge | ⚠️ Needs changes | ❌ Blocking).

**Threading (critical)** — no `modelLoader.createModel*` / `materialLoader.*` on a
background coroutine (`Dispatchers.IO`, `withContext`/`launch(Dispatchers.IO)`); async
loading uses `rememberModelInstance` (composable) or `loadModelInstanceAsync`
(imperative); all Filament JNI on the main thread.

**Compose / SwiftUI API** — nodes declared as composables inside `SceneView { }` /
`ARSceneView { }` (not imperative `addChildNode`); `rememberModelInstance` null case
handled; `LightNode.apply` is a **named** parameter; hierarchy via nested composables.

**Kotlin/Swift style** — style-guide compliant; no gratuitous `!!`; public API has KDoc;
changes minimal (no unrelated reformatting).

**Module boundaries** — `sceneview/` doesn't import `arsceneview/`; AR code lives in
`arsceneview/`.

**Filament materials** — any `.mat`/`.matc` change ships its recompiled `.filamat`
blob in the same commit (the ABI invariant, #1912).

## Depth: `high` — adversarial multi-agent triptych

Run the **`triptych` saved workflow** — the real pre-merge gate (5 independent Opus
reviewers across distinct lenses + updated-everywhere + serial visual device-QA + a
blocking gate). Tier-2 holistic review of a risky merged subsystem = `/code-review
ultra` (cloud). Use `high` for anything touching rendering, public API, threading,
breaking changes, or a user-visible demo/UI.

```
Workflow({ name: "triptych", args: { pr: <n> } })        # or { branch: "claude/..." }
```

A `BLOCKED` verdict (any reviewer `blocker:true`, any score ≤2/5, or a failed
visual-QA leg) STOPS the merge. Fix every blocker, no exceptions.

## Flag: `--score` — weighted scorecard (was /evaluate)

Score each criterion 1–5; **any 1–2 is BLOCKING**. Verify, don't trust: run
`git diff main...HEAD --stat`, build, run the relevant tests.

| Criterion | Weight | Looks for |
|---|---|---|
| **Correctness** | 3× | compiles on all targets · tests pass · no runtime crash · edge cases |
| **Safety** | 3× | no secret/PII/keystore · no security hole · no unintended breaking change · Filament-on-main-thread |
| **API consistency** | 2× | matches SceneView patterns · cross-platform naming · AI-generatable from docs |
| **Completeness** | 2× | features real (not TODOs) · docs updated (llms.txt/CLAUDE.md/README) · tests present |
| **Minimality** | 1× | no over-engineering · no unrelated changes |

Output a table with per-criterion score + notes, the weighted total /55, a verdict
(PASS / NEEDS WORK / BLOCKING), and an actionable fix per issue. **"Real product, not
a school project"** is an explicit Safety/Completeness lens — a user-visible defect is
a blocker.

## Flag: `--coverage` — test-coverage audit (was /test)

1. `git diff --name-only HEAD`; for each changed source under `sceneview/src/main/` or
   `arsceneview/src/main/`, find its test under `src/test/` (JVM, pure logic) or
   `src/androidTest/` (Filament/Compose/ARCore, `createComposeRule()`).
2. Identify untested public functions, edge cases, threading scenarios.
3. Propose ready-to-paste `@Test` functions + the file path each belongs in.

JVM unit tests for math/transform/state; instrumented tests for anything touching
Filament/Compose/ARCore; threading tests assert on the main thread
(`runOnUiThread` / `MainCoroutineRule`).

---

**Always:** skip checklist items not relevant to the diff; be concise; for `high`,
the triptych workflow's verdict is authoritative.
