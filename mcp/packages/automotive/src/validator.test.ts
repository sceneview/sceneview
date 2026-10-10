import { describe, it, expect } from "vitest";
import { validateAutomotiveCode, formatValidationReport } from "./validator.js";

describe("validateAutomotiveCode", () => {
  // ── Threading checks ───────────────────────────────────────────────────
  it("detects Filament calls on IO dispatcher", () => {
    const result = validateAutomotiveCode(`
      scope.launch(Dispatchers.IO) {
        modelLoader.createModelInstance("car.glb")
      }
    `);
    expect(result.valid).toBe(false);
    expect(result.issues[0].severity).toBe("error");
    expect(result.issues[0].message).toContain("main thread");
  });

  it("detects Filament calls on Default dispatcher", () => {
    const result = validateAutomotiveCode(`
      scope.launch(Dispatchers.Default) {
        materialLoader.createMaterial()
      }
    `);
    expect(result.valid).toBe(false);
  });

  it("detects GlobalScope usage", () => {
    const result = validateAutomotiveCode(`
      GlobalScope.launch { }
    `);
    expect(result.issues.some((i) => i.message.includes("GlobalScope"))).toBe(true);
  });

  // ── Null-safety checks ─────────────────────────────────────────────────
  it("warns about unhandled null from rememberModelInstance", () => {
    const result = validateAutomotiveCode(`
      val model = rememberModelInstance(loader, "car.glb")
      ModelNode(modelInstance = model)
    `);
    expect(result.issues.some((i) => i.message.includes("null"))).toBe(true);
  });

  it("passes when null is handled", () => {
    const result = validateAutomotiveCode(`
      val model = rememberModelInstance(loader, "car.glb")
      model?.let { ModelNode(modelInstance = it) }
    `);
    expect(result.issues.filter((i) => i.message.includes("null while loading"))).toHaveLength(0);
  });

  // ── LightNode bug ──────────────────────────────────────────────────────
  it("detects LightNode trailing-lambda bug", () => {
    const result = validateAutomotiveCode(`
      LightNode() {
        intensity(80000f)
      }
    `);
    expect(result.valid).toBe(false);
    expect(result.issues[0].message).toContain("named parameter");
  });

  it("passes correct LightNode usage", () => {
    const result = validateAutomotiveCode(`
      LightNode(
        type = LightManager.Type.DIRECTIONAL,
        apply = {
          intensity(80000f)
        }
      )
    `);
    const lightIssues = result.issues.filter((i) => i.message.includes("LightNode"));
    expect(lightIssues).toHaveLength(0);
  });

  // ── Deprecated APIs ────────────────────────────────────────────────────
  // `Scene { }` and `ARScene { }` were renamed to `SceneView { }` / `ARSceneView { }`
  // in v3.6. The old names still exist as deprecated aliases and should be flagged.
  it("detects deprecated Scene() composable usage", () => {
    const result = validateAutomotiveCode(`Scene(modifier = Modifier)`);
    expect(result.valid).toBe(false);
    expect(result.issues[0].message).toContain("deprecated since v3.6");
    expect(result.issues[0].message).toContain("SceneView");
  });

  it("detects deprecated ARScene() composable usage", () => {
    const result = validateAutomotiveCode(`ARScene(modifier = Modifier)`);
    expect(result.valid).toBe(false);
    expect(result.issues[0].message).toContain("deprecated since v3.6");
    expect(result.issues[0].message).toContain("ARSceneView");
  });

  it("detects Sceneform 1.x ArSceneView() usage", () => {
    const result = validateAutomotiveCode(`ArSceneView(modifier = Modifier)`);
    expect(result.valid).toBe(false);
  });

  it("detects loadModelAsync 2.x usage", () => {
    const result = validateAutomotiveCode(`modelLoader.loadModelAsync("car.glb")`);
    expect(result.valid).toBe(false);
  });

  it("detects Engine.create 2.x usage", () => {
    const result = validateAutomotiveCode(`val engine = Engine.create()`);
    expect(result.valid).toBe(false);
  });

  it("detects sceneform imports", () => {
    const result = validateAutomotiveCode(`import com.google.ar.sceneform.Node`);
    expect(result.valid).toBe(false);
  });

  // ── Automotive-specific checks ─────────────────────────────────────────
  it("warns about overly large scale", () => {
    const result = validateAutomotiveCode(`
      ModelNode(modelInstance = instance, scaleToUnits = 50f)
    `);
    expect(result.issues.some((i) => i.message.includes("very large"))).toBe(true);
  });

  it("info about very small scale", () => {
    const result = validateAutomotiveCode(`
      ModelNode(modelInstance = instance, scaleToUnits = 0.05f)
    `);
    expect(result.issues.some((i) => i.message.includes("very small"))).toBe(true);
  });

  it("no scale warning for reasonable car size", () => {
    const result = validateAutomotiveCode(`
      ModelNode(modelInstance = instance, scaleToUnits = 4.5f)
    `);
    expect(result.issues.filter((i) => i.message.includes("scale") && i.message.includes("very"))).toHaveLength(0);
  });

  it("info: FBX without conversion mention", () => {
    const result = validateAutomotiveCode(`
      modelLoader.createModel("car.fbx")
    `);
    expect(result.issues.some((i) => i.message.includes("FBX"))).toBe(true);
  });

  it("error: .blend file usage", () => {
    const result = validateAutomotiveCode(`
      modelLoader.createModel("car.blend")
    `);
    expect(result.issues.some((i) => i.message.includes(".blend"))).toBe(true);
    expect(result.valid).toBe(false);
  });

  it("info: KHR_materials_variants mention", () => {
    const result = validateAutomotiveCode(`
      // Using KHR_materials_variants for paint colors
    `);
    expect(result.issues.some((i) => i.message.includes("KHR_materials_variants"))).toBe(true);
  });

  it("info: turntable rotation detected", () => {
    const result = validateAutomotiveCode(`
      rotationAngle += 0.3f
    `);
    expect(result.issues.some((i) => i.message.includes("Turntable rotation"))).toBe(true);
  });

  // ── Missing imports ────────────────────────────────────────────────────
  it("warns about missing Scene import", () => {
    const result = validateAutomotiveCode(`
      @Composable
      fun CarViewer() {
        SceneView {
          ModelNode(modelInstance = model)
        }
      }
    `);
    expect(result.issues.some((i) => i.message.includes("Missing SceneView import"))).toBe(true);
  });

  it("warns about missing ARScene import", () => {
    const result = validateAutomotiveCode(`
      @Composable
      fun CarViewer() {
        ARSceneView {
          ModelNode(modelInstance = model)
        }
      }
    `);
    expect(result.issues.some((i) => i.message.includes("Missing ARSceneView import"))).toBe(true);
  });

  // ── Valid code ─────────────────────────────────────────────────────────
  it("passes valid automotive SceneView code", () => {
    const result = validateAutomotiveCode(`
      import io.github.sceneview.SceneView
      import io.github.sceneview.rememberEngine
      import io.github.sceneview.rememberModelLoader
      import io.github.sceneview.rememberModelInstance

      @Composable
      fun CarConfigurator() {
        val engine = rememberEngine()
        val modelLoader = rememberModelLoader(engine)
        val model = rememberModelInstance(modelLoader, "car.glb")

        SceneView(engine = engine) {
          model?.let {
            ModelNode(modelInstance = it)
          }
        }
      }
    `);
    expect(result.valid).toBe(true);
    expect(result.issueCount.errors).toBe(0);
  });
});

describe("formatValidationReport", () => {
  it("shows 'All checks passed' for valid code", () => {
    const report = formatValidationReport({
      valid: true,
      issues: [],
      issueCount: { errors: 0, warnings: 0, info: 0 },
    });
    expect(report).toContain("All checks passed");
  });

  it("shows error count in report", () => {
    const report = formatValidationReport({
      valid: false,
      issues: [{ severity: "error", message: "Test error" }],
      issueCount: { errors: 1, warnings: 0, info: 0 },
    });
    expect(report).toContain("1 errors");
    expect(report).toContain("[ERROR]");
  });

  it("shows warning and info labels", () => {
    const report = formatValidationReport({
      valid: true,
      issues: [
        { severity: "warning", message: "Test warning" },
        { severity: "info", message: "Test info" },
      ],
      issueCount: { errors: 0, warnings: 1, info: 1 },
    });
    expect(report).toContain("[WARN]");
    expect(report).toContain("[INFO]");
  });

  it("shows 'must be fixed' for invalid code", () => {
    const report = formatValidationReport({
      valid: false,
      issues: [{ severity: "error", message: "Error" }],
      issueCount: { errors: 1, warnings: 0, info: 0 },
    });
    expect(report).toContain("must be fixed");
  });
});
