import { describe, expect, it } from "vitest";
import { formatGeneratedScene, generateScene } from "./generate-scene.js";
import { LATEST_SCENEVIEW_RELEASE } from "./generated/version.js";

describe("generateScene", () => {
  it("generates a scene with a table and two chairs", () => {
    const result = generateScene("a room with a table and two chairs");
    expect(result.code).toContain("SceneView(");
    expect(result.code).toContain("rememberEngine()");
    expect(result.code).toContain("CubeNode("); // table
    expect(result.code).toContain("models/chair.glb"); // chairs are ModelNode
    expect(result.elements.length).toBeGreaterThanOrEqual(3); // table + 2 chairs + light + ground
    expect(result.isAR).toBe(false);
  });

  it("generates AR scene when description mentions AR", () => {
    const result = generateScene("AR scene with a chair placed in real world");
    expect(result.code).toContain("AutoPlacementScene(");
    expect(result.code).toContain("state = placementState");
    expect(result.code).toContain("AutoPlacementModel(placement, placementState, instance,");
    expect(result.isAR).toBe(true);
    expect(result.dependencies[0]).toContain("arsceneview");
    expect(result.notes.join("\n")).toContain('get_sample("ar-model-viewer")');
  });

  it("waits for the model to load before asking to place it", () => {
    const code = generateScene("AR scene with a chair").code;
    expect(code).toContain('rememberModelInstance(modelLoader, "models/chair.glb")');
    expect(code).toContain("assetReady = chairInstance != null");
    expect(code).toContain("chairInstance?.let { instance ->");
    expect(code).not.toMatch(/hitTest|onSingleTapConfirmed/);
  });

  it("imports the automatic placement composables in AR scenes only", () => {
    const ar = generateScene("AR scene with a chair").code;
    expect(ar).toContain("import io.github.sceneview.ar.AutoPlacementScene\n");
    expect(ar).toContain("import io.github.sceneview.ar.AutoPlacementModel\n");
    expect(ar).toContain("import io.github.sceneview.ar.rememberAutoPlacementState\n");
    expect(generateScene("a red sphere").code.startsWith("@Composable")).toBe(true);
  });

  it("places a group once all its models are loaded", () => {
    const code = generateScene("AR scene with a chair, a tree and a sphere").code;
    expect(code).toContain("chairInstance != null");
    expect(code).toContain("treeInstance != null");
    expect(code).toContain("AutoPlacementNode(placement, placementState) {");
    expect(code).toContain("SphereNode(");
    expect(code).toContain("ModelNode(");
  });

  it("includes light in every scene", () => {
    const result = generateScene("a sphere");
    expect(result.code).toContain("LightNode(");
    expect(result.code).toContain("intensity");
  });

  it("generates geometry nodes for basic shapes", () => {
    const result = generateScene("a red sphere and a cube");
    expect(result.code).toContain("SphereNode(");
    expect(result.code).toContain("CubeNode(");
  });

  it("adds ground plane for 3D scenes", () => {
    const result = generateScene("a chair");
    expect(result.code).toContain("PlaneNode(");
  });

  it("does not add ground plane for AR scenes", () => {
    const result = generateScene("AR with a chair");
    expect(result.code).not.toContain("PlaneNode(");
  });

  it("handles environment for 3D scenes", () => {
    const result = generateScene("outdoor scene with a tree");
    expect(result.code).toContain("rememberEnvironmentLoader");
    expect(result.code).toContain("createHDREnvironment");
  });

  it("handles multiple instances of same object", () => {
    const result = generateScene("three spheres");
    const sphereElements = result.elements.filter((e) => e.nodeType === "SphereNode");
    expect(sphereElements.length).toBe(3);
  });

  it("includes model notes when GLB models are referenced", () => {
    const result = generateScene("a car and a tree");
    expect(result.notes.some((n) => n.includes("GLB"))).toBe(true);
  });

  it("handles empty/minimal descriptions", () => {
    const result = generateScene("a scene");
    // Should at least have a light and ground
    expect(result.elements.length).toBeGreaterThanOrEqual(1);
    expect(result.code).toContain("SceneView(");
  });

  it("uses dim lighting for dark scenes", () => {
    const result = generateScene("a dark room with a table");
    expect(result.code).toContain("POINT");
    expect(result.code).toContain("50_000f");
  });
});

describe("formatGeneratedScene", () => {
  it("includes dependency block", () => {
    const result = generateScene("a sphere");
    const formatted = formatGeneratedScene(result);
    expect(formatted).toContain("```kotlin");
    expect(formatted).toContain("implementation(");
  });

  it("includes code block", () => {
    const result = generateScene("a cube");
    const formatted = formatGeneratedScene(result);
    expect(formatted).toContain("@Composable");
    expect(formatted).toContain("GeneratedScene");
  });

  it("shows AR mode for AR scenes", () => {
    const result = generateScene("AR with a robot");
    const formatted = formatGeneratedScene(result);
    expect(formatted).toContain("AR (AutoPlacementScene)");
  });

  it("shows 3D mode for regular scenes", () => {
    const result = generateScene("a table");
    const formatted = formatGeneratedScene(result);
    expect(formatted).toContain("3D (SceneView)");
  });
});

// #3054 — see samples.test.ts. The dependency line handed to the caller was a
// double-quoted `"io.github.sceneview:sceneview:${LATEST_SCENEVIEW_RELEASE}"`,
// i.e. an un-resolvable Gradle coordinate in every generated scene.
describe("version interpolation (#3054)", () => {
  it("emits a real Maven coordinate for 3D and for AR", () => {
    const scene = generateScene("a table");
    expect(scene.dependencies).toContain(
      `io.github.sceneview:sceneview:${LATEST_SCENEVIEW_RELEASE}`
    );
    expect(formatGeneratedScene(scene)).not.toContain("LATEST_SCENEVIEW_RELEASE");

    const arScene = generateScene("AR scene with a chair placed in real world");
    expect(arScene.dependencies).toContain(
      `io.github.sceneview:arsceneview:${LATEST_SCENEVIEW_RELEASE}`
    );
    expect(formatGeneratedScene(arScene)).not.toContain("LATEST_SCENEVIEW_RELEASE");
  });
});
