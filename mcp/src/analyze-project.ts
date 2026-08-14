/**
 * analyze-project — local filesystem scan of a SceneView project.
 *
 * Because the SceneView MCP server runs on the user's machine, we can read
 * their project files directly: detect the platform (Android / iOS / Web),
 * extract the SceneView dependency version, compare against the latest known
 * release, and scan source files for well-known anti-patterns (threading
 * violations, LightNode trailing-lambda bug, deprecated 2.x APIs, …).
 *
 * This is the first real *agentic* tool in the MCP — previous tools were
 * static getters, this one actually inspects the caller's filesystem to
 * produce a tailored report.
 *
 * Hard safety limits:
 *   - at most {@link MAX_FILES_SCANNED} source files read
 *   - at most {@link MAX_BYTES_SCANNED} bytes read in total
 *   - at most {@link MAX_DIR_DEPTH} directory levels traversed
 *
 * When any limit is hit we stop walking and return a warning so the caller
 * knows the scan was truncated — we never throw.
 */

import { promises as fs, type Stats } from "node:fs";
import path from "node:path";
import { LATEST_SCENEVIEW_RELEASE } from "./generated/version.js";

// ─── Constants ───────────────────────────────────────────────────────────────

/**
 * The latest SceneView release known to this build of the MCP, snapshotted
 * from the root `gradle.properties:VERSION_NAME` at build time via
 * `scripts/generate-version.js`. See #941 — pre-fix this was hardcoded to
 * "4.0.0" and never bumped, so every install of every MCP version told the
 * LLM that "4.0.0" was current even when the real SDK was at 4.0.9.
 */
export const LATEST_SCENEVIEW_VERSION = LATEST_SCENEVIEW_RELEASE;

/** Hard cap on the number of source files inspected per call. */
export const MAX_FILES_SCANNED = 30;

/** Hard cap on total bytes read across all source files per call (500 KB). */
export const MAX_BYTES_SCANNED = 500 * 1024;

/** Max directory tree depth traversed from the project root. */
export const MAX_DIR_DEPTH = 12;

/** Directories we never recurse into (build outputs, caches, vendored deps). */
const SKIP_DIRS = new Set<string>([
  "node_modules",
  ".git",
  "build",
  "dist",
  ".gradle",
  ".idea",
  ".vscode",
  ".claude",
  "Pods",
  "DerivedData",
  ".build",
  "out",
  "target",
]);

/** File extensions we consider "source" for anti-pattern scanning. */
const SOURCE_EXTENSIONS = new Set<string>([".kt", ".kts", ".swift", ".js", ".mjs", ".ts", ".tsx"]);

// ─── Public types ────────────────────────────────────────────────────────────

export type ProjectType = "android" | "ios" | "web" | "unknown";

export interface AnalysisWarning {
  /** Absolute file path the warning was found in, or a sentinel like `<scan>`. */
  file: string;
  /** 1-based line number, when the detector tracks it. */
  line?: number;
  /** Stable machine-readable warning id. */
  type: string;
  /** Human-readable explanation. */
  message: string;
}

export interface AnalysisSuggestion {
  /** Stable machine-readable suggestion id. */
  type: string;
  /** Human-readable recommendation. */
  message: string;
}

export interface AnalysisResult {
  /** Detected project type, or `"unknown"` if nothing matched. */
  projectType: ProjectType;
  /** The extracted SceneView dependency version, or `null` if none found. */
  sceneViewVersion: string | null;
  /** Latest version this MCP build is aware of. */
  latestVersion: string;
  /** True when {@link sceneViewVersion} is strictly older than {@link latestVersion}. */
  isOutdated: boolean;
  /** Any anti-patterns or scan-level concerns found, in the order they were discovered. */
  warnings: AnalysisWarning[];
  /** Recommended follow-ups derived from the analysis (upgrade, run validator, …). */
  suggestions: AnalysisSuggestion[];
  /** The absolute path that was actually scanned (after resolution / default). */
  scannedPath: string;
  /** Number of files actually read during the anti-pattern scan. */
  filesScanned: number;
  /** Total bytes actually read during the anti-pattern scan. */
  bytesScanned: number;
  /** When `true`, the scan was truncated because a hard limit was reached. */
  truncated: boolean;
}

