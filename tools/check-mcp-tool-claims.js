#!/usr/bin/env node
/**
 * check-mcp-tool-claims.js — the prose surfaces may not invent MCP tools.
 *
 * WHY THIS EXISTS
 * ---------------
 * Every falsehood found in the #3189 audit was the same shape: a fact about the
 * MCP server typed by hand into a surface no gate could see. Measured, on one
 * tree:
 *
 *   - `docs/docs/ai-development.md` documented four tools, three of which have
 *     never existed (`get_api_reference`, `get_sample_code`, `get_threading_rules`).
 *   - `website-static/playground.html` shipped a copy-to-clipboard prompt naming
 *     five tools that have never existed (`create_scene`, `add_model`,
 *     `configure_camera`, `set_environment`, `add_ar_plane_detection`) — handed
 *     straight to the reader's own assistant.
 *   - "28 MCP tools" appeared in SEVEN places against a real 31.
 *
 * `mcp/src/tool-count-claims.test.ts` already guards the numbers INSIDE `mcp/`
 * (mcpize.yaml, the tier map). Nothing guarded the README, the docs site, or the
 * website — which is exactly where the drift lived.
 *
 * WHAT IT CHECKS
 * --------------
 *   1. NAMES  — a verb-prefixed snake_case token in INLINE backticks that looks
 *      like an MCP tool but is in no registry.
 *   2. COUNTS — an "N tools" claim whose N matches no real surface's tool count.
 *
 * WHAT IT DELIBERATELY DOES NOT CHECK
 * -----------------------------------
 * The count check compares against the SET of real counts, not against one
 * number, because different surfaces legitimately count different things (the
 * stdio package, the gateway's mounted total, one vertical package). So a wrong
 * number that happens to equal another surface's real count slips through. That
 * is a known, accepted weakness — stated here rather than left for a reader to
 * discover, because a gate that overstates its reach is the defect this file
 * exists to prevent.
 *
 * Fenced code blocks are skipped: they hold real code, where snake_case means
 * something else. Only inline `backticks` are scanned for names.
 *
 * Usage: node tools/check-mcp-tool-claims.js [--json]
 * Exit:  0 clean · 1 findings · 2 could not build the registry (never a pass)
 */

import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, extname } from "node:path";
import { fileURLToPath } from "node:url";

// `--root <dir>` exists so the self-test can point the checker at a hermetic
// fixture tree. Without it the checker resolves its own location and there is no
// way to assert the FAILING direction without dirtying the real repo — and a
// suite that only ever asserts the passing direction is prose.
function resolveRoot(argv) {
  const i = argv.indexOf("--root");
  if (i !== -1) {
    if (!argv[i + 1]) {
      console.error("check-mcp-tool-claims: --root needs a directory");
      process.exit(2);
    }
    return argv[i + 1];
  }
  return join(fileURLToPath(new URL(".", import.meta.url)), "..");
}
const repoRoot = resolveRoot(process.argv);

// ── The registry: every tool name that really exists ────────────────────────
const TOOL_SOURCES = [
  "mcp/src/tools/definitions.ts",
  "mcp-gateway/src/mcp/widget-tools.ts",
  "mcp/packages/automotive/src/tools.ts",
  "mcp/packages/gaming/src/tools.ts",
  "mcp/packages/healthcare/src/tools.ts",
  "mcp/packages/interior/src/tools.ts",
  "mcp/packages/rerun/src/tools.ts",
];

function toolNamesIn(relPath) {
  const text = readFileSync(join(repoRoot, relPath), "utf8");
  return [...text.matchAll(/^\s*name:\s*"([a-z0-9_]+)"/gm)].map((m) => m[1]);
}

const registry = new Set();
const perSourceCount = new Map();
for (const src of TOOL_SOURCES) {
  let names;
  try {
    names = toolNamesIn(src);
  } catch (err) {
    console.error(`check-mcp-tool-claims: cannot read ${src} — ${err.message}`);
    console.error("  A registry that failed to build is not an empty registry.");
    process.exit(2);
  }
  if (names.length === 0) {
    console.error(`check-mcp-tool-claims: ${src} yielded 0 tool names.`);
    console.error("  Either the file moved or the `name: \"…\"` shape changed;");
    console.error("  either way this gate would silently pass everything. Refusing.");
    process.exit(2);
  }
  perSourceCount.set(src, names.length);
  for (const n of names) registry.add(n);
}

