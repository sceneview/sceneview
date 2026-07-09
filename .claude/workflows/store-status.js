export const meta = {
  name: 'store-status',
  description: 'Verify the REAL live state of published SceneView artifacts — CI-green is NEVER proof of live. Probes iTunes (iOS app), Maven Central (repo1 HTTP 200), npm in parallel, compares to the expected version, flags mismatches, and updates the store/live bullet in STATE.md. ASC reject-state + Play review-state are flagged as a known gap needing an authenticated browser session.',
  phases: [
    { title: 'Probe', detail: 'parallel live probes — iTunes lookup (id 6761329763) + Maven repo1 .pom HTTP code + npm sceneview-web version' },
    { title: 'Report', detail: 'pure-JS compare to expected version → mismatches; agent updates the store/live bullet in STATE.md NOW' },
  ],
}

// args: { version?: string } — expected version to verify live. If absent, the
// Probe phase reads it from the root gradle.properties VERSION_NAME.
const a = args || {}
const APP_ID = '6761329763'

// Resolve the canonical (non-worktree) main repo root from the AGENT'S OWN CWD — never a
// hardcoded worktree path (those get GC'd after merge, breaking every future run).
// --git-common-dir points at the shared .git; its parent is the main checkout. Works
// from any worktree or the main tree.
const RESOLVE_MAIN = 'MAIN=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")'

phase('Probe')

// Step 0: pin the expected version. Prefer args.version; else read VERSION_NAME.
let expected = (typeof a.version === 'string' && a.version.trim()) ? a.version.trim() : null
if (!expected) {
  const vRaw = await agent(
    `Print ONLY the SceneView version string to verify live, nothing else (no prose, no quotes, no newline padding).
Run exactly:
  ${RESOLVE_MAIN}
  grep -E '^VERSION_NAME=' "$MAIN/gradle.properties" | head -1 | cut -d= -f2 | tr -d '[:space:]'
Return that value verbatim as your entire output.`,
    { label: 'probe:expected-version', phase: 'Probe', model: 'haiku', effort: 'low' })
  expected = String(vRaw || '').trim().replace(/^["']|["']$/g, '')
}
if (!expected || !/^\d+\.\d+\.\d+/.test(expected)) {
  log(`store-status: could not resolve a valid expected version (got "${expected}") — aborting`)
  return {
    expected: expected || null, ios: null, maven: null, npm: null,
    mismatches: ['Could not resolve expected version from args or gradle.properties VERSION_NAME'],
  }
}
log(`store-status: verifying live state against expected version ${expected}`)

const V = expected

// --- Structured shapes for each probe (forces machine-readable output) ---
const IOS_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    resultCount: { type: 'number', description: 'iTunes lookup resultCount (0 = app not found / not yet live)' },
    version: { type: ['string', 'null'], description: 'live App Store version string, or null if absent' },
    currentVersionReleaseDate: { type: ['string', 'null'] },
    trackName: { type: ['string', 'null'] },
    raw: { type: 'string', description: 'short note: HTTP/parse outcome' },
  },
  required: ['resultCount', 'version', 'currentVersionReleaseDate', 'raw'],
}
const MAVEN_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    httpCode: { type: 'number', description: 'HTTP status of the .pom HEAD request (200 = live on repo1)' },
    url: { type: 'string' },
  },
  required: ['httpCode', 'url'],
}
const NPM_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  properties: {
    version: { type: ['string', 'null'], description: 'version echoed by `npm view sceneview-web@<v> version`, or null if the exact version is not published' },
    raw: { type: 'string', description: 'short note: stdout/stderr outcome' },
  },
  required: ['version', 'raw'],
}

// --- Three independent probes, run in parallel. A failed thunk ⇒ null (barrier
// tolerant), so one dead endpoint never poisons the other two verdicts. ---
const iosProbe = () => agent(
  `You verify the REAL live iOS App Store version of the SceneView demo (App id ${APP_ID}). CI-green is NEVER proof of live.
Run exactly:
  curl -s "https://itunes.apple.com/lookup?id=${APP_ID}&country=fr"
Parse the JSON: read \`resultCount\`, and from \`results[0]\` read \`version\`, \`currentVersionReleaseDate\`, \`trackName\`.
If resultCount is 0 (app not found / not yet live) return version=null, currentVersionReleaseDate=null, and say so in \`raw\`.
Do NOT guess or infer a version from anywhere else — report only what the live lookup returns. Return your structured verdict.`,
  { label: 'probe:ios-itunes', phase: 'Probe', schema: IOS_SCHEMA, model: 'haiku', effort: 'low' })

const POM_URL = `https://repo1.maven.org/maven2/io/github/sceneview/sceneview/${V}/sceneview-${V}.pom`
const mavenProbe = () => agent(
  `You verify that SceneView ${V} is REALLY live on Maven Central (repo1), not merely uploaded.
Run exactly:
  curl -sI -o /dev/null -w "%{http_code}" "${POM_URL}"
Report the integer HTTP status as \`httpCode\` (200 = the artifact's .pom is live on repo1; 404 = not present / still propagating) and echo the URL. Do not follow up with other endpoints. Return your structured verdict.`,
  { label: 'probe:maven-repo1', phase: 'Probe', schema: MAVEN_SCHEMA, model: 'haiku', effort: 'low' })

