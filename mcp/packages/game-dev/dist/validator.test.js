import { describe, it, expect } from "vitest";
import { validateGameCode, formatValidationReport } from "./validator.js";
describe("validateGameCode", () => {
    // ── Threading checks ───────────────────────────────────────────────────
    it("detects Filament calls on IO dispatcher", () => {
        const result = validateGameCode(`
      scope.launch(Dispatchers.IO) {
        modelLoader.createModelInstance("model.glb")
      }
    `);
        expect(result.valid).toBe(false);
        expect(result.issues[0].severity).toBe("error");
        expect(result.issues[0].message).toContain("main thread");
    });
    it("detects Filament calls on Default dispatcher", () => {
        const result = validateGameCode(`
      scope.launch(Dispatchers.Default) {
        materialLoader.createMaterial()
      }
    `);
        expect(result.valid).toBe(false);
    });
    it("detects GlobalScope usage", () => {
        const result = validateGameCode(`
      GlobalScope.launch { }
    `);
        expect(result.issues.some((i) => i.message.includes("GlobalScope"))).toBe(true);
    });
    // ── Null-safety checks ─────────────────────────────────────────────────
    it("warns about unhandled null from rememberModelInstance", () => {
        const result = validateGameCode(`
      val model = rememberModelInstance(loader, "model.glb")
      ModelNode(modelInstance = model)
    `);
        expect(result.issues.some((i) => i.message.includes("null"))).toBe(true);
    });
    it("passes when null is handled", () => {
        const result = validateGameCode(`
      val model = rememberModelInstance(loader, "model.glb")
      model?.let { ModelNode(modelInstance = it) }
    `);
        expect(result.issues.filter((i) => i.message.includes("null while loading"))).toHaveLength(0);
    });
    // ── LightNode bug ──────────────────────────────────────────────────────
    it("detects LightNode trailing-lambda bug", () => {
        const result = validateGameCode(`
      LightNode() {
        intensity(80000f)
      }
    `);
        expect(result.valid).toBe(false);
        expect(result.issues[0].message).toContain("named parameter");
    });
    it("passes correct LightNode usage", () => {
        const result = validateGameCode(`
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
        const result = validateGameCode(`Scene(modifier = Modifier)`);
        expect(result.valid).toBe(false);
        expect(result.issues[0].message).toContain("deprecated since v3.6");
        expect(result.issues[0].message).toContain("SceneView");
    });
    it("detects deprecated ARScene() composable usage", () => {
        const result = validateGameCode(`ARScene(modifier = Modifier)`);
        expect(result.valid).toBe(false);
        expect(result.issues[0].message).toContain("deprecated since v3.6");
        expect(result.issues[0].message).toContain("ARSceneView");
    });
    it("detects Sceneform 1.x ArSceneView() usage", () => {
        const result = validateGameCode(`ArSceneView(modifier = Modifier)`);
        expect(result.valid).toBe(false);
    });
    it("detects loadModelAsync 2.x usage", () => {
        const result = validateGameCode(`modelLoader.loadModelAsync("model.glb")`);
        expect(result.valid).toBe(false);
    });
    it("detects Engine.create 2.x usage", () => {
        const result = validateGameCode(`val engine = Engine.create()`);
        expect(result.valid).toBe(false);
    });
    it("detects sceneform imports", () => {
        const result = validateGameCode(`import com.google.ar.sceneform.Node`);
        expect(result.valid).toBe(false);
    });
    // ── Gaming-specific checks ─────────────────────────────────────────────
    it("detects Thread.sleep in onFrame", () => {
        const result = validateGameCode(`
      onFrame = {
        Thread.sleep(16)
        updatePositions()
      }
    `);
        expect(result.valid).toBe(false);
        expect(result.issues.some((i) => i.message.includes("Thread.sleep"))).toBe(true);
    });
    it("detects delay in onFrame", () => {
        const result = validateGameCode(`
      onFrame = {
        delay(100)
        physics.step()
      }
    `);
        expect(result.valid).toBe(false);
        expect(result.issues.some((i) => i.message.includes("onFrame"))).toBe(true);
    });
    it("warns about physics without fixed timestep", () => {
        const result = validateGameCode(`
      onFrame = { _ ->
        updatePhysics(bodies, velocity, collision)
      }
    `);
        expect(result.issues.some((i) => i.message.includes("timestep"))).toBe(true);
    });
    it("no timestep warning when dt is used", () => {
        const result = validateGameCode(`
      onFrame = { frameTimeNanos ->
        val dt = 1f / 60f
        updatePhysics(bodies, velocity, collision, dt)
      }
    `);
        expect(result.issues.filter((i) => i.message.includes("timestep"))).toHaveLength(0);
    });
    it("info: particle performance warning", () => {
        const result = validateGameCode(`
      for (p in particles) {
        SphereNode(radius = p.size)
      }
    `);
        expect(result.issues.some((i) => i.message.includes("particle"))).toBe(true);
    });
    it("info: animation count check", () => {
        const result = validateGameCode(`
      val animator = instance.animator
      animator.applyAnimation(0, time)
    `);
        expect(result.issues.some((i) => i.message.includes("animationCount"))).toBe(true);
    });
    it("info: unseeded Random", () => {
        const result = validateGameCode(`
      val random = Random()
      val level = generateLevel(random)
    `);
        expect(result.issues.some((i) => i.message.includes("seed"))).toBe(true);
    });
    // ── Missing imports ────────────────────────────────────────────────────
    it("warns about missing Scene import", () => {
        const result = validateGameCode(`
      @Composable
      fun GameViewer() {
        SceneView {
          ModelNode(modelInstance = model)
        }
      }
    `);
        expect(result.issues.some((i) => i.message.includes("Missing SceneView import"))).toBe(true);
    });
    it("warns about missing ARScene import", () => {
        const result = validateGameCode(`
      @Composable
      fun GameViewer() {
        ARSceneView {
          ModelNode(modelInstance = model)
        }
      }
    `);
        expect(result.issues.some((i) => i.message.includes("Missing ARSceneView import"))).toBe(true);
    });
    // ── Valid code ─────────────────────────────────────────────────────────
    it("passes valid gaming SceneView code", () => {
        const result = validateGameCode(`
      import io.github.sceneview.SceneView
      import io.github.sceneview.rememberEngine
      import io.github.sceneview.rememberModelLoader
      import io.github.sceneview.rememberModelInstance

      @Composable
      fun CharacterViewer() {
        val engine = rememberEngine()
        val modelLoader = rememberModelLoader(engine)
        val model = rememberModelInstance(modelLoader, "character.glb")

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
