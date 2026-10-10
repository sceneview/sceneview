import { describe, it, expect } from "vitest";
import {
  generateArShowroom,
  SHOWROOM_LOCATIONS,
  SHOWROOM_FEATURES,
} from "./ar-showroom.js";

describe("SHOWROOM_LOCATIONS", () => {
  it("has at least 4 locations", () => {
    expect(SHOWROOM_LOCATIONS.length).toBeGreaterThanOrEqual(4);
  });

  it("includes core locations", () => {
    expect(SHOWROOM_LOCATIONS).toContain("driveway");
    expect(SHOWROOM_LOCATIONS).toContain("parking-lot");
    expect(SHOWROOM_LOCATIONS).toContain("garage");
    expect(SHOWROOM_LOCATIONS).toContain("showroom-floor");
  });
});

describe("SHOWROOM_FEATURES", () => {
  it("has at least 5 features", () => {
    expect(SHOWROOM_FEATURES.length).toBeGreaterThanOrEqual(5);
  });

  it("includes core features", () => {
    expect(SHOWROOM_FEATURES).toContain("walk-around");
    expect(SHOWROOM_FEATURES).toContain("open-doors");
    expect(SHOWROOM_FEATURES).toContain("color-swap");
    expect(SHOWROOM_FEATURES).toContain("photo-capture");
  });
});

describe("generateArShowroom", () => {
  it("generates valid Kotlin AR code", () => {
    const code = generateArShowroom({ location: "driveway" });
    expect(code).toContain("package com.example.automotive.showroom");
    expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
    expect(code).toContain("ARSceneView(");
  });

  it("includes camera permission requirements", () => {
    const code = generateArShowroom({ location: "driveway" });
    expect(code).toContain("android.permission.CAMERA");
    expect(code).toContain("android.hardware.camera.ar");
  });

  it("includes placement instruction with location", () => {
    const code = generateArShowroom({ location: "parking-lot" });
    expect(code).toContain("parking lot");
    expect(code).toContain("tap to place");
  });

  it("uses real-world scale by default", () => {
    const code = generateArShowroom({ location: "driveway", realScale: true });
    expect(code).toContain("scaleToUnits = 4.5f");
  });

  it("uses table-top scale when realScale=false", () => {
    const code = generateArShowroom({ location: "driveway", realScale: false });
    expect(code).toContain("scaleToUnits = 2.0f");
  });

  it("includes color swap controls when enabled", () => {
    const code = generateArShowroom({
      location: "driveway",
      features: ["color-swap"],
    });
    expect(code).toContain("carColors");
    expect(code).toContain("selectedColor");
    expect(code).toContain("Color");
  });

  it("includes open doors toggle when enabled", () => {
    const code = generateArShowroom({
      location: "driveway",
      features: ["open-doors"],
    });
    expect(code).toContain("doorsOpen");
    expect(code).toContain("Open Doors");
    expect(code).toContain("Switch");
  });

  it("includes night lighting when enabled", () => {
    const code = generateArShowroom({
      location: "driveway",
      features: ["night-lighting"],
    });
    expect(code).toContain("nightMode");
    expect(code).toContain("Night Mode");
  });

  it("includes measurement button when enabled", () => {
    const code = generateArShowroom({
      location: "driveway",
      features: ["measurements"],
    });
    expect(code).toContain("Measure");
  });

  it("includes photo capture button when enabled", () => {
    const code = generateArShowroom({
      location: "driveway",
      features: ["photo-capture"],
    });
    expect(code).toContain("Take Photo");
  });

  it("includes shadow lighting when shadows=true", () => {
    const code = generateArShowroom({ location: "driveway", shadows: true });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("type = LightManager.Type.");
    expect(code).toContain("import com.google.android.filament.LightManager");
    expect(code).toContain("intensity(");
  });

  it("handles null model instance", () => {
    const code = generateArShowroom({ location: "driveway" });
    expect(code).toContain("carModel?.let");
  });

  it("includes plane renderer for placement", () => {
    const code = generateArShowroom({ location: "driveway" });
    expect(code).toContain("planeRenderer");
  });

  it("generates code for every location", () => {
    for (const location of SHOWROOM_LOCATIONS) {
      const code = generateArShowroom({ location });
      expect(code).toContain("@Composable");
      expect(code).toContain("ARSceneView(");
    }
  });

  it("uses showroom car model path", () => {
    const code = generateArShowroom({ location: "driveway" });
    expect(code).toContain("models/cars/showroom_car.glb");
  });
});
