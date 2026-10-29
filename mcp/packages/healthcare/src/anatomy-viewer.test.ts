import { describe, it, expect } from "vitest";
import {
  generateAnatomyViewer,
  ANATOMY_SYSTEMS,
  ANATOMY_REGIONS,
} from "./anatomy-viewer.js";

describe("ANATOMY_SYSTEMS", () => {
  it("has at least 10 systems", () => {
    expect(ANATOMY_SYSTEMS.length).toBeGreaterThanOrEqual(10);
  });

  it("includes core systems", () => {
    expect(ANATOMY_SYSTEMS).toContain("skeleton");
    expect(ANATOMY_SYSTEMS).toContain("muscular");
    expect(ANATOMY_SYSTEMS).toContain("circulatory");
    expect(ANATOMY_SYSTEMS).toContain("nervous");
    expect(ANATOMY_SYSTEMS).toContain("respiratory");
  });
});

describe("ANATOMY_REGIONS", () => {
  it("has at least 8 regions", () => {
    expect(ANATOMY_REGIONS.length).toBeGreaterThanOrEqual(8);
  });

  it("includes core regions", () => {
    expect(ANATOMY_REGIONS).toContain("head");
    expect(ANATOMY_REGIONS).toContain("torso");
    expect(ANATOMY_REGIONS).toContain("full");
  });
});

describe("generateAnatomyViewer", () => {
  it("generates valid Kotlin code for skeleton system", () => {
    const code = generateAnatomyViewer({ system: "skeleton" });
    expect(code).toContain("package com.example.medical.anatomy");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
    expect(code).toContain("rememberModelLoader");
    expect(code).toContain("rememberModelInstance");
    expect(code).toContain("ModelNode");
  });

  it("uses correct model path", () => {
    const code = generateAnatomyViewer({ system: "skeleton", region: "head" });
    expect(code).toContain("models/anatomy/skeleton_head.glb");
  });

  it("uses full-body path for full-body system", () => {
    const code = generateAnatomyViewer({ system: "full-body" });
    expect(code).toContain("models/anatomy/full_body.glb");
  });

  it("generates AR code when ar=true", () => {
    const code = generateAnatomyViewer({ system: "skeleton", ar: true });
    expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("arsceneview:4.53.0");
    expect(code).toContain("android.permission.CAMERA");
  });

  it("includes transparency slider when transparent=true", () => {
    const code = generateAnatomyViewer({ system: "skeleton", transparent: true });
    expect(code).toContain("opacity");
    expect(code).toContain("Slider");
  });

  it("excludes transparency when transparent=false", () => {
    const code = generateAnatomyViewer({ system: "skeleton", transparent: false });
    expect(code).not.toContain("onOpacityChange");
  });

  it("includes exploded view slider when exploded=true", () => {
    const code = generateAnatomyViewer({ system: "skeleton", exploded: true });
    expect(code).toContain("explodeFactor");
    expect(code).toContain("Exploded View");
  });

  it("includes labels overlay when labels=true", () => {
    const code = generateAnatomyViewer({ system: "skeleton", labels: true });
    expect(code).toContain("AnatomyLabelsOverlay");
  });

  it("excludes labels when labels=false", () => {
    const code = generateAnatomyViewer({ system: "skeleton", labels: false });
    expect(code).not.toContain("AnatomyLabelsOverlay");
  });

  it("includes LightNode with its type and named apply parameter", () => {
    const code = generateAnatomyViewer({ system: "skeleton" });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("type = LightManager.Type.");
    expect(code).toContain("import com.google.android.filament.LightManager");
    expect(code).toContain("intensity(");
  });

  it("includes loading indicator", () => {
    const code = generateAnatomyViewer({ system: "skeleton" });
    expect(code).toContain("CircularProgressIndicator");
    expect(code).toContain("modelInstance == null");
  });

  it("handles null modelInstance", () => {
    const code = generateAnatomyViewer({ system: "skeleton" });
    expect(code).toContain("modelInstance?.let");
  });

  it("generates code for every system", () => {
    for (const system of ANATOMY_SYSTEMS) {
      const code = generateAnatomyViewer({ system });
      expect(code).toContain("@Composable");
      expect(code).toContain("SceneView(");
    }
  });

  it("generates code for every region", () => {
    for (const region of ANATOMY_REGIONS) {
      const code = generateAnatomyViewer({ system: "skeleton", region });
      expect(code).toContain("@Composable");
    }
  });
});
