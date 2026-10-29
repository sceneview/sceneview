import { describe, it, expect } from "vitest";
import {
  generateSurgicalPlanning,
  SURGERY_TYPES,
  PLANNING_FEATURES,
} from "./surgical-planning.js";

describe("SURGERY_TYPES", () => {
  it("has at least 8 types", () => {
    expect(SURGERY_TYPES.length).toBeGreaterThanOrEqual(8);
  });

  it("includes core types", () => {
    expect(SURGERY_TYPES).toContain("orthopedic");
    expect(SURGERY_TYPES).toContain("cardiac");
    expect(SURGERY_TYPES).toContain("neurosurgery");
    expect(SURGERY_TYPES).toContain("spinal");
  });
});

describe("PLANNING_FEATURES", () => {
  it("has at least 6 features", () => {
    expect(PLANNING_FEATURES.length).toBeGreaterThanOrEqual(6);
  });

  it("includes measurement and annotation", () => {
    expect(PLANNING_FEATURES).toContain("measurement");
    expect(PLANNING_FEATURES).toContain("annotation");
    expect(PLANNING_FEATURES).toContain("cross-section");
    expect(PLANNING_FEATURES).toContain("implant-placement");
  });
});

describe("generateSurgicalPlanning", () => {
  it("generates valid Kotlin code for orthopedic surgery", () => {
    const code = generateSurgicalPlanning({ surgeryType: "orthopedic" });
    expect(code).toContain("package com.example.medical.surgical");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
  });

  it("uses correct model path", () => {
    const code = generateSurgicalPlanning({ surgeryType: "cardiac" });
    expect(code).toContain("models/surgical/cardiac_patient.glb");
  });

  it("includes implant model when implantModel=true", () => {
    const code = generateSurgicalPlanning({
      surgeryType: "orthopedic",
      implantModel: true,
    });
    expect(code).toContain("implant");
    expect(code).toContain("showImplant");
    expect(code).toContain("_implant.glb");
  });

  it("excludes implant when implantModel=false", () => {
    const code = generateSurgicalPlanning({
      surgeryType: "orthopedic",
      implantModel: false,
    });
    expect(code).not.toContain("showImplant");
  });

  it("includes pre-op comparison when preOpComparison=true", () => {
    const code = generateSurgicalPlanning({
      surgeryType: "orthopedic",
      preOpComparison: true,
    });
    expect(code).toContain("preOp");
    expect(code).toContain("showPreOp");
  });

  it("includes planning features as chips", () => {
    const code = generateSurgicalPlanning({
      surgeryType: "orthopedic",
      features: ["measurement", "annotation", "cross-section"],
    });
    expect(code).toContain("FilterChip");
    expect(code).toContain("measurement");
    expect(code).toContain("annotation");
    expect(code).toContain("cross-section");
  });

  it("includes cross-section slider when feature selected", () => {
    const code = generateSurgicalPlanning({
      surgeryType: "orthopedic",
      features: ["cross-section"],
    });
    expect(code).toContain("crossSectionPosition");
    expect(code).toContain("Slider");
  });

  it("generates AR code when ar=true", () => {
    const code = generateSurgicalPlanning({ surgeryType: "cardiac", ar: true });
    expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("android.permission.CAMERA");
  });

  it("AR version includes implant toggle when implantModel=true", () => {
    const code = generateSurgicalPlanning({
      surgeryType: "orthopedic",
      implantModel: true,
      ar: true,
    });
    expect(code).toContain("showImplant");
    expect(code).toContain("Switch");
  });

  it("includes clinical lighting (multiple light sources)", () => {
    const code = generateSurgicalPlanning({ surgeryType: "orthopedic" });
    const lightCount = (code.match(/LightNode\(/g) || []).length;
    expect(lightCount).toBeGreaterThanOrEqual(3);
  });

  it("includes LightNode with its type and named apply parameter", () => {
    const code = generateSurgicalPlanning({ surgeryType: "orthopedic" });
    expect(code).toContain("apply = {");
    expect(code).toContain("type = LightManager.Type.");
    expect(code).toContain("import com.google.android.filament.LightManager");
  });

  it("handles null modelInstance", () => {
    const code = generateSurgicalPlanning({ surgeryType: "orthopedic" });
    expect(code).toContain("patientModel?.let");
    expect(code).toContain("CircularProgressIndicator");
  });

  it("generates code for every surgery type", () => {
    for (const type of SURGERY_TYPES) {
      const code = generateSurgicalPlanning({ surgeryType: type });
      expect(code).toContain("@Composable");
    }
  });
});