const npmProbe = () => agent(
  `You verify that the EXACT npm version sceneview-web@${V} is published (the published web CDN artifact is sceneview-web).
Run exactly:
  npm view sceneview-web@${V} version
If that exact version is published, npm echoes "${V}" — set version to that. If the exact version is NOT published, npm prints nothing / an error to stderr — set version=null and capture the message in \`raw\`. Do not fall back to the dist-tag latest. Return your structured verdict.`,
  { label: 'probe:npm-sceneview-web', phase: 'Probe', schema: NPM_SCHEMA, model: 'haiku', effort: 'low' })

const [ios, maven, npmRes] = await parallel([iosProbe, mavenProbe, npmProbe])

log(`Probe done — iOS live=${ios && ios.version ? ios.version : 'n/a'} · Maven HTTP=${maven ? maven.httpCode : 'n/a'} · npm=${npmRes && npmRes.version ? npmRes.version : 'n/a'}`)

phase('Report')

// --- PURE-JS comparison: classify each surface against the expected version. ---
// Semver-aware "is live < expected" so an older live iOS version is flagged
// distinctly from "different but not lower".
function parseSemver(s) {
  const m = String(s || '').match(/^(\d+)\.(\d+)\.(\d+)/)
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null
}
function cmpSemver(x, y) {
  const px = parseSemver(x), py = parseSemver(y)
  if (!px || !py) return null
  for (let i = 0; i < 3; i++) { if (px[i] !== py[i]) return px[i] < py[i] ? -1 : 1 }
  return 0
}

const mismatches = []

// iOS — the canonical CI-green trap (iOS was stuck on 4.0.3 for 3 weeks).
if (!ios) {
  mismatches.push('iOS: iTunes lookup probe failed (no result) — re-run; verify manually with itunes.apple.com/lookup')
} else if (!ios.version || ios.resultCount === 0) {
  mismatches.push(`iOS: app id ${APP_ID} returned no live version (resultCount=${ios.resultCount}) — not yet published, or lookup region has no listing`)
} else {
  const c = cmpSemver(ios.version, expected)
  if (c === -1) {
    mismatches.push(`iOS: LIVE ${ios.version} < expected ${expected} (released ${ios.currentVersionReleaseDate || '?'}) — still awaiting App Review / REJECTED. Check ASC Resolution Center (#2252).`)
  } else if (c !== 0) {
    mismatches.push(`iOS: LIVE ${ios.version} != expected ${expected} (released ${ios.currentVersionReleaseDate || '?'}) — investigate (live is AHEAD of, or differently-shaped than, expected).`)
  }
}

// Maven Central — 200 on the .pom means live on repo1.
if (!maven) {
  mismatches.push(`Maven: probe failed — re-run; expected HTTP 200 at ${POM_URL}`)
} else if (maven.httpCode !== 200) {
  mismatches.push(`Maven: sceneview ${expected} .pom returned HTTP ${maven.httpCode} (expected 200) — not live on repo1 yet / still propagating.`)
}

// npm — exact-version echo.
if (!npmRes) {
  mismatches.push(`npm: probe failed — re-run; expected \`npm view sceneview-web@${expected} version\` to echo ${expected}`)
} else if (npmRes.version !== expected) {
  mismatches.push(`npm: sceneview-web@${expected} not confirmed (got ${npmRes.version === null ? 'no exact-version match' : npmRes.version}) — the exact version is not published.`)
}

const KNOWN_GAP = 'KNOWN GAP — ASC rejection-state (App Store Connect Resolution Center) and Play review-state are NOT machine-queryable here; they need an authenticated browser session (Chrome MCP). Flagged for a dedicated session — not faked.'

const liveLine = mismatches.length === 0
  ? `all probed surfaces match expected ${expected} (iOS App Store ${ios && ios.version ? ios.version : '?'}, Maven repo1 200, npm ${expected})`
  : `${mismatches.length} mismatch(es): ${mismatches.join(' | ')}`

log(`Report — ${mismatches.length === 0 ? 'ALL MATCH' : mismatches.length + ' MISMATCH(es)'}: ${liveLine}`)

// --- Evidence-Stamped Claim Gate (#2346): the machine-readable probe record. ---
// claim-gate.sh reads .claude/data/last-store-probe.json before a `git push` and
// blocks the push if STATE.md asserts an "all-live / deployed ✅" claim whose
// evidence is missing/stale/disagrees. We compute the exact fields here (pure JS)
// and have the report AGENT write the file with a REAL `ts` via `date -u` — the
// Workflow tool rejects Date.now()/new Date(), so the timestamp is stamped in the
// shell at write time, not in this JS.
const verdict = mismatches.length === 0 ? 'ALL_LIVE' : 'MISMATCH'
const iosLive = (ios && ios.version) ? ios.version : null
const mavenHttp = maven ? maven.httpCode : null
const npmLive = (npmRes && npmRes.version) ? npmRes.version : null
// Pre-render the JSON body with a `__TS__` placeholder the agent replaces with the
// `date -u` value. Booleans/numbers/null stay valid JSON; strings are quoted.
const probeBody = `{
  "expected": ${JSON.stringify(expected)},
  "iosLive": ${JSON.stringify(iosLive)},
  "mavenHttp": ${mavenHttp === null ? 'null' : JSON.stringify(mavenHttp)},
  "npm": ${JSON.stringify(npmLive)},
  "verdict": ${JSON.stringify(verdict)},
  "ts": "__TS__"
}`

