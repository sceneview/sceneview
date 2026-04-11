import { describe, it, expect } from "vitest";
import {
  generatePhysicsGame,
  getGravityValue,
  PHYSICS_PRESETS,
  GRAVITY_MODES,
} from "./physics-game.js";

describe("PHYSICS_PRESETS", () => {
  it("has at least 7 presets", () => {
    expect(PHYSICS_PRESETS.length).toBeGreaterThanOrEqual(7);
  });

  it("includes core presets", () => {
    expect(PHYSICS_PRESETS).toContain("bouncing-balls");
    expect(PHYSICS_PRESETS).toContain("bowling");
    expect(PHYSICS_PRESETS).toContain("billiards");
    expect(PHYSICS_PRESETS).toContain("tower-collapse");
    expect(PHYSICS_PRESETS).toContain("cannon");
  });
});

describe("GRAVITY_MODES", () => {
  it("has at least 6 modes", () => {
    expect(GRAVITY_MODES.length).toBeGreaterThanOrEqual(6);
  });

  it("includes earth, moon, and zero-g", () => {
    expect(GRAVITY_MODES).toContain("earth");
    expect(GRAVITY_MODES).toContain("moon");
    expect(GRAVITY_MODES).toContain("zero-g");
  });
});

describe("getGravityValue", () => {
  it("returns -9.81 for earth", () => {
    expect(getGravityValue("earth")).toBeCloseTo(-9.81);
  });

  it("returns -1.62 for moon", () => {
    expect(getGravityValue("moon")).toBeCloseTo(-1.62);
  });

  it("returns 0 for zero-g", () => {
    expect(getGravityValue("zero-g")).toBe(0);
  });

  it("returns positive for reverse gravity", () => {
    expect(getGravityValue("reverse")).toBeGreaterThan(0);
  });

  it("jupiter has strongest gravity", () => {
    expect(Math.abs(getGravityValue("jupiter"))).toBeGreaterThan(Math.abs(getGravityValue("earth")));
  });
});

describe("generatePhysicsGame", () => {
  it("generates valid Kotlin code for bouncing-balls", () => {
    const code = generatePhysicsGame({ preset: "bouncing-balls" });
    expect(code).toContain("package com.example.gaming.physics");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
    expect(code).toContain("SphereNode");
    expect(code).toContain("CubeNode");
  });

  it("includes physics simulation", () => {
    const code = generatePhysicsGame({ preset: "bouncing-balls" });
    expect(code).toContain("updatePhysics");
    expect(code).toContain("PhysicsBody");
    expect(code).toContain("gravity");
    expect(code).toContain("bounciness");
  });

  it("includes collision detection", () => {
    const code = generatePhysicsGame({ preset: "bouncing-balls" });
    expect(code).toContain("collision");
    expect(code).toContain("impulse");
  });

  it("includes pause and reset controls", () => {
    const code = generatePhysicsGame({ preset: "bouncing-balls" });
    expect(code).toContain("paused");
    expect(code).toContain("Resume");
    expect(code).toContain("Pause");
    expect(code).toContain("Reset");
  });

  it("uses correct gravity value", () => {
    const code = generatePhysicsGame({ preset: "bouncing-balls", gravity: "moon" });
    expect(code).toContain("-1.62");
  });

  it("uses custom bounciness", () => {
    const code = generatePhysicsGame({ preset: "bouncing-balls", bounciness: 0.9 });
    expect(code).toContain("0.9");
  });

  it("includes trajectory when showTrajectory=true", () => {
    const code = generatePhysicsGame({ preset: "cannon", showTrajectory: true });
    expect(code).toContain("predictTrajectory");
    expect(code).toContain("Trajectory");
  });

  it("excludes trajectory when showTrajectory=false", () => {
    const code = generatePhysicsGame({ preset: "cannon", showTrajectory: false });
    expect(code).not.toContain("predictTrajectory");
  });

  it("generates AR code when ar=true", () => {
    const code = generatePhysicsGame({ preset: "bouncing-balls", ar: true });
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("android.permission.CAMERA");
    expect(code).toContain("arsceneview:3.6.0");
  });

  it("includes LightNode with named apply parameter", () => {
    const code = generatePhysicsGame({ preset: "bouncing-balls" });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("intensity(");
  });

  it("includes score tracking", () => {
    const code = generatePhysicsGame({ preset: "bowling" });
    expect(code).toContain("score");
    expect(code).toContain("Score:");
  });

  it("generates code for every preset", () => {
    for (const preset of PHYSICS_PRESETS) {
      const code = generatePhysicsGame({ preset });
      expect(code).toContain("@Composable");
      expect(code).toContain("SceneView(");
    }
  });

  it("bowling preset creates pins", () => {
    const code = generatePhysicsGame({ preset: "bowling" });
    expect(code).toContain("Bowling");
    expect(code).toContain("pin");
  });

  it("includes materialLoader for geometry", () => {
    const code = generatePhysicsGame({ preset: "bouncing-balls" });
    expect(code).toContain("rememberMaterialLoader");
    expect(code).toContain("materialLoader");
  });
});
