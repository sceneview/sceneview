#!/usr/bin/env node
/**
 * Extract the ```kotlin snippets embedded in the AI-first doc surfaces
 * (`llms.txt` + `agents/sceneview/references/*.md`) into per-snippet Kotlin
 * source files that the `:snippets-check` Gradle module compiles in CI.
 *
 * WHY THIS EXISTS (issue #2759)
 * -----------------------------
 * `llms.txt` is the surface an AI reads to generate user code. It embeds
 * ~190 Kotlin snippets that NOTHING ever compiles: the existing guards are
 * an advisory grep (`check-doc-drift.sh`, exit 0) and a weekly LLM prose
 * audit — neither catches a renamed parameter deterministically. A drifted
 * snippet makes every AI assistant emit broken code (the `centerOrigin`
 * drift class, #2622/#2623). This script turns every documented snippet
 * into a compile-time invariant: an API change that breaks documented code
 * becomes a deterministic CI red.
 *
 * WHAT IT DOES
 * ------------
 * Parses the ```kotlin fenced blocks of each source, classifies every block:
 *
 *   - `compile`  — emitted as its own .kt file (own package, so blocks can
 *                  never collide). Fragments that are not top-level
 *                  declarations are wrapped in a `@Composable private fun`;
 *                  leading `import` lines are hoisted above the wrapper.
 *   - `notest`   — explicitly opted out in the doc with ```kotlin notest
 *                  (for intentionally non-standalone fragments). Every
 *                  `notest` MUST carry a reason: ```kotlin notest <reason>.
 *   - `ellipsis` — contains a `...` placeholder: structural pseudo-code by
 *                  convention, never compilable. Skipped automatically so
 *                  docs keep the freedom to elide.
 *   - `nonkotlin`— Gradle/Groovy blocks mistagged as kotlin (heuristic:
 *                  dependency/plugins DSL in the first lines).
 *   - `otherplatform` — blocks under the "SceneView Web" / "SceneViewSwift"
 *                  sections of llms.txt: Kotlin/JS (@JsExport) or Swift-bridge
 *                  API that can never compile on this Android classpath. A
 *                  JS-side guard is an honest follow-up (see #2759).
 *
 * A JSON manifest with per-category counts and per-block provenance
 * (source file + line) is written next to the generated sources, so a
 * compile error maps straight back to the doc line that caused it.
 *
 * USAGE
 * -----
 *   node tools/extract-doc-snippets.js                # write into tools/snippets-check/build/generated
 *   node tools/extract-doc-snippets.js --out <dir>    # custom output dir
 *   node tools/extract-doc-snippets.js --list         # human-readable classification report, no writes
 *
 * Pure Node, zero dependencies. Deterministic: same inputs → same outputs.
 */

"use strict";

const { readFileSync, writeFileSync, mkdirSync, rmSync } = require("node:fs");
const { resolve, join } = require("node:path");

const toolsDir = __dirname;
const repoRoot = resolve(toolsDir, "..");

// ─── Sources ────────────────────────────────────────────────────────────────
// slug → repo-relative path. The slug becomes part of the generated package.
const SOURCES = [
  { slug: "llms", path: "llms.txt" },
  { slug: "cheatsheet", path: "agents/sceneview/references/cheatsheet.md" },
  { slug: "migration", path: "agents/sceneview/references/migration.md" },
  { slug: "recipes", path: "agents/sceneview/references/recipes.md" },
];

// ─── Preamble imports ───────────────────────────────────────────────────────
// Injected into every generated file. Snippets are documentation fragments:
// they omit imports the way every Kotlin doc does, so the harness supplies
// the SDK surface wholesale. Wildcards keep this list short; a snippet's own
// explicit imports are hoisted AFTER these and win any ambiguity.
const PREAMBLE_IMPORTS = [
  "androidx.compose.foundation.layout.*",
  "androidx.compose.material3.Text",
  "androidx.compose.material3.Button",
  "androidx.compose.runtime.*",
  "androidx.compose.ui.Alignment",
  "androidx.compose.ui.Modifier",
  "androidx.compose.ui.graphics.Color",
  "androidx.compose.ui.unit.dp",
  "com.google.android.filament.Engine",
  "com.google.ar.core.Anchor",
  "com.google.ar.core.Config",
  "com.google.ar.core.Frame",
  "com.google.ar.core.HitResult",
  "com.google.ar.core.Plane",
  "com.google.ar.core.TrackingFailureReason",
  "com.google.ar.core.TrackingState",
  "io.github.sceneview.*",
  "io.github.sceneview.animation.*",
  "io.github.sceneview.ar.*",
  "io.github.sceneview.ar.arcore.*",
  "io.github.sceneview.ar.node.*",
  "io.github.sceneview.ar.scene.*",
  "io.github.sceneview.collision.*",
  "io.github.sceneview.environment.*",
  "io.github.sceneview.geometries.*",
  "io.github.sceneview.loaders.*",
  "io.github.sceneview.material.*",
  "io.github.sceneview.math.*",
  "io.github.sceneview.model.*",
  "io.github.sceneview.node.*",
  "io.github.sceneview.texture.*",
  "kotlinx.coroutines.*",
];

