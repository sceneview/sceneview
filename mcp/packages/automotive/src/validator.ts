// ─── Automotive SceneView code validator ──────────────────────────────────────

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
 * Validates a Kotlin SceneView snippet for common automotive-app mistakes.
 * Checks: threading, null-safety, SceneView API misuse, automotive-specific patterns.
 */
export function validateAutomotiveCode(code: string): ValidationResult {
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

  // ── Automotive-specific checks ────────────────────────────────────────────
  if (/scaleToUnits\s*=\s*(\d+\.?\d*)f/.test(code)) {
    const match = code.match(/scaleToUnits\s*=\s*(\d+\.?\d*)f/);
    if (match) {
      const scale = parseFloat(match[1]);
      if (scale > 10) {
        issues.push({
          severity: "warning",
          message: `scaleToUnits = ${scale}f is very large for a car model. Real cars are ~4.5m. Consider a smaller value for AR placement.`,
        });
      }
      if (scale < 0.1) {
        issues.push({
          severity: "info",
          message: `scaleToUnits = ${scale}f is very small. A car at this scale will be thumbnail-sized. Use ~4.5f for real-world scale or ~2.0f for table display.`,
        });
      }
    }
  }

  if (/\.fbx/i.test(code) && !/convert|GLB|glb/.test(code)) {
    issues.push({
      severity: "info",
      message:
        "FBX files must be converted to GLB/glTF for SceneView. Use Blender (File > Export > glTF) or FBX2glTF tool.",
    });
  }

  if (/\.blend/i.test(code)) {
    issues.push({
      severity: "error",
      message:
        ".blend files cannot be loaded directly by SceneView. Export to GLB from Blender (File > Export > glTF 2.0).",
    });
  }

  if (/KHR_materials_variants/i.test(code) || /material.?variant/i.test(code)) {
    issues.push({
      severity: "info",
      message:
        "KHR_materials_variants is ideal for car configurators (paint colors, interior trims). Ensure your GLB contains variant data exported from Blender or 3ds Max.",
    });
  }

  if (/onFrame.*rotation.*\+=/i.test(code) || /rotationAngle\s*\+=/i.test(code)) {
    issues.push({
      severity: "info",
      message:
        "Turntable rotation detected. For smooth 60fps rotation, keep the increment small (0.1-0.5 degrees per frame). Consider using Compose animation APIs for frame-rate-independent rotation.",
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