export interface AnalyzeProjectInput {
  /** Absolute or relative path to the project root. Defaults to `process.cwd()`. */
  path?: string;
}

// ─── Anti-pattern detectors ──────────────────────────────────────────────────

interface AntiPatternDetector {
  id: string;
  /** Which languages this detector applies to — others are skipped for speed. */
  languages: ReadonlySet<"kotlin" | "swift" | "js">;
  /** Regex that, if matched, triggers the detector. */
  pattern: RegExp;
  /** Human-readable explanation returned in the warning. */
  message: string;
  /**
   * Optional extra guard: runs on the full file contents after the line
   * pattern matches. Used for cross-line context (e.g. "inside a `launch {`"
   * for the threading rule).
   */
  fileGuard?: (fileContents: string) => boolean;
}

/**
 * The curated list of anti-patterns we scan for. This is a deliberately
 * smaller subset of the full `validator.ts` rules — the goal here is "fast
 * sanity check of an entire project", not a per-file lint pass.
 *
 * Covered:
 *   - threading: Filament/ModelLoader calls inside a background launch block
 *   - API misuse: LightNode trailing-lambda bug
 *   - 2.x deprecated APIs: ArSceneView, TransformableNode, PlacementNode,
 *     ViewRenderable, loadModelAsync, Sceneform imports
 */
