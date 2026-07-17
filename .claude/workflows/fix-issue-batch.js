export const meta = {
  name: 'fix-issue-batch',
  description: 'Continuous issue cycle: pick open issues (priority order, skip in-progress), then pipeline() one lean-clone agent per issue that claims → fixes → self-reviews → fire-and-forget merges → releases the claim. Replace-on-completion, no barrier, disk-gated.',
  phases: [
    { title: 'Preflight', detail: 'disk-gated-spawn-check.sh; if no issues given, one agent picks the top max||6 open issues in priority order, excluding in-progress' },
    { title: 'Cycle', detail: 'pipeline() over the chosen issues — one lean-clone agent each: claim → challenge → fix → tests → changelog → self-review → open PR (trivial: --auto merge; medium+: PR only)' },
    { title: 'Review', detail: 'medium+ PRs only: review-fanout (4 adversarial reviewers) → graded merge, or block/draft on a confirmed ERROR / breaking public-API' },
  ],
}

// args: { issues?: number[], max?: number }
//   issues — explicit issue numbers to drive. If empty/omitted, the preflight
//            agent queries open issues and selects the top `max` in priority order.
//   max    — cap on auto-selected issues (default 6).
// Be robust to args arriving as a JSON string (some invocation paths stringify it — same guard as review-fanout.js).
let a = args
if (typeof a === 'string') {
  try { a = JSON.parse(a) } catch {
    throw new Error('fix-issue-batch: args must be JSON — got a non-JSON string: ' + a.slice(0, 120))
  }
}
a = a || {}
const MAX = Number(a.max) > 0 ? Number(a.max) : 6
const GIVEN = Array.isArray(a.issues) ? a.issues.map(Number).filter(n => Number.isFinite(n) && n > 0) : []

const REPO = 'sceneview/sceneview'

// ── Phase 0: preflight ──────────────────────────────────────────────────────────
phase('Preflight')

// Disk gate — the script owns the threshold (default 15 GB). We don't re-encode it;
// we just run it and surface the verdict. The runtime caps build-heavy concurrency
// at ~2 agents regardless, but a hard disk floor must be respected before fan-out.
const DISK_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    freeGb: { type: 'number', description: 'DISK_FREE_GB parsed from the script output' },
    safe: { type: 'boolean', description: 'true iff the script exited 0 (free >= threshold)' },
    raw: { type: 'string', description: 'the script stdout/stderr verbatim' },
  },
  required: ['freeGb', 'safe', 'raw'],
}
const disk = await agent(
  `You are a one-shot preflight probe for the SceneView issue cycle. Run EXACTLY this, capturing combined output and the exit code:

  bash .claude/scripts/disk-gated-spawn-check.sh; echo "EXIT=$?"

The script prints a machine-readable \`DISK_FREE_GB=<n>\` line and exits 0 when free space clears its threshold (default 15 GB), 1 below it. Do NOT run any cleanup, do NOT spawn anything, do NOT clone — just report. Parse DISK_FREE_GB into freeGb, set safe=true iff EXIT=0, and put the verbatim output in raw.`,
  { label: 'preflight:disk', phase: 'Preflight', schema: DISK_SCHEMA, model: 'haiku', effort: 'low' })

if (disk) {
  log(`Disk preflight: ${disk.freeGb} GB free — ${disk.safe ? 'SAFE to spawn' : 'BELOW threshold (cleanup advised; build-heavy fan-out risky)'}. Hard cap ~2 build-heavy agents (runtime also caps concurrency).`)
} else {
  log('Disk preflight returned nothing — proceeding cautiously; the runtime concurrency cap (~2 build-heavy agents) still applies.')
}

// ── Issue selection ──────────────────────────────────────────────────────────────
// Either the caller named issues, or one agent picks them in priority order.
const PICK_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    issues: {
      type: 'array',
      description: 'Chosen issue numbers, already ordered by priority (highest first).',
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          number: { type: 'number' },
          title: { type: 'string' },
          priority: { type: 'string', enum: ['CI', 'BUG_CRITICAL', 'BUG', 'PARITY', 'REFACTO', 'DOCS', 'TEST', 'OTHER'] },
        },
        required: ['number', 'title', 'priority'],
      },
    },
    note: { type: 'string' },
  },
  required: ['issues', 'note'],
}

