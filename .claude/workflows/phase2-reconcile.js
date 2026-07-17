export const meta = {
  name: 'phase2-reconcile',
  description: 'Backlog triage (not a fresh bug): reconcile the MED-severity per-surface backlog — micro-allocs, small parity gaps, refactor debt — into one deduped tracker issue per surface (the #2328–#2332 pattern). Lighter sibling of audit-sweep.',
  phases: [
    { title: 'Triage', detail: 'one read-only Explore agent per surface, scanning ONLY its surface for MED-severity backlog items from the code + any existing audit docs; structured findings' },
    { title: 'Reconcile', detail: 'dedupe per surface in pure JS, then one agent ensures a per-surface tracker issue exists (create if missing, else update the body) via gh; returns the tracker issue numbers' },
  ],
}

// args: { surfaces?: string[] }
//   surfaces — module surfaces to triage; each maps 1:1 to a top-level module dir.
// This is BACKLOG reconciliation, not a fresh-bug audit: there is no trigger seed.
// We collect the standing MED-severity debt per surface and fold it into the
// per-surface tracker issues (#2328 sceneview / #2329 arsceneview / #2330 core /
// #2331 SceneViewSwift / #2332 web), creating one where it doesn't yet exist.
// Be robust to args arriving as a JSON string (some invocation paths stringify it — same guard as review-fanout.js).
let a = args
if (typeof a === 'string') {
  try { a = JSON.parse(a) } catch {
    throw new Error('phase2-reconcile: args must be JSON — got a non-JSON string: ' + a.slice(0, 120))
  }
}
a = a || {}
const DEFAULT_SURFACES = ['sceneview', 'arsceneview', 'sceneview-core', 'SceneViewSwift', 'sceneview-web']
const SURFACES = (Array.isArray(a.surfaces) && a.surfaces.length) ? a.surfaces : DEFAULT_SURFACES

// Map each logical surface to its module dir + renderer/language context so the
// agent greps the right tree, plus the canonical per-surface tracker the
// reconcile step should reuse (create if absent). Unknown surfaces fall back to a
// repo-wide search with no preset tracker.
const SURFACE_CTX = {
  sceneview: {
    dir: 'sceneview/',
    ctx: 'Android 3D library — Kotlin, Jetpack Compose + Filament renderer. Filament JNI MUST run on the main thread. Hot paths: gesture handling, LightNode, rotation/transform math, parent/child updates, per-frame node sync.',
    tracker: 2328,
    titlePrefix: 'perf(sceneview): Phase-2 hot-path cleanups',
  },
  arsceneview: {
    dir: 'arsceneview/',
    ctx: 'Android AR layer — Kotlin, ARCore integration on top of sceneview. Hot paths: ARCameraNode projection, PointCloudNode buffers, HitResultNode hitTest, per-frame anchor/pose updates.',
    tracker: 2329,
    titlePrefix: 'perf(arsceneview): Phase-2 hot-path cleanups',
  },
  'sceneview-core': {
    dir: 'sceneview-core/',
    ctx: 'Kotlin Multiplatform core — math, collision, geometry, animation, physics (commonMain/androidMain/iosMain/jsMain). Portable, no renderer. Hot paths: intersection tests, Octree query, math allocations.',
    tracker: 2330,
    titlePrefix: 'perf(sceneview-core): Phase-2 collision allocation hygiene',
  },
  SceneViewSwift: {
    dir: 'SceneViewSwift/',
    ctx: 'Apple 3D+AR library — Swift, RealityKit renderer (iOS/macOS/visionOS). iOS V1 is a strict subset of Android — gaps must be honest "Coming soon", never hidden. Hot paths: applyCamera diff-guard, Rerun/SceneObserver per-emit churn.',
    tracker: 2331,
    titlePrefix: 'perf(SceneViewSwift): Phase-2 hot-path cleanups',
  },
  'sceneview-web': {
    dir: 'sceneview-web/',
    ctx: 'Web 3D library — Kotlin/JS + Filament.js (WASM/WebGL2). Same engine as Android, different host. Hot paths: dirty-flag render gate, per-frame WASM/DOM/alloc churn.',
    tracker: 2332,
    titlePrefix: 'perf(sceneview-web): Phase-2 hot-path cleanups',
  },
}

