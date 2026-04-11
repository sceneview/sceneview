import { describe, it, expect } from "vitest";
import {
  generateFurniturePlacement,
  FURNITURE_CATEGORIES,
  FURNITURE_SIZES,
} from "./furniture-placement.js";

describe("FURNITURE_CATEGORIES", () => {
  it("has at least 10 categories", () => {
    expect(FURNITURE_CATEGORIES.length).toBeGreaterThanOrEqual(10);
  });

  it("includes core categories", () => {
    expect(FURNITURE_CATEGORIES).toContain("sofa");
    expect(FURNITURE_CATEGORIES).toContain("chair");
    expect(FURNITURE_CATEGORIES).toContain("table");
    expect(FURNITURE_CATEGORIES).toContain("bed");
    expect(FURNITURE_CATEGORIES).toContain("desk");
    expect(FURNITURE_CATEGORIES).toContain("lamp");
  });
});

describe("FURNITURE_SIZES", () => {
  it("has 4 size options", () => {
    expect(FURNITURE_SIZES).toHaveLength(4);
  });

  it("includes small, medium, large, custom", () => {
    expect(FURNITURE_SIZES).toContain("small");
    expect(FURNITURE_SIZES).toContain("medium");
    expect(FURNITURE_SIZES).toContain("large");
    expect(FURNITURE_SIZES).toContain("custom");
  });
});

describe("generateFurniturePlacement", () => {
  it("generates AR code by default", () => {
    const code = generateFurniturePlacement({ category: "sofa" });
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
    expect(code).toContain("android.permission.CAMERA");
  });

  it("generates 3D preview when ar=false", () => {
    const code = generateFurniturePlacement({ category: "sofa", ar: false });
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("SceneView(");
    expect(code).not.toContain("ARScene");
  });

  it("uses correct model path for category", () => {
    const code = generateFurniturePlacement({ category: "chair" });
    expect(code).toContain("models/furniture/chair.glb");
  });

  it("uses custom model path when provided", () => {
    const code = generateFurniturePlacement({
      category: "sofa",
      modelPath: "models/custom_sofa.glb",
    });
    expect(code).toContain("models/custom_sofa.glb");
  });

  it("includes rotation slider when rotatable=true", () => {
    const code = generateFurniturePlacement({ category: "table", rotatable: true });
    expect(code).toContain("rotationY");
    expect(code).toContain("Rotation");
  });

  it("includes scale slider when scalable=true", () => {
    const code = generateFurniturePlacement({ category: "table", scalable: true });
    expect(code).toContain("scaleFactor");
    expect(code).toContain("Size");
  });

  it("handles null modelInstance", () => {
    const code = generateFurniturePlacement({ category: "desk" });
    expect(code).toContain("modelInstance?.let");
  });

  it("shows loading indicator", () => {
    const code = generateFurniturePlacement({ category: "lamp" });
    expect(code).toContain("CircularProgressIndicator");
  });

  it("shows placement instruction", () => {
    const code = generateFurniturePlacement({ category: "rug" });
    expect(code).toContain("Tap");
    expect(code).toContain("place");
  });

  it("generates code for every category", () => {
    for (const category of FURNITURE_CATEGORIES) {
      const code = generateFurniturePlacement({ category });
      expect(code).toContain("@Composable");
      expect(code).toContain("rememberModelInstance");
    }
  });

  it("applies small scale for small size", () => {
    const code = generateFurniturePlacement({ category: "chair", size: "small" });
    expect(code).toContain("0.5f");
  });

  it("applies large scale for large size", () => {
    const code = generateFurniturePlacement({ category: "chair", size: "large" });
    expect(code).toContain("1.5f");
  });
});
