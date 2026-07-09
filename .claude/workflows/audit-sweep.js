export const meta = {
  name: 'audit-sweep',
  description: 'A bug revealed a CLASS of problems → audit every surface in parallel (read-only Explore agents on a tiered grid), dedupe in pure JS, then frame the findings as a GitHub umbrella tracker + one sub-issue per HIGH.',
  phases: [
    { title: 'Audit', detail: 'one read-only Explore agent per surface, given the trigger context + a tiered (HIGH/MED/LOW) grid; structured findings' },
    { title: 'Frame', detail: 'dedupe across surfaces in pure JS, then one agent opens an umbrella tracker + a repro-style sub-issue per HIGH (gh issue create)' },
  ],
}

// args: { trigger?: string, surfaces?: string[] }
//   trigger  — the bug / observation that revealed the class of problems (the seed).
//   surfaces — module surfaces to audit; each maps 1:1 to a top-level module dir.
// Depth scales with budget: if budget.total is set, agents go DEEPER (more findings,
// LOW micro-issues included); otherwise default depth (HIGH/MED focus).
const a = args || {}
const DEFAULT_SURFACES = ['sceneview', 'arsceneview', 'sceneview-core', 'SceneViewSwift', 'sceneview-web']
const SURFACES = (Array.isArray(a.surfaces) && a.surfaces.length) ? a.surfaces : DEFAULT_SURFACES
const TRIGGER = (a.trigger && String(a.trigger).trim())
  || 'an unspecified bug that the maintainer suspects is one instance of a recurring CLASS of problems (audit defensively for the pattern, not just the single site)'

const DEEP = !!(budget && budget.total)
const DEPTH = DEEP
  ? 'DEEP pass: report every instance of the pattern you can substantiate — HIGH and MED exhaustively, plus LOW micro-issues (small allocs, minor doc drift, naming). Aim for thoroughness over brevity; do not stop at the first few.'
  : 'DEFAULT pass: concentrate on HIGH and MED instances with high confidence. A handful of well-substantiated LOW items is fine, but do not pad with speculative nits.'

// Map each logical surface to its module dir + renderer/language context so the
// agent greps the right tree. Unknown surfaces fall back to a repo-wide search.
const SURFACE_CTX = {
  sceneview:       { dir: 'sceneview/',       ctx: 'Android 3D library — Kotlin, Jetpack Compose + Filament renderer. Filament JNI MUST run on the main thread.' },
  arsceneview:     { dir: 'arsceneview/',     ctx: 'Android AR layer — Kotlin, ARCore integration on top of sceneview. Session lifecycle, anchors, frame callbacks.' },
  'sceneview-core':{ dir: 'sceneview-core/',  ctx: 'Kotlin Multiplatform core — math, collision, geometry, animation, physics (commonMain/androidMain/iosMain/jsMain). Portable, no renderer.' },
  SceneViewSwift:  { dir: 'SceneViewSwift/',  ctx: 'Apple 3D+AR library — Swift, RealityKit renderer (iOS/macOS/visionOS). iOS V1 is a strict subset of Android — gaps must be honest "Coming soon", never hidden.' },
  'sceneview-web': { dir: 'sceneview-web/',   ctx: 'Web 3D library — Kotlin/JS + Filament.js (WASM/WebGL2). Same engine as Android, different host.' },
}

const FINDINGS = {
  type: 'object',
  additionalProperties: false,
  properties: {
    surface: { type: 'string' },
    summary: { type: 'string', description: 'one line: did the trigger pattern appear on this surface, and how widely?' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          severity: { type: 'string', enum: ['HIGH', 'MED', 'LOW'] },
          file: { type: 'string', description: 'repo-relative path' },
          line: { type: 'number', description: 'best-known line number (0 if whole-file)' },
          issue: { type: 'string', description: 'what is wrong, concretely — same class as the trigger' },
          fix: { type: 'string', description: 'a concrete fix sketch' },
          impact: { type: 'string', description: 'who/what breaks if left — user-visible? silent drift? perf?' },
        },
        required: ['severity', 'file', 'issue', 'fix', 'impact'],
      },
    },
  },
  required: ['surface', 'summary', 'findings'],
}

phase('Audit')