let chosen = []
if (GIVEN.length) {
  chosen = GIVEN.map(n => ({ number: n, title: `(caller-specified #${n})`, priority: 'OTHER' }))
  log(`Issue set supplied by caller: ${GIVEN.map(n => '#' + n).join(', ')}`)
} else {
  const pick = await agent(
    `You are the issue-selection probe for SceneView's continuous fix cycle. Run:

  gh issue list --repo ${REPO} --state open --json number,title,labels --limit 40

From the result:
- EXCLUDE any issue carrying the \`in-progress\` label (already claimed by another session — never re-pick).
- EXCLUDE pure-tracking umbrellas and anything obviously needing a human design call.
- Rank the rest by label priority: CI > BUG_CRITICAL > BUG > PARITY > REFACTO > DOCS > TEST. Map common label spellings (e.g. "bug", "critical", "documentation", "refactor", "parity", "tests", "ci/build") to those buckets; if an issue has no priority-signalling label, treat it as OTHER and rank it AFTER all labelled ones, then ascending by issue number.
- Pick the TOP ${MAX}, ordered highest-priority first (ties broken by ascending number).

Return the chosen issues with each one's number, title, and resolved priority bucket. Do NOT claim, clone, or modify anything — selection only.`,
    { label: 'preflight:select-issues', phase: 'Preflight', schema: PICK_SCHEMA, model: 'sonnet', effort: 'medium' })
  chosen = (pick && Array.isArray(pick.issues) ? pick.issues : []).slice(0, MAX)
  log(`Auto-selected ${chosen.length} issue(s): ${chosen.map(i => `#${i.number}[${i.priority}]`).join(', ') || '(none open / all in-progress)'}`)
}

if (!chosen.length) {
  return {
    verdict: 'NO_WORK',
    disk: disk ? { freeGb: disk.freeGb, safe: disk.safe } : null,
    results: [],
    note: GIVEN.length
      ? 'Caller supplied no resolvable issue numbers.'
      : 'No open, non-in-progress issues matched the priority filter — backlog drained or everything is already claimed.',
  }
}

// ── Phase 1: the cycle — one fix agent per issue, replace-on-completion ───────────
phase('Cycle')

// Structured result every fix agent must return.
// outcome:
//   'merged'                 — trivial scope, fire-and-forget --auto merge (worker self-reviewed).
//   'pr-open-pending-review' — medium+ scope: PR opened WITHOUT --auto; stage 2 runs review-fanout.
//   'skipped' / 'failed'     — as before.
const FIX_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    issue: { type: 'number' },
    outcome: { type: 'string', enum: ['merged', 'pr-open-pending-review', 'skipped', 'failed'] },
    scope: { type: 'string', enum: ['trivial', 'medium-plus'], description: 'worker self-classification; medium-plus ⇒ stage-2 adversarial review before merge' },
    pr: { type: 'number', description: 'PR number if one was opened, else omit' },
    branch: { type: 'string' },
    skipReason: { type: 'string', enum: ['claim-collision', 'stale-already-fixed', 'not-reproducible', 'none'] },
    challengeVerdict: { type: 'string', description: 'one line: did the probe confirm or DISPROVE the issue\'s prescribed root cause, and what was actually fixed' },
    compiled: { type: 'boolean' },
    testsRun: { type: 'boolean' },
    changelogFragment: { type: 'string', description: 'path of the changelog.d/*.md fragment added, or empty if none' },
    selfReview: { type: 'string', description: 'one-line summary of the 5-angle self-review verdict' },
    note: { type: 'string' },
  },
  required: ['issue', 'outcome', 'note'],
}

