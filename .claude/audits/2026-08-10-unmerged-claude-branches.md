# Audit — unmerged `origin/claude/*` branches

**Date:** 2026-08-10 · **Mode:** read-only (nothing checked out, nothing pushed, nothing deleted)
**Origin:** chip `task_ce398049`, decision of 2026-07-17 ("audit them one by one in a dedicated session")

## Headline

**26 branches** matched `git branch -r --list 'origin/claude/*' --no-merged origin/main`
today — not the 29 the brief quoted. The list is regenerated, not inherited.

| Verdict | Count |
|---|---|
| **DELETE** — work is on `main` today, or was deliberately reverted there | 21 |
| **RECOVER** — real work `main` still does not have | 2 |
| **ISSUE** — one claim needs a decision before the branch dies | 1 |
| **KEEP** — live branch with an open PR | 2 |

## Method, and the trap it had to walk around

`~/Projects/sceneview` is a **shallow clone** (`.git/shallow` present, `git rev-list
--count origin/main` = **12**). In that state:

- `git merge-base origin/main <branch>` **exits 1 for 24 of the 26 branches** — no
  common ancestor is reachable inside the graft.
- `git rev-list --left-right --count` therefore reports nonsense: `12 / 3286` for a
  branch that is really 2 commits ahead and 448 behind.
- **`--no-merged origin/main` is itself unreliable here** — a branch merged in a commit
  outside the 12-commit graft still lists as unmerged. The list below is an upper
  bound on the debt, not a proof that each entry is genuinely unmerged.

So fork distance and content delta were measured **server-side** via
`gh api repos/sceneview/sceneview/compare/main...<branch>`, which resolves the real
merge-base, and "is this already on `main`?" was answered by reading `origin/main`'s
tree directly (`git grep`/`git ls-tree` on the ref — the tip tree is complete even in a
shallow clone).

`ahead`/`behind` and `files` below are the server-side numbers.

## Verdicts