// --- One agent updates ONLY the store/live bullet in STATE.md NOW, AND writes
// the claim-gate evidence file. ---
// The bullet to touch is the iOS + macOS App Store line under '## NOW'.
const reportTask = () => agent(
  `You are updating the canonical SceneView STATE.md after a live-store verification run. Touch ONLY the store/live bullet; leave every other line, section, and bullet byte-for-byte unchanged.

Resolve the file path:
  ${RESOLVE_MAIN}
  echo "$MAIN/.claude/STATE.md"
Open "$MAIN/.claude/STATE.md". Under the '## NOW' section there is exactly one bullet about the App Store / live store state (it begins with something like "**iOS + macOS App Store:" and mentions App Review / live version / #2252). Edit ONLY that one bullet so it reflects this verification run's findings. Do not add, delete, or reorder any other bullet. Do not touch '## IN-FLIGHT', '## NEXT', or '## BOOTSTRAP'.

Verified this run (expected SDK version = ${expected}):
- iOS App Store live version (iTunes lookup, id ${APP_ID}): ${ios ? (ios.version ? `${ios.version} (released ${ios.currentVersionReleaseDate || 'unknown'}, track "${ios.trackName || '?'}")` : `NO live version — resultCount=${ios.resultCount}`) : 'PROBE FAILED'}
- Maven Central (repo1 .pom HTTP): ${maven ? maven.httpCode : 'PROBE FAILED'} ${maven && maven.httpCode === 200 ? '(LIVE)' : '(NOT live)'}
- npm sceneview-web@${expected}: ${npmRes ? (npmRes.version === expected ? 'CONFIRMED live' : `NOT confirmed (${npmRes.version === null ? 'no exact match' : npmRes.version})`) : 'PROBE FAILED'}
- Mismatches vs expected: ${mismatches.length === 0 ? 'NONE — every probed surface matches.' : mismatches.map((m, i) => `(${i + 1}) ${m}`).join('  ')}

Write the bullet truthfully and concisely (1–3 sentences). Rules for the prose:
- If iOS live < expected: state the REAL live iOS version explicitly and that ${expected} is "still awaiting App Review / possibly rejected — check ASC Resolution Center (#2252)". Never imply ${expected} is live on iOS when it is not.
- If everything matches: say all consumer artifacts (Maven, npm) for ${expected} are verified live, and note the iOS App Store live version explicitly with its release date.
- Keep the existing reminder to verify via itunes.apple.com/lookup, never CI-green.
- Append, as the last sentence of the same bullet, this exact known-gap note so it is not lost: "${KNOWN_GAP}"
- Preserve the leading "- " list marker and Markdown bold style used by the surrounding bullets.

THEN write the Evidence-Stamped Claim Gate (#2346) probe record so a future \`git push\` can prove this live-store claim. Run EXACTLY (the \`date -u\` stamps a REAL timestamp — do NOT invent one):
  ${RESOLVE_MAIN}
  mkdir -p "$MAIN/.claude/data"
  TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  cat > "$MAIN/.claude/data/last-store-probe.json" <<JSON
${probeBody}
JSON
  python3 - "$MAIN/.claude/data/last-store-probe.json" "$TS" <<'PY'
import json,sys
p=sys.argv[1]; ts=sys.argv[2]
d=json.load(open(p)); d["ts"]=ts
json.dump(d,open(p,"w"),indent=2); open(p,"a").write("\\n")
print("wrote",p,"ts",ts)
PY
This file is gitignored runtime evidence (\`.claude/data/\`), never committed. Its \`verdict\` is "${verdict}"; claim-gate.sh blocks an "all-live ✅" STATE.md claim unless verdict=ALL_LIVE and the record is fresh.

After editing, print a 3-line confirmation: (1) the absolute STATE.md path you edited, (2) the new bullet text verbatim, (3) the absolute probe path written + its verdict. Do NOT git add / commit / push. Do NOT modify any file other than STATE.md and .claude/data/last-store-probe.json.`,
  { label: 'report:update-state', phase: 'Report', model: 'sonnet', effort: 'low' })

const reportConfirm = await reportTask()

return {
  expected,
  ios,
  maven,
  npm: npmRes,
  mismatches,
  knownGap: KNOWN_GAP,
  stateUpdate: reportConfirm,
  verdict,
  // The claim-gate (#2346) evidence the report agent stamped on disk.
  probeFile: '.claude/data/last-store-probe.json',
  probe: { expected, iosLive, mavenHttp, npm: npmLive, verdict },
}