// The single per-issue agent brief. It runs OFF worktree-isolation: it does its own
// lean clone --depth 1 + sparse-checkout into /tmp (the validated isolation per the
// monster-clone footgun), so the workflow does NOT pass isolation:'worktree'.
function fixBrief(num, title, priority) {
  return `You are a SOLO background fix agent for ONE SceneView issue: #${num} — "${title}" (priority bucket ${priority}). SceneView is an AI-first 3D/AR SDK (Android Jetpack Compose + Filament; Apple SwiftUI + RealityKit; Web Filament.js; shared Kotlin Multiplatform core). You own this issue end to end and you DO NOT spawn nested agents (the harness rejects them — issue #1243): do a single self-review pass yourself.

Repo: ${REPO}. Git email = thomas.gorisse@gmail.com. English everywhere. Remote = origin.

Follow these steps IN ORDER. If a step's gate fails, STOP and return the matching structured outcome — never push half-work.

== 1. CLAIM (kills the #2300 race) ==
From the MAIN repo checkout (current working directory), run:

  bash .claude/scripts/claim.sh ${num}

If it exits 2 → COLLISION: another session already holds this issue. STOP immediately. Return { issue:${num}, outcome:"skipped", skipReason:"claim-collision", compiled:false, testsRun:false, changelogFragment:"", selfReview:"n/a", note:"claim.sh exited 2 — already in-progress, did not clone." }. Do NOT clone, do NOT release a claim you never took.
If it exits 0 → you hold the claim; continue. (claim.sh adds the GitHub \`in-progress\` label + a STATE.md row.)

== 2. LEAN CLONE into /tmp (NEVER a full clone — the monster-clone footgun) ==
A full clone is ~2.3 GB; a lean sparse one is ~0.3-0.6 GB. Pick a short slug from the issue title (lowercase, hyphens). Then:

  git clone --depth 1 https://github.com/${REPO}.git /tmp/sv-${num}
  cd /tmp/sv-${num}
  git sparse-checkout set sceneview arsceneview sceneview-core gradle buildSrc .claude .github changelog.d
  # add ONLY the extra module(s) this issue actually touches, e.g.:
  #   git sparse-checkout add samples/android-demo      (Android demo)
  #   git sparse-checkout add SceneViewSwift samples/ios-demo   (Apple)
  #   git sparse-checkout add sceneview-web samples/web-demo     (Web)
  #   git sparse-checkout add docs llms.txt mcp                  (docs / MCP)
  git checkout -b claude/${num}-<slug> origin/main

If any script later complains about a missing path: \`git sparse-checkout add <path>\`. ALL remaining work happens inside /tmp/sv-${num}.

== 3. CHALLENGE → IMPLEMENT (the issue's prescribed root cause is OFTEN wrong) ==
STALENESS + reproduce first: \`git fetch origin\`, scan \`git log --oneline HEAD..origin/main\` and the diffs of recently-merged PRs touching your files — stale "already fixed" issues are common (a title-only scan is not enough). If already fixed/obsoleted on origin/main:
  - close honestly: \`gh issue comment ${num} --repo ${REPO} --body "Verified fixed on main as of <SHA> — closing as already-resolved."\` then \`gh issue close ${num} --repo ${REPO}\`,
  - release the claim (\`bash .claude/scripts/claim.sh --release ${num}\`),
  - rm -rf /tmp/sv-${num},
  - return outcome:"skipped" with skipReason:"stale-already-fixed" (or "not-reproducible").
Otherwise reproduce the bug, then run a DETERMINISTIC PROBE that tests the issue's PRESCRIBED cause/fix (does the symbol exist in the consumed version? is the divergence actually where the issue claims? a standalone repro of the asserted mechanism). If the probe DISPROVES it → **diverge with evidence** and fix the REAL cause — this is why #2354 avoided a needless Earcut port and #2361 avoided an uncompilable \`key=\` fix (cf. feedback_reproduce_before_prescribed_fix). Capture one line for challengeVerdict. Then implement the MINIMAL correct option (clean; breaking changes only if justified, but NEVER bump major — 4 is FROZEN). If you change ANY public API on one renderer, mirror it on the others or leave a documented honest "Coming soon" (iOS V1 is a strict subset of Android — never hide a gap).

== 4. COMPILE + TEST (only what's relevant) ==
Compile the modules you touched, e.g.:
  ./gradlew :sceneview:compileReleaseKotlin :arsceneview:compileReleaseKotlin
Run the RELEVANT unit tests only (never the full connectedDebugAndroidTest after a small change — feedback_no_full_test_suite_per_commit.md), e.g.:
  ./gradlew :sceneview:testDebugUnitTest :arsceneview:testDebugUnitTest
  (+ :sceneview-core:testDebugUnitTest / MCP \`cd mcp && npm test\` if you touched those)
If it does not compile or a relevant test fails and you cannot fix it cleanly → STOP, release the claim, rm -rf the clone, return outcome:"failed" with the error in note. NEVER push code that does not compile.

== 5. CHANGELOG FRAGMENT + version sync ==
Add ONE fragment (do NOT edit CHANGELOG.md — see changelog.d/README.md):
  changelog.d/${num}-<slug>.md
with a leading \`<!-- category: Fixed -->\` (or Added/Changed/Removed/Tests/Docs) tag line and one bullet:
  - **Headline ([#${num}](https://github.com/${REPO}/issues/${num})).** One or two sentences.
If you changed any version-bearing file, run \`bash .claude/scripts/sync-versions.sh\` (\`--fix\` if it reports a MISMATCH) — stale version/generated files are the #1 red-main cause. (Most single-issue fixes touch NO version file; only run it if versions moved.)

== 6. SELF-REVIEW — 5 angles (Tier-1, single pass, NO nested agents) ==
Review your own diff and FIX anything actionable before merge:
  - Correctness: does it actually fix #${num}? edge cases, null-handling (rememberModelInstance returns null while loading), no behavior-preserving violation.
  - Threading: Filament JNI on the MAIN thread (never createModel*/materialLoader.* off a background coroutine); no coroutine leak / race / lifecycle bug.
  - API consistency: Compose/SwiftUI idiom (declarative node composables; LightNode apply is a NAMED param), naming/visibility/default parity, KDoc on new public symbols, no deprecated-API reintroduction.
  - Minimality: smallest change that fixes it; no dead code, no unrelated churn, no accidental public surface.
  - Cross-platform: Android/iOS/Web parity mirrored or honestly deferred; llms.txt + agents/sceneview* skills + cheatsheets stay truthful for any public change.
Capture a one-line verdict for selfReview.

== 7. OPEN PR + MERGE — GRADED BY SCOPE (then EXIT, never watch CI) ==
First self-classify SCOPE: "trivial" = < ~20 lines AND no public API / renderer / threading / \`.filamat\` shader / machine-readable contract touched. Anything else = "medium-plus".
  git add -A && git commit -m "Fix <headline> (#${num})

Closes #${num}

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
  git push -u origin claude/${num}-<slug>
  gh pr create --repo ${REPO} --base main --head claude/${num}-<slug> --title "Fix <headline> (#${num})" --body "Closes #${num}

<what changed · the challengeVerdict · the 5-angle self-review summary · 'scope: trivial|medium-plus' · for trivial: 'self-reviewed inline; review-fanout skipped because trivial'>

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
- **scope = trivial** → fire-and-forget: \`gh pr merge <PR#> --repo ${REPO} --squash --auto\`, then go to step 8 (release claim + clean). Return outcome:"merged".
- **scope = medium-plus** → do **NOT** \`--auto\` merge. The ORCHESTRATOR (stage 2) runs \`review-fanout\` on your PR and merges/blocks (a breaking public-API change → draft + maintainer). rm -rf your /tmp clone but **DO NOT release the claim** (stage 2 releases it after the merge decision). Return outcome:"pr-open-pending-review", scope:"medium-plus", pr:<PR#>.
Do NOT sit watching CI either way.

== 8. RELEASE CLAIM + CLEAN UP (trivial scope only — medium+ leaves the claim for stage 2) ==
  bash .claude/scripts/claim.sh --release ${num}
  rm -rf /tmp/sv-${num}

Return the structured result, e.g. outcome:"merged", scope:"trivial", pr:<PR#>, branch:"claude/${num}-<slug>", challengeVerdict:"<one line>", compiled:true, testsRun:true, changelogFragment:"changelog.d/${num}-<slug>.md", selfReview:"<one line>", note:"<short summary>". (Always rm -rf /tmp/sv-${num} before returning, even on skip/fail.)

Hard rules: no polling/sleep loops; no raw adb (android CLI / lib/android-cli.sh only); no image > 1800px; never bump major; never push uncompiled code; always rm -rf /tmp/sv-${num} before returning (even on skip/fail).`
}

