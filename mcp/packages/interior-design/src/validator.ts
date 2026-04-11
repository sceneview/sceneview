// ─── Interior design SceneView code validator ────────────────────────────────

export interface ValidationIssue {
  severity: "error" | "warning" | "info";
  message: string;
  line?: number;
}

export interface ValidationResult {
  valid: boolean;
  issues: ValidationIssue[];
  issueCount: { errors: number; warnings: number; info: number };
}

/**
 * Validates a Kotlin SceneView snippet for common interior-design app mistakes.
 * Checks: threading, null-safety, SceneView API misuse, interior-specific patterns.
 */
export function validateInteriorCode(code: string): ValidationResult {
  const issues: ValidationIssue[] = [];
  const lines = code.split("\n");

  // ── Threading checks ─────────────────────────────────────────────────────
  if (/Dispatchers\.(IO|Default)/.test(code) && /modelLoader|materialLoader/.test(code)) {
    issues.push({
      severity: "error",
      message:
        "Filament JNI calls (modelLoader, materialLoader) must run on the main thread. Use `rememberModelInstance` in composables or `loadModelInstanceAsync` for imperative code.",
    });
  }

  if (/GlobalScope\.launch/.test(code)) {
    issues.push({
      severity: "warning",
      message:
        "Avoid GlobalScope in Android. Use viewModelScope, lifecycleScope, or rememberCoroutineScope().",
    });
  }

  // ── Null-safety checks ───────────────────────────────────────────────────
  if (/rememberModelInstance/.test(code) && !/\?\.|!!|null|let\s*\{/.test(code)) {
    issues.push({
      severity: "warning",
      message:
        "rememberModelInstance returns null while loading. Handle the null case (e.g., show a loading indicator).",
    });
  }

  // ── LightNode trailing-lambda bug ────────────────────────────────────────
  for (let i = 0; i < lines.length; i++) {
    if (/LightNode\s*\(/.test(lines[i]) && /\)\s*\{/.test(lines[i])) {
      issues.push({
        severity: "error",
        message: `Line ${i + 1}: LightNode's \`apply\` is a named parameter (apply = { ... }), not a trailing lambda. Use: LightNode(apply = { intensity(...) })`,
        line: i + 1,
      });
    }
  }

  // ── Deprecated APIs ──────────────────────────────────────────────────────
  // The `Scene { }` / `ARScene { }` composables were renamed to `SceneView { }` /
  // `ARSceneView { }` in v3.6 for cross-platform consistency with SceneViewSwift.
  // The old names are kept as @Deprecated aliases in Scene.kt but should not
  // appear in new code.
  const deprecated: [RegExp, string][] = [
    [/(?<![\w.])Scene\s*\(/, "`Scene { }` is deprecated since v3.6. Use `SceneView { }`."],
    [/(?<![\w.])ARScene\s*\(/, "`ARScene { }` is deprecated since v3.6. Use `ARSceneView { }`."],
    [/ArSceneView\s*\(/, "`ArSceneView()` is Sceneform 1.x. Use `ARSceneView { }` from io.github.sceneview."],
    [/loadModelAsync/, "`loadModelAsync` is Sceneform. Use `rememberModelInstance` in composables."],
    [/Engine\.create/, "`Engine.create` is imperative. Use `rememberEngine()` in composables."],
    [/import\s+.*sceneform/, "Sceneform imports are obsolete. SceneView 3.x does not use Sceneform."],
  ];

  for (const [pattern, msg] of deprecated) {
    if (pattern.test(code)) {
      issues.push({ severity: "error", message: msg });
    }
  }

  // ── Interior-specific checks ─────────────────────────────────────────────
  if (/\.fbx/i.test(code) && !/convert|GLB|glb/.test(code)) {
    issues.push({
      severity: "info",
      message:
        "FBX files must be converted to GLB/glTF for SceneView. Use Blender (File > Export > glTF 2.0) or fbx2gltf.",
    });
  }

  if (/\.obj/i.test(code) && !/convert|GLB|glb/.test(code)) {
    issues.push({
      severity: "info",
      message:
        "OBJ files should be converted to GLB/glTF for optimal SceneView performance. Use Blender or obj2gltf.",
    });
  }

  if (/\.3ds/i.test(code) && !/convert|GLB|glb/.test(code)) {
    issues.push({
      severity: "info",
      message:
        "3DS files are not supported by SceneView. Convert to GLB/glTF using Blender before use.",
    });
  }

  if (/texture.*4096|4k.*texture/i.test(code)) {
    issues.push({
      severity: "warning",
      message:
        "4K textures may cause memory issues on mobile. Consider 1024x1024 or 2048x2048 for furniture/room textures.",
    });
  }

  if (/multiple.*ModelNode|ModelNode.*forEach|repeat.*ModelNode/i.test(code)) {
    issues.push({
      severity: "info",
      message:
        "For multiple furniture instances, use rememberModelInstance for each and consider LOD (Level of Detail) for performance.",
    });
  }

  // ── Missing imports check ────────────────────────────────────────────────
  // Detect usage of the `SceneView { }` composable without a corresponding import.
  if (
    /(?<![\w.])SceneView\s*[\(\{]/.test(code) &&
    !/import\s+io\.github\.sceneview\.SceneView\b/.test(code)
  ) {
    issues.push({
      severity: "warning",
      message: "Missing SceneView import. Add: import io.github.sceneview.SceneView",
    });
  }

  // Detect usage of the `ARSceneView { }` composable without a corresponding import.
  if (
    /(?<![\w.])ARSceneView\s*[\(\{]/.test(code) &&
    !/import\s+io\.github\.sceneview\.ar\.ARSceneView\b/.test(code)
  ) {
    issues.push({
      severity: "warning",
      message: "Missing ARSceneView import. Add: import io.github.sceneview.ar.ARSceneView",
    });
  }

  const errors = issues.filter((i) => i.severity === "error").length;
  const warnings = issues.filter((i) => i.severity === "warning").length;
  const info = issues.filter((i) => i.severity === "info").length;

  return {
    valid: errors === 0,
    issues,
    issueCount: { errors, warnings, info },
  };
}

export function formatValidationReport(result: ValidationResult): string {
  const { issues, issueCount } = result;

  if (issues.length === 0) {
    return "All checks passed. No issues found.";
  }

  const icon = (s: string) => (s === "error" ? "ERROR" : s === "warning" ? "WARN" : "INFO");
  const lines = issues.map((i) => `[${icon(i.severity)}] ${i.message}`);

  return [
    `## Validation Report`,
    ``,
    `**${issueCount.errors} errors, ${issueCount.warnings} warnings, ${issueCount.info} info**`,
    ``,
    ...lines,
    ``,
    result.valid ? "Code is valid (warnings/info are advisory)." : "Code has errors that must be fixed.",
  ].join("\n");
}