// Depth scales with budget: a budgeted run goes deeper into LOW-adjacent micro-debt;
// the default run stays on well-substantiated MED items.
const DEEP = !!(budget && budget.total)
const DEPTH = DEEP
  ? 'DEEP pass: enumerate the MED backlog exhaustively, including borderline MED/LOW micro-allocations and minor refactor debt, as long as each is substantiated by reading the code in context.'
  : 'DEFAULT pass: concentrate on well-substantiated MED items (clear latent risk, a real parity/doc gap, a concrete hot-path alloc, or maintainability debt). Do not pad with speculative nits.'

const FINDINGS = {
  type: 'object',
  additionalProperties: false,
  properties: {
    surface: { type: 'string' },
    summary: { type: 'string', description: 'one line: the shape and size of the standing MED backlog on this surface' },
    items: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          title: { type: 'string', description: 'short imperative title for this backlog item' },
          file: { type: 'string', description: 'repo-relative path' },
          line: { type: 'number', description: 'best-known line number (0 if whole-file)' },
          rationale: { type: 'string', description: 'why this is MED debt — the concrete cost (micro-alloc in a hot loop, small parity gap, refactor/maintainability hazard) and a fix sketch' },
        },
        required: ['title', 'file', 'rationale'],
      },
    },
  },
  required: ['surface', 'summary', 'items'],
}

phase('Triage')

// One read-only Explore agent PER surface. parallel() is a genuine barrier here:
// every surface's MED backlog must be collected before we can dedupe + reconcile
// the trackers. Explore agents never mutate files, so no worktree isolation.
const triageTasks = SURFACES.map((s) => () => {
  const meta = SURFACE_CTX[s] || { dir: '', ctx: 'surface not in the standard module map — search the whole repo.' }
  const scope = meta.dir
    ? `Scope your search STRICTLY to \`${meta.dir}\` (this is the ${s} surface) — do not stray into other modules. ${meta.ctx}`
    : `No standard module dir for "${s}" — search the repo broadly. ${meta.ctx}`
  return agent(
    `You are an INDEPENDENT, skeptical, READ-ONLY auditor of the SceneView SDK doing BACKLOG TRIAGE — not fixing anything, and NOT chasing a fresh crash. You collect the STANDING MED-severity technical debt on ONE surface so it can be folded into that surface's tracker issue. Do not edit files.

SceneView is an AI-first 3D/AR SDK (Android Jetpack Compose + Filament; Apple SwiftUI + RealityKit; Web Filament.js; shared Kotlin Multiplatform core). Read CLAUDE.md / llms.txt for API + threading conventions if useful.

YOUR SURFACE: ${s}
${scope}

WHAT COUNTS AS MED-SEVERITY BACKLOG (the only tier you report):
- micro-allocations / by-value copies in a per-frame or per-event hot path (gesture, render loop, hitTest, intersection, projection);
- a small cross-platform PARITY gap on a public surface (Android has it, this surface doesn't, and it's not yet an honest "Coming soon"), or a minor doc/KDoc/llms.txt drift on a public symbol;
- refactor / maintainability debt — a behavior-preservation hazard, a duplicated routine, a setter that re-decomposes work, a latent (not yet user-visible) risk.
EXCLUDE both extremes: do NOT report HIGH items (a real user-visible crash/corruption, a leak, a security/PII issue — those are not backlog, raise them separately) and do NOT report trivial LOW nits (pure naming/style with no cost).

HOW TO WORK:
1. Read this surface's existing audit material first if present — the hot-path audit doc(s) under \`.claude/plans/\` (e.g. a perf/hot-path audit), the Phase-2 backlog already recorded in the surface's tracker issue (\`gh issue view <n> --repo sceneview/sceneview\` — see below), and any \`changelog.d/\` fragments — so you EXTEND the known backlog rather than re-deriving it from scratch.
2. Then grep/read the surface for MED items the docs haven't captured. Use ripgrep widely; read the surrounding code in context before asserting — never flag a false positive.
${DEPTH}

This surface's standing tracker issue (read it for the already-known MED backlog, do not duplicate what's already listed there): ${meta.tracker ? `#${meta.tracker} — \`gh issue view ${meta.tracker} --repo sceneview/sceneview\`` : '(none yet — this surface has no tracker; report a fresh MED backlog)'}.

Be concrete: EVERY item needs a short imperative title, a repo-relative file path, a best-known line number, and a rationale that states the concrete MED cost plus a fix sketch. If this surface genuinely has NO standing MED backlog beyond what its tracker already lists, return an empty items array and say so in the summary — never invent items to look productive (honesty rule). You are read-only; use raw \`adb\` for nothing.

Return your structured MED backlog for the ${s} surface.`,
    { label: `triage:${s}`, phase: 'Triage', agentType: 'Explore', schema: FINDINGS, model: 'sonnet', effort: 'medium' })
})