// pipeline() = replace-on-completion with NO barrier: as each per-issue agent
// returns, the next item flows through immediately (the runtime caps how many
// build-heavy agents run at once, ~2). Each item passes through exactly one stage.
// pipeline() stage args (EMPIRICALLY VERIFIED): arg0 = prevResult (== the item itself
// for the first/only stage), arg1 = ALWAYS the originalItem, arg2 = index. We read the
// issue from arg1 (originalItem) so this stays correct regardless of stage position.
const results = await pipeline(
  chosen,
  // Stage 1 — fix: claim → challenge → fix → self-review → open PR. Trivial scope fire-and-forget
  // merges itself; medium+ opens the PR WITHOUT --auto and returns 'pr-open-pending-review'.
  (_prev, item) =>
    agent(fixBrief(item.number, item.title, item.priority), {
      label: `fix:#${item.number}`,
      phase: 'Cycle',
      schema: FIX_SCHEMA,
      model: 'opus',
      effort: 'high',
    }),
  // Stage 2 — graded review (medium+ only): the orchestrator-level adversarial fan-out the worker
  // cannot run itself (#1243). Trivial/skipped/failed pass straight through.
  async (fixRes, item) => {
    if (!fixRes || fixRes.outcome !== 'pr-open-pending-review' || !fixRes.pr) return fixRes
    const branch = fixRes.branch || `claude/${item.number}`
    let review
    try {
      review = await workflow('review-fanout', { diffRef: `origin/main...origin/${branch}`, issue: item.number, pr: fixRes.pr })
    } catch (e) {
      return { ...fixRes, outcome: 'blocked-by-review', reviewRecommendation: 'REVIEW_ERROR', note: `review-fanout threw (${String(e)}) — PR #${fixRes.pr} left open + claim held for the orchestrator` }
    }
    const rec = review && review.merge_recommendation
    if (rec === 'MERGE' || rec === 'MERGE_AFTER_WARNINGS') {
      await agent(
        `Finalize SceneView PR #${fixRes.pr} (issue #${item.number}): run \`gh pr merge ${fixRes.pr} --repo ${REPO} --squash --auto\`, then \`bash .claude/scripts/claim.sh --release ${item.number}\`. Report one line. Do NOT watch CI.`,
        { label: `merge:#${item.number}`, phase: 'Review', model: 'haiku', effort: 'low' },
      )
      return { ...fixRes, outcome: 'merged', reviewRecommendation: rec, reviewWarnings: (review.warnings || []).length }
    }
    // DO_NOT_MERGE / REVIEW_INCOMPLETE / breaking public-API → leave open (draft if breaking),
    // keep the claim, surface for the orchestrator/maintainer. Never auto-merge.
    if (review.breakingApi) {
      await agent(
        `Mark SceneView PR #${fixRes.pr} as a DRAFT — it has a confirmed BREAKING public-API change and needs the maintainer: \`gh pr ready ${fixRes.pr} --repo ${REPO} --undo\`. Do NOT merge. Report one line.`,
        { label: `draft:#${item.number}`, phase: 'Review', model: 'haiku', effort: 'low' },
      )
    }
    return { ...fixRes, outcome: 'blocked-by-review', reviewRecommendation: rec, breakingApi: !!review.breakingApi, blockers: (review.confirmedErrors || []).length, note: `${fixRes.note || ''} | review=${rec}${review.breakingApi ? ' BREAKING-API → drafted, maintainer gate' : ''}; PR #${fixRes.pr} open, claim held`.trim() }
  },
)