// Counts a surface may legitimately advertise: the stdio package, the gateway's
// mounted total, and each vertical package on its own.
const stdioCount = perSourceCount.get("mcp/src/tools/definitions.ts");
const gatewayTotal = registry.size;
const legitCounts = new Set([stdioCount, gatewayTotal, ...perSourceCount.values()]);

// The TIER split is legitimate to advertise too, and it is NOT derivable from
// the counts above — which is how a correct number nearly got "corrected".
// Measured: the stdio package defines 31 tools, three of which are Pro
// (`render_3d_preview`, `create_3d_artifact`, `generate_scene`), so its FREE
// surface is 28. Add the gateway-only-but-free `view_3d_model` and the overall
// free surface is 29, against 38 Pro, totalling the 67 mounted. `mcp/mcpize.yaml`
// advertises "28 free tools" and is RIGHT — a gate that rejected it would push a
// reader to break a true claim, which is worse than missing a false one.
// `TOOL_SOURCES` exits 2 on ENOENT and `SAMPLES_SOURCE` exits 2 on a zero count,
// but this one fell back to "" — the one source in the file that did not refuse.
// Measured by moving `mcp/src/tiers.ts` aside: the allowlist silently narrowed
// from 1/5/7/9/28/29/31/35/38/67 to 1/5/7/9/31/35/67 and the gate then reported
// the TRUE claims as wrong (`docs.tsx:65 claims "29 free tools"`). A false red,
// not a false green — but it contradicts the contract stated ten lines below
// ("a tier split that failed to PARSE is not a tier split of zero"), and a gate
// that reports correct prose as broken pushes a reader to break it.
const tierText = (() => {
  try {
    return readFileSync(join(repoRoot, "mcp/src/tiers.ts"), "utf8");
  } catch (e) {
    console.error(`check-mcp-tool-claims: cannot read mcp/src/tiers.ts — ${e.code || e.message}.`);
    console.error("  The tier split is not derivable from the other sources, so continuing");
    console.error("  would narrow the allowlist and report TRUE claims as wrong.");
    process.exit(2);
  }
})();
function tierArraySize(name) {
  // The arrays close with `] as const;`, not `];` — an earlier pattern assumed
  // the latter, silently matched nothing, and the tier counts stayed absent
  // from legitCounts without any error. Accept both terminators.
  const m = tierText.match(
    new RegExp(`const ${name}: readonly string\\[\\] = \\[([\\s\\S]*?)\\n\\](?: as const)?;`),
  );
  return m ? (m[1].match(/^\s*"[a-z0-9_]+",/gm) || []).length : 0;
}
const freeTierCount = tierArraySize("FREE_TOOLS");
const proTierCount = tierArraySize("PRO_TOOLS");
// Same contract as the registry and the sample count: a tier split that failed
// to PARSE is not a tier split of zero. This bit on the first attempt — the
// pattern assumed the arrays closed with `];` when they close with `] as const;`,
// matched nothing, and the counts silently stayed out of legitCounts with no
// error. A gate that quietly narrows its own allowlist is the shape this file
// exists to catch, so it refuses instead.
if (freeTierCount === 0 || proTierCount === 0) {
  console.error("check-mcp-tool-claims: mcp/src/tiers.ts parsed to 0 free or 0 pro tools.");
  console.error(`  free=${freeTierCount} pro=${proTierCount} — the array shape changed.`);
  console.error("  Refusing rather than silently dropping the tier counts.");
  process.exit(2);
}
if (freeTierCount > 0) {
  legitCounts.add(freeTierCount);
  // …and the free surface MINUS the gateway-only entries, which is the scope
  // `mcpize.yaml` counts.
  legitCounts.add(freeTierCount - 1);
}
if (proTierCount > 0) legitCounts.add(proTierCount);

