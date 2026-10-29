// ─── Medical SceneView code validator ────────────────────────────────────────

import {
  checkDeprecatedApi,
  checkMissingSceneViewImports,
  type ValidationIssue,
} from "./deprecated-api-check.js";

export type { ValidationIssue };

export interface ValidationResult {
  valid: boolean;
  issues: ValidationIssue[];
  issueCount: { errors: number; warnings: number; info: number };
}

/**
 * Validates a Kotlin SceneView snippet for common medical-app mistakes.
 * Checks: threading, null-safety, SceneView API misuse, medical-specific patterns.
 */
export function validateMedicalCode(code: string): ValidationResult {
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
        message: `Line ${i + 1}: LightNode's \`apply\` is a named parameter (apply = { ... }), not a trailing lambda. Use: LightNode(type = LightManager.Type.DIRECTIONAL, apply = { intensity(...) })`,
        line: i + 1,
      });
    }
  }

  // ── Deprecated APIs (shared check) ───────────────────────────────────────
  issues.push(...checkDeprecatedApi(code));

  // ── Medical-specific checks ──────────────────────────────────────────────
  if (/DICOM|dicom/i.test(code) && !/cornerstone|itk|vtk|dcm4che/i.test(code)) {
    issues.push({
      severity: "info",
      message:
        "DICOM parsing requires a dedicated library (e.g., dcm4che for JVM, cornerstone.js for web). SceneView handles 3D rendering, not DICOM decoding.",
    });
  }

  if (/\.stl/i.test(code) && !/convert|GLB|glb/.test(code)) {
    issues.push({
      severity: "info",
      message:
        "STL files must be converted to GLB/glTF for SceneView. Use Blender, meshoptimizer, or gltf-transform for conversion.",
    });
  }

  if (/\.obj/i.test(code) && !/convert|GLB|glb/.test(code)) {
    issues.push({
      severity: "info",
      message:
        "OBJ files should be converted to GLB/glTF for optimal SceneView performance. Use Blender or obj2gltf.",
    });
  }

  if (/alpha|transparency|opacity/i.test(code) && /organ|anatomy|body/i.test(code)) {
    issues.push({
      severity: "info",
      message:
        "When using transparency for anatomy layers, set material blending mode to TRANSPARENT and render opaque parts first for correct depth sorting.",
    });
  }

  // ── Missing imports check (shared check) ─────────────────────────────────
  issues.push(...checkMissingSceneViewImports(code));

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
