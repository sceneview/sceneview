import { describe, it, expect } from "vitest";
import {
  generateMoleculeViewer,
  MOLECULE_TYPES,
  MOLECULE_REPRESENTATIONS,
} from "./molecule-viewer.js";

describe("MOLECULE_TYPES", () => {
  it("has at least 7 types", () => {
    expect(MOLECULE_TYPES.length).toBeGreaterThanOrEqual(7);
  });

  it("includes core types", () => {
    expect(MOLECULE_TYPES).toContain("protein");
    expect(MOLECULE_TYPES).toContain("dna");
    expect(MOLECULE_TYPES).toContain("antibody");
  });
});

describe("MOLECULE_REPRESENTATIONS", () => {
  it("has 5 representations", () => {
    expect(MOLECULE_REPRESENTATIONS).toHaveLength(5);
  });

  it("includes ball-and-stick and ribbon", () => {
    expect(MOLECULE_REPRESENTATIONS).toContain("ball-and-stick");
    expect(MOLECULE_REPRESENTATIONS).toContain("ribbon");
    expect(MOLECULE_REPRESENTATIONS).toContain("space-filling");
  });
});

describe("generateMoleculeViewer", () => {
  it("generates valid Kotlin code for protein", () => {
    const code = generateMoleculeViewer({ moleculeType: "protein" });
    expect(code).toContain("package com.example.medical.molecule");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
    expect(code).toContain("rememberModelInstance");
    expect(code).toContain("ModelNode");
  });

  it("uses default model path without PDB ID", () => {
    const code = generateMoleculeViewer({ moleculeType: "protein" });
    expect(code).toContain("models/molecules/protein.glb");
  });

  it("uses PDB ID in model path when provided", () => {
    const code = generateMoleculeViewer({ moleculeType: "protein", pdbId: "1HHO" });
    expect(code).toContain("models/molecules/1hho.glb");
    expect(code).toContain("PDB ID: 1HHO");
  });

  it("generates AR code when ar=true", () => {
    const code = generateMoleculeViewer({ moleculeType: "dna", ar: true });
    expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("android.permission.CAMERA");
  });

  it("includes auto-rotate when animate=true", () => {
    const code = generateMoleculeViewer({ moleculeType: "protein", animate: true });
    expect(code).toContain("autoRotate");
    expect(code).toContain("rotationAngle");
  });

  it("excludes rotation when animate=false", () => {
    const code = generateMoleculeViewer({ moleculeType: "protein", animate: false });
    expect(code).not.toContain("autoRotate");
  });

  it("includes hydrogen toggle when showHydrogens=true", () => {
    const code = generateMoleculeViewer({ moleculeType: "protein", showHydrogens: true });
    expect(code).toContain("showHydrogens");
    expect(code).toContain("true");
  });

  it("includes representation selector controls", () => {
    const code = generateMoleculeViewer({ moleculeType: "protein" });
    expect(code).toContain("FilterChip");
    expect(code).toContain("ball-and-stick");
  });

  it("includes CPK color scheme documentation", () => {
    const code = generateMoleculeViewer({ moleculeType: "protein", colorScheme: "element" });
    expect(code).toContain("CPK");
  });

  it("includes LightNode with its type and named apply", () => {
    const code = generateMoleculeViewer({ moleculeType: "protein" });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("type = LightManager.Type.");
    expect(code).toContain("import com.google.android.filament.LightManager");
  });

  it("handles null modelInstance", () => {
    const code = generateMoleculeViewer({ moleculeType: "protein" });
    expect(code).toContain("modelInstance?.let");
    expect(code).toContain("CircularProgressIndicator");
  });

  it("generates code for every molecule type", () => {
    for (const type of MOLECULE_TYPES) {
      const code = generateMoleculeViewer({ moleculeType: type });
      expect(code).toContain("@Composable");
    }
  });

  it("includes RCSB PDB workflow documentation", () => {
    const code = generateMoleculeViewer({ moleculeType: "protein" });
    expect(code).toContain("RCSB PDB");
    expect(code).toContain("GLB");
  });
});