// The five vertical packages COMBINED is an aggregate the Pro copy advertises —
// `tiers.ts`'s own PRO_UPGRADE_MESSAGE says "5 vertical packages … 35
// specialized tools" — and it is the sum of five per-package counts, so it is
// derivable from none of them individually. Without it the gate rejects a true
// claim, which is the failure mode that nearly cost mcpize.yaml its correct 28.
const verticalTotal = [...perSourceCount.entries()]
  .filter(([src]) => src.startsWith("mcp/packages/"))
  .reduce((n, [, count]) => n + count, 0);
if (verticalTotal > 0) {
  legitCounts.add(verticalTotal);
  // The package COUNT itself is advertised alongside it ("5 vertical packages").
  legitCounts.add([...perSourceCount.keys()].filter((k) => k.startsWith("mcp/packages/")).length);
}

// Sample scenarios drift exactly like tool counts do: "33 compilable samples"
// stood in SEVEN places against a real 38 — including two files written during
// the #3189 cleanup itself, by copying the number from a README instead of
// deriving it. Same treatment, same reason.
const SAMPLES_SOURCE = "mcp/src/samples.ts";
let sampleCount = 0;
try {
  const text = readFileSync(join(repoRoot, SAMPLES_SOURCE), "utf8");
  // Keys are QUOTED and two-space indented: `  "model-viewer": {`. Requiring
  // the quotes is what keeps the `Sample` interface's own fields (`id:`,
  // `title:`, … at the same indent, unquoted) out of the count.
  sampleCount = new Set([...text.matchAll(/^ {2}"([a-z0-9-]+)":\s*\{/gm)].map((m) => m[1])).size;
} catch {
  sampleCount = 0;
}
if (sampleCount === 0) {
  console.error(`check-mcp-tool-claims: ${SAMPLES_SOURCE} yielded 0 samples.`);
  console.error("  A count that failed to derive is not a count of zero. Refusing.");
  process.exit(2);
}

// ── Surfaces to scan ────────────────────────────────────────────────────────
// Public, human-facing prose only. `.claude/`, `changelog.d/` and `CHANGELOG.md`
// are EXCLUDED on purpose: they document removed and fabricated tool names as
// history, and a gate that cannot tell "we shipped this" from "we removed this"
// would force the record to be falsified to stay green.
// `mcp-gateway/src/dashboard` is the COMMERCIAL front end — the pricing page a
// subscriber reads and the docs page the gateway serves. It sat outside every
// pathspec here, exactly the way `.cursorrules` sat outside the mirror gate, and
// exactly the same thing happened: `pricing.tsx` advertised "27 free tools",
// "4 vertical packages … 24 specialised tools" and "(27 tools…)" against a real
// 29 / 5 / 35 / 31, and contradicted `tiers.ts`'s own PRO_UPGRADE_MESSAGE on the
// same funnel. A gate that skips the checkout page is not covering the surface
// that costs money to get wrong.
const SCAN_DIRS = [
  "docs/docs",
  "website-static",
  "gpt",
  "agents",
  "pro",
  "mcp-gateway/src/dashboard",
];
const SCAN_FILES = ["README.md", "AGENTS.md", "mcp/README.md", "llms.txt"];
const SCAN_EXT = new Set([".md", ".html", ".txt", ".tsx"]);
// Same reason as CHANGELOG.md: this is the docs-site mirror of it, and a release
// note naming `list_node_types` — a tool that existed when it was written — is
// history, not a false claim.
const SKIP_FILE = new Set(["docs/docs/changelog.md"]);
const SKIP_DIR = new Set(["node_modules", "assets", "models", "environments", "js", "fonts"]);

function walk(dir, out = []) {
  let entries;
  try {
    entries = readdirSync(join(repoRoot, dir));
  } catch {
    return out;
  }
  for (const e of entries) {
    if (SKIP_DIR.has(e)) continue;
    const rel = join(dir, e);
    // A broken symlink under a scanned dir must not crash a blocking gate with
    // an unhandled ENOENT — the filter below already tolerates it, this did not.
    let st;
    try {
      st = statSync(join(repoRoot, rel));
    } catch {
      continue;
    }
    if (st.isDirectory()) walk(rel, out);
    else if (SCAN_EXT.has(extname(e)) && !SKIP_FILE.has(rel)) out.push(rel);
  }
  return out;
}

// A scan dir that contributes ZERO files, or a named scan file that is missing,
// means the corpus moved — a Docusaurus restructure, a renamed static site — and
// the gate then passes because it has nothing left to look at. The registry side
// of this file already refuses on a source it cannot read (three `exit(2)`
// guards); the corpus side did not. Measured on a mutated tree: renaming
// `docs/docs` → `docs/content` printed `OK — 61 prose file(s) scanned`, and
// moving all six dirs plus all four files away printed
// `OK — 0 prose file(s) scanned` with rc=0. That is precisely the defect class
// this file's own header names — "a gate that passes because it found nothing is
// the worst defect class in this repo" — so the corpus gets the same contract.
const perDirFiles = new Map(SCAN_DIRS.map((d) => [d, walk(d)]));
const missingFiles = SCAN_FILES.filter((f) => {
  try {
    return !statSync(join(repoRoot, f)).isFile();
  } catch {
    return true;
  }
});
const emptyDirs = [...perDirFiles].filter(([, found]) => found.length === 0).map(([d]) => d);
if (emptyDirs.length || missingFiles.length) {
  console.error("check-mcp-tool-claims: the prose corpus moved — refusing to report OK.");
  for (const d of emptyDirs) console.error(`  SCAN_DIRS entry contributed 0 files: ${d}/`);
  for (const f of missingFiles) console.error(`  SCAN_FILES entry is missing: ${f}`);
  console.error("  Either the path moved (update SCAN_DIRS/SCAN_FILES) or the surface is");
  console.error("  gone. Scanning nothing and printing OK is not an option.");
  process.exit(2);
}

const files = [...SCAN_FILES, ...perDirFiles.values()].flat().filter((f) => {
  try {
    return statSync(join(repoRoot, f)).isFile();
  } catch {
    return false;
  }
});

// ── Heuristics ──────────────────────────────────────────────────────────────
// The shape of an MCP tool name: a verb, an underscore, more snake_case. Every
// fabricated name the audit found matches it, and so does every real one.
const TOOL_SHAPE =
  /^(get|list|create|add|set|generate|validate|migrate|search|analyze|debug|render|fetch|configure|convert|optimize|explain|view)_[a-z0-9_]+$/;

// Verb_snake tokens that are NOT MCP tools. Each needs a reason; an allowlist
// that grows without justification is how a gate stops meaning anything.
const NOT_A_TOOL = new Map([
  ["get_started", "prose phrase, and a removed tool the docs still describe as a concept"],
  ["set_state", "generic API prose"],
  ["create_react_app", "third-party tooling"],
  // NOT listed, deliberately: `add_model`. It reads like an allowlist candidate
  // and is not one — the playground regression that named it must stay caught.
  // It used to be inserted here and deleted on the next line as a way of saying
  // so; insert-then-delete is dead code inside a blocking gate, so the note says
  // it instead.
]);

// A QUALIFIER may sit between the number and "tools" — "27 free tools",
// "24 specialised tools", "38 Pro tools". The first version of this pattern
// allowed only "MCP"/"AI", so two of the three false counts on the live
// pricing page walked straight past it while the third was caught. A gate
// that catches one of three instances of the same claim reads as coverage.
const COUNT_QUALIFIER = "(?:MCP|AI|free|Pro|specialised|specialized|developer|total|compilable)\\s+";
const COUNT_RE = new RegExp(`\\b(\\d{1,3})\\+?\\s+(?:${COUNT_QUALIFIER})?tools?\\b`, "gi");
const SAMPLE_COUNT_RE =
  /\b(\d{1,3})\+?\s+(?:compilable\s+|code\s+|working,?\s+tested\s+)?(?:samples|scenarios)\b|\bany of (\d{1,3}) scenarios\b/gi;
const INLINE_CODE_RE = /`([^`\n]+)`/g;

function stripFencedBlocks(text) {
  // Replace fenced blocks with blank lines so line numbers survive.
  const lines = text.split("\n");
  let inFence = false;
  return lines
    .map((l) => {
      if (/^\s*```/.test(l)) {
        inFence = !inFence;
        return "";
      }
      return inFence ? "" : l;
    })
    .join("\n");
}

