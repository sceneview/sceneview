export const meta = {
  name: 'parity-audit',
  description: 'SceneView cross-platform public-API parity audit: per target platform (iOS/Web/bridges), one agent lists capabilities present on the Android (Filament) reference but missing/divergent/undeferred on the target, then EVERY claimed gap is adversarially verified against real source (does the cited file:line resolve to the claimed symbol? is it truly missing, or actually mirrored / honestly deferred?). Default realGap=false unless independently confirmed.',
  whenToUse: 'Run periodically, or after a public-API change, to keep Android/iOS/Web parity honest at the suite level (sv-impact-reviewer gates per-PR; this is the standing audit). Returns verified gaps grouped by platform; the orchestrator frames them as a tracker issue + one sub-issue per HIGH (audit-sweep style). Pure read-only — no code is written.',
  phases: [
    { title: 'Audit' },
    { title: 'Verify' },
  ],
}

// args: { platforms?: ['ios','web','flutter','rn'], reference?: 'android' }
let A = args
if (typeof A === 'string') { try { A = JSON.parse(A) } catch { A = {} } }
A = A || {}
const REFERENCE = A.reference || 'android'
const TARGETS = (Array.isArray(A.platforms) && A.platforms.length) ? A.platforms : ['ios', 'web']

const PLATFORM = {
  android: { label: 'Android (Filament, Jetpack Compose)', dirs: 'sceneview/ arsceneview/ sceneview-core/' },
  ios:     { label: 'Apple (RealityKit, SwiftUI — iOS/macOS/visionOS)', dirs: 'SceneViewSwift/' },
  web:     { label: 'Web (Filament.js, Kotlin/JS)', dirs: 'sceneview-web/' },
  flutter: { label: 'Flutter bridge (PlatformView)', dirs: 'flutter/' },
  rn:      { label: 'React Native bridge (Fabric)', dirs: 'react-native/' },
}
const ref = PLATFORM[REFERENCE] || PLATFORM.android

const GAPS_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['gaps'],
  properties: {
    gaps: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['symbol', 'kind', 'severity'],
        properties: {
          symbol: { type: 'string', description: 'the public capability — composable / node type / param / method' },
          kind: { type: 'string', enum: ['missing', 'divergent', 'undeferred'], description: 'missing = absent on target; divergent = present but different signature/behaviour; undeferred = present-but-broken/stub with no honest "Coming soon"' },
          refLocation: { type: 'string', description: 'file:line on the reference platform where the capability lives' },
          targetStatus: { type: 'string', description: 'what the target actually has (or "absent")' },
          severity: { type: 'string', enum: ['high', 'med', 'low'] },
          note: { type: 'string' },
        },
      },
    },
    coverageNote: { type: 'string', description: 'one line: what was compared + any area not reachable' },
  },
}

const VERDICT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['realGap', 'reason'],
  properties: {
    realGap: { type: 'boolean', description: 'true ONLY if independently confirmed: the capability exists on the reference and is genuinely missing/divergent on the target AND is NOT an honestly-documented deferral' },
    reason: { type: 'string' },
    deferredHonestly: { type: 'boolean', description: 'true if the target leaves an explicit "Coming soon"/documented gap (⇒ not a real parity bug)' },
  },
}

phase('Audit')
log(`Parity audit: ${TARGETS.join(', ')} vs the ${ref.label} reference`)

const auditPrompt = (t) => {
  const p = PLATFORM[t] || { label: t, dirs: '' }
  return `Audit cross-platform public-API parity for **${p.label}** (dirs: ${p.dirs}) against the reference **${ref.label}** (dirs: ${ref.dirs}).

SceneView ships the same conceptual API across renderers. List GAPS — a PUBLIC capability (composable, node type, parameter, public method/initializer) present on the reference but, on the target, one of:
  - **missing** — absent entirely;
  - **divergent** — present with a different signature/default/behaviour an AI would mis-generate against;
  - **undeferred** — present but a broken stub / no-op, with NO honest "Coming soon"/documented deferral.

CRITICAL: iOS V1 is a deliberate STRICT SUBSET of Android — an **honestly documented deferral** ("Coming soon", a documented gap) is NOT a finding. Only a SILENT divergence/absence that a developer or AI would trip over IS. Read both public surfaces (the reference dirs and the target dirs); \`bash .claude/scripts/cross-platform-check.sh\` reasoning and \`llms.txt\` help frame the expected surface. Read-only: do not write code. Don't invent gaps — a small honest subset is the expected, correct state.`
}