| Branch | Tip | ahead/behind | Subject | Already on `main`? | Verdict |
|---|---|---|---|---|---|
| `2400-clear-keyed-followup` | 2026-06-06 | 2 / 448 | Key the per-frame clear on `(renderer, isOpaque)` | **Yes** — the keyed clear lives in `SceneView.kt:351-372` with the "applies once per change instead of … a JNI `setClearOptions` on every recomposition" rationale. The branch still edits the old `Scene.kt` site. | **DELETE** |
| `2443-build-number-timestamp` | 2026-06-06 | 1 / 430 | UTC-timestamp `CFBundleVersion` for App Store | **No** — `app-store.yml:205` still computes `BUILD_NUMBER=$(( run_number + 1000 ))`, the exact fragile scheme this branch replaces. | **RECOVER** |
| `2801-parity-tally-gate` | 2026-07-22 | 1 / 136 | Gate parity-manifest section tallies | **Yes** — `check-demo-id-parity.sh` has "Invariant B — declared tally vs the bucket's real row count" plus the header tally check; `test-check-demo-id-parity.sh` exists. | **DELETE** |
| `audit-fix-mcp-web` | 2026-05-21 | 1 / 752 | MCP issue type-guard, web README CDN, "missing TS types" | **Yes, and partly *refused*** — the type-guard is at `mcp/src/issues.ts:190-228`, the README CDN line is at `4.27.0`, and `sceneview-web.d.ts` ships types. The `package.json` half is **actively reverted on `main`**: its header reads "Do NOT add main/files/publishConfig back … a manual `npm publish` from this directory shipped a broken tarball (#2058)". Recovering this branch would re-break publishing. | **DELETE** |
| `docs-1860-scene-reconstruction-parity` | 2026-05-22 | 7 / 673 | #1860 cheatsheet parity + #1049 iOS native camera modes | **Split.** #1860 is on `main` (`cheatsheet-ios.md:571-572` marks `SceneReconstructionNode` Available). #1049 is **contradicted**: the branch removes `.gimbal` ("RealityKit SDK only has .none, .tilt, .dolly"), while `main` documents `.gimbal` as a native Apple mode in 4 places (`SceneView.swift:540,1028,1137`, `CameraControls.swift:87`). One of the two is wrong about the SDK. | **ISSUE** then delete |
| `feat-1872-ios-demo-registry` | 2026-05-22 | 2 / 722 | Append-only iOS demo registry (`DemoScene.swift` + 50 files) | **Yes** — `DemoScene.swift` and the whole `Views/Demos/Scenes/` tree are on `main`. | **DELETE** |
| `feat-2148-ios-environment-demo` | 2026-05-22 | 1 / 685 | HDR Environment demo | **Yes** — `EnvironmentDemo.swift` + `EnvironmentScene.swift`. | **DELETE** |
| `feat-2149-collision-gesture-v2` | 2026-05-22 | 2 / 683 | Collision & Hit Test + Gesture Editing demos | **Yes** — `CollisionHitTestDemo.swift`, `GestureEditingDemo.swift`. | **DELETE** |
| `feat-2152-ios-advanced-demos` | 2026-05-22 | 1 / 683 | Shape Extrude, Reflection Probes, Video Texture | **Yes** — all three `*Demo.swift` on `main`. | **DELETE** |
| `feat-2152-ios-advanced-demos-v2` | 2026-05-22 | 1 / 682 | Same commit, retry | Same as above. **Duplicate of the line above.** | **DELETE** |
| `feat-2154-ios-advanced-demos-v3` | 2026-05-22 | 1 / 681 | Same commit, retry | Same. **Third copy.** | **DELETE** |
| `feat-910-ios-debug-overlay` | 2026-05-22 | 1 / 674 | Debug overlay / FPS stress test | **Yes** — `DebugOverlayDemo.swift` + `DebugOverlayScene.swift`. | **DELETE** |
| `feat-910-ios-texture-streaming` | 2026-05-22 | 2 / 679 | Texture Streaming demo | **Yes** — `TextureStreamingDemo.swift`. | **DELETE** |
| `feat-910-ios-texture-streaming-v2` | 2026-05-22 | 2 / 675 | Same commit, retry | Same. **Duplicate.** | **DELETE** |
| `feat-gesture-editing-demo` | 2026-05-22 | 2 / 684 | Same two demos as `feat-2149-…-v2` | Same. **Duplicate.** | **DELETE** |
| `festive-varahamihira-c4fb8d` | 2026-08-07 | 12 / 11 | #3037 tap reports the model, bridges sweep | **Yes** — shipped as `717b352cd` (#3037 via #3063); `SceneViewerModelListTests.swift` is on `main`. | **DELETE** |
| `fix-2224-plane-renderer-opacity` | 2026-05-27 | 2 / 617 | Translucent plane-renderer mesh | **Yes, and superseded** — `main` is on the v2 fix (`plane_renderer.mat:123` `min(alpha, 0.20)`, with the v4.16.4→0.45→0.20 history in the header). | **DELETE** |
| `gifted-brahmagupta-1d7c7e` | 2026-06-01 | 1 / 492 | Sweep stale node-type counts to "42+" | **Yes, and stale** — `main` says **46+ node types** in 17 places. Recovering it would *regress* the docs. | **DELETE** |
| `kind-varahamihira-d566be` | 2026-08-10 | 15 / 3 | Flutter demo iOS viewer | **Open PR [#3048](https://github.com/sceneview/sceneview/pull/3048)** | **KEEP** |
| `marker-anywhere-in-line` | 2026-08-10 | 1 / 0 | Breaking-marker placement in changelog fragments | **Open PR [#3078](https://github.com/sceneview/sceneview/pull/3078)** | **KEEP** |
| `pedantic-robinson-cdb53e` | 2026-05-22 | 1 / 722 | `-PversionName` honoured in android-demo | **Yes** — `samples/android-demo/build.gradle:67`. | **DELETE** |
| `perf-2267-quat` | 2026-05-28 | 1 / 545 | Direct-Quaternion world↔local overloads | **Yes** — `localToWorldQuaternion(Quaternion, Quaternion)` is in the `sceneview-core.api` dump, and `WorldQuaternionConversionTest.kt` is on `main`. | **DELETE** |
| `point-and-ask-voice` | 2026-07-20 | 1 / 208 | On-device voice input + Drop-3D mode for Point & Ask | **No** — `PointAndAskDemo.kt` exists on `main` but carries **zero** `SpeechRecognizer`/`RecognizerIntent` hits; the branch has 13 in that file plus the `RECORD_AUDIO` manifest entry. `main`'s manifest only mentions `RECORD_AUDIO` in a comment about the *removed* screen-recording system. +738/−258 of real work. | **RECOVER** |
| `rn-tap-nodename-sentinel` | 2026-08-07 | 6 / 18 | One null sentinel for `TapEvent.nodeName` | **Yes** — `react-native/.../src/index.tsx:104-206` documents exactly this contract; `__tests__/tapEvent.types.test.ts` is on `main`. | **DELETE** |
| `tree-clean-cleanup` | 2026-07-22 | 1 / 137 | Hook dispatcher reviving ~17 dead hooks | **Yes** — `.claude/scripts/hook-dispatch.sh` and `.xcodebuildmcp/config.yaml` are on `main`, `.gitignore` carries the `xcodebuildmcp` entries. | **DELETE** |
| `zen-saha-22a0ae` | 2026-07-12 | 1 / 304 | RELAY handoff note | **Yes** — `.claude/RELAY-20260712.md` is committed on `main`. | **DELETE** |

## What this says about the debt

- **7 of the 21 dead branches are literal duplicates** of another dead branch
  (`-v2`, `-v3`, and `feat-gesture-editing-demo`). They are retry branches from
  the May 2026 iOS-demo wave (#910) that were never cleaned up after the work landed.
- **The whole May-2026 cohort (11 branches, all tipped 2026-05-22) is fully covered.**
  It is one wave of iOS demo work that reached `main` by another route.
- **Two branches are traps, not debt.** `audit-fix-mcp-web` would re-break the
  `sceneview-web` npm publish (#2058), and `gifted-brahmagupta-1d7c7e` would walk the
  docs back from 46+ to 42+ node types. "Unmerged" did not mean "pending" for these —
  it meant "rejected, then abandoned rather than deleted".
- **Only 2 of 26 carry work `main` genuinely lacks.** The GC's unmerged protection is
  doing its job but has no expiry: it protects a branch forever, including branches
  whose content was superseded 700 commits ago.

## Proposed follow-up (nothing below has been executed)

### 1. Recover, as ordinary work

- `claude/2443-build-number-timestamp` — the UTC-timestamp `CFBundleVersion`. `main`'s
  `run_number + 1000` works today and breaks the day the run counter resets on a
  workflow rename. Small, self-contained, one file.
- `claude/point-and-ask-voice` — voice input + Drop-3D. Non-trivial (+738/−258 against a
  208-commit-old base); the memory note "patch voix `bc3ed0170` non mergé" matches.
  Needs a rebase and on-device QA, not a cherry-pick.

### 2. Decide before deleting

- `claude/docs-1860-scene-reconstruction-parity` — settle whether
  `RealityKit.CameraControls` actually has a `.gimbal` case. `main` assumes yes in four
  places; this branch asserts no. Whichever way it lands, `main` is what needs the fix
  — the branch itself is otherwise fully covered.

### 3. Deletion command (for a human to run — **not** run by this session)

Verify each verdict above first; this is a one-way action on 21 remote refs.

```bash
git push origin --delete claude/2400-clear-keyed-followup claude/2801-parity-tally-gate claude/audit-fix-mcp-web claude/docs-1860-scene-reconstruction-parity claude/feat-1872-ios-demo-registry claude/feat-2148-ios-environment-demo claude/feat-2149-collision-gesture-v2 claude/feat-2152-ios-advanced-demos claude/feat-2152-ios-advanced-demos-v2 claude/feat-2154-ios-advanced-demos-v3 claude/feat-910-ios-debug-overlay claude/feat-910-ios-texture-streaming claude/feat-910-ios-texture-streaming-v2 claude/feat-gesture-editing-demo claude/festive-varahamihira-c4fb8d claude/fix-2224-plane-renderer-opacity claude/gifted-brahmagupta-1d7c7e claude/pedantic-robinson-cdb53e claude/perf-2267-quat claude/rn-tap-nodename-sentinel claude/tree-clean-cleanup claude/zen-saha-22a0ae
```

`claude/docs-1860-scene-reconstruction-parity` is included above — delete it **after**
follow-up 2 is settled, not before. `claude/2443-build-number-timestamp` and
`claude/point-and-ask-voice` are deliberately absent; so are the two open-PR branches.

### 4. Fix the measurement, not just the symptom

Two things made this audit harder than it should be, and both will recur:

- **The shallow clone.** Any future branch-GC reasoning done in
  `~/Projects/sceneview` inherits a 12-commit view of `main`, where `--no-merged` and
  `merge-base` both lie. `cleanup-branches-worktrees.sh` protects unmerged branches
  from *that* view. Worth checking whether the GC script detects `.git/shallow` and
  says so, rather than silently over-protecting.
- **No expiry on the protection.** 21 of 26 protected branches were superseded, 11 of
  them by a single wave three months ago. A "delete-if-superseded" signal — e.g. the
  branch's own files all present on `main` — would have caught the entire May cohort
  without a human reading 26 diffs.
