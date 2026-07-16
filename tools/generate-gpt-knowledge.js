#!/usr/bin/env node
/**
 * Generate the `gpt/knowledge-*.md` knowledge base from the repo root
 * `llms.txt` — the single source of truth for the SceneView API surface.
 *
 * WHY THIS EXISTS (issue #2724)
 * -----------------------------
 * `gpt/` holds the knowledge files uploaded to the "SceneView 3D & AR
 * Assistant" Custom GPT (OpenAI GPT Store — see `gpt/README.md`). They were
 * originally hand-maintained copies of what `llms.txt` already encodes, and
 * NOTHING regenerated them, so they rotted (the platform table sat at 3.6.2
 * while the SDK shipped 4.22.0; the sample index claimed "39 samples"). In an
 * AI-first repo a second, stale knowledge surface is worse than none: a GPT
 * that reads it emits stale code. This script derives the four knowledge
 * files from `llms.txt` deterministically so they can never drift again, and
 * a CI drift check (`--check`, wired into `ci.yml` → `repo-hygiene`) fails the
 * build if a committed file is out of date.
 *
 * WHAT IT DOES
 * ------------
 * Parses `llms.txt` into its top-level `## ` sections, routes each section to
 * exactly one of four knowledge buckets (overview / api / practices /
 * samples), and writes `gpt/knowledge-<bucket>.md` with a GENERATED banner.
 * The `# SceneView` preamble (intro + version block) is prepended to the
 * overview file so the platform/version facts live with the overview.
 *
 * USAGE
 * -----
 *   node tools/generate-gpt-knowledge.js          # (re)write the files
 *   node tools/generate-gpt-knowledge.js --check   # CI: exit 1 if any drift
 *
 * Pure Node, zero dependencies.
 */

"use strict";

const { readFileSync, writeFileSync } = require("node:fs");
const { resolve, dirname, relative } = require("node:path");

const toolsDir = __dirname;
const repoRoot = resolve(toolsDir, "..");
const llmsPath = resolve(repoRoot, "llms.txt");
const gradlePropsPath = resolve(repoRoot, "gradle.properties");
const gptDir = resolve(repoRoot, "gpt");

// ── Bucket definitions ─────────────────────────────────────────────────────
// Order matters only for the file list; section order within each file always
// follows llms.txt document order. Each bucket has a title (H1 of the file)
// and a short human note describing what it covers.
const BUCKETS = {
  overview: {
    file: "knowledge-overview.md",
    title: "SceneView — Platform Overview & Setup",
    note: "Platform support, setup, cross-platform architecture, and why SceneView.",
  },
  api: {
    file: "knowledge-api.md",
    title: "SceneView — API Reference",
    note: "Composables, node types, resource loading, camera, math, and per-platform APIs.",
  },
  practices: {
    file: "knowledge-practices.md",
    title: "SceneView — Best Practices & Troubleshooting",
    note: "Threading, performance, error handling, debugging, recording, and media.",
  },
  samples: {
    file: "knowledge-samples.md",
    title: "SceneView — Recipes & Sample Index",
    note: "Copy-paste recipes and the full demo/sample catalog.",
  },
};

/**
 * Route a top-level llms.txt section (heading text WITHOUT the leading `## `)
 * to a bucket. Classified by ASCII prefix so it is robust to the em-dash / #NNN
 * suffixes in the headings. Anything unmatched defaults to `api` — a new
 * llms.txt section then lands in knowledge-api.md and the `--check` drift gate
 * flags the committed file as stale, prompting a regenerate (never a silent
 * miss).
 */
function classify(title) {
  const startsWithAny = (prefixes) =>
    prefixes.some((p) => title.startsWith(p));

  if (
    startsWithAny([
      "Setup",
      "Why SceneView",
      "AI Integration",
      "Platform Coverage",
      "Cross-Platform",
    ])
  ) {
    return "overview";
  }

  if (
    startsWithAny([
      "Recipes",
      "Sample app demos",
      "Sketchfab streaming",
      "DemoScaffold",
    ])
  ) {
    return "samples";
  }

  if (
    startsWithAny([
      "Threading Rules",
      "Performance",
      "Error Handling",
      "Render Quality",
      "AR Debug",
      "Record the scene",
      "AR Recording",
      "AR Image Stabilization",
      "Haptic",
      "Spatial Audio",
    ])
  ) {
    return "practices";
  }

  return "api";
}

/**
 * Split llms.txt into { preamble, sections[] }. `preamble` is everything
 * before the first `## ` heading (the `# SceneView` title + intro + version
 * block). Each section is { title, body } where body includes the `## `
 * heading line through the line before the next `## ` heading.
 */