// Pipeline: each platform's gaps are adversarially verified the moment that platform audit returns.
const audited = await pipeline(
  TARGETS,
  (t) => agent(auditPrompt(t), { label: `audit:${t}`, phase: 'Audit', schema: GAPS_SCHEMA, model: 'sonnet', effort: 'medium' }).then((r) => (r ? { ...r, platform: t } : null)),
  (r, t) => {
    if (!r) return null
    const gaps = r.gaps || []
    if (!gaps.length) return { ...r, verifiedGaps: [] }
    return parallel(
      gaps.map((g) => () =>
        agent(
          `An independent parity audit flagged this as a cross-platform gap on **${(PLATFORM[t] || {}).label || t}** vs the ${ref.label} reference:

  symbol: ${g.symbol}
  kind: ${g.kind}   severity: ${g.severity}
  reference location: ${g.refLocation || '?'}
  target status: ${g.targetStatus || '?'}

Try to REFUTE it. Open the actual source on BOTH platforms: does the reference \`refLocation\` resolve to that public symbol? Is it genuinely missing/divergent on the target, or is it actually mirrored under a different name, or left as an HONEST documented deferral ("Coming soon")? Default realGap=false unless you independently confirm a genuine, silent parity break. Set deferredHonestly=true if the target documents the gap.`,
          { label: `verify:${t}:${(g.symbol || '').slice(0, 24)}`, phase: 'Verify', schema: VERDICT_SCHEMA, model: 'opus', effort: 'high' },
        ).then((v) => ({ ...g, platform: t, verified: v })),
      ),
    ).then((verified) => ({ ...r, verifiedGaps: verified.filter(Boolean) }))
  },
)

const perPlatform = audited.filter(Boolean)
const confirmedGaps = perPlatform.flatMap((r) => (r.verifiedGaps || []).filter((g) => g.verified?.realGap))
const bySeverity = (s) => confirmedGaps.filter((g) => g.severity === s)
const platformsAudited = perPlatform.map((r) => r.platform)
const platformsExpected = TARGETS.length

log(`Parity audit done — ${confirmedGaps.length} confirmed gap(s) across ${platformsAudited.length}/${platformsExpected} platform(s): ${bySeverity('high').length} HIGH / ${bySeverity('med').length} MED / ${bySeverity('low').length} LOW`)

// Like review-fanout, a dropped auditor makes "no gaps" meaningless — never report a clean parity
// on an incomplete run.
const verdict =
  platformsAudited.length < platformsExpected ? 'AUDIT_INCOMPLETE'
    : confirmedGaps.length === 0 ? 'PARITY_CLEAN'
      : 'GAPS_CONFIRMED'

return {
  reference: REFERENCE,
  targets: TARGETS,
  verdict,
  platformsAudited,
  platformsExpected,
  confirmedGaps,
  counts: { high: bySeverity('high').length, med: bySeverity('med').length, low: bySeverity('low').length, total: confirmedGaps.length },
  byPlatform: perPlatform.map((r) => ({ platform: r.platform, confirmed: (r.verifiedGaps || []).filter((g) => g.verified?.realGap).length, coverageNote: r.coverageNote || '' })),
  note: 'Confirmed gaps are real, silent cross-platform divergences (honest "Coming soon" deferrals are excluded). The orchestrator frames them as a tracker issue + one sub-issue per HIGH (audit-sweep style). AUDIT_INCOMPLETE ⇒ never claim parity-clean; re-run the dropped platform.',
}
