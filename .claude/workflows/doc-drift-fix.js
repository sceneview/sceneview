export const meta = {
  name: 'doc-drift-fix',
  description: 'Weekly deep doc↔API drift pass (seeded by doc-audit.yml): audit the repo for candidate drift, reason each candidate against the CURRENT public API, patch the doc surface or defer, then open ONE draft PR (human-gated) bundling the patches.',
  phases: [
    { title: 'Audit', detail: 'one agent runs check-doc-drift.sh --audit → structured candidate-drift worklist' },
    { title: 'Patch', detail: 'pipeline over candidates — each reasons over llms.txt / KDoc / docs/docs / recipes vs current API, edits the doc OR defers' },
    { title: 'PR', detail: 'one agent opens a DRAFT PR bundling the patches, or a single deduped tracking issue if nothing was safely patchable' },
  ],
}

// args: { dry?: boolean }
//   dry — skip the PR/issue phase (audit + patch only; nothing pushed to GitHub).
const a = args || {}
const DRY = a.dry === true

// AI-first invariant repeated into every agent: prose docs are the surface an AI
// reads to generate USER code; a hallucinated API is worse than a gap.
const AI_FIRST = `SceneView is an AI-first 3D/AR SDK. Its PROSE docs (llms.txt, KDoc, docs/docs/*.md, samples/recipes/*.md) are the surface an AI reads to generate USER code — stale docs make an AI emit stale code. The four doc surfaces, in priority order:
1. \`llms.txt\` (repo root) — canonical AI-first API reference (signatures, node types, params, threading rules, patterns). HIGHEST priority.
2. KDoc on public Kotlin symbols in \`sceneview/\` and \`arsceneview/\` (+ Swift docs in \`SceneViewSwift/Sources\`, web in \`sceneview-web/src\`).
3. \`docs/docs/*.md\` — cheatsheet, quickstart, migration, platforms, android-xr (developer-facing prose).
4. \`samples/recipes/*.md\` + module READMEs — user-facing code examples.
HARD RULES: NEVER document an API that does not exist in source — verify every symbol against the actual \`.kt\`/\`.swift\` file; a hallucinated API in llms.txt is worse than a gap. Do NOT bump versions, edit changelog version anchors, or touch \`mcp/package.json\` (independent track). Leave generated mirrors alone: \`docs/docs/llms.txt\`, \`docs/docs/llms-full.txt\`, \`mcp/src/generated/*\` (see check-llms-drift.sh). Follow DESIGN.md if you touch any rendered HTML/MkDocs UI.`

// ──────────────────────────────────────────────────────────────────────────
// PHASE 1 — Audit. One agent runs the deterministic worklist and returns it
// structured. The script (check-doc-drift.sh --audit) is the single source of
// truth for what counts as candidate drift — we do NOT re-encode its heuristics.
// ──────────────────────────────────────────────────────────────────────────
phase('Audit')

const WORKLIST = {
  type: 'object',
  additionalProperties: false,
  properties: {
    candidates: {
      type: 'array',
      description: 'One row per candidate drift signal from the audit worklist + the week\'s git log cross-reference.',
      items: {
        type: 'object',
        additionalProperties: false,
        properties: {
          surface: { type: 'string', enum: ['llms.txt', 'kdoc', 'docs-pages', 'recipes'], description: 'Which doc surface the candidate touches.' },
          symbol: { type: 'string', description: 'The public symbol / composable / node type / param at issue.' },
          file: { type: 'string', description: 'The doc file to inspect/patch (e.g. llms.txt, docs/docs/cheatsheet.md, the .kt for KDoc).' },
          why: { type: 'string', description: 'Why this is candidate drift (missing from llms.txt, no KDoc, recipe references a symbol to verify, API moved this week, …).' },
        },
        required: ['surface', 'symbol', 'file', 'why'],
      },
    },
    note: { type: 'string', description: 'One-line summary of the worklist (counts, anything notable).' },
  },
  required: ['candidates', 'note'],
}

