import { describe, it, expect } from "vitest";
import {
  generateDentalViewer,
  DENTAL_VIEW_TYPES,
  DENTAL_FEATURES,
} from "./dental-viewer.js";

describe("DENTAL_VIEW_TYPES", () => {
  it("has at least 7 types", () => {
    expect(DENTAL_VIEW_TYPES.length).toBeGreaterThanOrEqual(7);
  });

  it("includes core types", () => {
    expect(DENTAL_VIEW_TYPES).toContain("full-arch");
    expect(DENTAL_VIEW_TYPES).toContain("implant");
    expect(DENTAL_VIEW_TYPES).toContain("orthodontic");
    expect(DENTAL_VIEW_TYPES).toContain("intraoral-scan");
  });
});

describe("DENTAL_FEATURES", () => {
  it("has at least 6 features", () => {
    expect(DENTAL_FEATURES.length).toBeGreaterThanOrEqual(6);
  });

  it("includes measurement and comparison", () => {
    expect(DENTAL_FEATURES).toContain("measurement");
    expect(DENTAL_FEATURES).toContain("comparison");
    expect(DENTAL_FEATURES).toContain("treatment-stages");
  });
});

describe("generateDentalViewer", () => {
  it("generates valid Kotlin code for full-arch", () => {
    const code = generateDentalViewer({ viewType: "full-arch" });
    expect(code).toContain("package com.example.medical.dental");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
  });

  it("uses correct model path with arch", () => {
    const code = generateDentalViewer({ viewType: "full-arch", arch: "upper" });
    expect(code).toContain("models/dental/full-arch_upper.glb");
  });

  it("defaults to both arches", () => {
    const code = generateDentalViewer({ viewType: "full-arch" });
    expect(code).toContain("full-arch_both.glb");
  });

  it("includes roots overlay when showRoots=true", () => {
    const code = generateDentalViewer({ viewType: "implant", showRoots: true });
    expect(code).toContain("showRootsLayer");
    expect(code).toContain("roots.glb");
    expect(code).toContain("Show roots");
  });

  it("excludes roots when showRoots=false", () => {
    const code = generateDentalViewer({ viewType: "implant", showRoots: false });
    expect(code).not.toContain("showRootsLayer");
  });

  it("includes nerves overlay when showNerves=true", () => {
    const code = generateDentalViewer({ viewType: "implant", showNerves: true });
    expect(code).toContain("showNervesLayer");
    expect(code).toContain("nerves.glb");
    expect(code).toContain("Show nerves");
  });

  it("includes treatment stages when feature selected", () => {
    const code = generateDentalViewer({
      viewType: "orthodontic",
      features: ["treatment-stages"],
    });
    expect(code).toContain("currentStage");
    expect(code).toContain("totalStages");
    expect(code).toContain("Slider");
  });

  it("includes comparison toggle when feature selected", () => {
    const code = generateDentalViewer({
      viewType: "full-arch",
      features: ["comparison"],
    });
    expect(code).toContain("showComparison");
    expect(code).toContain("Before / After");
  });

  it("generates AR code when ar=true", () => {
    const code = generateDentalViewer({ viewType: "full-arch", ar: true });
    expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("android.permission.CAMERA");
  });

  it("includes dental-specific lighting", () => {
    const code = generateDentalViewer({ viewType: "full-arch" });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("type = LightManager.Type.");
    expect(code).toContain("import com.google.android.filament.LightManager");
    const lightCount = (code.match(/LightNode\(/g) || []).length;
    expect(lightCount).toBeGreaterThanOrEqual(2);
  });

  it("includes loading indicator", () => {
    const code = generateDentalViewer({ viewType: "full-arch" });
    expect(code).toContain("CircularProgressIndicator");
    expect(code).toContain("Loading dental scan");
  });

  it("handles null modelInstance", () => {
    const code = generateDentalViewer({ viewType: "full-arch" });
    expect(code).toContain("dentalModel?.let");
  });

  it("includes intraoral scanner workflow doc", () => {
    const code = generateDentalViewer({ viewType: "intraoral-scan" });
    expect(code).toContain("iTero");
    expect(code).toContain("3Shape");
  });

  it("generates code for every view type", () => {
    for (const type of DENTAL_VIEW_TYPES) {
      const code = generateDentalViewer({ viewType: type });
      expect(code).toContain("@Composable");
    }
  });

  it("scale is appropriate for dental arch (~15cm)", () => {
    const code = generateDentalViewer({ viewType: "full-arch" });
    expect(code).toContain("scaleToUnits = 0.15f");
  });
});