// Suppressed diagnostics: snippets illustrate one API each — unused locals,
// shadowed names and missing usages are inherent, not defects.
const FILE_SUPPRESS =
  '@file:Suppress("unused", "UNUSED_VARIABLE", "UNUSED_PARAMETER", ' +
  '"UNUSED_ANONYMOUS_PARAMETER", "UNUSED_EXPRESSION", "RedundantSuppression", ' +
  '"LocalVariableName", "FunctionName", "SpellCheckingInspection")';

// A block whose first meaningful line starts a top-level declaration is
// emitted verbatim; anything else is a fragment and gets wrapped in a
// @Composable function body (composable context is a superset: plain
// statements compile there too, and most fragments use remember*/Composable
// APIs that REQUIRE it).
const TOP_LEVEL_RE =
  /^(@\w[\w.]*(\(.*\))?\s*$|(private\s+|internal\s+|public\s+|abstract\s+|sealed\s+|open\s+)*(fun|class|interface|object|enum\s+class|data\s+class|annotation\s+class|typealias)\b|@Composable\b)/;

// Heuristic for Gradle DSL blocks mistagged ```kotlin.
const GRADLE_RE =
  /^\s*(dependencies\s*\{|plugins\s*\{|implementation\s*\(|api\s*\(|classpath\s*\()/m;

// ─── Fence parsing ──────────────────────────────────────────────────────────

// llms.txt is multi-platform: its "SceneView Web" section documents the
// Kotlin/JS API (@JsExport surface of sceneview-web) — those blocks can never
// compile on this module's Android classpath. They are classified out with
// their own manifest category so the gap stays visible (a JS-side compile
// guard is an honest follow-up, tracked on #2759).
const NON_ANDROID_SECTIONS = [
  { prefix: "## SceneView Web", platform: "web" },
  { prefix: "## SceneViewSwift", platform: "swift" },
];

/** @returns {{info:string, line:number, code:string, platform:string}[]} */
function parseKotlinBlocks(text) {
  const lines = text.split("\n");
  const blocks = [];
  let inBlock = false;
  let info = "";
  let start = 0;
  let buf = [];
  let platform = "android";
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!inBlock && line.startsWith("## ")) {
      const hit = NON_ANDROID_SECTIONS.find((s) => line.startsWith(s.prefix));
      platform = hit ? hit.platform : "android";
    }
    if (!inBlock) {
      const m = line.match(/^```kotlin\b(.*)$/);
      if (m) {
        inBlock = true;
        info = m[1].trim();
        start = i + 2; // 1-indexed line of the first code line
        buf = [];
      }
    } else if (line.startsWith("```")) {
      inBlock = false;
      blocks.push({ info, line: start, code: buf.join("\n"), platform });
    } else {
      buf.push(line);
    }
  }
  return blocks;
}

// ─── Classification ─────────────────────────────────────────────────────────

function classify(block) {
  if (block.platform !== "android") {
    return { category: "otherplatform", reason: `${block.platform} API section` };
  }
  if (/^notest\b/.test(block.info)) {
    const reason = block.info.replace(/^notest\s*/, "").trim();
    return { category: "notest", reason: reason || "(MISSING REASON)" };
  }
  if (block.code.includes("...")) {
    return { category: "ellipsis", reason: "contains ... placeholder" };
  }
  if (GRADLE_RE.test(block.code)) {
    return { category: "nonkotlin", reason: "Gradle DSL mistagged as kotlin" };
  }
  return { category: "compile" };
}

// ─── Emission ───────────────────────────────────────────────────────────────

function emit(block, slug, index) {
  const pkg = `docsnippets.${slug}.b${String(index).padStart(3, "0")}`;
  const codeLines = block.code.split("\n");

  // Hoist the block's own leading imports (they may appear anywhere in a
  // fragment's first lines, before any code).
  const hoisted = [];
  const body = [];
  let inHeader = true;
  for (const l of codeLines) {
    if (inHeader && /^import\s+/.test(l.trim())) {
      hoisted.push(l.trim());
    } else {
      if (l.trim() !== "" || !inHeader) {
        if (l.trim() !== "") inHeader = false;
        body.push(l);
      }
    }
  }

  const firstCode = body.find((l) => l.trim() !== "" && !/^\/\//.test(l.trim()));
  const topLevel = firstCode !== undefined && TOP_LEVEL_RE.test(firstCode.trim());

  const header = [
    FILE_SUPPRESS,
    "",
    `package ${pkg}`,
    "",
    ...PREAMBLE_IMPORTS.map((i) => `import ${i}`),
    ...hoisted.map((h) => (h.startsWith("import") ? h : `import ${h}`)),
    "",
    `// GENERATED by tools/extract-doc-snippets.js — DO NOT EDIT.`,
    `// Source: ${block.sourcePath}:${block.line}`,
  ];

  const content = topLevel
    ? body.join("\n")
    : [
        "@Composable",
        `private fun Snippet_${slug}_${index}() {`,
        ...body.map((l) => (l === "" ? l : `    ${l}`)),
        "}",
      ].join("\n");

  return {
    file: `${pkg.replace(/\./g, "/")}/Snippet.kt`,
    text: header.join("\n") + "\n" + content + "\n",
    wrapped: !topLevel,
  };
}

// ─── Main ───────────────────────────────────────────────────────────────────

function main() {
  const args = process.argv.slice(2);
  const listOnly = args.includes("--list");
  const outIdx = args.indexOf("--out");
  const outDir =
    outIdx !== -1
      ? resolve(args[outIdx + 1])
      : join(repoRoot, "tools/snippets-check/build/generated/snippets");

  const manifest = { generatedBy: "tools/extract-doc-snippets.js", sources: {}, counts: {} };
  const emitted = [];
  const report = [];

  for (const src of SOURCES) {
    const text = readFileSync(join(repoRoot, src.path), "utf8");
    const blocks = parseKotlinBlocks(text);
    const perSource = { total: blocks.length, compile: 0, notest: 0, ellipsis: 0, nonkotlin: 0, otherplatform: 0, blocks: [] };

    blocks.forEach((block, i) => {
      block.sourcePath = src.path;
      const cls = classify(block);
      perSource[cls.category]++;
      perSource.blocks.push({ index: i, line: block.line, ...cls });
      report.push(
        `${src.path}:${block.line}\t${cls.category}${cls.reason ? `\t(${cls.reason})` : ""}`
      );
      if (cls.category === "compile") {
        emitted.push(emit(block, src.slug, i));
      }
      if (cls.category === "notest" && cls.reason === "(MISSING REASON)") {
        console.error(
          `ERROR: ${src.path}:${block.line} — \`\`\`kotlin notest without a reason. ` +
            "Every opt-out must say why: ```kotlin notest <reason>"
        );
        process.exitCode = 1;
      }
    });
    manifest.sources[src.path] = perSource;
  }

  for (const cat of ["compile", "notest", "ellipsis", "nonkotlin", "otherplatform"]) {
    manifest.counts[cat] = Object.values(manifest.sources).reduce((a, s) => a + s[cat], 0);
  }

  if (listOnly) {
    console.log(report.join("\n"));
    console.log(`\nTotals: ${JSON.stringify(manifest.counts)}`);
    return;
  }

  if (manifest.counts.compile === 0) {
    console.error("ERROR: zero compilable snippets extracted — parser or docs broken.");
    process.exit(1);
  }

  rmSync(outDir, { recursive: true, force: true });
  for (const e of emitted) {
    const path = join(outDir, e.file);
    mkdirSync(resolve(path, ".."), { recursive: true });
    writeFileSync(path, e.text);
  }
  writeFileSync(join(outDir, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
  console.log(
    `Extracted ${manifest.counts.compile} compilable snippets ` +
      `(${manifest.counts.ellipsis} ellipsis, ${manifest.counts.notest} notest, ` +
      `${manifest.counts.nonkotlin} non-kotlin, ` +
      `${manifest.counts.otherplatform} other-platform) → ${outDir}`
  );
}

main();