const audit = await agent(
  `You are the SceneView weekly documentation-drift AUDITOR (worklist phase).

${AI_FIRST}

TASK — build the candidate-drift worklist (do NOT patch anything yet):
1. Run \`bash .claude/scripts/check-doc-drift.sh --audit\` from the repo root. It emits a deterministic, machine-readable candidate map: composables/node types missing from llms.txt, public Kotlin declarations likely missing KDoc (sampled), recipe files to verify, MkDocs pages. This is your starting signal — it does NOT judge prose semantics.
2. Also run \`git log --oneline --since="8 days ago"\` and cross-reference any public-API change merged this week against the four doc surfaces — an API that moved without its docs following is a candidate even if the script didn't flag it.
3. Distil into a DEDUPED list of concrete candidates. Each candidate names exactly one doc surface, the symbol at issue, the doc file to inspect/patch, and why it's suspect. Drop noise (the script's known false positives: a local val/var inside a refactored body, an internal-only symbol). Prefer high-signal candidates an Opus agent can actually act on. Cap at the ~20 most worthwhile; the long tail is fine to omit (the script's truncation is intentional).

Return the structured worklist.`,
  { label: 'doc-drift:audit', phase: 'Audit', schema: WORKLIST })

const candidates = (audit && Array.isArray(audit.candidates)) ? audit.candidates : []
log(`Audit: ${candidates.length} candidate(s) — ${audit ? audit.note : 'no worklist returned'}`)

if (candidates.length === 0) {
  log('No candidate drift — docs appear in sync. Nothing to patch.')
  return {
    candidates: [],
    patched: [],
    deferred: [],
    note: audit ? audit.note : 'audit returned no candidates',
  }
}

// ──────────────────────────────────────────────────────────────────────────
// PHASE 2 — Patch. pipeline() over candidates (no barrier — each is independent
// and edits a distinct doc surface). Each agent reasons over the relevant prose
// against the CURRENT public API and EITHER edits the doc file (action:'patched')
// OR records why a patch isn't safe (action:'deferred'). Conservative by design:
// a small correct patch beats a large speculative one; uncertain ⇒ defer.
// ──────────────────────────────────────────────────────────────────────────
phase('Patch')

const PATCH = {
  type: 'object',
  additionalProperties: false,
  properties: {
    candidate: { type: 'string', description: 'surface · symbol — echoes which candidate this resolves.' },
    surface: { type: 'string', enum: ['llms.txt', 'kdoc', 'docs-pages', 'recipes'] },
    action: { type: 'string', enum: ['patched', 'deferred'] },
    file: { type: 'string', description: 'The doc file edited (when patched) or the file that would need a change (when deferred).' },
    detail: { type: 'string', description: 'If patched: what the prose now says + the source file:line that confirms the API. If deferred: precisely why it is not safe to auto-patch (ambiguous, needs a design call, API genuinely absent so nothing to document, false positive).' },
  },
  required: ['candidate', 'surface', 'action', 'detail'],
}

const patchStage = (c, _orig, i) => agent(
  `You are SceneView doc-drift PATCHER #${i + 1} — you fix exactly ONE candidate drift, conservatively.

${AI_FIRST}

YOUR CANDIDATE:
  surface: ${c.surface}
  symbol:  ${c.symbol}
  file:    ${c.file}
  why:     ${c.why}

PROCEDURE:
1. Open the named doc surface AND the actual source (\`.kt\`/\`.swift\`/web) that defines \`${c.symbol}\`. Read the real, CURRENT signature/visibility/KDoc — never trust the doc, never trust this prompt, trust the source.
2. Decide:
   - If the doc is genuinely stale vs the current public API AND the correct fix is unambiguous, EDIT the doc file to match the source exactly (use the Edit/Write tools). Keep the patch MINIMAL and surface-local — touch only what drifted. Do NOT restructure, do NOT bump versions, do NOT touch generated mirrors (\`docs/docs/llms.txt\`, \`docs/docs/llms-full.txt\`, \`mcp/src/generated/*\`). For KDoc, add/fix the doc comment directly above the public symbol in its \`.kt\`.
   - If it is NOT safe to patch — the symbol does not actually exist in source (so there is nothing to document, and inventing it is forbidden), the fix needs a design decision, the candidate is a known false positive (a local val/var, an internal symbol), or you are genuinely uncertain — DEFER. Do not guess. A hallucinated API is worse than a gap.
3. You touch ONLY doc/prose files for THIS candidate. Never edit library logic. Never edit another candidate's file. Do NOT git add/commit/push — the PR phase bundles everything.

Return your structured result: action='patched' (with the file you edited + the source file:line that proves the API) or action='deferred' (with the precise reason).`,
  { label: `doc-drift:patch:${c.surface}:${(c.symbol || '').slice(0, 24)}`, phase: 'Patch', schema: PATCH })

const results = (await pipeline(candidates, patchStage)).filter(Boolean)

