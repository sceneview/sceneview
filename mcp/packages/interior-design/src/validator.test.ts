import { describe, it, expect } from "vitest";
import { validateInteriorCode, formatValidationReport } from "./validator.js";

describe("validateInteriorCode", () => {
  // ── Threading checks ───────────────────────────────────────────────────
  it("detects Filament calls on IO dispatcher", () => {
    const result = validateInteriorCode(`
      scope.launch(Dispatchers.IO) {
        modelLoader.createModelInstance("model.glb")
      }
    `);
    expect(result.valid).toBe(false);
    expect(result.issues[0].severity).toBe("error");
    expect(result.issues[0].message).toContain("main thread");
  });

  it("detects Filament calls on Default dispatcher", () => {
    const result = validateInteriorCode(`
      scope.launch(Dispatchers.Default) {
        materialLoader.createMaterial()
      }
    `);
    expect(result.valid).toBe(false);
  });

  it("detects GlobalScope usage", () => {
    const result = validateInteriorCode(`
      GlobalScope.launch { }
    `);
    expect(result.issues.some((i) => i.message.includes("GlobalScope"))).toBe(true);
  });

  // ── Null-safety checks ─────────────────────────────────────────────────
  it("warns about unhandled null from rememberModelInstance", () => {
    const result = validateInteriorCode(`
      val model = rememberModelInstance(loader, "model.glb")
      ModelNode(modelInstance = model)
    `);
    expect(result.issues.some((i) => i.message.includes("null"))).toBe(true);
  });

  it("passes when null is handled", () => {
    const result = validateInteriorCode(`
      val model = rememberModelInstance(loader, "model.glb")
      model?.let { ModelNode(modelInstance = it) }
    `);
    expect(result.issues.filter((i) => i.message.includes("null while loading"))).toHaveLength(0);
  });

  // ── LightNode bug ──────────────────────────────────────────────────────
  it("detects LightNode trailing-lambda bug", () => {
    const result = validateInteriorCode(`
      LightNode() {
        intensity(80000f)
      }
    `);
    expect(result.valid).toBe(false);
    expect(result.issues[0].message).toContain("named parameter");
  });

  it("passes correct LightNode usage", () => {
    const result = validateInteriorCode(`
      LightNode(
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
    const result = validateInteriorCode(`Scene(modifier = Modifier)`);
    expect(result.valid).toBe(false);
    expect(result.issues[0].message).toContain("deprecated since v3.6");
    expect(result.issues[0].message).toContain("SceneView");
  });

  it("detects deprecated ARScene() composable usage", () => {
    const result = validateInteriorCode(`ARScene(modifier = Modifier)`);
    expect(result.valid).toBe(false);
    expect(result.issues[0].message).toContain("deprecated since v3.6");
    expect(result.issues[0].message).toContain("ARSceneView");
  });

  it("detects Sceneform 1.x ArSceneView() usage", () => {
    const result = validateInteriorCode(`ArSceneView(modifier = Modifier)`);
    expect(result.valid).toBe(false);
    expect(result.issues[0].message).toContain("Sceneform");
  });

  it("detects loadModelAsync 2.x usage", () => {
    const result = validateInteriorCode(`modelLoader.loadModelAsync("model.glb")`);
    expect(result.valid).toBe(false);
  });

  it("detects Engine.create 2.x usage", () => {
    const result = validateInteriorCode(`val engine = Engine.create()`);
    expect(result.valid).toBe(false);
  });

  it("detects sceneform imports", () => {
    const result = validateInteriorCode(`import com.google.ar.sceneform.Node`);
    expect(result.valid).toBe(false);
  });

  // ── Interior-specific checks ───────────────────────────────────────────
  it("info: FBX without conversion mention", () => {
    const result = validateInteriorCode(`
      modelLoader.createModel("furniture.fbx")
    `);
    expect(result.issues.some((i) => i.message.includes("FBX"))).toBe(true);
  });

  it("no FBX warning when GLB conversion is mentioned", () => {
    const result = validateInteriorCode(`
      // Convert FBX to GLB first
      modelLoader.createModel("furniture.glb")
    `);
    expect(result.issues.filter((i) => i.message.includes("FBX"))).toHaveLength(0);
  });

  it("info: OBJ without conversion mention", () => {
    const result = validateInteriorCode(`
      modelLoader.createModel("chair.obj")
    `);
    expect(result.issues.some((i) => i.message.includes("OBJ"))).toBe(true);
  });

  it("info: 3DS files not supported", () => {
    const result = validateInteriorCode(`
      modelLoader.createModel("room.3ds")
    `);
    expect(result.issues.some((i) => i.message.includes("3DS"))).toBe(true);
  });

  it("warns about 4K textures", () => {
    const result = validateInteriorCode(`
      // Load 4K texture for wall
      val texture4096 = loadTexture("wall_4096.ktx")
    `);
    expect(result.issues.some((i) => i.message.includes("4K") || i.message.includes("memory"))).toBe(true);
  });

  // ── Missing imports ────────────────────────────────────────────────────
  it("warns about missing SceneView import", () => {
    const result = validateInteriorCode(`
      @Composable
      fun RoomViewer() {
        SceneView {
          ModelNode(modelInstance = model)
        }
      }
    `);
    expect(result.issues.some((i) => i.message.includes("Missing SceneView import"))).toBe(true);
  });

  it("warns about missing ARSceneView import", () => {
    const result = validateInteriorCode(`
      @Composable
      fun RoomViewer() {
        ARSceneView {
          ModelNode(modelInstance = model)
        }
      }
    `);
    expect(result.issues.some((i) => i.message.includes("Missing ARSceneView import"))).toBe(true);
  });

  // ── Valid code ─────────────────────────────────────────────────────────
  it("passes valid interior SceneView code", () => {
    const result = validateInteriorCode(`
      import io.github.sceneview.SceneView
      import io.github.sceneview.rememberEngine
      import io.github.sceneview.rememberModelLoader
      import io.github.sceneview.rememberModelInstance

      @Composable
      fun RoomViewer() {
        val engine = rememberEngine()
        val modelLoader = rememberModelLoader(engine)
        val model = rememberModelInstance(modelLoader, "room.glb")

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
