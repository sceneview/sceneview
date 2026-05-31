export const meta = {
  name: 'triptych',
  description: 'The mandatory pre-merge triptych for a SceneView PR: independent multi-agent reviews + updated-everywhere + visual device-QA, with a blocking gate.',
  phases: [
    { title: 'Verify', detail: '5 independent Opus reviewers + impact-check + serial visual device-QA, in parallel' },
    { title: 'Gate', detail: 'aggregate verdicts → CLEAR or BLOCKED with the blocker list' },
  ],
}

// args: { pr?: number, branch?: string, base?: string, platforms?: string, summary?: string }
//   pr       — review a GitHub PR by number (reviewers run `gh pr diff <pr>`)
//   branch   — else review this local branch vs base (default origin/main)
//   platforms— hint for the visual-QA leg: "android" | "ios" | "web" | "none"
const a = args || {}
const BASE = a.base || 'origin/main'
const TARGET = a.pr
  ? `GitHub PR #${a.pr} — get the diff with: gh pr diff ${a.pr} --repo sceneview/sceneview`
  : `local branch ${a.branch || '(current HEAD)'} vs ${BASE} — get the diff with: git diff ${BASE}...${a.branch || 'HEAD'}`
const SUMMARY = a.summary ? `\nChange summary: ${a.summary}` : ''
const PLATFORMS = a.platforms || 'auto-detect from the changed files'

const VERDICT = {
  type: 'object',
  additionalProperties: false,
  properties: {
    lens: { type: 'string' },
    blocker: { type: 'boolean', description: 'true if at least one finding must block merge' },
    score: { type: 'number', description: '1-5 on this lens; 1-2 is itself blocking' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          severity: { type: 'string', enum: ['blocker', 'high', 'med', 'low'] },
          file: { type: 'string' },
          line: { type: 'number' },
          issue: { type: 'string' },
          fix: { type: 'string' },
        },
        required: ['severity', 'issue', 'fix'],
      },
    },
    summary: { type: 'string' },
  },
  required: ['lens', 'blocker', 'score', 'findings', 'summary'],
}

const COMMON = `You are an INDEPENDENT, skeptical reviewer of a SceneView change — you did NOT write it. SceneView is an AI-first 3D/AR SDK (Android Jetpack Compose + Filament; Apple SwiftUI + RealityKit; Web Filament.js; shared Kotlin Multiplatform core).
Review target: ${TARGET}${SUMMARY}
Read the FULL diff first, then investigate the touched files in context. Default to blocker=true when genuinely uncertain about a correctness/safety risk. Be concrete: every finding has file:line + a fix sketch. Score 1-5 on YOUR lens (1-2 = blocking).`

const LENSES = [
  { key: 'correctness-threading', brief: 'Correctness + threading. Filament JNI must run on the MAIN thread (never createModel*/materialLoader.* off a background coroutine). Look for: aliasing, lifecycle/leak, null-handling (rememberModelInstance returns null while loading), coroutine misuse, race conditions, behavior-preserving violations (a "perf"/"refactor" change that silently alters semantics).' },
  { key: 'api-consistency', brief: 'Public-API & Compose/SwiftUI idiom consistency. Declarative node composables (not imperative), LightNode apply is a NAMED param, naming/visibility/default consistency with the surrounding API, KDoc on new public symbols, no accidental public surface, no deprecated-API reintroduction.' },
  { key: 'cross-platform-parity', brief: 'Cross-platform parity. A change to one renderer (Android/Apple/Web/core) usually needs a mirror or a documented honest "Coming soon". Check llms.txt + the agents/sceneview* skills + cheatsheets stay truthful. iOS V1 is a strict subset of Android — never hide a gap, never overclaim.' },
  { key: 'real-product-ux', brief: '"Real product, not a school project." If this touches a demo/sample/UI/website: every action has feedback, no dead toggles, no silent crash, loading shows progress, polish matches market references. Would a paying user forgive this? A visible-to-user defect is a blocker.' },
  { key: 'safety-security', brief: 'Safety & security. No secret/keystore/PII committed, no employer/personal identity, no raw adb in scripts, no oversized assets, .filamat ABI invariant respected (a .mat edit ships its recompiled .filamat), no resource leak, no destructive/irreversible op without guard.' },
]