const patched = results.filter(r => r.action === 'patched')
const deferred = results.filter(r => r.action === 'deferred')
log(`Patch: ${patched.length} patched, ${deferred.length} deferred (of ${candidates.length} candidates, ${results.length} returned)`)

// ──────────────────────────────────────────────────────────────────────────
// PHASE 3 — PR. One agent (serial — single git push) bundles the working-tree
// doc edits into a DRAFT PR (human-gated by design), or opens a single deduped
// tracking issue if nothing was safely patchable. Skipped entirely on --dry.
// ──────────────────────────────────────────────────────────────────────────
let prResult = null

if (DRY) {
  log('Dry run — skipping PR/issue phase. Doc edits remain in the working tree for inspection.')
} else {
  phase('PR')

  // Short, deterministic branch suffix derived from the run shape (NOT Date.now /
  // random — those break workflow resume). The agent appends the real run id.
  const slug = `${patched.length}p${deferred.length}d`

  const patchedList = patched.map(p => `- [${p.surface}] ${p.file || '?'} — ${p.candidate}: ${p.detail}`).join('\n') || '(none)'
  const deferredList = deferred.map(d => `- [${d.surface}] ${d.candidate}: ${d.detail}`).join('\n') || '(none)'

  prResult = await agent(
    `You are the SceneView doc-drift PR agent (serial — you alone run git/gh; never parallelise a push). The patch phase has already EDITED the doc files in the working tree.

${AI_FIRST}

WHAT THE PATCH PHASE PRODUCED
Patched (${patched.length}):
${patchedList}

Deferred (${deferred.length}):
${deferredList}

TASK:
1. Run \`git status --short\` and \`git diff --stat\` to see exactly which files the patch phase changed.
2. IF there are real doc-file changes in the working tree:
   - Create a branch \`claude/doc-drift-${slug}-<short>\` where <short> is the first 7 chars of \`git rev-parse HEAD\` (a stable, deterministic suffix — do NOT use a timestamp or random number).
   - Add a changelog fragment \`changelog.d/doc-drift-<short>.md\` whose body lists the doc surfaces fixed, tagged \`<!-- category: Docs -->\`.
   - Commit ONLY the doc changes + the fragment (\`git add\` the specific files; never \`git add -A\` blind — do not sweep in unrelated working-tree residue). Commit message: \`docs: weekly doc↔API drift fixes\`. End the commit body with the Co-Authored-By: Claude trailer.
   - Before opening a PR, run \`gh pr list --state open --search "doc↔API drift in:title" --repo sceneview/sceneview\`. If a prior doc-drift/doc-audit PR is still open, push your commits to ITS branch and add a refresh comment INSTEAD of opening a duplicate.
   - Otherwise open a **DRAFT** PR: \`gh pr create --draft --base main --title "docs: weekly doc↔API drift fixes" --body "<per-surface summary of what drifted and what changed; list the deferred items as known-not-patched follow-ups>"\`. DRAFT is mandatory — a human reviews before merge; never auto-merge, never mark ready.
3. IF the patch phase produced NO file changes (everything deferred or in sync):
   - Do NOT open an empty PR. If there are deferred items worth tracking, open ONE deduped tracking issue: first \`gh issue list --state open --search "doc↔API drift in:title" --repo sceneview/sceneview\`; if one exists, comment an update on it; else \`gh issue create --title "docs: doc↔API drift backlog" --body "<the deferred items, each with surface + symbol + why it needs a human/design call>"\`. If nothing is even worth tracking, do nothing and say so.
4. Report: the PR URL (or the issue URL, or "no action — docs in sync"), the branch name, and the exact files committed.

End the PR body with: 🤖 Generated with [Claude Code](https://claude.com/claude-code)`,
    { label: 'doc-drift:pr', phase: 'PR' })

  log(`PR phase: ${prResult ? String(prResult).slice(0, 160) : 'no result'}`)
}

// ──────────────────────────────────────────────────────────────────────────
// Structured return. Mirrors the doc-audit.yml contract: candidates audited,
// what got patched, what was deferred, and the PR (or tracking issue) outcome.
// ──────────────────────────────────────────────────────────────────────────
const out = {
  candidates,
  patched,
  deferred,
  note: audit ? audit.note : '',
}
// Surface the PR/issue outcome under the key the spec asks for. We can't reliably
// classify the agent's free text as PR-vs-issue here, so expose it raw under both
// names the caller may read; `dry` runs carry neither.
if (!DRY) {
  out.pr = prResult || null
  out.issue = prResult || null
}
return out
