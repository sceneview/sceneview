export const meta = {
  name: 'release-checkpoint',
  description: 'The per-iteration / release-window checkpoint: preflight (sync-versions + quality-gate + green CI + next-version compute, 4 FROZEN) → device-QA (web BLOCKING) → tag+push (sync-versions --fix is the single source-of-truth updater; mcp left on its independent track) → verify-live.',
  phases: [
    { title: 'Preflight', detail: 'sync-versions 0-MISMATCH + quality-gate + main CI green; compute next version from changelog.d (patch=fixes, minor=features; NEVER major)' },
    { title: 'DeviceQA', detail: 'inline device-qa-orchestrate (platform=all) — web BLOCKING, android/ar ADVISORY' },
    { title: 'Tag', detail: 'sync-versions --fix → collate-changelog → commit → push tag vX.Y.Z (skipped on dry); assert mcp/ untouched (#1705)' },
    { title: 'VerifyLive', detail: 'inline store-status — iTunes lookup + ASC reject-states + Maven repo1 HTTP 200' },
  ],
}

// args: { dry?: boolean }
//   dry — preflight + device-QA + verify-live only; NEVER commits/pushes a tag.
const a = args || {}
const DRY = a.dry === true

const REPO = 'sceneview/sceneview'
const FROZEN_MAJOR = 4
const RULES = `Hard rules (SceneView house style):
- The orchestrator/workflow can't run bash — YOU (the agent) run every script via Bash, from the repo root.
- Major version ${FROZEN_MAJOR} is FROZEN: releases are capped at a minor bump, never a major. A 5.0.0 is a deliberate human milestone, never automatic.
- ⛔ #1705: \`mcp/package.json\` and \`mcp/src/index.ts\` follow an INDEPENDENT MCP npm track — do NOT sync them to VERSION_NAME. \`sync-versions.sh\` already excludes them; never hand-edit a version string anywhere (sync-versions.sh --fix is the single source-of-truth updater for all 30+ files).
- CI-green is never proof of live (upload ≠ submitted ≠ approved ≠ live). Verify the REAL version, never infer from a badge.
- No raw adb; no oversized screenshots (>1800px); no polling loops.
Return your structured verdict as your FINAL message (raw data, not prose).`

// ─────────────────────────────────────────────────────────── Phase 1: Preflight
const PREFLIGHT = {
  type: 'object',
  additionalProperties: false,
  properties: {
    clean: { type: 'boolean', description: 'true ⇔ sync-versions 0 MISMATCH AND quality-gate passed AND main CI green AND no other blocker' },
    nextVersion: { type: 'string', description: 'computed X.Y.Z to release — empty if needsHuman' },
    currentVersion: { type: 'string', description: 'VERSION_NAME read from root gradle.properties' },
    bumpKind: { type: 'string', enum: ['patch', 'minor', 'major', 'none', 'unknown'] },
    needsHuman: { type: 'boolean', description: 'true ONLY if a major bump appears warranted (4 is FROZEN) or scope is genuinely ambiguous' },
    syncMismatch: { type: 'number', description: 'count of MISMATCH lines from sync-versions.sh (0 required to be clean)' },
    qualityGatePass: { type: 'boolean' },
    ciGreen: { type: 'boolean' },
    fragments: { type: 'array', items: { type: 'string' }, description: 'changelog.d/*.md fragment filenames considered (excluding README.md)' },
    blockers: { type: 'array', items: { type: 'string' } },
    summary: { type: 'string' },
  },
  required: ['clean', 'nextVersion', 'bumpKind', 'needsHuman', 'syncMismatch', 'qualityGatePass', 'ciGreen', 'blockers', 'summary'],
}

phase('Preflight')

const preflight = await agent(
  `You are the PREFLIGHT gate for a SceneView release checkpoint. ${RULES}

Run these, from the repo root, and reason over their output:

1. \`bash .claude/scripts/sync-versions.sh\`  → count MISMATCH lines (set syncMismatch). MUST be 0 to be clean. The 2-3 WARN lines (Flutter/RN consumed-dep #1494, mcp #1705) are EXPECTED and are NOT mismatches — ignore them.
2. \`bash .claude/scripts/quality-gate.sh --quick\`  → set qualityGatePass.
3. \`gh run list --repo ${REPO} --branch main --limit 8\`  → set ciGreen (the latest push/ci-gate run on main is success/completed, not failure/in-progress-stuck). The single required check is ci-gate.yml.
4. Read \`VERSION_NAME\` from root \`gradle.properties\` → currentVersion.
5. Compute nextVersion from the \`changelog.d/*.md\` fragments (list them with \`ls changelog.d/\`, IGNORE README.md). Read each fragment's \`<!-- category: X -->\` tag:
   - if EVERY fragment is Fixed/Tests/Docs (no new public API, no behavior change) → PATCH bump (Z+1), bumpKind="patch".
   - if ANY fragment is Added/Changed/Removed (new feature, API change, or breaking) → MINOR bump (Y+1, Z→0), bumpKind="minor".
   - if there are NO fragments at all → bumpKind="none", clean=false, blocker "nothing to release".
   - Major ${FROZEN_MAJOR} is FROZEN. If a fragment looks like it warrants a major (a genuinely breaking public-API removal that can't ship under minor), DO NOT pick a major: set needsHuman=true, bumpKind="major", nextVersion="", clean=false, and STOP — a human decides 5.0.0.
   nextVersion must keep major=${FROZEN_MAJOR} (e.g. 4.17.0 + minor → 4.18.0; + patch → 4.17.1).

clean = (syncMismatch==0) AND qualityGatePass AND ciGreen AND bumpKind in {patch,minor} AND NOT needsHuman. Put every failing reason in blockers. Be honest — a half-green CI or a single real MISMATCH means clean=false.`,
  { label: 'preflight', phase: 'Preflight', schema: PREFLIGHT, model: 'sonnet', effort: 'medium' })