// One read-only Explore agent PER surface. parallel() is a genuine barrier here:
// every surface's findings must be collected before we can dedupe + frame them.
// Explore agents never mutate files, so no worktree isolation is needed.
const auditTasks = SURFACES.map((s, i) => () => {
  const sc = SURFACE_CTX[s] || { dir: '', ctx: 'surface not in the standard module map — search the whole repo for the pattern.' }
  const scope = sc.dir
    ? `Scope your search to \`${sc.dir}\` (this is the ${s} surface). ${sc.ctx}`
    : `No standard module dir for "${s}" — search the repo broadly. ${sc.ctx}`
  return agent(
    `You are an INDEPENDENT, skeptical, READ-ONLY auditor of the SceneView SDK. You are auditing ONE surface for a CLASS of bug, not fixing anything — do not edit files.

SceneView is an AI-first 3D/AR SDK (Android Jetpack Compose + Filament; Apple SwiftUI + RealityKit; Web Filament.js; shared Kotlin Multiplatform core). Read CLAUDE.md / llms.txt for API + threading conventions if useful.

THE TRIGGER (the seed bug that revealed the class):
${TRIGGER}

YOUR SURFACE: ${s}
${scope}

YOUR JOB — find OTHER instances of the SAME CLASS as the trigger, on this surface:
1. First, reason about what general defect the trigger is an instance of (e.g. "Filament JNI on a background coroutine", "transform decomposed every frame → float drift", "by-value copy in a hot ray-test loop", "public-API change not mirrored in docs/parity", "missing null-handling on rememberModelInstance", "resource/anchor leak on lifecycle teardown", "secret/PII leak", "non-extractable assumption", "iOS gap silently faked rather than 'Coming soon'").
2. Then grep/read this surface for every place that matches that pattern. Use ripgrep widely; read the surrounding code in context before asserting — do not flag a false positive.
3. Grade each on a tiered grid:
   - HIGH — a real user-visible crash/corruption, a silent correctness/drift bug, a leak, or a safety/security issue.
   - MED  — a latent risk, a parity/doc gap on a public surface, a maintainability/behavior-preservation hazard.
   - LOW  — micro-allocs in a hot path, minor doc drift, naming/consistency nits.
${DEPTH}

Be concrete: EVERY finding needs a repo-relative file path, a best-known line number, the issue, a fix sketch, and the impact. If the class genuinely does NOT appear on this surface, return an empty findings array and say so in the summary — never invent findings to look productive (honesty rule). Use raw \`adb\` for nothing; you are read-only anyway.

Return your structured findings for the ${s} surface.`,
    { label: `audit:${s}`, phase: 'Audit', agentType: 'Explore', schema: FINDINGS, model: 'sonnet', effort: 'medium' })
})

const results = (await parallel(auditTasks)).filter(Boolean)