const ANTI_PATTERNS: AntiPatternDetector[] = [
  // ─── Threading: modelLoader.createModel* inside a non-main launch ──────────
  {
    id: "threading/filament-off-main-thread",
    languages: new Set(["kotlin"]),
    pattern: /modelLoader\s*\.\s*createModel\w*\s*\(/,
    message:
      "`modelLoader.createModel*` is called in a file that also uses a background coroutine " +
      "(`launch {` / `Dispatchers.IO` / `Dispatchers.Default`). Filament JNI calls must run on " +
      "the main thread. Use `rememberModelInstance(modelLoader, path)` in composables, or " +
      "`withContext(Dispatchers.Main)` in imperative code.",
    fileGuard: (contents) =>
      /\blaunch\s*\(?\s*Dispatchers\.(IO|Default)\b/.test(contents) ||
      /\blaunch\s*\{/.test(contents) ||
      /\bwithContext\s*\(\s*Dispatchers\.(IO|Default)\b/.test(contents),
  },

  // ─── API: LightNode(...) { ... } trailing-lambda bug ───────────────────────
  {
    id: "api/light-node-trailing-lambda",
    languages: new Set(["kotlin"]),
    pattern: /\bLightNode\s*\([^)]*\)\s*\{/,
    message:
      "`LightNode`'s configuration block is a **named parameter** `apply`, not a trailing " +
      "lambda. Write `LightNode(engine = engine, type = ..., apply = { intensity(100_000f) })`. " +
      "Without `apply =` the block is silently ignored.",
  },

  // ─── Deprecated 2.x APIs ───────────────────────────────────────────────────
  {
    id: "migration/ar-scene-view-rename",
    languages: new Set(["kotlin"]),
    pattern: /\bArSceneView\s*\(/,
    message:
      "`ArSceneView` was renamed to `ARSceneView` in SceneView 3.0. Update the call site " +
      "(capital R). Run `migrate_code` for an automatic rewrite.",
  },
  {
    id: "migration/transformable-node-removed",
    languages: new Set(["kotlin"]),
    pattern: /\bTransformableNode\b/,
    message:
      "`TransformableNode` was removed in SceneView 3.0. Set `isEditable = true` on a " +
      "`ModelNode` or `GeometryNode` instead.",
  },
  {
    id: "migration/placement-node-removed",
    languages: new Set(["kotlin"]),
    pattern: /\bPlacementNode\b/,
    message:
      "`PlacementNode` was removed in SceneView 3.0. Use `AnchorNode` + `HitResultNode` " +
      "inside an `ARSceneView` instead.",
  },
  {
    id: "migration/view-renderable-removed",
    languages: new Set(["kotlin"]),
    pattern: /\bViewRenderable\b/,
    message:
      "`ViewRenderable` was removed in SceneView 3.0. Use `ViewNode` with a `@Composable` " +
      "content lambda instead.",
  },
  {
    id: "migration/load-model-async",
    languages: new Set(["kotlin"]),
    pattern: /\bmodelLoader\s*\.\s*loadModelAsync\s*\(/,
    message:
      "`modelLoader.loadModelAsync` was removed in SceneView 3.0. Use " +
      "`rememberModelInstance(modelLoader, path)` in composables, or " +
      "`modelLoader.loadModelInstanceAsync(path)` in imperative code.",
  },
  {
    id: "migration/sceneform-import",
    languages: new Set(["kotlin"]),
    pattern: /\bimport\s+com\.google\.ar\.sceneform\b/,
    message:
      "`com.google.ar.sceneform.*` was deprecated by Google in 2021. Replace with " +
      "`io.github.sceneview.*` imports — SceneView is the official successor.",
  },
  {
    id: "migration/scene-view-renamed",
    languages: new Set(["kotlin"]),
    pattern: /\bimport\s+com\.google\.ar\.sceneform\.ux\.ArFragment\b/,
    message:
      "`ArFragment` (Sceneform) removed. Replace with `ARSceneView { … }` composable from " +
      "`io.github.sceneview.ar`.",
  },
];

// ─── Core scanner ────────────────────────────────────────────────────────────

type DetectedLanguage = "kotlin" | "swift" | "js";

function languageOf(filePath: string): DetectedLanguage | null {
  const ext = path.extname(filePath).toLowerCase();
  if (ext === ".kt" || ext === ".kts") return "kotlin";
  if (ext === ".swift") return "swift";
  if (ext === ".js" || ext === ".mjs" || ext === ".ts" || ext === ".tsx") return "js";
  return null;
}

/** Walk `dir` depth-first and collect source files, respecting skip-dirs and depth. */
async function collectSourceFiles(
  rootDir: string,
  dir: string,
  depth: number,
  out: string[]
): Promise<void> {
  if (depth > MAX_DIR_DEPTH) return;
  if (out.length >= MAX_FILES_SCANNED) return;

  let entries: { name: string; isDirectory: () => boolean; isFile: () => boolean }[];
  try {
    entries = await fs.readdir(dir, { withFileTypes: true });
  } catch {
    return;
  }

  // Deterministic order for reproducible tests.
  entries.sort((a, b) => a.name.localeCompare(b.name));

  for (const entry of entries) {
    if (out.length >= MAX_FILES_SCANNED) return;
    const abs = path.join(dir, entry.name);

    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name) || entry.name.startsWith(".")) continue;
      await collectSourceFiles(rootDir, abs, depth + 1, out);
      continue;
    }

    if (!entry.isFile()) continue;
    if (SOURCE_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) {
      out.push(abs);
    }
  }
}

// ─── Project type + version detection ────────────────────────────────────────

interface ProjectDetection {
  projectType: ProjectType;
  sceneViewVersion: string | null;
  /** The file the version was extracted from, for error reporting. */
  sourceFile: string | null;
}

async function readFileSafe(filePath: string): Promise<string | null> {
  try {
    return await fs.readFile(filePath, "utf-8");
  } catch {
    return null;
  }
}

async function tryRead(
  dir: string,
  ...relativeCandidates: string[]
): Promise<{ path: string; contents: string } | null> {
  for (const rel of relativeCandidates) {
    const abs = path.join(dir, rel);
    const contents = await readFileSafe(abs);
    if (contents != null) return { path: abs, contents };
  }
  return null;
}

/** Extract `X.Y.Z` from a Gradle-style `io.github.sceneview:sceneview:X.Y.Z` reference. */
function extractGradleVersion(contents: string): string | null {
  const match = contents.match(
    /io\.github\.sceneview:(?:sceneview|arsceneview|sceneview-core)[:"']?\s*[:"']?\s*(?:version\s*=\s*["'])?(\d+\.\d+\.\d+(?:-[\w.]+)?)/
  );
  if (match) return match[1];
  return null;
}

/** Extract `X.Y.Z` from a `Package.swift` `SceneViewSwift` dependency. */
function extractSwiftVersion(contents: string): string | null {
  // Matches `.package(url: ".../sceneview"..., from: "4.0.0-rc.1")` or `.upToNextMajor(from: "3.6.0")`.
  const fromMatch = contents.match(/sceneview[^"]*"[^)]*from:\s*"(\d+\.\d+\.\d+(?:-[\w.]+)?)"/i);
  if (fromMatch) return fromMatch[1];
  const exactMatch = contents.match(/sceneview[^"]*"[^)]*exact:\s*"(\d+\.\d+\.\d+(?:-[\w.]+)?)"/i);
  if (exactMatch) return exactMatch[1];
  const branchOrVersion = contents.match(/\.package\([^)]*sceneview[^)]*"(\d+\.\d+\.\d+)"/i);
  if (branchOrVersion) return branchOrVersion[1];
  return null;
}

/** Extract the `sceneview-web` version from a `package.json`. */
function extractNpmVersion(contents: string): string | null {
  try {
    const pkg = JSON.parse(contents) as {
      dependencies?: Record<string, string>;
      devDependencies?: Record<string, string>;
    };
    const deps = { ...pkg.dependencies, ...pkg.devDependencies };
    for (const [name, spec] of Object.entries(deps)) {
      if (name === "sceneview-web" || name === "@sceneview/sceneview-web") {
        // Strip leading ^ / ~ / >= etc.
        const cleaned = spec.replace(/^[^\d]*/, "");
        const match = cleaned.match(/\d+\.\d+\.\d+(?:-[\w.]+)?/);
        if (match) return match[0];
      }
    }
  } catch {
    // Not JSON — fall through.
  }
  return null;
}

async function detectProject(rootDir: string): Promise<ProjectDetection> {
  // Android: build.gradle or build.gradle.kts anywhere reachable from root.
  // We first check the root for speed, then one level down for typical module layouts.
  const androidCandidates = [
    "build.gradle.kts",
    "build.gradle",
    "app/build.gradle.kts",
    "app/build.gradle",
    "sample/build.gradle.kts",
    "sample/build.gradle",
  ];
  for (const rel of androidCandidates) {
    const read = await tryRead(rootDir, rel);
    if (
      read &&
      /io\.github\.sceneview:(sceneview|arsceneview|sceneview-core)/.test(read.contents)
    ) {
      return {
        projectType: "android",
        sceneViewVersion: extractGradleVersion(read.contents),
        sourceFile: read.path,
      };
    }
  }

  // iOS: Package.swift at root mentioning SceneViewSwift.
  const packageSwift = await tryRead(rootDir, "Package.swift");
  if (packageSwift && /SceneViewSwift|sceneview\/sceneview/i.test(packageSwift.contents)) {
    return {
      projectType: "ios",
      sceneViewVersion: extractSwiftVersion(packageSwift.contents),
      sourceFile: packageSwift.path,
    };
  }

  // Web: package.json referencing sceneview-web.
  const packageJson = await tryRead(rootDir, "package.json");
  if (packageJson && /"(?:@sceneview\/)?sceneview-web"/.test(packageJson.contents)) {
    return {
      projectType: "web",
      sceneViewVersion: extractNpmVersion(packageJson.contents),
      sourceFile: packageJson.path,
    };
  }

  return { projectType: "unknown", sceneViewVersion: null, sourceFile: null };
}

// ─── Semver comparison (simple X.Y.Z) ────────────────────────────────────────

/**
 * Returns `true` when `version` is strictly older than `LATEST_SCENEVIEW_VERSION`.
 * Falls back to `false` (don't flag) if either side fails to parse cleanly —
 * we'd rather underreport than harass a user with a pre-release build.
 */
function isVersionOutdated(version: string | null): boolean {
  if (!version) return false;
  const parse = (v: string): [number, number, number] | null => {
    const m = v.match(/^(\d+)\.(\d+)\.(\d+)/);
    if (!m) return null;
    return [parseInt(m[1], 10), parseInt(m[2], 10), parseInt(m[3], 10)];
  };
  const a = parse(version);
  const b = parse(LATEST_SCENEVIEW_VERSION);
  if (!a || !b) return false;
  for (let i = 0; i < 3; i++) {
    if (a[i] < b[i]) return true;
    if (a[i] > b[i]) return false;
  }
  return false;
}

// ─── Public entrypoint ───────────────────────────────────────────────────────

/**
 * Scan a local SceneView project and return a structured analysis.
 *
 * Never throws for filesystem errors — missing directory, permission denied,
 * broken file: all surface as a warning in the returned result.
 */
export async function analyzeProject(input: AnalyzeProjectInput = {}): Promise<AnalysisResult> {
  const rawPath = input.path ?? process.cwd();
  const scannedPath = path.resolve(rawPath);

  const result: AnalysisResult = {
    projectType: "unknown",
    sceneViewVersion: null,
    latestVersion: LATEST_SCENEVIEW_VERSION,
    isOutdated: false,
    warnings: [],
    suggestions: [],
    scannedPath,
    filesScanned: 0,
    bytesScanned: 0,
    truncated: false,
  };

  // Verify the path exists and is a directory.
  let stat: Stats;
  try {
    stat = await fs.stat(scannedPath);
  } catch (err) {
    result.warnings.push({
      file: scannedPath,
      type: "scan/path-not-found",
      message:
        `Could not stat project path: ${(err as Error).message}. ` +
        "Pass an existing directory in the `path` argument, or run the tool from inside your project.",
    });
    return result;
  }
  if (!stat.isDirectory()) {
    result.warnings.push({
      file: scannedPath,
      type: "scan/not-a-directory",
      message: `Project path exists but is not a directory: ${scannedPath}.`,
    });
    return result;
  }

  // Step 1: project type + version.
  const detection = await detectProject(scannedPath);
  result.projectType = detection.projectType;
  result.sceneViewVersion = detection.sceneViewVersion;
  result.isOutdated = isVersionOutdated(detection.sceneViewVersion);

  if (detection.projectType === "unknown") {
    result.warnings.push({
      file: scannedPath,
      type: "scan/no-sceneview-dependency",
      message:
        "No SceneView dependency was detected. Looked for `io.github.sceneview:sceneview` in " +
        "Gradle files, `SceneViewSwift` in `Package.swift`, and `sceneview-web` in `package.json`.",
    });
  } else if (!detection.sceneViewVersion) {
    result.warnings.push({
      file: detection.sourceFile ?? scannedPath,
      type: "scan/version-unresolved",
      message:
        "SceneView dependency was found but the version could not be extracted. This usually " +
        "means the version is declared through a variable or a version catalog — check your " +
        "`libs.versions.toml` or a shared `ext.sceneview_version` definition.",
    });
  }

  if (result.isOutdated && detection.sceneViewVersion) {
    result.suggestions.push({
      type: "suggestion/upgrade-sceneview",
      message:
        `You are on SceneView ${detection.sceneViewVersion}. The latest known release is ` +
        `${LATEST_SCENEVIEW_VERSION}. Consider upgrading — run \`get_migration_guide\` for ` +
        "the 2.x → 3.x migration notes, or `migrate_code` for automatic Kotlin rewrites.",
    });
  }

  // Step 2: anti-pattern scan (only if we have a known project type).
  if (detection.projectType !== "unknown") {
    const sourceFiles: string[] = [];
    await collectSourceFiles(scannedPath, scannedPath, 0, sourceFiles);

    const truncatedByFileCount = sourceFiles.length >= MAX_FILES_SCANNED;
    let totalBytes = 0;

    for (const file of sourceFiles) {
      if (result.filesScanned >= MAX_FILES_SCANNED) {
        result.truncated = true;
        break;
      }
      if (totalBytes >= MAX_BYTES_SCANNED) {
        result.truncated = true;
        break;
      }

      const contents = await readFileSafe(file);
      if (contents == null) continue;
      const byteLen = Buffer.byteLength(contents, "utf-8");
      // Would this file push us over the byte cap? If yes, still scan once
      // (a single giant file shouldn't silently fail) then stop the loop.
      totalBytes += byteLen;
      result.filesScanned += 1;
      result.bytesScanned = totalBytes;

      const lang = languageOf(file);
      if (!lang) continue;

      scanFileForAntiPatterns(file, lang, contents, result.warnings);

      if (totalBytes >= MAX_BYTES_SCANNED) {
        result.truncated = true;
        break;
      }
    }

    if (truncatedByFileCount) {
      result.truncated = true;
    }

    if (result.truncated) {
      result.warnings.push({
        file: scannedPath,
        type: "scan/truncated",
        message:
          `Scan truncated at ${result.filesScanned} files / ${result.bytesScanned} bytes ` +
          `(limits: ${MAX_FILES_SCANNED} files, ${MAX_BYTES_SCANNED} bytes). Some files may ` +
          "not have been inspected — run `validate_code` on specific snippets for a deeper check.",
      });
    }
  }

  // Step 3: tailored suggestions.
  if (result.warnings.some((w) => w.type.startsWith("migration/"))) {
    result.suggestions.push({
      type: "suggestion/run-migrate-code",
      message:
        "Deprecated 2.x APIs were detected. Run the `migrate_code` tool on the offending " +
        "files for an automatic rewrite to 3.x.",
    });
  }
  if (result.warnings.some((w) => w.type === "threading/filament-off-main-thread")) {
    result.suggestions.push({
      type: "suggestion/fix-threading",
      message:
        "Filament JNI calls were detected alongside background coroutines. Switch to " +
        "`rememberModelInstance` in composables, or wrap imperative loads in " +
        "`withContext(Dispatchers.Main)`.",
    });
  }
  if (
    result.projectType !== "unknown" &&
    result.warnings.filter((w) => !w.type.startsWith("scan/")).length === 0
  ) {
    result.suggestions.push({
      type: "suggestion/no-issues",
      message:
        "No known anti-patterns detected in the scanned files. Call `validate_code` on " +
        "individual snippets for a deeper per-file check.",
    });
  }

  return result;
}

/** Run every registered detector on one file and append findings in place. */
function scanFileForAntiPatterns(
  file: string,
  language: DetectedLanguage,
  contents: string,
  warnings: AnalysisWarning[]
): void {
  const lines = contents.split("\n");
  for (const detector of ANTI_PATTERNS) {
    if (!detector.languages.has(language)) continue;
    if (detector.fileGuard && !detector.fileGuard(contents)) continue;
    for (let i = 0; i < lines.length; i++) {
      if (detector.pattern.test(lines[i])) {
        warnings.push({
          file,
          line: i + 1,
          type: detector.id,
          message: detector.message,
        });
      }
    }
  }
}

// ─── Formatting helper (used by the MCP handler) ────────────────────────────

/** Render an {@link AnalysisResult} as a Markdown report for MCP clients. */
export function formatAnalysisReport(result: AnalysisResult): string {
  const lines: string[] = [];
  lines.push(`## SceneView project analysis`);
  lines.push(``);
  lines.push(`**Path:** \`${result.scannedPath}\``);
  lines.push(`**Project type:** ${result.projectType}`);
  lines.push(
    `**SceneView version:** ${result.sceneViewVersion ?? "(not detected)"} — latest: ${result.latestVersion}${result.isOutdated ? " ⚠️ outdated" : ""}`
  );
  lines.push(
    `**Scan:** ${result.filesScanned} file(s), ${result.bytesScanned} byte(s)${result.truncated ? " (truncated)" : ""}`
  );
  lines.push(``);

  if (result.warnings.length === 0) {
    lines.push(`### Warnings`);
    lines.push(`No anti-patterns detected.`);
  } else {
    lines.push(`### Warnings (${result.warnings.length})`);
    for (const w of result.warnings) {
      const loc = w.line ? `${w.file}:${w.line}` : w.file;
      lines.push(`- **${w.type}** — \`${loc}\`: ${w.message}`);
    }
  }
  lines.push(``);

  if (result.suggestions.length > 0) {
    lines.push(`### Suggestions`);
    for (const s of result.suggestions) {
      lines.push(`- **${s.type}** — ${s.message}`);
    }
  }

  return lines.join("\n");
}