function parseLlms(text) {
  const lines = text.split("\n");
  const preambleLines = [];
  const sections = [];
  let current = null;
  let seenSection = false;

  for (const line of lines) {
    const m = /^## (.+?)\s*$/.exec(line);
    if (m) {
      seenSection = true;
      if (current) sections.push(current);
      current = { title: m[1], lines: [line] };
    } else if (!seenSection) {
      preambleLines.push(line);
    } else {
      current.lines.push(line);
    }
  }
  if (current) sections.push(current);

  return {
    preamble: preambleLines.join("\n").trim(),
    sections: sections.map((s) => ({
      title: s.title,
      body: s.lines.join("\n").trim(),
    })),
  };
}

function readVersion() {
  const props = readFileSync(gradlePropsPath, "utf-8");
  const m = /^VERSION_NAME=(.+)$/m.exec(props);
  if (!m) {
    console.error("[generate-gpt-knowledge] VERSION_NAME not found in gradle.properties");
    process.exit(1);
  }
  return m[1].trim();
}

function banner(version) {
  const rel = relative(repoRoot, __filename).split(require("node:path").sep).join("/");
  return [
    "<!--",
    "  GENERATED FILE — DO NOT EDIT.",
    "  Source of truth: /llms.txt  (SceneView " + version + ")",
    "  Regenerate:      node " + rel,
    "  Drift is caught in CI (ci.yml -> repo-hygiene). Edit llms.txt instead.",
    "  See issue #2724.",
    "-->",
  ].join("\n");
}

/** Build the full text of one knowledge file. */
function renderFile(bucketKey, parsed, version) {
  const bucket = BUCKETS[bucketKey];
  const parts = [];
  parts.push(banner(version));
  parts.push("");
  parts.push("# " + bucket.title);
  parts.push("");
  parts.push("> " + bucket.note);
  parts.push(
    "> Auto-generated from `llms.txt` (SceneView " +
      version +
      "). This is a slice of the machine-readable API reference — the same content an AI reads to generate SceneView code.",
  );
  parts.push("");

  // The overview file carries the llms.txt preamble (intro + version block)
  // so the platform/version facts stay with the overview.
  if (bucketKey === "overview" && parsed.preamble) {
    parts.push(parsed.preamble);
    parts.push("");
    parts.push("---");
    parts.push("");
  }

  const owned = parsed.sections.filter((s) => classify(s.title) === bucketKey);
  parts.push(owned.map((s) => s.body).join("\n\n"));
  parts.push("");

  return parts.join("\n").replace(/\n{3,}/g, "\n\n").replace(/\s*$/, "") + "\n";
}

// ── Main ───────────────────────────────────────────────────────────────────
function main() {
  const check = process.argv.includes("--check");
  const llms = readFileSync(llmsPath, "utf-8");
  const parsed = parseLlms(llms);
  const version = readVersion();

  // Sanity: every section must route somewhere (classify never returns
  // undefined, but assert the four buckets are all non-empty so a broken
  // parse can't silently write four near-empty files).
  const counts = { overview: 0, api: 0, practices: 0, samples: 0 };
  for (const s of parsed.sections) counts[classify(s.title)]++;
  const empty = Object.entries(counts).filter(([, n]) => n === 0);
  if (empty.length) {
    console.error(
      "[generate-gpt-knowledge] bucket(s) with zero sections: " +
        empty.map(([k]) => k).join(", ") +
        " — llms.txt structure changed; update classify() in " +
        "tools/generate-gpt-knowledge.js",
    );
    process.exit(1);
  }

  let drift = false;
  for (const key of Object.keys(BUCKETS)) {
    const outPath = resolve(gptDir, BUCKETS[key].file);
    const next = renderFile(key, parsed, version);
    if (check) {
      let current = "";
      try {
        current = readFileSync(outPath, "utf-8");
      } catch {
        current = "";
      }
      if (current !== next) {
        drift = true;
        console.error("DRIFT: gpt/" + BUCKETS[key].file + " is out of date vs llms.txt");
      }
    } else {
      writeFileSync(outPath, next);
      console.log("wrote gpt/" + BUCKETS[key].file + " (" + counts[key] + " sections)");
    }
  }

  if (check) {
    if (drift) {
      console.error(
        "\ngpt/knowledge-*.md drifted from llms.txt.\n" +
          "Regenerate and commit:\n  node tools/generate-gpt-knowledge.js\n",
      );
      process.exit(1);
    }
    console.log("gpt/knowledge-*.md are in sync with llms.txt.");
  }
}

main();