if (!preflight) {
  log('Preflight agent returned null — BLOCKED')
  return { version: '', verdict: 'BLOCKED', blockers: ['preflight agent returned null'], live: null }
}

log(`Preflight: current=${preflight.currentVersion || '?'} next=${preflight.nextVersion || '?'} (${preflight.bumpKind}) clean=${preflight.clean} mismatch=${preflight.syncMismatch} qg=${preflight.qualityGatePass} ci=${preflight.ciGreen}`)

if (preflight.needsHuman) {
  log('Preflight flagged a MAJOR bump — 4 is FROZEN. Stopping for human decision.')
  return {
    version: preflight.nextVersion || '',
    verdict: 'NEEDS_HUMAN',
    blockers: ['major version bump warranted — the 4 is FROZEN; a human must sanction 5.0.0', ...(preflight.blockers || [])],
    live: null,
  }
}

if (!preflight.clean) {
  log(`Preflight NOT clean — BLOCKED (${(preflight.blockers || []).length} blocker(s))`)
  return {
    version: preflight.nextVersion || '',
    verdict: 'BLOCKED',
    blockers: preflight.blockers && preflight.blockers.length ? preflight.blockers : ['preflight not clean (see summary)', preflight.summary],
    live: null,
  }
}

const nextVersion = preflight.nextVersion

// ─────────────────────────────────────────────────────────── Phase 2: DeviceQA
phase('DeviceQA')

// Inline the sibling orchestrator — it holds the single emulator lease, boots
// every leg, and pre-grades device-qa-report.json: web BLOCKING, android/ar
// ADVISORY (flaky SwiftShader #1643). One level deep, by name.
let deviceQA = null
try {
  deviceQA = await workflow('device-qa-orchestrate', { platform: 'all' })
} catch (e) {
  log(`device-qa-orchestrate threw: ${e && e.message ? e.message : e}`)
}

// Normalize the verdict defensively — the sibling may surface either a graded
// releaseGate verdict (clear|warn|blocked) or a coarse verdict string. The ONLY
// hard block is a failed BLOCKING (web) leg; advisory red (android/ar) → WARN.
const dq = deviceQA || {}
const dqVerdict = String(dq.verdict || (dq.releaseGate && dq.releaseGate.verdict) || '').toLowerCase()
const dqBlockingFailed = (dq.blockingFailed || (dq.releaseGate && dq.releaseGate.blockingFailed) || [])
const dqAdvisoryFailed = (dq.advisoryFailed || (dq.releaseGate && dq.releaseGate.advisoryFailed) || [])
const deviceQABlocked = dqVerdict === 'blocked' || (Array.isArray(dqBlockingFailed) && dqBlockingFailed.length > 0)
const deviceQAWarn = dqVerdict === 'warn' || (Array.isArray(dqAdvisoryFailed) && dqAdvisoryFailed.length > 0)

log(`DeviceQA verdict=${dqVerdict || 'n/a'} blockingFailed=[${(dqBlockingFailed || []).join(',')}] advisoryFailed=[${(dqAdvisoryFailed || []).join(',')}]`)

if (deviceQABlocked) {
  log('DeviceQA BLOCKING leg (web) failed — BLOCKED, not tagging.')
  return {
    version: nextVersion,
    verdict: 'BLOCKED',
    blockers: [`device-QA blocking leg failed: ${(dqBlockingFailed && dqBlockingFailed.length ? dqBlockingFailed.join(', ') : 'web')} — a demo crashes for a real user; fix before tagging`],
    live: null,
  }
}

const advisoryNote = deviceQAWarn
  ? `device-QA advisory leg(s) did not pass: ${(dqAdvisoryFailed && dqAdvisoryFailed.length ? dqAdvisoryFailed.join(', ') : 'android/ar')} — non-blocking (flaky emulator #1643), reviewed.`
  : null

// ─────────────────────────────────────────────────────────── Phase 3: Tag
phase('Tag')

if (DRY) {
  log(`DRY run — preflight clean, device-QA not blocked. Would tag v${nextVersion}. Not committing/pushing.`)
  return {
    version: nextVersion,
    verdict: 'DRY',
    blockers: advisoryNote ? [advisoryNote] : [],
    live: null,
  }
}