const results = (await parallel(triageTasks)).filter(Boolean)

// --- Pure-JS dedupe PER SURFACE (resume-safe: no Date/random). ---
// Backlog is reconciled per surface (one tracker each), so we dedupe WITHIN a
// surface only. Two items collide when they point at the same file AND describe
// the same item; keep the first, remember the count.
const norm = (x) => String(x || '').toLowerCase().replace(/\s+/g, ' ').replace(/[`'".,;:()]/g, '').trim()
const perSurface = []
let rawCount = 0
let dedupCount = 0
for (const s of SURFACES) {
  const r = results.find((x) => x && x.surface === s)
  const meta = SURFACE_CTX[s] || {}
  const items = (r && Array.isArray(r.items)) ? r.items : []
  const byKey = new Map()
  for (const it of items) {
    rawCount++
    const key = `${norm(it.file)}|${norm(it.title)}`
    if (!byKey.has(key)) byKey.set(key, it)
  }
  const deduped = [...byKey.values()]
  dedupCount += deduped.length
  perSurface.push({
    surface: s,
    tracker: meta.tracker || null,
    titlePrefix: meta.titlePrefix || `perf(${s}): Phase-2 hot-path cleanups`,
    summary: (r && r.summary) || '(no triage result returned)',
    items: deduped,
  })
  log(`Triage[${s}] — ${items.length} raw → ${deduped.length} deduped MED item(s); tracker ${meta.tracker ? '#' + meta.tracker : '(none yet)'}`)
}

log(`Triage done — ${SURFACES.length} surfaces, ${rawCount} raw → ${dedupCount} deduped MED item(s) total`)

phase('Reconcile')

const totalItems = perSurface.reduce((n, s) => n + s.items.length, 0)
if (totalItems === 0) {
  log('No standing MED backlog beyond what the trackers already list — nothing to reconcile.')
  return {
    perSurface,
    trackers: perSurface.map((s) => ({ surface: s.surface, number: s.tracker, action: 'unchanged' })),
    note: 'Backlog reconciliation clean: every surface\'s standing MED debt is already captured in its tracker. No issue created or updated.',
  }
}

// Serialise the per-surface deduped backlog into a compact, agent-readable block
// so the reconcile agent doesn't re-derive it. The agent runs gh itself.
const fmtItem = (it, i) => `  ${i + 1}. ${it.title} — ${it.file}:${it.line || '?'}\n     ${it.rationale}`
const SURFACE_BLOCKS = perSurface.map((s) => {
  const head = `### ${s.surface} — tracker ${s.tracker ? '#' + s.tracker + ' (UPDATE its body)' : '(NONE — create it)'} · title prefix "${s.titlePrefix}"`
  const body = s.items.length ? s.items.map(fmtItem).join('\n') : '  (no new MED items — leave this surface\'s tracker untouched)'
  return `${head}\n  summary: ${s.summary}\n${body}`
}).join('\n\n')

