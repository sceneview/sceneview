import { describe, expect, it } from "vitest";
import { SYMBOLS } from "./generated/symbols.js";
import {
  editDistance,
  isKnownTopLevelFunction,
  isKnownTypeName,
  membersOfClass,
  rememberHelperNames,
  resolveImport,
  suggestClosest,
  tokenSimilarity,
} from "./symbols.js";

// ─── Generated index sanity — parsed from the real committed .api dumps ──────

describe("generated symbols index", () => {
  it("covers all three Android/KMP modules", () => {
    expect(SYMBOLS.generatedFrom).toEqual([
      "sceneview.api",
      "arsceneview.api",
      "sceneview-core.api",
    ]);
  });

  it("indexes core node classes", () => {
    expect(SYMBOLS.classes["io.github.sceneview.node.ModelNode"]).toBeDefined();
    expect(SYMBOLS.classes["io.github.sceneview.node.LightNode"]).toBeDefined();
    expect(SYMBOLS.classes["io.github.sceneview.loaders.ModelLoader"]).toBeDefined();
  });

  it("indexes composables as top-level functions (SceneView, ARSceneView)", () => {
    expect("SceneView" in SYMBOLS.topLevel).toBe(true);
    expect("ARSceneView" in SYMBOLS.topLevel).toBe(true);
    expect("rememberEngine" in SYMBOLS.topLevel).toBe(true);
    expect("rememberModelInstance" in SYMBOLS.topLevel).toBe(true);
  });

  it("keeps compiler noise out of the index", () => {
    const classNames = Object.keys(SYMBOLS.classes);
    expect(classNames.some((c) => c.includes("ComposableSingletons"))).toBe(false);
    expect(classNames.some((c) => c.endsWith("BuildConfig"))).toBe(false);
    const modelNode = SYMBOLS.classes["io.github.sceneview.node.ModelNode"].members;
    expect(modelNode).not.toContain("$stable");
    expect(modelNode.some((m) => m.includes("$default"))).toBe(false);
  });

  it("derives Kotlin property names from JVM accessors", () => {
    const modelNode = SYMBOLS.classes["io.github.sceneview.node.ModelNode"].members;
    expect(modelNode).toContain("modelInstance");
  });

  it("registers packages that only contain top-level composables", () => {
    // Every facade's package must be importable even without a plain class.
    expect(SYMBOLS.packages).toContain("io.github.sceneview");
    expect(SYMBOLS.packages).toContain("io.github.sceneview.node");
  });

  it("indexes source-level typealiases absent from the .api dumps", () => {
    // binary-compatibility-validator erases typealiases to their expansion,
    // yet `llms.txt` imports them everywhere — the generator recovers them
    // from the Kotlin sources (PR #2814 adversarial-review finding #1).
    expect(SYMBOLS.classes).toHaveProperty(["io.github.sceneview.math.Position"]);
    expect(SYMBOLS.classes).toHaveProperty(["io.github.sceneview.math.Rotation"]);
    expect(SYMBOLS.classes).toHaveProperty(["io.github.sceneview.math.Color"]);
    expect(SYMBOLS.classes).toHaveProperty(["io.github.sceneview.model.Model"]);
    expect(SYMBOLS.classes).toHaveProperty(["io.github.sceneview.model.ModelInstance"]);
  });
});

// ─── Lookups ─────────────────────────────────────────────────────────────────

describe("symbol lookups", () => {
  it("knows classes, composables and SceneScope factories as type names", () => {
    expect(isKnownTypeName("ModelNode")).toBe(true); // class
    expect(isKnownTypeName("SceneView")).toBe(true); // composable
    expect(isKnownTypeName("WallPlacementScene")).toBe(true); // composable
    expect(isKnownTypeName("SplatNode")).toBe(true); // SceneScope factory
    expect(isKnownTypeName("HologramNode")).toBe(false);
  });

  it("resolves imports: class, package, top-level member", () => {
    expect(resolveImport("io.github.sceneview.node.ModelNode")).toBe("class");
    expect(resolveImport("io.github.sceneview.node")).toBe("package");
    expect(resolveImport("io.github.sceneview.SceneView")).toBe("member");
    expect(resolveImport("io.github.sceneview.node.ShadowNode")).toBeNull();
  });

  it("resolves typealias and Companion imports", () => {
    expect(resolveImport("io.github.sceneview.math.Position")).toBe("class");
    expect(resolveImport("io.github.sceneview.math.Rotation")).toBe("class");
    // The generator folds `X.Companion` members into X and drops the entry —
    // the companion itself must still resolve through its host class.
    expect(resolveImport("io.github.sceneview.node.ModelNode.Companion")).toBe("member");
    expect(resolveImport("io.github.sceneview.node.ShadowNode.Companion")).toBeNull();
  });

  it("exposes loader members including suspend/async variants", () => {
    const members = membersOfClass("ModelLoader");
    expect(members).not.toBeNull();
    expect(members?.has("loadModelInstanceAsync")).toBe(true);
    expect(members?.has("createModelInstanceAsync")).toBe(false);
  });

  it("lists real remember* helpers", () => {
    const helpers = rememberHelperNames();
    expect(helpers).toContain("rememberEngine");
    expect(helpers).toContain("rememberModelInstance");
  });

  it("is case-sensitive for top-level functions", () => {
    expect(isKnownTopLevelFunction("rememberEngine")).toBe(true);
    expect(isKnownTopLevelFunction("RememberEngine")).toBe(false);
  });
});

// ─── Suggestions ─────────────────────────────────────────────────────────────

describe("suggestions", () => {
  it("editDistance bands out far names", () => {
    expect(editDistance("modelnode", "modellnode", 2)).toBe(1);
    expect(editDistance("hologram", "billboard", 2)).toBe(Number.POSITIVE_INFINITY);
  });

  it("tokenSimilarity reads camelCase structure", () => {
    expect(tokenSimilarity("createModelInstanceAsync", "loadModelInstanceAsync")).toBeCloseTo(
      3 / 5
    );
    expect(tokenSimilarity("clear", "clone")).toBe(0);
  });

  it("suggests across a pure typo (edit distance), dropping unrelated names", () => {
    // LightNode is neither an edit-distance nor a token match for
    // `ModellNode` — a good suggester stays quiet about it.
    expect(suggestClosest("ModellNode", ["ModelNode", "LightNode"])).toEqual(["ModelNode"]);
  });

  it("suggests across a structural hallucination (token overlap)", () => {
    const members = [...(membersOfClass("ModelLoader") ?? [])];
    const suggestions = suggestClosest("createModelInstanceAsync", members);
    // Both plausible corrections must surface: the sync variant that really
    // exists (`createModelInstance`, closest by token overlap) and the async
    // API the snippet was reaching for (`loadModelInstanceAsync`).
    expect(suggestions).toContain("createModelInstance");
    expect(suggestions).toContain("loadModelInstanceAsync");
  });

  it("stays silent rather than suggesting noise", () => {
    expect(suggestClosest("HologramNode", ["SceneView", "CameraNode"])).toEqual([]);
  });
});
