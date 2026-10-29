import { describe, it, expect } from "vitest";
import {
  generateCarConfigurator,
  CAR_BODY_STYLES,
  CAMERA_PRESETS,
  COLOR_CATEGORIES,
} from "./car-configurator.js";

describe("CAR_BODY_STYLES", () => {
  it("has at least 8 body styles", () => {
    expect(CAR_BODY_STYLES.length).toBeGreaterThanOrEqual(8);
  });

  it("includes core body styles", () => {
    expect(CAR_BODY_STYLES).toContain("sedan");
    expect(CAR_BODY_STYLES).toContain("suv");
    expect(CAR_BODY_STYLES).toContain("coupe");
    expect(CAR_BODY_STYLES).toContain("sports");
    expect(CAR_BODY_STYLES).toContain("electric");
  });
});

describe("CAMERA_PRESETS", () => {
  it("has at least 8 presets", () => {
    expect(CAMERA_PRESETS.length).toBeGreaterThanOrEqual(8);
  });

  it("includes exterior and interior presets", () => {
    expect(CAMERA_PRESETS).toContain("exterior-front");
    expect(CAMERA_PRESETS).toContain("interior-driver");
    expect(CAMERA_PRESETS).toContain("detail-wheel");
  });
});

describe("COLOR_CATEGORIES", () => {
  it("has at least 4 categories", () => {
    expect(COLOR_CATEGORIES.length).toBeGreaterThanOrEqual(4);
  });

  it("includes metallic and matte", () => {
    expect(COLOR_CATEGORIES).toContain("metallic");
    expect(COLOR_CATEGORIES).toContain("matte");
  });
});

describe("generateCarConfigurator", () => {
  it("generates valid Kotlin code for sedan", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan" });
    expect(code).toContain("package com.example.automotive.configurator");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
    expect(code).toContain("rememberModelLoader");
    expect(code).toContain("rememberModelInstance");
    expect(code).toContain("ModelNode");
  });

  it("uses correct model path for body style", () => {
    const code = generateCarConfigurator({ bodyStyle: "suv" });
    expect(code).toContain("models/cars/suv_car.glb");
  });

  it("generates AR code when ar=true", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan", ar: true });
    expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("arsceneview:4.53.0");
    expect(code).toContain("android.permission.CAMERA");
  });

  it("includes color picker when colorPicker=true", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan", colorPicker: true });
    expect(code).toContain("Exterior Color");
    expect(code).toContain("carColors");
    expect(code).toContain("selectedColor");
  });

  it("excludes color picker when colorPicker=false", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan", colorPicker: false });
    expect(code).not.toContain("Exterior Color");
  });

  it("includes material variants when materialVariants=true", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan", materialVariants: true });
    expect(code).toContain("Paint Finish");
    expect(code).toContain("Metallic");
    expect(code).toContain("Matte");
  });

  it("excludes material variants when materialVariants=false", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan", materialVariants: false });
    expect(code).not.toContain("Paint Finish");
  });

  it("includes turntable when turntable=true", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan", turntable: true });
    expect(code).toContain("rotationAngle");
    expect(code).toContain("Auto-Rotate");
  });

  it("excludes turntable when turntable=false", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan", turntable: false });
    expect(code).not.toContain("autoRotate");
  });

  it("includes camera preset bar", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan" });
    expect(code).toContain("CameraPresetBar");
    expect(code).toContain("FilterChip");
  });

  it("includes LightNode with its type and named apply parameter", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan" });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("type = LightManager.Type.");
    expect(code).toContain("import com.google.android.filament.LightManager");
    expect(code).toContain("intensity(");
  });

  it("includes loading indicator", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan" });
    expect(code).toContain("CircularProgressIndicator");
    expect(code).toContain("modelInstance == null");
  });

  it("handles null modelInstance", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan" });
    expect(code).toContain("modelInstance?.let");
  });

  it("generates code for every body style", () => {
    for (const style of CAR_BODY_STYLES) {
      const code = generateCarConfigurator({ bodyStyle: style });
      expect(code).toContain("@Composable");
      expect(code).toContain("SceneView(");
    }
  });

  it("uses studio HDR environment", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan" });
    expect(code).toContain("studio_hdr.ktx");
  });

  it("includes three-point lighting", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan" });
    const lightCount = (code.match(/LightNode\(/g) || []).length;
    expect(lightCount).toBeGreaterThanOrEqual(3);
  });

  it("uses real-world scale for AR mode", () => {
    const code = generateCarConfigurator({ bodyStyle: "sedan", ar: true });
    expect(code).toContain("scaleToUnits = 4.5f");
  });

  it("includes custom camera presets", () => {
    const code = generateCarConfigurator({
      bodyStyle: "sedan",
      cameraPresets: ["exterior-front", "detail-wheel"],
    });
    expect(code).toContain("exterior-front");
    expect(code).toContain("detail-wheel");
  });
});
