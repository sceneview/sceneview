// ─── Gaming SceneView code validator ──────────────────────────────────────────
/**
 * Validates a Kotlin SceneView snippet for common gaming-app mistakes.
 * Checks: threading, null-safety, SceneView API misuse, gaming-specific patterns.
 */
export function validateGameCode(code) {
    const issues = [];
    const lines = code.split("\n");
    // ── Threading checks ─────────────────────────────────────────────────────
    if (/Dispatchers\.(IO|Default)/.test(code) && /modelLoader|materialLoader/.test(code)) {
        issues.push({
            severity: "error",
            message: "Filament JNI calls (modelLoader, materialLoader) must run on the main thread. Use `rememberModelInstance` in composables or `loadModelInstanceAsync` for imperative code.",
        });
    }
    if (/GlobalScope\.launch/.test(code)) {
        issues.push({
            severity: "warning",
            message: "Avoid GlobalScope in Android. Use viewModelScope, lifecycleScope, or rememberCoroutineScope().",
        });
    }
    // ── Null-safety checks ───────────────────────────────────────────────────
    if (/rememberModelInstance/.test(code) && !/\?\.|!!|null|let\s*\{/.test(code)) {
        issues.push({
            severity: "warning",
            message: "rememberModelInstance returns null while loading. Handle the null case (e.g., show a loading indicator).",
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
    const deprecated = [
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
    // ── Gaming-specific checks ───────────────────────────────────────────────
    if (/onFrame/.test(code) && /Thread\.sleep|delay\(/.test(code)) {
        issues.push({
            severity: "error",
            message: "Never use Thread.sleep or delay inside onFrame. onFrame runs on the render thread — blocking it freezes the game. Use elapsed time for timing.",
        });
    }
    if (/physics|collision|velocity/i.test(code) && /onFrame/.test(code) && !/dt|deltaTime|fixedTimestep|frameTime/i.test(code)) {
        issues.push({
            severity: "warning",
            message: "Physics simulation in onFrame should use a fixed timestep (dt) for deterministic results. Variable frame time causes inconsistent physics.",
        });
    }
    if (/for\s*\(.*in\s+.*particles/i.test(code) && !/\.take\(|\.filter|count\s*[<>]/i.test(code)) {
        issues.push({
            severity: "info",
            message: "Rendering many particles as individual SphereNode instances is expensive. For 500+ particles, consider Filament's particle system or instanced rendering.",
        });
    }
    if (/animator/i.test(code) && !/animationCount/i.test(code)) {
        issues.push({
            severity: "info",
            message: "Check animator.animationCount before accessing animations. Some models may have zero animations.",
        });
    }
    if (/Random\(\)/.test(code) && !/seed|Random\(\d/.test(code)) {
        issues.push({
            severity: "info",
            message: "Consider seeding Random for reproducible level generation. Use Random(seed) for deterministic procedural content.",
        });
    }
    // ── Missing imports check ──────────────────────────────────────────────
    if (/(?<![\w.])SceneView\s*[\(\{]/.test(code) &&
        !/import\s+io\.github\.sceneview\.SceneView\b/.test(code)) {
        issues.push({
            severity: "warning",
            message: "Missing SceneView import. Add: import io.github.sceneview.SceneView",
        });
    }
    if (/(?<![\w.])ARSceneView\s*[\(\{]/.test(code) &&
        !/import\s+io\.github\.sceneview\.ar\.ARSceneView\b/.test(code)) {
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
export function formatValidationReport(result) {
    const { issues, issueCount } = result;
    if (issues.length === 0) {
        return "All checks passed. No issues found.";
    }
    const icon = (s) => (s === "error" ? "ERROR" : s === "warning" ? "WARN" : "INFO");
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
