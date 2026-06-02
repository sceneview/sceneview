export const meta = {
  name: 'review-fanout',
  description: 'SceneView independent adversarial review: 4 dedicated reviewer agents (code/security/impact/doc-freshness) in parallel on a diff, then adversarially verify every ERROR finding before a graded merge_recommendation. The autonomous-merge safety gate (a breaking public-API change blocks auto-merge).',
  whenToUse: 'Run by the issue-batch ORCHESTRATOR on a worker\'s PR diff for MEDIUM+ scope (medium/large, or any change touching public API / rendering / threading / a machine-readable contract). Workers (background agents) cannot spawn nested reviewers (#1243), so this runs at the orchestrator level. Trivial diffs skip it (worker self-review, declared in the PR body).',
  phases: [
    { title: 'Review' },
    { title: 'Verify' },
  ],
}

// args: { diffRef?: 'main...HEAD' | 'origin/main...<branch>', issue?: number, pr?: number }
// Be robust to args arriving as a JSON string (some invocation paths stringify it).
let A = args
if (typeof A === 'string') {
  try { A = JSON.parse(A) } catch { A = {} }
}
A = A || {}
const diffRef = A.diffRef || 'main...HEAD'
const ctx = [A.issue ? `issue #${A.issue}` : null, A.pr ? `PR #${A.pr}` : null].filter(Boolean).join(', ') || 'the current change'

const REVIEW_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['verdict', 'findings'],
  properties: {
    verdict: { type: 'string', enum: ['FAIL', 'PASS_WITH_WARNINGS', 'PASS'] },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['severity', 'problem'],
        properties: {
          severity: { type: 'string', enum: ['error', 'warning'] },
          file: { type: 'string' },
          line: { type: 'string' },
          problem: { type: 'string' },
          fix: { type: 'string' },
        },
      },
    },
    propagation: { type: 'array', items: { type: 'string' }, description: 'other platforms/docs the change must reach' },
    notes: { type: 'string' },
  },
}

const VERDICT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['real', 'reason'],
  properties: {
    real: { type: 'boolean', description: 'true ONLY if this is a genuine, reproducible problem that blocks merge' },
    reason: { type: 'string' },
  },
}

// Each reviewer is a dedicated agent type whose mandate lives in .claude/agents/<type>.md
// (single source of truth). If the type isn't registered in this session, fall back to a
// general agent that READS the .md and adopts the role — so a reviewer is never silently dropped.
const REVIEWERS = [
  { key: 'code', agentType: 'sv-code-reviewer' },
  { key: 'security', agentType: 'sv-security-reviewer' },
  { key: 'impact', agentType: 'sv-impact-reviewer' },
  { key: 'doc', agentType: 'sv-doc-freshness' },
]

const reviewPrompt = (key) =>
  `Review the SceneView change for ${ctx}. First \`git fetch origin --quiet\` so the refs resolve, then review the diff: \`git diff ${diffRef}\` (also check \`git diff\` for any uncommitted work). Read the WHOLE diff and the surrounding code, then apply your ${key}-reviewer mandate.

Map your output to the schema: every issue you'd FAIL on → a finding with severity:"error"; every should-fix → severity:"warning". Set verdict accordingly (any error ⇒ FAIL). Be concrete with file:line, and reproduce any runtime claim before asserting it. A clean PASS is valid — do not invent findings.`

phase('Review')
log(`Running ${REVIEWERS.length} independent reviewers on ${diffRef} (${ctx})`)

async function runReviewer(r) {
  const opts = { label: `review:${r.key}`, phase: 'Review', schema: REVIEW_SCHEMA }
  try {
    return await agent(reviewPrompt(r.key), { ...opts, agentType: r.agentType })
  } catch (e) {
    log(`agentType '${r.agentType}' unavailable — adopting its mandate from file`)
    try {
      return await agent(
        `Read the file \`.claude/agents/${r.agentType}.md\` and adopt that reviewer role EXACTLY — its mandate, hard checks, and output fields. Then: ${reviewPrompt(r.key)}`,
        { ...opts, label: `review:${r.key}(fallback)` },
      )
    } catch (e2) {
      // Total reviewer failure → null → filtered → reviewersRan < expected ⇒ REVIEW_INCOMPLETE.
      // Absence of evidence is NEVER evidence of safety; this can never become a false MERGE.
      log(`reviewer '${r.key}' failed entirely — dropping; REVIEW_INCOMPLETE will fire`)
      return null
    }
  }
}