// --- Pure-JS dedupe across surfaces (resume-safe: no Date/random). ---
// Two findings collide when they point at the same file AND describe the same
// issue. Normalise both keys; keep the highest severity + remember every surface.
const norm = (x) => String(x || '').toLowerCase().replace(/\s+/g, ' ').replace(/[`'".,;:()]/g, '').trim()
const SEV_RANK = { HIGH: 3, MED: 2, LOW: 1 }
const byKey = new Map()
let rawCount = 0
for (const r of results) {
  if (!r || !Array.isArray(r.findings)) continue
  for (const f of r.findings) {
    rawCount++
    const key = `${norm(f.file)}|${norm(f.issue)}`
    const prev = byKey.get(key)
    if (!prev) {
      byKey.set(key, { ...f, surfaces: [r.surface] })
    } else {
      if (!prev.surfaces.includes(r.surface)) prev.surfaces.push(r.surface)
      if ((SEV_RANK[f.severity] || 0) > (SEV_RANK[prev.severity] || 0)) prev.severity = f.severity
    }
  }
}
const sevOrder = (f) => SEV_RANK[f.severity] || 0
const deduped = [...byKey.values()].sort((x, y) => sevOrder(y) - sevOrder(x))
const highs = deduped.filter(f => f.severity === 'HIGH')
const meds = deduped.filter(f => f.severity === 'MED')
const lows = deduped.filter(f => f.severity === 'LOW')

log(`Audit done — ${SURFACES.length} surfaces, ${rawCount} raw → ${deduped.length} deduped findings (${highs.length} HIGH / ${meds.length} MED / ${lows.length} LOW)`)

phase('Frame')

if (deduped.length === 0) {
  log('No findings of the trigger class on any surface — nothing to file.')
  return {
    findings: { bySurface: results, deduped: [], counts: { high: 0, med: 0, low: 0 } },
    umbrella: null,
    subIssues: [],
    note: `Audit clean: the class behind "${TRIGGER}" did not reproduce on any audited surface. No issues created.`,
  }
}

// Serialise the deduped grid into a compact, agent-readable block so the framing
// agent doesn't have to re-derive it. The agent runs `gh issue create` itself.
const fmt = (f, i) => `${i + 1}. [${f.severity}] ${f.file}:${f.line || '?'} (${(f.surfaces || []).join(', ')})\n   issue: ${f.issue}\n   fix: ${f.fix}\n   impact: ${f.impact}`
const HIGH_BLOCK = highs.length ? highs.map(fmt).join('\n') : '(none)'
const MED_BLOCK = meds.length ? meds.map(fmt).join('\n') : '(none)'
const LOW_BLOCK = lows.length ? lows.map(fmt).join('\n') : '(none)'

const FRAME_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    umbrella: { type: 'number', description: 'the umbrella tracker issue number created, or 0 if creation failed' },
    umbrellaUrl: { type: 'string' },
    subIssues: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          number: { type: 'number' },
          title: { type: 'string' },
          finding: { type: 'string', description: 'which HIGH finding (file:line) this issue covers' },
        },
        required: ['number', 'title'],
      },
    },
    note: { type: 'string', description: 'anything the orchestrator should know (e.g. a gh failure, a skipped duplicate)' },
  },
  required: ['umbrella', 'subIssues'],
}

const framed = await agent(
  `You are the issue-framing agent for a SceneView class-of-bug AUDIT. An audit fanned out across surfaces and produced the DEDUPED findings below. Turn them into GitHub issues with \`gh issue create --repo sceneview/sceneview\`. Do NOT write any code or edit files — you only create issues.

THE TRIGGER that seeded this audit:
${TRIGGER}

SURFACES AUDITED: ${SURFACES.join(', ')}
COUNTS: ${highs.length} HIGH · ${meds.length} MED · ${lows.length} LOW

=== HIGH findings (one sub-issue each) ===
${HIGH_BLOCK}

=== MED findings (catalog in the umbrella body) ===
${MED_BLOCK}

=== LOW findings (catalog in the umbrella body) ===
${LOW_BLOCK}

DO THIS, IN ORDER:

A) Before creating anything, run \`gh issue list --repo sceneview/sceneview --state open --limit 200 --search "audit"\` (and a couple of keyword searches drawn from the trigger) to AVOID DUPLICATING an existing open umbrella/sub-issue. If a near-identical open issue already exists, reference it instead of re-creating it, and note the skip.

B) Create ONE umbrella tracker issue first:
   - Title references the trigger, e.g. "[audit] <class of bug> — cross-surface sweep".
   - Body: a 2-3 sentence statement of the class (what general defect the trigger is an instance of); a "Surfaces audited" line; a markdown table or checklist of the HIGH sub-issues (leave the #numbers to fill after you create them, or create children first then edit — your call); and full **MED** + **LOW** catalogs inline (file:line · issue · fix · impact) so nothing is lost. Label it with \`gh issue create ... --label "audit"\` only if that label already exists in the repo (check \`gh label list\`); never invent labels — omit \`--label\` if unsure.

C) Create ONE sub-issue PER HIGH finding:
   - Repro-style and self-contained (an agent with no audit context must be able to act on it cold): the exact \`file:line\`, the concrete issue, the fix sketch, the impact, which surface(s), and a short **Acceptance criteria** checklist (what "fixed" looks like + how it's proven — a test or a visual check).
   - Cross-link: each sub-issue body references the umbrella ("Part of #<umbrella>"); the umbrella lists each child number.

D) Use \`--repo sceneview/sceneview\` on every \`gh\` call. Capture each created issue NUMBER (parse it from the URL gh prints). If a \`gh\` call fails (auth, rate-limit), do NOT fake a number — report the failure in \`note\` and set the number to 0.

Return the umbrella number + URL, the list of sub-issue numbers/titles, and a note.`,
  { label: 'frame-issues', phase: 'Frame', schema: FRAME_SCHEMA, model: 'opus', effort: 'medium' })

const subIssues = (framed && Array.isArray(framed.subIssues)) ? framed.subIssues : []
log(`Frame done — umbrella #${(framed && framed.umbrella) || '?'}, ${subIssues.length} sub-issue(s) for ${highs.length} HIGH finding(s)`)

return {
  findings: {
    bySurface: results,
    deduped,
    counts: { high: highs.length, med: meds.length, low: lows.length, raw: rawCount },
  },
  umbrella: (framed && framed.umbrella) || null,
  umbrellaUrl: (framed && framed.umbrellaUrl) || null,
  subIssues,
  note: (framed && framed.note)
    || `Filed umbrella + ${subIssues.length} HIGH sub-issue(s); ${meds.length} MED / ${lows.length} LOW cataloged in the umbrella body. Trigger: ${TRIGGER}`,
}