const TAG = {
  type: 'object',
  additionalProperties: false,
  properties: {
    tagged: { type: 'boolean', description: 'true ⇔ commit made AND tag vX.Y.Z pushed' },
    pushedTag: { type: 'string', description: 'the tag pushed, e.g. v4.18.0 — empty if not tagged' },
    syncFixMismatch: { type: 'number', description: 'MISMATCH count AFTER sync-versions.sh --fix re-verify (must be 0)' },
    mcpUntouched: { type: 'boolean', description: 'true ⇔ git diff shows mcp/package.json AND mcp/src/index.ts are UNCHANGED by this checkpoint (#1705)' },
    changelogCollated: { type: 'boolean' },
    blockers: { type: 'array', items: { type: 'string' } },
    summary: { type: 'string' },
  },
  required: ['tagged', 'pushedTag', 'syncFixMismatch', 'mcpUntouched', 'changelogCollated', 'blockers', 'summary'],
}

const tag = await agent(
  `You are the TAG step of a SceneView release checkpoint. Preflight is clean and device-QA is NOT blocked. Release version: ${nextVersion}. ${RULES}

This is a SINGLE \`/release\` actor — never run a second release agent concurrently. Do, in order, from the repo root:

1. \`bash .claude/scripts/sync-versions.sh --fix\`  — the SINGLE source-of-truth updater. It rewrites VERSION_NAME and all 30+ dependent files to ${nextVersion}. Do NOT hand-edit any version string. (It expects root gradle.properties already = ${nextVersion}; if it does not bump because the source is unchanged, first set root \`gradle.properties\` VERSION_NAME=${nextVersion} and re-run --fix.)
2. Re-run \`bash .claude/scripts/sync-versions.sh\` and confirm 0 MISMATCH → set syncFixMismatch (the #1494 Flutter/RN + #1705 mcp WARN lines are expected, not mismatches).
3. ⛔ #1705 ASSERTION: \`git status --porcelain mcp/package.json mcp/src/index.ts\` MUST be empty — sync-versions.sh excludes the independent MCP track and must NOT have touched them. Set mcpUntouched accordingly. If EITHER shows as modified, REVERT just those two files (\`git checkout -- mcp/package.json mcp/src/index.ts\`), set mcpUntouched=false, and add a blocker — never ship an accidental MCP downgrade.
4. \`bash .claude/scripts/collate-changelog.sh ${nextVersion}\`  — collates changelog.d/*.md into a new \`## v${nextVersion} — <date>\` section and deletes the fragments → set changelogCollated. (Optional: hand-edit the title to add the thematic summary.)
5. \`git add -A && git commit -m "chore: release ${nextVersion}\\n\\nCo-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"\`.
6. \`git tag v${nextVersion}\` then \`git push origin HEAD --tags\` (push the current main HEAD + the new tag — this triggers release.yml: Maven Central / npm sceneview-web / GitHub Release, plus the store workflows). Set pushedTag="v${nextVersion}", tagged=true.

If ANY step fails (dirty mcp diff, non-zero MISMATCH after --fix, push rejected), STOP, set tagged=false, leave pushedTag empty, and explain in blockers. Do NOT force-push, do NOT bump a major.`,
  { label: 'tag', phase: 'Tag', schema: TAG, model: 'opus', effort: 'medium' })

if (!tag || !tag.tagged) {
  const tb = (tag && tag.blockers && tag.blockers.length) ? tag.blockers : ['tag step did not complete (agent returned null or tagged=false)']
  if (tag && tag.mcpUntouched === false) tb.unshift('#1705 violation: sync-versions touched the independent MCP track (mcp/package.json or mcp/src/index.ts)')
  log(`Tag step did NOT complete — BLOCKED. ${tb.join(' | ')}`)
  return {
    version: nextVersion,
    verdict: 'BLOCKED',
    blockers: advisoryNote ? [...tb, advisoryNote] : tb,
    live: null,
  }
}

log(`Tagged ${tag.pushedTag} — sync-fix mismatch=${tag.syncFixMismatch}, mcpUntouched=${tag.mcpUntouched}, changelog=${tag.changelogCollated}`)

// ─────────────────────────────────────────────────────────── Phase 4: VerifyLive
phase('VerifyLive')

// Inline store-status — iTunes lookup (real live iOS version, never CI-green) +
// ASC reject-states + Maven repo1 HTTP 200 + npm. Publish is async on the tag, so
// "live" here is a best-effort first read; a green Maven/npm is the dev-facing truth.
let live = null
try {
  live = await workflow('store-status', { version: nextVersion })
} catch (e) {
  log(`store-status threw: ${e && e.message ? e.message : e}`)
  live = { error: String(e && e.message ? e.message : e) }
}

const blockers = advisoryNote ? [advisoryNote] : []
log(`Release checkpoint complete — v${nextVersion} RELEASED (tag pushed; publish jobs running). ${blockers.length} advisory note(s).`)

return {
  version: nextVersion,
  verdict: 'RELEASED',
  blockers,
  live,
  note: 'Tag pushed → release.yml (Maven/npm/GitHub Release) + store workflows run async. CI-green ≠ live: the store-status read is a first snapshot; re-run /store-status + /sync-check after propagation. mcp stays on its independent track (#1705).',
}