// Pipeline: each reviewer's ERROR findings are adversarially verified the moment that
// reviewer returns — no barrier between Review and Verify.
const reviewed = await pipeline(
  REVIEWERS,
  (r) => runReviewer(r).then((res) => (res ? { ...res, reviewer: r.key } : null)),
  (res, r) => {
    if (!res) return null
    const errors = (res.findings || []).filter((f) => f.severity === 'error')
    if (!errors.length) return { ...res, verifiedErrors: [] }
    return parallel(
      errors.map((f) => () =>
        agent(
          `An independent ${r.key} reviewer flagged this as an ERROR that blocks merge on the SceneView change (diff: \`git diff ${diffRef}\`):

  file: ${f.file || '?'}  line: ${f.line || '?'}
  problem: ${f.problem}
  proposed fix: ${f.fix || '(none given)'}

Try to REFUTE it. Read the actual code at that location and reproduce the failure if it is a runtime/compile claim (e.g. grep the call site, check the signature, trace the thread). Default to real=false if you cannot independently confirm a genuine, merge-blocking problem. Only real=true if you confirm it.`,
          { label: `verify:${r.key}`, phase: 'Verify', schema: VERDICT_SCHEMA },
        ).then((v) => ({ ...f, reviewer: r.key, verified: v })),
      ),
    ).then((verified) => ({ ...res, verifiedErrors: verified.filter(Boolean) }))
  },
)

const results = reviewed.filter(Boolean)
const reviewersRan = results.length
const reviewersExpected = REVIEWERS.length
const confirmedErrors = results.flatMap((res) => (res.verifiedErrors || []).filter((f) => f.verified?.real))
const allWarnings = results.flatMap((res) => (res.findings || []).filter((f) => f.severity === 'warning').map((f) => ({ ...f, reviewer: res.reviewer })))
const propagation = results.flatMap((res) => res.propagation || [])

// The impact reviewer is the autonomous-merge SAFETY gate: a confirmed ERROR from it
// (a breaking public-API change / unmirrored cross-platform divergence) is never an
// auto-fixable warning — it must stop for the maintainer, not just trigger a re-review.
const breakingApi = confirmedErrors.some((f) => f.reviewer === 'impact')

// NEVER recommend MERGE on an incomplete review — a dropped reviewer makes "no findings"
// meaningless (absence of evidence ≠ evidence of safety).
const merge_recommendation =
  reviewersRan < reviewersExpected ? 'REVIEW_INCOMPLETE'
    : confirmedErrors.length ? 'DO_NOT_MERGE'
      : allWarnings.length ? 'MERGE_AFTER_WARNINGS'
        : 'MERGE'

// SceneView graded-merge autonomy (his own low-personal-risk repo → more autonomous than
// pm-tools, but the impact gate is preserved): the orchestrator reads autonomousAction.
const autonomousAction =
  merge_recommendation === 'MERGE' ? 'auto-merge (squash --auto), no maintainer gate'
    : merge_recommendation === 'MERGE_AFTER_WARNINGS' ? 'fix warnings in the same PR, then auto-merge'
      : merge_recommendation === 'REVIEW_INCOMPLETE' ? 'NEVER merge — re-run, investigate why a reviewer dropped'
        : breakingApi ? 'DRAFT PR + STOP for the maintainer — breaking public-API / cross-platform divergence'
          : 'fix the confirmed root cause, then re-run review-fanout (do not merge)'

if (reviewersRan < reviewersExpected) {
  log(`⚠️ only ${reviewersRan}/${reviewersExpected} reviewers completed — REVIEW_INCOMPLETE, not MERGE`)
}
log(`Verdict: ${merge_recommendation}${breakingApi ? ' (BREAKING public-API — maintainer gate)' : ''} → ${autonomousAction}`)

return {
  diffRef,
  context: ctx,
  merge_recommendation,
  autonomousAction,
  breakingApi,
  reviewersRan,
  reviewersExpected,
  confirmedErrors,
  warnings: allWarnings,
  propagation,
  perReviewer: results.map((r) => ({ reviewer: r.reviewer, verdict: r.verdict, errorCount: (r.findings || []).filter((f) => f.severity === 'error').length })),
}
