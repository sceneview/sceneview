export const meta = {
  name: 'device-qa-orchestrate',
  description: 'Drive the device-QA harness SERIALLY — one dedicated agent holds the single emulator/sim lease (never fan out device agents), runs device-qa.sh, reads device-qa-report.json, and grades the release gate (web BLOCKING, android/ar ADVISORY).',
  phases: [
    { title: 'Run', detail: 'ONE serial agent runs device-qa.sh for the platform, then reports releaseGate.verdict + per-leg status from device-qa-report.json' },
    { title: 'Grade', detail: 'pure-JS gate: web red ⇒ blocked, android/ar red ⇒ warn (advisory, flaky #1643, never a silent pass); true ARCore is replay-only on arm64' },
  ],
}

// args: { platform?: 'android'|'ios'|'web'|'ar'|'all', fast?: boolean }
//   platform — which device-QA leg(s) to run (default 'all').
//   fast     — pass --fast for a per-category subset rather than the full catalog.
const a = args || {}
const VALID = ['android', 'ios', 'web', 'ar', 'all']
const PLATFORM = VALID.includes(a.platform) ? a.platform : 'all'
const FAST = a.fast === true
const REPORT_PATH = '.claude/worktrees/upbeat-bohr-c0b0b5/device-qa-report.json'
// The script writes the report to the repo root by default. Express the command
// the agent runs verbatim; the agent resolves the absolute repo-root path itself.
const CMD = `bash .claude/scripts/device-qa.sh --platform=${PLATFORM}${FAST ? ' --fast' : ''}`

// Structured verdict the serial agent returns — read straight from
// device-qa-report.json so the gate logic below never re-parses prose.
const LEG = {
  type: 'object',
  additionalProperties: false,
  properties: {
    platform: { type: 'string', description: 'web | android | ar | ios | web-perf' },
    status: { type: 'string', enum: ['passed', 'failed', 'skipped'] },
    advisory: { type: 'boolean', description: 'from the report — true ⇒ a non-pass is a WARN, not a block' },
    reason: { type: 'string' },
  },
  required: ['platform', 'status', 'advisory'],
}
const RUN_VERDICT = {
  type: 'object',
  additionalProperties: false,
  properties: {
    ran: { type: 'boolean', description: 'true if device-qa.sh executed and produced device-qa-report.json' },
    reportVerdict: {
      type: 'string',
      enum: ['clear', 'warn', 'blocked', 'missing'],
      description: "the report's pre-computed releaseGate.verdict, or 'missing' if no report was produced",
    },
    overallStatus: { type: 'string', enum: ['passed', 'warn', 'failed', 'unknown'], description: 'top-level report.status' },
    exitCode: { type: 'number', description: 'device-qa.sh exit code (0 pass/warn, 1 required-leg fail, 2 bad-invocation/disk-gate)' },
    blockingFailed: { type: 'array', items: { type: 'string' }, description: 'releaseGate.blockingFailed — non-advisory legs that failed' },
    advisoryFailed: { type: 'array', items: { type: 'string' }, description: 'releaseGate.advisoryFailed — advisory legs that did not pass' },
    legs: { type: 'array', items: LEG },
    reportPath: { type: 'string', description: 'absolute path to device-qa-report.json' },
    notes: { type: 'string', description: 'one-line human summary, incl. any honest skip (e.g. ARCore unsupported on arm64 emulator)' },
  },
  required: ['ran', 'reportVerdict', 'legs', 'reportPath'],
}

phase('Run')
log(`device-QA orchestrate — platform=${PLATFORM} fast=${FAST} (SERIAL: one emulator/sim lease)`)

// ONE dedicated agent. NEVER parallelise device work — a single emulator/sim
// lease, one harness, back-to-back legs. device-qa.sh itself boots the
// emulator/sim each leg needs, builds+installs the demo, delegates to the
// per-platform harness, and aggregates device-qa-report.json. The workflow
// can't run bash — the bash runs inside this agent.
const run = await agent(
  `You are the SINGLE dedicated device-QA agent for SceneView (AI-first 3D/AR SDK: Android Jetpack Compose + Filament; Apple SwiftUI + RealityKit; Web Filament.js; KMP core). You hold the ONE emulator/simulator lease for this run — NEVER spawn parallel device agents, NEVER QA on a personal device (routine QA runs on the reusable ARCore emulator only).

TASK — run the cross-platform device-QA harness for platform=${PLATFORM}${FAST ? ' (fast/per-category subset)' : ' (full catalog)'}:

1. From the repo root, run EXACTLY:
     ${CMD}
   This is the single orchestrator entrypoint. It boots the emulator/simulator each leg needs (via the RAM-budgeted adaptive pool / setup-ar-emulator.sh), builds + installs the demo app, delegates to the per-platform harness (Android Maestro, iOS Maestro, web Playwright, AR replay), and aggregates EVERY verdict into device-qa-report.json at the repo root. It is long-running — allow ample time (a full --platform=all pass spins up emulator + simulator + browser + several builds). Do not poll in a tight loop; let the command run to completion and capture its exit code.
   Use the 'android' CLI / .claude/scripts/lib/android-cli.sh for any UI dump/screenshot/install you do yourself — NEVER raw adb. Do not take screenshots larger than 1800px, and keep them to a handful.

2. After it exits, READ device-qa-report.json (repo root — its absolute path) and report from the JSON it produced, NOT from your own impression of the logs:
   - releaseGate.verdict  → reportVerdict ('clear' | 'warn' | 'blocked'). If the file is missing/unparseable, set reportVerdict='missing' and ran=false.
   - status               → overallStatus ('passed' | 'warn' | 'failed').
   - releaseGate.blockingFailed  → blockingFailed (non-advisory legs that failed — these hard-block).
   - releaseGate.advisoryFailed  → advisoryFailed (advisory legs that did not pass — WARN only).
   - platforms[]          → legs[]: one entry per leg with platform, status (passed|failed|skipped), advisory (the report's own boolean), and a short reason.
   - the device-qa.sh exit code → exitCode (0 = passed or advisory-only WARN; 1 = a REQUIRED leg failed; 2 = bad invocation / disk gate tripped before any platform ran).
   - reportPath           → the absolute path to device-qa-report.json.

GATE POLICY you must reflect honestly (do NOT re-grade — just surface the report's own fields):
- web is BLOCKING (reliable). A red web leg ⇒ the report verdict is 'blocked'.
- android and ar are ADVISORY (chronically flaky SwiftShader emulator, #1643). A red android/ar leg ⇒ 'warn', surfaced loudly, NEVER a silent pass.
- HONEST BLIND SPOT: true ARCore world-tracking cannot run on arm64 emulators. The 'ar' leg is 3D-emulated / recorded-session replay only; if it legitimately can't exercise tracking (e.g. ar-record-playback replayed 0 frames), it records as 'skipped' — that is honest, never fake a green AR leg. Note such a skip in notes.
- If the disk gate trips (exit 2) or the report was never written, say so plainly: ran=false, reportVerdict='missing'.

Return your structured verdict. Be precise; the orchestrator's gate reads your fields directly.`,
  { label: `device-qa:${PLATFORM}`, phase: 'Run', schema: RUN_VERDICT })