const findings = [];

for (const file of files) {
  const raw = readFileSync(join(repoRoot, file), "utf8");
  const prose = extname(file) === ".md" ? stripFencedBlocks(raw) : raw;
  const lines = prose.split("\n");

  lines.forEach((line, i) => {
    for (const m of line.matchAll(INLINE_CODE_RE)) {
      const token = m[1].trim();
      if (!TOOL_SHAPE.test(token)) continue;
      if (registry.has(token)) continue;
      if (NOT_A_TOOL.has(token)) continue;
      findings.push({
        file,
        line: i + 1,
        kind: "unknown-tool",
        detail: `\`${token}\` is not an MCP tool in any registry`,
      });
    }
    for (const m of line.matchAll(SAMPLE_COUNT_RE)) {
      const n = Number(m[1] ?? m[2]);
      if (n === sampleCount) continue;
      findings.push({
        file,
        line: i + 1,
        kind: "wrong-sample-count",
        detail: `claims "${m[0].trim()}" — ${SAMPLES_SOURCE} defines ${sampleCount}`,
      });
    }
    for (const m of line.matchAll(COUNT_RE)) {
      const n = Number(m[1]);
      if (legitCounts.has(n)) continue;
      findings.push({
        file,
        line: i + 1,
        kind: "wrong-count",
        detail: `claims "${m[0].trim()}" — real counts are ${[...legitCounts].sort((a, b) => a - b).join(", ")}`,
      });
    }

    // A line wrap can split the number from the noun, and a per-line scan is
    // blind to it. Measured: `mcp-gateway/src/dashboard/docs.tsx:16` read
    // "The 27 free" / "tools work without authentication" and passed this gate
    // while every other count in the same file was correct — on a page a paying
    // subscriber reads. Re-scan across one line boundary and keep only matches
    // that actually straddle it, so a same-line claim is never reported twice.
    const next = lines[i + 1];
    if (next === undefined) return;
    const joined = `${line} ${next}`;
    const straddles = (m) => m.index < line.length && m.index + m[0].length > line.length + 1;
    for (const m of joined.matchAll(COUNT_RE)) {
      if (!straddles(m)) continue;
      const n = Number(m[1]);
      if (legitCounts.has(n)) continue;
      findings.push({
        file,
        line: i + 1,
        kind: "wrong-count",
        detail:
          `claims "${m[0].trim().replace(/\s+/g, " ")}" (wrapped onto line ${i + 2}) — ` +
          `real counts are ${[...legitCounts].sort((a, b) => a - b).join(", ")}`,
      });
    }
    for (const m of joined.matchAll(SAMPLE_COUNT_RE)) {
      if (!straddles(m)) continue;
      const n = Number(m[1] ?? m[2]);
      if (n === sampleCount) continue;
      findings.push({
        file,
        line: i + 1,
        kind: "wrong-sample-count",
        detail: `claims "${m[0].trim().replace(/\s+/g, " ")}" (wrapped onto line ${i + 2}) — ${SAMPLES_SOURCE} defines ${sampleCount}`,
      });
    }
  });
}

if (process.argv.includes("--json")) {
  console.log(JSON.stringify({ registrySize: registry.size, legitCounts: [...legitCounts], findings }, null, 2));
} else if (findings.length === 0) {
  console.log(
    `check-mcp-tool-claims: OK — ${files.length} prose file(s) scanned, ` +
      `${registry.size} known tools, ${sampleCount} samples, counts ${[...legitCounts].sort((a, b) => a - b).join("/")}.`,
  );
} else {
  console.error("::error::Prose surfaces claim MCP tools or counts that do not exist.");
  console.error("");
  for (const f of findings) {
    console.error(`  ${f.file}:${f.line}  [${f.kind}]  ${f.detail}`);
  }
  console.error("");
  console.error("  Tool names come from mcp/src/tools/definitions.ts and the mounted");
  console.error("  packages. If a name is legitimately not a tool, add it to NOT_A_TOOL");
  console.error("  in tools/check-mcp-tool-claims.js WITH A REASON.");
}

process.exit(findings.length === 0 ? 0 : 1);
