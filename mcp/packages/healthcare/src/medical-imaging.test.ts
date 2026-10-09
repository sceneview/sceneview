import { describe, it, expect } from "vitest";
import {
  generateMedicalImaging,
  IMAGING_MODALITIES,
  RENDERING_MODES,
} from "./medical-imaging.js";

describe("IMAGING_MODALITIES", () => {
  it("has 5 modalities", () => {
    expect(IMAGING_MODALITIES).toHaveLength(5);
  });

  it("includes CT and MRI", () => {
    expect(IMAGING_MODALITIES).toContain("ct");
    expect(IMAGING_MODALITIES).toContain("mri");
    expect(IMAGING_MODALITIES).toContain("pet");
  });
});

describe("RENDERING_MODES", () => {
  it("has 4 modes", () => {
    expect(RENDERING_MODES).toHaveLength(4);
  });

  it("includes surface and volume", () => {
    expect(RENDERING_MODES).toContain("surface");
    expect(RENDERING_MODES).toContain("volume");
  });
});

describe("generateMedicalImaging", () => {
  it("generates valid Kotlin code for CT", () => {
    const code = generateMedicalImaging({ modality: "ct" });
    expect(code).toContain("package com.example.medical.imaging");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
    expect(code).toContain("rememberModelInstance");
  });

  it("uses correct model path", () => {
    const code = generateMedicalImaging({ modality: "ct", bodyRegion: "head" });
    expect(code).toContain("models/imaging/ct_head.glb");
  });

  it("includes DICOM pipeline documentation", () => {
    const code = generateMedicalImaging({ modality: "ct" });
    expect(code).toContain("DICOM");
    expect(code).toContain("dcm4che");
    expect(code).toContain("marching cubes");
  });

  it("includes windowing controls when windowing=true", () => {
    const code = generateMedicalImaging({ modality: "ct", windowing: true });
    expect(code).toContain("windowCenter");
    expect(code).toContain("windowWidth");
    expect(code).toContain("Slider");
    expect(code).toContain("HU");
  });

  it("excludes windowing when windowing=false", () => {
    const code = generateMedicalImaging({ modality: "ct", windowing: false });
    expect(code).not.toContain("windowCenter");
  });

  it("includes segmentation overlay when segmentation=true", () => {
    const code = generateMedicalImaging({ modality: "ct", segmentation: true });
    expect(code).toContain("segmentation");
    expect(code).toContain("showSegmentation");
    expect(code).toContain("Switch");
  });

  it("excludes segmentation when segmentation=false", () => {
    const code = generateMedicalImaging({ modality: "ct", segmentation: false });
    expect(code).not.toContain("showSegmentation");
  });

  it("generates AR code when ar=true", () => {
    const code = generateMedicalImaging({ modality: "mri", ar: true });
    expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("android.permission.CAMERA");
  });

  it("includes LightNode with its type and named apply", () => {
    const code = generateMedicalImaging({ modality: "ct" });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("type = LightManager.Type.");
    expect(code).toContain("import com.google.android.filament.LightManager");
  });

  it("handles null modelInstance", () => {
    const code = generateMedicalImaging({ modality: "ct" });
    expect(code).toContain("modelInstance?.let");
    expect(code).toContain("CircularProgressIndicator");
  });

  it("uses correct default window center for CT (40 HU)", () => {
    const code = generateMedicalImaging({ modality: "ct", windowing: true });
    expect(code).toContain("40f");
  });

  it("uses correct default window width for CT (400 HU)", () => {
    const code = generateMedicalImaging({ modality: "ct", windowing: true });
    expect(code).toContain("400f");
  });

  it("generates code for every modality", () => {
    for (const modality of IMAGING_MODALITIES) {
      const code = generateMedicalImaging({ modality });
      expect(code).toContain("@Composable");
    }
  });

  it("includes loading text with modality name", () => {
    const code = generateMedicalImaging({ modality: "mri" });
    expect(code).toContain("Loading MRI scan");
  });
});