phase('Verify')

const tasks = LENSES.map(L => () =>
  agent(`${COMMON}\n\nYOUR LENS — ${L.key}: ${L.brief}\n\nReturn your structured verdict.`,
    { label: `review:${L.key}`, phase: 'Verify', schema: VERDICT }))

// Updated-everywhere leg — runs impact-check + the all-platforms surface audit.
const impactTask = () => agent(
  `${COMMON}\n\nYOUR LENS — updated-everywhere: run \`bash .claude/scripts/impact-check.sh\` and reason over its output for the diff. Verify EVERY impacted surface is in sync: llms.txt, KDoc, docs/docs/*, samples/recipes, README, CLAUDE.md, the agents/sceneview* skills, the version map (sync-versions.sh), changelog.d fragment present, MCP generated artefacts. A missing doc/changelog/parity update for a public change is a blocker. List exactly which surfaces are stale.`,
  { label: 'review:updated-everywhere', phase: 'Verify', schema: VERDICT })

// Visual device-QA leg — ONE serial agent (single emulator/sim lease). It is the
// only QA agent in this batch, so it never contends for the device.
const VQ = {
  type: 'object',
  additionalProperties: false,
  properties: {
    applicable: { type: 'boolean' },
    pass: { type: 'boolean' },
    platform: { type: 'string' },
    notes: { type: 'string' },
    evidence: { type: 'string', description: 'screenshot path(s) ≤1800px, or why none' },
  },
  required: ['applicable', 'pass', 'platform', 'notes'],
}
const visualTask = () => agent(
  `You are the dedicated visual device-QA agent for this SceneView change (serial — you hold the single emulator/sim lease, never parallelise device work). Target platform(s): ${PLATFORMS}.
Change: ${TARGET}${SUMMARY}
If the diff touches a demo/sample/UI on a platform you can drive locally:
- Android → reusable ARCore emulator (\`setup-ar-emulator.sh\`), drive the demo via DemoHostActivity, screenshots ≤1800px (≤5 total) with the \`android\` CLI (NEVER raw adb).
- iOS → simulator via XcodeBuildMCP, drive the demo, screenshot.
- Web → Playwright (\`samples/web-demo/tests/\`).
Assert: no crash, the change renders as intended, no rendering corruption (drift/skew/black viewport/frozen billboard). If the platform can't be driven locally (e.g. true ARCore tracking on arm64), say so HONESTLY and mark applicable=false — never fake a pass. Return your structured verdict.`,
  { label: 'visual-qa', phase: 'Verify', schema: VQ })

const all = await parallel([...tasks, impactTask, visualTask])

phase('Gate')

const reviews = all.slice(0, LENSES.length + 1).filter(Boolean)   // 5 lenses + updated-everywhere
const visual = all[all.length - 1]

const blockers = []
for (const r of reviews) {
  if (!r) continue
  if (r.blocker) blockers.push(`[${r.lens}] blocker: ${r.summary}`)
  if (typeof r.score === 'number' && r.score <= 2) blockers.push(`[${r.lens}] score ${r.score}/5 (blocking)`)
  for (const f of (r.findings || [])) {
    if (f.severity === 'blocker') blockers.push(`[${r.lens}] ${f.file || '?'}:${f.line || '?'} — ${f.issue}`)
  }
}
if (visual && visual.applicable && visual.pass === false) {
  blockers.push(`[visual-qa:${visual.platform || '?'}] FAILED — ${visual.notes}`)
}

const verdict = blockers.length === 0 ? 'CLEAR' : 'BLOCKED'
log(`Triptych ${verdict} — ${blockers.length} blocker(s), ${reviews.length} review legs, visual ${visual ? (visual.applicable ? (visual.pass ? 'PASS' : 'FAIL') : 'N/A') : 'missing'}`)

return {
  verdict,
  blockers,
  reviews,
  visual,
  note: 'Tier-2 holistic review for risky/merged subsystems = `/code-review ultra` (cloud, off-budget). Merge only on CLEAR + green CI.',
}
