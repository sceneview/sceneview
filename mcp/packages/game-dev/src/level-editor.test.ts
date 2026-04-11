import { describe, it, expect } from "vitest";
import {
  generateLevelEditor,
  getThemeColors,
  LEVEL_THEMES,
  GEOMETRY_TYPES,
} from "./level-editor.js";

describe("LEVEL_THEMES", () => {
  it("has at least 8 themes", () => {
    expect(LEVEL_THEMES.length).toBeGreaterThanOrEqual(8);
  });

  it("includes core themes", () => {
    expect(LEVEL_THEMES).toContain("dungeon");
    expect(LEVEL_THEMES).toContain("forest");
    expect(LEVEL_THEMES).toContain("space");
    expect(LEVEL_THEMES).toContain("city");
    expect(LEVEL_THEMES).toContain("lava");
  });
});

describe("GEOMETRY_TYPES", () => {
  it("has at least 8 geometry types", () => {
    expect(GEOMETRY_TYPES.length).toBeGreaterThanOrEqual(8);
  });

  it("includes core geometries", () => {
    expect(GEOMETRY_TYPES).toContain("cube");
    expect(GEOMETRY_TYPES).toContain("sphere");
    expect(GEOMETRY_TYPES).toContain("cylinder");
    expect(GEOMETRY_TYPES).toContain("plane");
    expect(GEOMETRY_TYPES).toContain("wall");
  });
});

describe("getThemeColors", () => {
  it("returns colors for every theme", () => {
    for (const theme of LEVEL_THEMES) {
      const colors = getThemeColors(theme);
      expect(colors.primary).toBeTruthy();
      expect(colors.accent).toBeTruthy();
      expect(colors.lightIntensity).toBeGreaterThan(0);
      expect(colors.lightColor).toBeTruthy();
      expect(colors.fillIntensity).toBeGreaterThan(0);
      expect(colors.fillColor).toBeTruthy();
    }
  });

  it("dungeon has warm torch lighting", () => {
    const colors = getThemeColors("dungeon");
    expect(colors.lightIntensity).toBeLessThan(80_000);
    expect(colors.accent).toContain("orange");
  });

  it("space has cooler lighting", () => {
    const colors = getThemeColors("space");
    expect(colors.lightIntensity).toBeLessThan(60_000);
  });

  it("desert has bright lighting", () => {
    const colors = getThemeColors("desert");
    expect(colors.lightIntensity).toBeGreaterThanOrEqual(100_000);
  });
});

describe("generateLevelEditor", () => {
  it("generates valid Kotlin code for dungeon theme", () => {
    const code = generateLevelEditor({ theme: "dungeon" });
    expect(code).toContain("package com.example.gaming.level");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
    expect(code).toContain("CubeNode");
  });

  it("uses procedural geometry nodes", () => {
    const code = generateLevelEditor({ theme: "forest" });
    expect(code).toContain("CubeNode");
    expect(code).toContain("SphereNode");
    expect(code).toContain("CylinderNode");
  });

  it("includes grid floor when showGrid=true", () => {
    const code = generateLevelEditor({ theme: "dungeon", showGrid: true });
    expect(code).toContain("Grid floor");
  });

  it("excludes grid when showGrid=false", () => {
    const code = generateLevelEditor({ theme: "dungeon", showGrid: false });
    expect(code).not.toContain("Grid floor");
  });

  it("includes geometry palette when editable=true", () => {
    const code = generateLevelEditor({ theme: "dungeon", editable: true });
    expect(code).toContain("Geometry Palette");
    expect(code).toContain("selectedGeometry");
  });

  it("excludes palette when editable=false", () => {
    const code = generateLevelEditor({ theme: "dungeon", editable: false });
    expect(code).not.toContain("Geometry Palette");
  });

  it("uses custom grid size", () => {
    const code = generateLevelEditor({ theme: "dungeon", gridSize: 20 });
    expect(code).toContain("20");
  });

  it("generates AR code when ar=true", () => {
    const code = generateLevelEditor({ theme: "dungeon", ar: true });
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("android.permission.CAMERA");
    expect(code).toContain("arsceneview:3.6.0");
  });

  it("includes LightNode with named apply parameter", () => {
    const code = generateLevelEditor({ theme: "forest" });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("intensity(");
  });

  it("includes block count display", () => {
    const code = generateLevelEditor({ theme: "dungeon" });
    expect(code).toContain("Block count");
  });

  it("generates code for every theme", () => {
    for (const theme of LEVEL_THEMES) {
      const code = generateLevelEditor({ theme });
      expect(code).toContain("@Composable");
      expect(code).toContain("SceneView(");
    }
  });

  it("includes materialLoader for geometry nodes", () => {
    const code = generateLevelEditor({ theme: "dungeon" });
    expect(code).toContain("rememberMaterialLoader");
    expect(code).toContain("materialLoader");
  });

  it("generates procedural level data", () => {
    const code = generateLevelEditor({ theme: "dungeon" });
    expect(code).toContain("generateProceduralLevel");
    expect(code).toContain("LevelBlock");
  });
});