const RECONCILE_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    trackers: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          surface: { type: 'string' },
          number: { type: 'number', description: 'the tracker issue number (existing or newly created), or 0 if the gh call failed' },
          action: { type: 'string', enum: ['created', 'updated', 'unchanged', 'failed'] },
          url: { type: 'string' },
        },
        required: ['surface', 'number', 'action'],
      },
    },
    note: { type: 'string', description: 'anything the orchestrator should know (a gh failure, a skipped surface, a duplicate avoided)' },
  },
  required: ['trackers'],
}

const reconciled = await agent(
  `You are the tracker-reconciliation agent for a SceneView BACKLOG TRIAGE (NOT a fresh-bug audit). A read-only triage fanned out across surfaces and produced the DEDUPED per-surface MED-severity backlog below. Your job: ensure ONE per-surface tracker issue exists for each surface that has MED items, listing that surface's MED backlog. Use \`gh\` with \`--repo sceneview/sceneview\` on every call. Do NOT write any code or edit files — you only create/update issues.

This mirrors the existing #2328–#2332 per-surface Phase-2 tracker pattern (perf(<surface>): Phase-2 …). Reuse those exact issues where a number is given.

PER-SURFACE DEDUPED MED BACKLOG:
${SURFACE_BLOCKS}

DO THIS, PER SURFACE (in order), only for surfaces that have ≥1 new MED item:

A) If a tracker NUMBER is given for the surface (UPDATE case):
   - \`gh issue view <number> --repo sceneview/sceneview --json number,title,state,body\` first.
   - If it is CLOSED, do NOT reopen it silently — instead create a fresh tracker (CREATE case below) and note that the prior one was closed.
   - If OPEN, MERGE the new MED items into its body: keep every item already listed, append the new ones under the same checklist/section, and DEDUPE against what's already there (do not double-list an item the body already has — match on file path + intent, not exact wording). Preserve the existing title and labels. Edit with \`gh issue edit <number> --repo sceneview/sceneview --body-file <tmp>\` (write the merged body to a temp file to keep markdown intact). Action = "updated" (or "unchanged" if every new item was already present).

B) If NO number is given (CREATE case):
   - First run \`gh issue list --repo sceneview/sceneview --state open --limit 200 --search "<surface> Phase-2 perf"\` to AVOID DUPLICATING an existing open tracker; if a near-identical one exists, UPDATE it (case A) instead of creating a second, and note the reuse.
   - Otherwise \`gh issue create --repo sceneview/sceneview\` with the given title prefix (you may append a short specifics clause), a body that opens with a 1-2 sentence statement that this is the surface's standing Phase-2 MED backlog (micro-allocs / small parity gaps / refactor debt — intentional deduped backlog, not blocking bugs), then a markdown checklist of the items (each: \`- [ ] file:line — title — rationale/fix\`). Add \`--label\` ONLY for labels that already exist (check \`gh label list --repo sceneview/sceneview\`; the siblings use \`bug\` + a \`module:\`/\`platform:\` label) — never invent a label. Action = "created".

C) For a surface with NO new MED items, do nothing and report action "unchanged" with its given number (or 0 if none).

D) Capture each tracker's NUMBER (parse it from the URL gh prints on create, or echo it on update). If a \`gh\` call fails (auth, rate-limit), do NOT fake a number — set number 0, action "failed", and explain in \`note\`.

Return one entry per surface (number + action + url) and a note.`,
  { label: 'reconcile-trackers', phase: 'Reconcile', schema: RECONCILE_SCHEMA, model: 'sonnet', effort: 'medium' })

const trackers = (reconciled && Array.isArray(reconciled.trackers)) ? reconciled.trackers : []
const created = trackers.filter((t) => t.action === 'created').length
const updated = trackers.filter((t) => t.action === 'updated').length
const failed = trackers.filter((t) => t.action === 'failed').length
log(`Reconcile done — ${trackers.length} surface tracker(s): ${created} created, ${updated} updated, ${failed} failed`)

return {
  perSurface,
  trackers,
  note: (reconciled && reconciled.note)
    || `Reconciled ${dedupCount} MED item(s) across ${SURFACES.length} surfaces into per-surface trackers (${created} created, ${updated} updated).`,
}