// ── Aggregate ────────────────────────────────────────────────────────────────────
const norm = (chosen || []).map((item, i) => {
  const r = results[i]
  const issue = (r && r.issue) || item.number
  if (!r) {
    return { issue, failed: true, note: 'agent returned nothing (skipped, errored, or budget-capped) — verify the claim/clone was cleaned up' }
  }
  if (r.outcome === 'merged') {
    return { issue, merged: true, pr: r.pr || null, note: r.note || `merged via claude/${issue}-…` }
  }
  if (r.outcome === 'blocked-by-review') {
    return { issue, blocked: true, pr: r.pr || null, breakingApi: !!r.breakingApi, note: `review=${r.reviewRecommendation || '?'}${r.breakingApi ? ' (BREAKING-API → draft, maintainer gate)' : ''} — ${r.note || ''}`.trim() }
  }
  if (r.outcome === 'skipped') {
    return { issue, skipped: true, note: `${r.skipReason || 'skipped'} — ${r.note || ''}`.trim() }
  }
  return { issue, failed: true, note: r.note || 'failed' }
})

const merged = norm.filter(r => r.merged).length
const blocked = norm.filter(r => r.blocked).length
const skipped = norm.filter(r => r.skipped).length
const failed = norm.filter(r => r.failed).length
log(`Cycle done — ${merged} merged/merging, ${blocked} blocked-by-review (PR open), ${skipped} skipped, ${failed} failed across ${norm.length} issue(s).`)

return {
  verdict: failed === 0 ? (blocked ? 'CLEAR_WITH_REVIEW_HOLDS' : 'CLEAR') : 'PARTIAL',
  disk: disk ? { freeGb: disk.freeGb, safe: disk.safe } : null,
  counts: { merged, blocked, skipped, failed, total: norm.length },
  results: norm,
  note: 'Trivial scope was fire-and-forget --auto merged; medium+ scope ran the `review-fanout` adversarial pass and merged on MERGE/MERGE_AFTER_WARNINGS, else left the PR open (drafted on a breaking public-API change — the one maintainer gate) with the claim held. The orchestrator watches ci-gate.yml + triages stuck PRs, and runs a release checkpoint once merges land. Cap stays at minor — 4 is frozen.',
}
