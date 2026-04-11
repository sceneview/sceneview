import { describe, it, expect } from "vitest";
import {
  generateCharacterViewer,
  CHARACTER_STYLES,
  ANIMATION_STATES,
} from "./character-viewer.js";

describe("CHARACTER_STYLES", () => {
  it("has at least 7 styles", () => {
    expect(CHARACTER_STYLES.length).toBeGreaterThanOrEqual(7);
  });

  it("includes core styles", () => {
    expect(CHARACTER_STYLES).toContain("humanoid");
    expect(CHARACTER_STYLES).toContain("cartoon");
    expect(CHARACTER_STYLES).toContain("robot");
    expect(CHARACTER_STYLES).toContain("creature");
    expect(CHARACTER_STYLES).toContain("fantasy");
  });
});

describe("ANIMATION_STATES", () => {
  it("has at least 7 states", () => {
    expect(ANIMATION_STATES.length).toBeGreaterThanOrEqual(7);
  });

  it("includes core animation states", () => {
    expect(ANIMATION_STATES).toContain("idle");
    expect(ANIMATION_STATES).toContain("walk");
    expect(ANIMATION_STATES).toContain("run");
    expect(ANIMATION_STATES).toContain("jump");
    expect(ANIMATION_STATES).toContain("attack");
  });
});

describe("generateCharacterViewer", () => {
  it("generates valid Kotlin code for humanoid style", () => {
    const code = generateCharacterViewer({ style: "humanoid" });
    expect(code).toContain("package com.example.gaming.character");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
    expect(code).toContain("rememberModelLoader");
    expect(code).toContain("rememberModelInstance");
    expect(code).toContain("ModelNode");
  });

  it("uses correct model path", () => {
    const code = generateCharacterViewer({ style: "robot" });
    expect(code).toContain("models/characters/robot_character.glb");
  });

  it("generates animation state management", () => {
    const code = generateCharacterViewer({ style: "humanoid", animations: ["idle", "walk", "run"] });
    expect(code).toContain("currentAnimation");
    expect(code).toContain("animator");
  });

  it("generates buttons for each animation", () => {
    const code = generateCharacterViewer({ style: "humanoid", animations: ["idle", "walk", "run", "jump"] });
    expect(code).toContain('"idle"');
    expect(code).toContain('"walk"');
    expect(code).toContain('"run"');
    expect(code).toContain('"jump"');
  });

  it("generates AR code when ar=true", () => {
    const code = generateCharacterViewer({ style: "humanoid", ar: true });
    expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("android.permission.CAMERA");
    expect(code).toContain("arsceneview:3.6.0");
  });

  it("includes auto-rotate when autoRotate=true", () => {
    const code = generateCharacterViewer({ style: "humanoid", autoRotate: true });
    expect(code).toContain("rotationY");
    expect(code).toContain("Rotation");
  });

  it("excludes auto-rotate when autoRotate=false", () => {
    const code = generateCharacterViewer({ style: "humanoid", autoRotate: false });
    expect(code).not.toContain("rotationY");
  });

  it("includes controls when showControls=true", () => {
    const code = generateCharacterViewer({ style: "humanoid", showControls: true });
    expect(code).toContain("Animation Controls");
    expect(code).toContain("Button(");
  });

  it("excludes controls when showControls=false", () => {
    const code = generateCharacterViewer({ style: "humanoid", showControls: false });
    expect(code).not.toContain("Animation Controls");
  });

  it("includes LightNode with named apply parameter", () => {
    const code = generateCharacterViewer({ style: "humanoid" });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("intensity(");
  });

  it("includes three-point lighting", () => {
    const code = generateCharacterViewer({ style: "humanoid" });
    const lightCount = (code.match(/LightNode\(/g) || []).length;
    expect(lightCount).toBeGreaterThanOrEqual(3);
  });

  it("includes loading indicator", () => {
    const code = generateCharacterViewer({ style: "humanoid" });
    expect(code).toContain("CircularProgressIndicator");
    expect(code).toContain("modelInstance == null");
  });

  it("handles null modelInstance", () => {
    const code = generateCharacterViewer({ style: "humanoid" });
    expect(code).toContain("modelInstance?.let");
  });

  it("generates code for every style", () => {
    for (const style of CHARACTER_STYLES) {
      const code = generateCharacterViewer({ style });
      expect(code).toContain("@Composable");
      expect(code).toContain("SceneView(");
    }
  });

  it("AR version includes placement instructions", () => {
    const code = generateCharacterViewer({ style: "cartoon", ar: true });
    expect(code).toContain("tap to place character");
    expect(code).toContain("placed");
  });

  it("default animations are idle, walk, run", () => {
    const code = generateCharacterViewer({ style: "humanoid" });
    expect(code).toContain('"idle"');
    expect(code).toContain('"walk"');
    expect(code).toContain('"run"');
  });
});
