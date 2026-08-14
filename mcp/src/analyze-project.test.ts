import { promises as fs } from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

import {
  analyzeProject,
  formatAnalysisReport,
  LATEST_SCENEVIEW_VERSION,
  MAX_FILES_SCANNED,
} from "./analyze-project.js";

// Resolve the on-disk fixture directory. Fixtures live in `src/__fixtures__`
// and are NOT copied to `dist/` by tsc (non-TS files are ignored). To make
// the same test file runnable from either the `src/` sources or the compiled
// `dist/` output, we locate the fixtures by walking up from `import.meta.url`
// until we find the `src/__fixtures__/analyze-project` directory.
function resolveFixtures(): string {
  const hereDir = path.dirname(fileURLToPath(import.meta.url));
  // `hereDir` is either `<repo>/mcp/src` or `<repo>/mcp/dist`. From either we
  // go up to `<repo>/mcp` and descend into `src/__fixtures__/analyze-project`.
  const mcpDir = path.dirname(hereDir);
  return path.join(mcpDir, "src", "__fixtures__", "analyze-project");
}

const FIXTURES = resolveFixtures();

const ANDROID_OK = path.join(FIXTURES, "android-ok");
const ANDROID_WARN = path.join(FIXTURES, "android-with-warnings");
const IOS_OUTDATED = path.join(FIXTURES, "ios-outdated");

describe("analyzeProject — project type detection", () => {
  it("detects an Android project via build.gradle.kts", async () => {
    const result = await analyzeProject({ path: ANDROID_OK });
    expect(result.projectType).toBe("android");
    // Fixture is now pinned to LATEST_SCENEVIEW_VERSION (see fixture
    // comment) — verify the pin survives + isOutdated reads false.
    expect(result.sceneViewVersion).toBe(LATEST_SCENEVIEW_VERSION);
    expect(result.isOutdated).toBe(false);
    expect(result.latestVersion).toBe(LATEST_SCENEVIEW_VERSION);
  });

  it("detects an iOS project via Package.swift", async () => {
    const result = await analyzeProject({ path: IOS_OUTDATED });
    expect(result.projectType).toBe("ios");
    expect(result.sceneViewVersion).toBe("3.0.0");
  });

  it("flags an iOS project as outdated", async () => {
    const result = await analyzeProject({ path: IOS_OUTDATED });
    expect(result.isOutdated).toBe(true);
    expect(result.suggestions.some((s) => s.type === "suggestion/upgrade-sceneview")).toBe(true);
  });

  it("returns unknown projectType + warning when no dep is found", async () => {
    // Create an empty temp dir with just a placeholder file.
    const tmp = await fs.mkdtemp(path.join(os.tmpdir(), "sv-analyze-empty-"));
    try {
      await fs.writeFile(path.join(tmp, "README.md"), "# empty");
      const result = await analyzeProject({ path: tmp });
      expect(result.projectType).toBe("unknown");
      expect(result.sceneViewVersion).toBeNull();
      expect(result.warnings.some((w) => w.type === "scan/no-sceneview-dependency")).toBe(true);
    } finally {
      await fs.rm(tmp, { recursive: true, force: true });
    }
  });
});

describe("analyzeProject — version extraction", () => {
  it("extracts the patch version from a Gradle dependency string", async () => {
    const result = await analyzeProject({ path: ANDROID_OK });
    // Fixture is pinned to LATEST_SCENEVIEW_VERSION (see android-ok/
    // build.gradle.kts) — same reason as the "up-to-date" assertion above.
    expect(result.sceneViewVersion).toBe(LATEST_SCENEVIEW_VERSION);
  });

  it("extracts the version from a Package.swift `from:` clause", async () => {
    const result = await analyzeProject({ path: IOS_OUTDATED });
    expect(result.sceneViewVersion).toBe("3.0.0");
  });

  it("detects an outdated Android project", async () => {
    const result = await analyzeProject({ path: ANDROID_WARN });
    expect(result.projectType).toBe("android");
    expect(result.sceneViewVersion).toBe("2.2.1");
    expect(result.isOutdated).toBe(true);
  });
});

describe("analyzeProject — anti-pattern detection", () => {
  it("flags threading violations in the warnings fixture", async () => {
    const result = await analyzeProject({ path: ANDROID_WARN });
    const threadingWarnings = result.warnings.filter(
      (w) => w.type === "threading/filament-off-main-thread"
    );
    expect(threadingWarnings.length).toBeGreaterThan(0);
    expect(threadingWarnings[0].line).toBeGreaterThan(0);
  });

  it("flags the LightNode trailing-lambda bug", async () => {
    const result = await analyzeProject({ path: ANDROID_WARN });
    expect(result.warnings.some((w) => w.type === "api/light-node-trailing-lambda")).toBe(true);
  });

  it("flags TransformableNode as deprecated 2.x API", async () => {
    const result = await analyzeProject({ path: ANDROID_WARN });
    expect(result.warnings.some((w) => w.type === "migration/transformable-node-removed")).toBe(
      true
    );
  });

  it("flags a Sceneform import", async () => {
    const result = await analyzeProject({ path: ANDROID_WARN });
    expect(result.warnings.some((w) => w.type === "migration/sceneform-import")).toBe(true);
  });

  it("adds a run-migrate-code suggestion when migration warnings exist", async () => {
    const result = await analyzeProject({ path: ANDROID_WARN });
    expect(result.suggestions.some((s) => s.type === "suggestion/run-migrate-code")).toBe(true);
  });

  it("does NOT report anti-patterns in a clean Android project", async () => {
    const result = await analyzeProject({ path: ANDROID_OK });
    const nonScanWarnings = result.warnings.filter((w) => !w.type.startsWith("scan/"));
    expect(nonScanWarnings).toHaveLength(0);
    expect(result.suggestions.some((s) => s.type === "suggestion/no-issues")).toBe(true);
  });
});