phase('Grade')

// PURE JS gate — map the harness's graded report to the release gate. The
// report already pre-computes releaseGate.verdict; we trust it as the primary
// signal and independently re-derive from the per-leg statuses as a cross-check
// so a missing/garbled report degrades to 'blocked' (fail-safe) rather than a
// false 'clear'. web = BLOCKING; android/ar = ADVISORY (#1643 flaky, never a
// silent pass); ARCore tracking is replay-only on arm64 → advisory by nature.
const ADVISORY_DEFAULT = new Set(['android', 'ar', 'web-perf'])
const legs = (run && Array.isArray(run.legs)) ? run.legs : []

const legMap = {}
const blockingFailed = []
const advisoryNonPass = []
for (const leg of legs) {
  if (!leg || !leg.platform) continue
  // Trust the report's own `advisory` flag; fall back to the default set.
  const isAdvisory = (typeof leg.advisory === 'boolean')
    ? leg.advisory
    : ADVISORY_DEFAULT.has(leg.platform)
  legMap[leg.platform] = { status: leg.status, advisory: isAdvisory, reason: leg.reason || '' }
  if (leg.status === 'passed') continue
  // A leg that did not pass: a blocking (web) failure hard-blocks; everything
  // else (advisory failure, or any skip) is at most a WARN.
  if (!isAdvisory && leg.status === 'failed') {
    blockingFailed.push(leg.platform)
  } else if (leg.status !== 'passed') {
    advisoryNonPass.push(leg.platform)
  }
}

// Derive the verdict from the legs (cross-check), then reconcile with the
// report's pre-graded verdict. Disagreement resolves to the STRICTER of the two.
const reportVerdict = run ? run.reportVerdict : 'missing'
const reportMissing = !run || run.ran === false || reportVerdict === 'missing'

let derived
if (blockingFailed.length > 0) derived = 'blocked'
else if (advisoryNonPass.length > 0) derived = 'warn'
else derived = 'clear'

const SEVERITY = { clear: 0, warn: 1, blocked: 2, missing: 2 }
// Fail-safe: a missing/unparseable report can never read as 'clear'.
let verdict
if (reportMissing) {
  verdict = 'blocked'
} else {
  // Take the stricter of the report's graded verdict and our independent derivation.
  verdict = (SEVERITY[reportVerdict] >= SEVERITY[derived]) ? reportVerdict : derived
}

const reportPath = (run && run.reportPath) || REPORT_PATH

log(`device-QA gate: ${verdict.toUpperCase()} — blockingFailed=[${blockingFailed.join(', ') || 'none'}] advisoryNonPass=[${advisoryNonPass.join(', ') || 'none'}]${reportMissing ? ' (report missing → fail-safe blocked)' : ''}`)
if (verdict === 'blocked') {
  log('BLOCKED — a blocking (web) leg failed or no report was produced. Release checkpoint must NOT tag.')
} else if (verdict === 'warn') {
  log(`WARN — advisory leg(s) did not pass: ${advisoryNonPass.join(', ')}. A human should review device-qa-report.json before tagging; not a hard block (#1643/#1651).`)
} else {
  log('CLEAR — every selected leg passed. Release checkpoint may proceed.')
}

return {
  verdict,                       // 'clear' | 'warn' | 'blocked'
  platform: PLATFORM,
  fast: FAST,
  reportVerdict,                 // the report's own releaseGate.verdict (or 'missing')
  reportMissing,
  legs: legMap,                  // { web: {status, advisory, reason}, android: {...}, ... }
  blockingFailed,                // web-class legs that hard-block
  advisoryFailed: advisoryNonPass, // android/ar/web-perf legs that WARN
  exitCode: run ? run.exitCode : undefined,
  reportPath,
  notes: (run && run.notes) || (reportMissing ? 'device-qa.sh produced no parseable report — gated as blocked (fail-safe).' : ''),
  policy: 'web BLOCKING · android/ar ADVISORY (#1643 flaky, never silent pass) · true ARCore tracking is replay-only on arm64 (honest skip, never a faked green AR leg).',
}
