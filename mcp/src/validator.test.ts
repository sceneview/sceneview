import { describe, it, expect } from "vitest";
import { validateCode, formatValidationReport } from "./validator.js";

// ─── Helpers ─────────────────────────────────────────────────────────────────

function ruleIds(code: string) {
  return validateCode(code).map((i) => i.rule);
}

function hasRule(code: string, rule: string) {
  return ruleIds(code).includes(rule);
}

// ─── threading/filament-off-main-thread ──────────────────────────────────────

describe("threading/filament-off-main-thread", () => {
  const RULE = "threading/filament-off-main-thread";

  it("fires when modelLoader.createModel* used near Dispatchers.IO", () => {
    const code = `
      withContext(Dispatchers.IO) {
        val model = modelLoader.createModelInstance("models/chair.glb")
      }
    `;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("fires when Texture.Builder used near Dispatchers.Default", () => {
    const code = `
      launch(Dispatchers.Default) {
        val texture = Texture.Builder().width(4).build(engine)
      }
    `;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire without a background dispatcher", () => {
    const code = `val model = modelLoader.createModelInstance("models/chair.glb")`;
    expect(hasRule(code, RULE)).toBe(false);
  });

  it("does NOT fire when Dispatchers.Main is used", () => {
    const code = `
      withContext(Dispatchers.Main) {
        val model = modelLoader.createModelInstance("models/chair.glb")
      }
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── ar/node-not-anchor ───────────────────────────────────────────────────────

describe("ar/node-not-anchor", () => {
  const RULE = "ar/node-not-anchor";

  it("fires when worldPosition is set inside an ARScene", () => {
    const code = `
      ARSceneView(engine = engine) {
        node.worldPosition = Position(0f, 0f, -1f)
      }
    `;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire for worldPosition outside ARScene", () => {
    const code = `node.worldPosition = Position(0f, 0f, -1f)`;
    expect(hasRule(code, RULE)).toBe(false);
  });

  it("does NOT fire when AnchorNode is used correctly", () => {
    const code = `
      ARSceneView(engine = engine) {
        AnchorNode(anchor = hitResult.createAnchor()) { }
      }
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── composable/model-instance-null-check ────────────────────────────────────

describe("composable/model-instance-null-check", () => {
  const RULE = "composable/model-instance-null-check";

  it("fires when rememberModelInstance result used without null guard", () => {
    const code = `
      val instance = rememberModelInstance(modelLoader, "models/chair.glb")
      ModelNode(modelInstance = instance, scaleToUnits = 1f)
    `;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire when guarded with ?.", () => {
    const code = `
      val instance = rememberModelInstance(modelLoader, "models/chair.glb")
      instance?.let { ModelNode(modelInstance = it) }
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });

  it("does NOT fire when guarded with !!", () => {
    const code = `
      val instance = rememberModelInstance(modelLoader, "models/chair.glb")
      ModelNode(modelInstance = instance!!, scaleToUnits = 1f)
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });

  it("does NOT fire when rememberModelInstance is not assigned to a var", () => {
    const code = `ModelNode(modelInstance = someOtherInstance, scaleToUnits = 1f)`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── api/light-node-trailing-lambda ──────────────────────────────────────────

describe("api/light-node-trailing-lambda", () => {
  const RULE = "api/light-node-trailing-lambda";

  it("fires on trailing lambda syntax", () => {
    const code = `LightNode(engine = engine, type = LightManager.Type.SUN) { intensity(100_000f) }`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire when apply = { } is used correctly", () => {
    const code = `LightNode(engine = engine, type = LightManager.Type.SUN, apply = { intensity(100_000f) })`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── lifecycle/manual-engine-create ──────────────────────────────────────────

describe("lifecycle/manual-engine-create", () => {
  const RULE = "lifecycle/manual-engine-create";

  it("fires on Engine.create()", () => {
    const code = `val engine = Engine.create(eglContext)`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire when rememberEngine() is used", () => {
    const code = `val engine = rememberEngine()`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── lifecycle/manual-engine-destroy ─────────────────────────────────────────

describe("lifecycle/manual-engine-destroy", () => {
  const RULE = "lifecycle/manual-engine-destroy";

  it("fires when engine.destroy() called alongside rememberEngine", () => {
    const code = `
      val engine = rememberEngine()
      DisposableEffect(Unit) { onDispose { engine.destroy() } }
    `;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire when rememberEngine is absent (imperative code)", () => {
    const code = `engine.destroy()`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── lifecycle/texture-destroy-order ─────────────────────────────────────────

describe("lifecycle/texture-destroy-order", () => {
  const RULE = "lifecycle/texture-destroy-order";

  it("fires when texture is destroyed before material instance", () => {
    const code = `
      engine.safeDestroyTexture(texture)
      materialLoader.destroyMaterialInstance(instance)
    `;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire when material instance destroyed first", () => {
    const code = `
      materialLoader.destroyMaterialInstance(instance)
      engine.safeDestroyTexture(texture)
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });

  it("does NOT fire when only one of the two is present", () => {
    expect(hasRule(`engine.safeDestroyTexture(texture)`, RULE)).toBe(false);
    expect(hasRule(`materialLoader.destroyMaterialInstance(instance)`, RULE)).toBe(false);
  });
});

// ─── composable/prefer-remember-model-instance ────────────────────────────────

describe("composable/prefer-remember-model-instance", () => {
  const RULE = "composable/prefer-remember-model-instance";

  it("fires when createModelInstance is used directly", () => {
    const code = `val m = modelLoader.createModelInstance("models/chair.glb")`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire for rememberModelInstance", () => {
    const code = `val m = rememberModelInstance(modelLoader, "models/chair.glb")`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── ar/anchor-node-missing-anchor ───────────────────────────────────────────

describe("ar/anchor-node-missing-anchor", () => {
  const RULE = "ar/anchor-node-missing-anchor";

  it("fires on empty AnchorNode()", () => {
    const code = `val node = AnchorNode()`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire when anchor param is provided", () => {
    const code = `AnchorNode(anchor = hitResult.createAnchor())`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── migration/old-api ────────────────────────────────────────────────────────

describe("migration/old-api", () => {
  const RULE = "migration/old-api";

  it("does NOT fire on SceneView composable (correct 3.0 name)", () => {
    expect(hasRule(`SceneView(modifier = Modifier.fillMaxSize())`, RULE)).toBe(false);
  });

  it("fires on ArSceneView composable", () => {
    expect(hasRule(`ArSceneView(modifier = Modifier.fillMaxSize())`, RULE)).toBe(true);
  });

  // ── 4.0 rename: `Scene { }` / `ARScene { }` → `SceneView { }` / `ARSceneView { }`
  it("fires on legacy Scene { } trailing-lambda call (4.0 rename — #939)", () => {
    expect(hasRule(`Scene { ModelNode(...) }`, RULE)).toBe(true);
  });

  it("fires on legacy ARScene { } trailing-lambda call (4.0 rename — #939)", () => {
    expect(hasRule(`ARScene { ModelNode(...) }`, RULE)).toBe(true);
  });

  it("does NOT false-positive on Filament's `Scene` class (followed by `.` or `(`)", () => {
    // Filament has its own `Scene` class which is used as `Scene()` or
    // `scene.addEntity(...)`. The 4.0-rename rule must NOT fire there.
    expect(hasRule(`val s = Scene()`, RULE)).toBe(false);
    expect(hasRule(`scene.addEntity(entity)`, RULE)).toBe(false);
  });

  it("fires on TransformableNode", () => {
    expect(hasRule(`val node = TransformableNode(system)`, RULE)).toBe(true);
  });

  it("fires on PlacementNode", () => {
    expect(hasRule(`val node = PlacementNode()`, RULE)).toBe(true);
  });

  it("fires on ViewRenderable", () => {
    expect(hasRule(`ViewRenderable.builder()`, RULE)).toBe(true);
  });

  it("fires on loadModelAsync", () => {
    expect(hasRule(`modelLoader.loadModelAsync("models/x.glb")`, RULE)).toBe(true);
  });

  it("does NOT fire on modern 3.0 APIs", () => {
    const code = `
      val engine = rememberEngine()
      SceneView(engine = engine) {
        rememberModelInstance(modelLoader, "models/x.glb")?.let { ModelNode(modelInstance = it) }
      }
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── api/scene-missing-engine ─────────────────────────────────────────────────

describe("api/scene-missing-engine", () => {
  const RULE = "api/scene-missing-engine";

  it("fires when SceneView() has no engine nearby", () => {
    const code = `SceneView(modifier = Modifier.fillMaxSize()) { }`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire when engine is provided", () => {
    const code = `
      val engine = rememberEngine()
      SceneView(modifier = Modifier.fillMaxSize(), engine = engine) { }
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── api/fog-node-missing-view ────────────────────────────────────────────────

describe("api/fog-node-missing-view", () => {
  const RULE = "api/fog-node-missing-view";

  it("fires when FogNode has no view parameter", () => {
    const code = `FogNode(density = 0.1f, enabled = true)`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire when view is provided", () => {
    const code = `FogNode(view = view, density = 0.1f)`;
    expect(hasRule(code, RULE)).toBe(false);
  });

  it("does NOT fire when FogNode is not used", () => {
    const code = `SceneView(engine = engine) { }`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── api/reflection-probe-missing-camera ─────────────────────────────────────

describe("api/reflection-probe-missing-camera", () => {
  const RULE = "api/reflection-probe-missing-camera";

  it("fires when ReflectionProbeNode has no cameraPosition", () => {
    const code = `ReflectionProbeNode(filamentScene = scene, environment = env)`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire when cameraPosition is provided", () => {
    const code = `ReflectionProbeNode(
      filamentScene = scene,
      environment = env,
      cameraPosition = cameraPos
    )`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── api/physics-node-missing-radius ─────────────────────────────────────────

describe("api/physics-node-missing-radius", () => {
  const RULE = "api/physics-node-missing-radius";

  it("fires info when PhysicsNode has no radius", () => {
    const code = `PhysicsNode(node = sphere, mass = 1f, restitution = 0.7f)`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire when radius is provided", () => {
    const code = `PhysicsNode(node = sphere, mass = 1f, radius = 0.15f)`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── api/dynamic-sky-outside-scene ───────────────────────────────────────────

describe("api/dynamic-sky-outside-scene", () => {
  const RULE = "api/dynamic-sky-outside-scene";

  it("fires when DynamicSkyNode is used without a Scene", () => {
    const code = `DynamicSkyNode(timeOfDay = 12f)`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire when inside a SceneView", () => {
    const code = `
      SceneView(engine = engine) {
        DynamicSkyNode(timeOfDay = 12f)
      }
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── performance/multiple-engines ─────────────────────────────────────────────

describe("performance/multiple-engines", () => {
  const RULE = "performance/multiple-engines";

  it("fires when multiple rememberEngine() calls exist", () => {
    const code = `
      val engine1 = rememberEngine()
      val engine2 = rememberEngine()
    `;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire for a single rememberEngine()", () => {
    const code = `val engine = rememberEngine()`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── threading/model-in-launched-effect ─────────────────────────────────────

describe("threading/model-in-launched-effect", () => {
  const RULE = "threading/model-in-launched-effect";

  it("fires when modelLoader is used inside LaunchedEffect", () => {
    const code = `
      LaunchedEffect(Unit) {
        val model = modelLoader.createModelInstance("models/chair.glb")
      }
    `;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire when rememberModelInstance is used", () => {
    const code = `
      val instance = rememberModelInstance(modelLoader, "models/chair.glb")
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── api/asset-path-leading-slash ──────────────────────────────────────────

describe("api/asset-path-leading-slash", () => {
  const RULE = "api/asset-path-leading-slash";

  it("fires when asset path starts with /", () => {
    const code = `val m = rememberModelInstance(modelLoader, "/models/chair.glb")`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire for correct relative path", () => {
    const code = `val m = rememberModelInstance(modelLoader, "models/chair.glb")`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── api/scene-zero-size ──────────────────────────────────────────────────

describe("api/scene-zero-size", () => {
  const RULE = "api/scene-zero-size";

  it("fires info when SceneView has no Modifier", () => {
    const code = `SceneView(engine = engine) { }`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire when fillMaxSize is used", () => {
    const code = `SceneView(modifier = Modifier.fillMaxSize(), engine = engine) { }`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── api/remember-model-missing-loader ──────────────────────────────────────

describe("api/remember-model-missing-loader", () => {
  const RULE = "api/remember-model-missing-loader";

  it("fires when rememberModelInstance used without modelLoader", () => {
    const code = `val m = rememberModelInstance(loader, "models/chair.glb")`;
    // 'loader' not 'modelLoader' — but the rule checks for the string "modelLoader"
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does NOT fire when modelLoader is present", () => {
    const code = `
      val modelLoader = rememberModelLoader(engine)
      val m = rememberModelInstance(modelLoader, "models/chair.glb")
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

// ─── formatValidationReport ───────────────────────────────────────────────────

describe("formatValidationReport", () => {
  it("returns success message when no issues", () => {
    expect(formatValidationReport([])).toContain("No issues found");
  });

  it("includes severity counts in the header", () => {
    const issues = validateCode(`
      val instance = rememberModelInstance(modelLoader, "x.glb")
      ModelNode(modelInstance = instance, scaleToUnits = 1f)
      LightNode(engine = engine) { intensity(1f) }
    `);
    const report = formatValidationReport(issues);
    expect(report).toMatch(/error/);
    expect(report).toContain("🔴");
  });

  it("includes line numbers when available", () => {
    const code = `\nval instance = rememberModelInstance(modelLoader, "x.glb")\nModelNode(modelInstance = instance, scaleToUnits = 1f)`;
    const report = formatValidationReport(validateCode(code));
    expect(report).toMatch(/line \d+/);
  });
});

// ─── Web (Kotlin/JS) validation rules ──────────────────────────────────────

describe("web/ar-not-supported", () => {
  const RULE = "web/ar-not-supported";

  it("fires when ARScene used in web code", () => {
    const code = `
import io.github.sceneview.web.SceneView
import kotlinx.browser.document
ARSceneView(modifier = Modifier.fillMaxSize())
`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does not fire for 3D-only web code", () => {
    const code = `
import io.github.sceneview.web.SceneView
import kotlinx.browser.document
SceneView.create(canvas = canvas, configure = {})
`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

describe("web/missing-start-rendering", () => {
  const RULE = "web/missing-start-rendering";

  it("fires when SceneView.create used without startRendering", () => {
    const code = `
import io.github.sceneview.web.SceneView
SceneView.create(canvas = canvas, configure = {}, onReady = { })
`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does not fire when startRendering is called", () => {
    const code = `
import io.github.sceneview.web.SceneView
SceneView.create(canvas = canvas, configure = {}, onReady = { it.startRendering() })
`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

describe("web/missing-canvas-resize", () => {
  const RULE = "web/missing-canvas-resize";

  it("fires when canvas not sized before SceneView.create", () => {
    const code = `
import io.github.sceneview.web.SceneView
val canvas = document.getElementById("c") as HTMLCanvasElement
SceneView.create(canvas = canvas, configure = {}, onReady = { it.startRendering() })
`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("does not fire when clientWidth is used", () => {
    const code = `
import io.github.sceneview.web.SceneView
val canvas = document.getElementById("c") as HTMLCanvasElement
canvas.width = canvas.clientWidth
SceneView.create(canvas = canvas, configure = {}, onReady = { it.startRendering() })
`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

describe("web language detection", () => {
  it("detects kotlin-js from sceneview.web import", () => {
    const code = `import io.github.sceneview.web.SceneView`;
    // Should not trigger Android-only rules
    expect(hasRule(code, "threading/filament-off-main-thread")).toBe(false);
  });

  it("detects kotlin-js from kotlinx.browser import", () => {
    const code = `
import kotlinx.browser.document
val canvas = document.getElementById("c") as HTMLCanvasElement
SceneView.create(canvas = canvas, configure = {}, onReady = { it.startRendering() })
`;
    // Should trigger web rules, not Android rules
    expect(hasRule(code, "web/missing-canvas-resize")).toBe(true);
  });
});

// ─── symbols/* — existence checks against the generated .api index (#2760) ───

describe("symbols/unknown-member", () => {
  const RULE = "symbols/unknown-member";

  it("rejects the canonical hallucination with the right suggestion", () => {
    // The exact failure mode from the issue: a method that does not exist,
    // structurally close to the real `loadModelInstanceAsync`.
    const code = `modelLoader.createModelInstanceAsync("models/chair.glb")`;
    const issues = validateCode(code).filter((i) => i.rule === RULE);
    expect(issues).toHaveLength(1);
    expect(issues[0].severity).toBe("error");
    expect(issues[0].message).toContain("`loadModelInstanceAsync`");
  });

  it("accepts every real loader call", () => {
    const code = `
      val instances = modelLoader.createInstancedModel("models/chair.glb", 4)
      val model = modelLoader.loadModelInstanceAsync("https://example.com/x.glb")
      val mat = materialLoader.createColorInstance(color)
      modelLoader.clear()
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });

  it("does not flag Kotlin stdlib scope functions on a loader", () => {
    const code = `modelLoader.apply { clear() }`;
    expect(hasRule(code, RULE)).toBe(false);
  });

  it("does not flag chained calls through a loader property", () => {
    // The receiver of `createScene()` is `engine`, not the loader — the
    // member check must only look at direct `loader.member(` calls.
    const code = `modelLoader.engine.createScene()`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

describe("symbols/unknown-type", () => {
  const RULE = "symbols/unknown-type";

  it("rejects a close-typo node type with a suggestion", () => {
    const code = `ModellNode(modelInstance = instance)`;
    const issues = validateCode(code).filter((i) => i.rule === RULE);
    expect(issues).toHaveLength(1);
    expect(issues[0].message).toContain("`ModelNode`");
  });

  it("rejects a made-up node type even with no close suggestion", () => {
    const code = `HologramNode(size = Size(1f))`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("accepts real node classes, factories and Scene composables", () => {
    const code = `
      ModelNode(modelInstance = instance)
      SplatNode(splat = splat)
      WallPlacementScene(engine = engine)
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });

  it("leaves migration-covered legacy names to migration/old-api", () => {
    const code = `PlacementNode(engine)`;
    expect(hasRule(code, RULE)).toBe(false);
    expect(hasRule(code, "migration/old-api")).toBe(true);
  });
});

describe("symbols/unknown-import", () => {
  const RULE = "symbols/unknown-import";

  it("rejects an import of a nonexistent SceneView class", () => {
    const code = `import io.github.sceneview.node.ShadowNode`;
    expect(hasRule(code, RULE)).toBe(true);
  });

  it("accepts class, composable-member and wildcard imports", () => {
    const code = `
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.*
import io.github.sceneview.rememberEngine
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });

  it("ignores non-SceneView imports entirely", () => {
    const code = `
import androidx.compose.ui.Modifier
import com.google.android.filament.Engine
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });

  it("never sees web imports — they route to the kotlin-js rule set", () => {
    // `io.github.sceneview.web.*` is not in the index (no committed dump),
    // but detectLanguage routes such snippets to WEB_RULES before the
    // symbol rules run. Guard the routing so this stays true.
    const code = `import io.github.sceneview.web.SceneView`;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

describe("symbols/unknown-remember-helper", () => {
  const RULE = "symbols/unknown-remember-helper";

  it("warns on an invented SceneView-ish remember helper, with suggestion", () => {
    const code = `val instance = rememberModelInstanceAsync(modelLoader, "models/x.glb")`;
    const issues = validateCode(code).filter((i) => i.rule === RULE);
    expect(issues).toHaveLength(1);
    expect(issues[0].severity).toBe("warning");
    expect(issues[0].message).toContain("`rememberModelInstance`");
  });

  it("accepts every real remember helper", () => {
    const code = `
      val engine = rememberEngine()
      val modelLoader = rememberModelLoader(engine)
      val view = rememberView(engine)
      val instance = rememberModelInstance(modelLoader, "models/x.glb")
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });

  it("does not flag non-SceneView Compose helpers", () => {
    const code = `
      val scope = rememberCoroutineScope()
      val state = rememberSaveable { mutableStateOf(0) }
      val nav = rememberNavController()
    `;
    expect(hasRule(code, RULE)).toBe(false);
  });
});

describe("symbols/* — false-positive guard on real canonical snippets", () => {
  it("the llms.txt hello-world passes with zero symbols/* issues", () => {
    const code = `
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

@Composable
fun ModelViewer() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/damaged_helmet.glb")
    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
    ) {
        modelInstance?.let {
            ModelNode(modelInstance = it, scaleToUnits = 1.0f)
        }
    }
}
`;
    const symbolIssues = validateCode(code).filter((i) =>
      i.rule.startsWith("symbols/"),
    );
    expect(symbolIssues).toEqual([]);
  });

  it("a real AR wall-placement snippet passes with zero symbols/* issues", () => {
    const code = `
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.WallPlacementScene
import io.github.sceneview.ar.rememberARCameraStream

@Composable
fun WallDemo() {
    val engine = rememberEngine()
    WallPlacementScene(
        engine = engine,
        onSeamChanged = { seam -> },
    )
}
`;
    const symbolIssues = validateCode(code).filter((i) =>
      i.rule.startsWith("symbols/"),
    );
    expect(symbolIssues).toEqual([]);
  });
});
