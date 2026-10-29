import { describe, it, expect } from "vitest";
import {
  generatePartsCatalog,
  PART_CATEGORIES,
  CATALOG_FEATURES,
} from "./parts-catalog.js";

describe("PART_CATEGORIES", () => {
  it("has at least 8 categories", () => {
    expect(PART_CATEGORIES.length).toBeGreaterThanOrEqual(8);
  });

  it("includes core categories", () => {
    expect(PART_CATEGORIES).toContain("engine");
    expect(PART_CATEGORIES).toContain("transmission");
    expect(PART_CATEGORIES).toContain("brakes");
    expect(PART_CATEGORIES).toContain("suspension");
    expect(PART_CATEGORIES).toContain("exhaust");
  });
});

describe("CATALOG_FEATURES", () => {
  it("has at least 5 features", () => {
    expect(CATALOG_FEATURES.length).toBeGreaterThanOrEqual(5);
  });

  it("includes core features", () => {
    expect(CATALOG_FEATURES).toContain("exploded-view");
    expect(CATALOG_FEATURES).toContain("part-selection");
    expect(CATALOG_FEATURES).toContain("detail-zoom");
    expect(CATALOG_FEATURES).toContain("cross-section");
  });
});

describe("generatePartsCatalog", () => {
  it("generates valid Kotlin code for engine parts", () => {
    const code = generatePartsCatalog({ category: "engine" });
    expect(code).toContain("package com.example.automotive.parts");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
    expect(code).toContain("rememberModelInstance");
  });

  it("uses correct model path for category", () => {
    const code = generatePartsCatalog({ category: "brakes" });
    expect(code).toContain("models/parts/brakes_assembly.glb");
  });

  it("includes exploded view slider when enabled", () => {
    const code = generatePartsCatalog({
      category: "engine",
      features: ["exploded-view"],
    });
    expect(code).toContain("explodeFactor");
    expect(code).toContain("Exploded View");
    expect(code).toContain("Slider");
  });

  it("includes part selection when enabled", () => {
    const code = generatePartsCatalog({
      category: "engine",
      features: ["part-selection"],
    });
    expect(code).toContain("selectedPart");
  });

  it("includes cross-section when enabled", () => {
    const code = generatePartsCatalog({
      category: "engine",
      features: ["cross-section"],
    });
    expect(code).toContain("crossSectionEnabled");
    expect(code).toContain("Cross-Section");
  });

  it("includes search when enabled", () => {
    const code = generatePartsCatalog({
      category: "engine",
      features: ["search"],
    });
    expect(code).toContain("searchQuery");
    expect(code).toContain("Search parts");
    expect(code).toContain("OutlinedTextField");
  });

  it("includes part numbers when partNumbers=true", () => {
    const code = generatePartsCatalog({ category: "engine", partNumbers: true });
    expect(code).toContain("partNumber");
    expect(code).toContain("Part#");
  });

  it("excludes part numbers when partNumbers=false", () => {
    const code = generatePartsCatalog({ category: "engine", partNumbers: false });
    expect(code).not.toContain("partNumber");
  });

  it("includes pricing when pricing=true", () => {
    const code = generatePartsCatalog({ category: "engine", pricing: true });
    expect(code).toContain("price");
    expect(code).toContain("$");
  });

  it("generates AR code when ar=true", () => {
    const code = generatePartsCatalog({ category: "engine", ar: true });
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("android.permission.CAMERA");
  });

  it("includes LightNode with its type and named apply parameter", () => {
    const code = generatePartsCatalog({ category: "engine" });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("type = LightManager.Type.");
    expect(code).toContain("import com.google.android.filament.LightManager");
    expect(code).toContain("intensity(");
  });

  it("includes loading indicator", () => {
    const code = generatePartsCatalog({ category: "engine" });
    expect(code).toContain("CircularProgressIndicator");
  });

  it("handles null model instance", () => {
    const code = generatePartsCatalog({ category: "engine" });
    expect(code).toContain("assemblyModel?.let");
  });

  it("includes parts data for engine", () => {
    const code = generatePartsCatalog({ category: "engine" });
    expect(code).toContain("Cylinder Head");
    expect(code).toContain("Crankshaft");
  });

  it("includes parts data for brakes", () => {
    const code = generatePartsCatalog({ category: "brakes" });
    expect(code).toContain("Front Caliper");
    expect(code).toContain("Brake Pad");
  });

  it("includes parts data for transmission", () => {
    const code = generatePartsCatalog({ category: "transmission" });
    expect(code).toContain("Gear Set");
    expect(code).toContain("Clutch Kit");
  });

  it("generates code for every part category", () => {
    for (const category of PART_CATEGORIES) {
      const code = generatePartsCatalog({ category });
      expect(code).toContain("@Composable");
    }
  });

  it("includes PartData data class", () => {
    const code = generatePartsCatalog({ category: "engine" });
    expect(code).toContain("data class PartData");
    expect(code).toContain("PartItem");
  });
});
