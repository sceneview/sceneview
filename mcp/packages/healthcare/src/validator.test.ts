import { describe, it, expect } from "vitest";
import { validateMedicalCode, formatValidationReport } from "./validator.js";

describe("validateMedicalCode", () => {
  // ── Threading checks ───────────────────────────────────────────────────
  it("detects Filament calls on IO dispatcher", () => {
    const result = validateMedicalCode(`
      scope.launch(Dispatchers.IO) {
        modelLoader.createModelInstance("model.glb")
      }
    `);
    expect(result.valid).toBe(false);
    expect(result.issues[0].severity).toBe("error");
    expect(result.issues[0].message).toContain("main thread");
  });

  it("detects Filament calls on Default dispatcher", () => {
    const result = validateMedicalCode(`
      scope.launch(Dispatchers.Default) {
        materialLoader.createMaterial()
      }
    `);
    expect(result.valid).toBe(false);
  });

  it("detects GlobalScope usage", () => {
    const result = validateMedicalCode(`
      GlobalScope.launch { }
    `);
    expect(result.issues.some((i) => i.message.includes("GlobalScope"))).toBe(true);
  });

  // ── Null-safety checks ─────────────────────────────────────────────────
  it("warns about unhandled null from rememberModelInstance", () => {
    const result = validateMedicalCode(`
      val model = rememberModelInstance(loader, "model.glb")
      ModelNode(modelInstance = model)
    `);
    expect(result.issues.some((i) => i.message.includes("null"))).toBe(true);
  });

  it("passes when null is handled", () => {
    const result = validateMedicalCode(`
      val model = rememberModelInstance(loader, "model.glb")
      model?.let { ModelNode(modelInstance = it) }
    `);
    expect(result.issues.filter((i) => i.message.includes("null while loading"))).toHaveLength(0);
  });

  // ── LightNode bug ──────────────────────────────────────────────────────
  it("detects LightNode trailing-lambda bug", () => {
    const result = validateMedicalCode(`
      LightNode() {
        intensity(80000f)
      }
    `);
    expect(result.valid).toBe(false);
    expect(result.issues[0].message).toContain("named parameter");
  });

  it("passes correct LightNode usage", () => {
    const result = validateMedicalCode(`
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
    const result = validateMedicalCode(`Scene(modifier = Modifier)`);
    expect(result.valid).toBe(false);
    expect(result.issues[0].message).toContain("deprecated since v3.6");
    expect(result.issues[0].message).toContain("SceneView");
  });

  it("detects deprecated ARScene() composable usage", () => {
    const result = validateMedicalCode(`ARScene(modifier = Modifier)`);
    expect(result.valid).toBe(false);
    expect(result.issues[0].message).toContain("deprecated since v3.6");
    expect(result.issues[0].message).toContain("ARSceneView");
  });

  it("detects Sceneform 1.x ArSceneView() usage", () => {
    const result = validateMedicalCode(`ArSceneView(modifier = Modifier)`);
    expect(result.valid).toBe(false);
  });

  it("detects loadModelAsync 2.x usage", () => {
    const result = validateMedicalCode(`modelLoader.loadModelAsync("model.glb")`);
    expect(result.valid).toBe(false);
  });

  it("detects Engine.create 2.x usage", () => {
    const result = validateMedicalCode(`val engine = Engine.create()`);
    expect(result.valid).toBe(false);
  });

  it("detects sceneform imports", () => {
    const result = validateMedicalCode(`import com.google.ar.sceneform.Node`);
    expect(result.valid).toBe(false);
  });

  // ── Medical-specific checks ────────────────────────────────────────────
  it("info: DICOM without parsing library", () => {
    const result = validateMedicalCode(`
      // Load DICOM file
      val dicomFile = File("scan.dcm")
    `);
    expect(result.issues.some((i) => i.message.includes("DICOM parsing"))).toBe(true);
  });

  it("no DICOM warning when dcm4che is mentioned", () => {
    const result = validateMedicalCode(`
      // Using dcm4che for DICOM parsing
      val dicomFile = File("scan.dcm")
    `);
    expect(result.issues.filter((i) => i.message.includes("DICOM parsing"))).toHaveLength(0);
  });

  it("info: STL without conversion mention", () => {
    const result = validateMedicalCode(`
      modelLoader.createModel("anatomy.stl")
    `);
    expect(result.issues.some((i) => i.message.includes("STL"))).toBe(true);
  });

  it("no STL warning when GLB conversion is mentioned", () => {
    const result = validateMedicalCode(`
      // Convert STL to GLB first
      modelLoader.createModel("anatomy.glb")
    `);
    expect(result.issues.filter((i) => i.message.includes("STL"))).toHaveLength(0);
  });

  it("info: OBJ without conversion mention", () => {
    const result = validateMedicalCode(`
      modelLoader.createModel("skeleton.obj")
    `);
    expect(result.issues.some((i) => i.message.includes("OBJ"))).toBe(true);
  });

  it("info: transparency with anatomy models", () => {
    const result = validateMedicalCode(`
      // Set alpha transparency for organ layer
      val organMaterial = createMaterial(alpha = 0.5f)
    `);
    expect(result.issues.some((i) => i.message.includes("transparency"))).toBe(true);
  });

  // ── Missing imports ────────────────────────────────────────────────────
  it("warns about missing Scene import", () => {
    const result = validateMedicalCode(`
      @Composable
      fun MyViewer() {
        SceneView {
          ModelNode(modelInstance = model)
        }
      }
    `);
    expect(result.issues.some((i) => i.message.includes("Missing SceneView import"))).toBe(true);
  });

  it("warns about missing ARScene import", () => {
    const result = validateMedicalCode(`
      @Composable
      fun MyViewer() {
        ARSceneView {
          ModelNode(modelInstance = model)
        }
      }
    `);
    expect(result.issues.some((i) => i.message.includes("Missing ARSceneView import"))).toBe(true);
  });

  // ── Valid code ─────────────────────────────────────────────────────────
  it("passes valid medical SceneView code", () => {
    const result = validateMedicalCode(`
      import io.github.sceneview.SceneView
      import io.github.sceneview.rememberEngine
      import io.github.sceneview.rememberModelLoader
      import io.github.sceneview.rememberModelInstance

      @Composable
      fun AnatomyViewer() {
        val engine = rememberEngine()
        val modelLoader = rememberModelLoader(engine)
        val model = rememberModelInstance(modelLoader, "anatomy.glb")

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