describe("analyzeProject — graceful error handling", () => {
  it("returns an error warning for a missing directory", async () => {
    const bogus = path.join(os.tmpdir(), `sv-analyze-missing-${Date.now()}`);
    const result = await analyzeProject({ path: bogus });
    expect(result.projectType).toBe("unknown");
    expect(result.warnings.some((w) => w.type === "scan/path-not-found")).toBe(true);
    // Should NOT throw and should still return a structured result.
    expect(result.latestVersion).toBe(LATEST_SCENEVIEW_VERSION);
  });

  it("returns a not-a-directory warning when the path is a file", async () => {
    const tmp = await fs.mkdtemp(path.join(os.tmpdir(), "sv-analyze-file-"));
    const filePath = path.join(tmp, "a.txt");
    await fs.writeFile(filePath, "hello");
    try {
      const result = await analyzeProject({ path: filePath });
      expect(result.warnings.some((w) => w.type === "scan/not-a-directory")).toBe(true);
    } finally {
      await fs.rm(tmp, { recursive: true, force: true });
    }
  });

  it("handles an empty directory gracefully", async () => {
    const tmp = await fs.mkdtemp(path.join(os.tmpdir(), "sv-analyze-empty2-"));
    try {
      const result = await analyzeProject({ path: tmp });
      expect(result.projectType).toBe("unknown");
      expect(result.warnings.some((w) => w.type === "scan/no-sceneview-dependency")).toBe(true);
      expect(result.filesScanned).toBe(0);
      expect(result.bytesScanned).toBe(0);
      expect(result.truncated).toBe(false);
    } finally {
      await fs.rm(tmp, { recursive: true, force: true });
    }
  });

  it("defaults path to process.cwd() when none is provided", async () => {
    // Easiest observable: when run from the repo root (Vitest cwd), this may or
    // may not detect a SceneView dep depending on where tests run. We just
    // verify the function returns a well-formed result.
    const result = await analyzeProject();
    expect(typeof result.projectType).toBe("string");
    expect(result.scannedPath).toBe(path.resolve(process.cwd()));
  });
});

describe("analyzeProject — formatting", () => {
  it("renders a Markdown report with headings and counts", async () => {
    const result = await analyzeProject({ path: ANDROID_WARN });
    const report = formatAnalysisReport(result);
    expect(report).toContain("## SceneView project analysis");
    expect(report).toContain("**Project type:** android");
    expect(report).toContain("Warnings");
  });

  it("renders a clean report when there are no anti-pattern warnings", async () => {
    const result = await analyzeProject({ path: ANDROID_OK });
    const report = formatAnalysisReport(result);
    expect(report).toContain("## SceneView project analysis");
    expect(report).toContain("**Project type:** android");
    // Should not list migration warnings for a clean project.
    expect(report).not.toContain("TransformableNode");
  });
});

describe("analyzeProject — scan limits", () => {
  // Guard-rails: ensure constants haven't been silently loosened.
  it("caps at no more than 30 files by default", () => {
    expect(MAX_FILES_SCANNED).toBeLessThanOrEqual(30);
  });

  it("respects the file cap when more than 30 source files exist", async () => {
    const tmp = await fs.mkdtemp(path.join(os.tmpdir(), "sv-analyze-cap-"));
    try {
      // Minimal Gradle build so it's detected as Android.
      await fs.writeFile(
        path.join(tmp, "build.gradle.kts"),
        'dependencies {\n  implementation("io.github.sceneview:sceneview:4.0.0-rc.1")\n}\n'
      );
      const srcDir = path.join(tmp, "src");
      await fs.mkdir(srcDir, { recursive: true });
      for (let i = 0; i < 45; i++) {
        await fs.writeFile(path.join(srcDir, `File${i}.kt`), `// file ${i}\nval x${i} = ${i}\n`);
      }
      const result = await analyzeProject({ path: tmp });
      expect(result.projectType).toBe("android");
      expect(result.filesScanned).toBeLessThanOrEqual(MAX_FILES_SCANNED);
      expect(result.truncated).toBe(true);
      expect(result.warnings.some((w) => w.type === "scan/truncated")).toBe(true);
    } finally {
      await fs.rm(tmp, { recursive: true, force: true });
    }
  });
});
