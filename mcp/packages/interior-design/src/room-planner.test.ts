import { describe, it, expect } from "vitest";
import {
  generateRoomPlanner,
  ROOM_TYPES,
  WALL_STYLES,
  FLOOR_STYLES,
} from "./room-planner.js";

describe("ROOM_TYPES", () => {
  it("has at least 10 room types", () => {
    expect(ROOM_TYPES.length).toBeGreaterThanOrEqual(10);
  });

  it("includes core room types", () => {
    expect(ROOM_TYPES).toContain("living-room");
    expect(ROOM_TYPES).toContain("bedroom");
    expect(ROOM_TYPES).toContain("kitchen");
    expect(ROOM_TYPES).toContain("bathroom");
    expect(ROOM_TYPES).toContain("office");
  });
});

describe("WALL_STYLES", () => {
  it("has at least 4 wall styles", () => {
    expect(WALL_STYLES.length).toBeGreaterThanOrEqual(4);
  });

  it("includes standard and brick", () => {
    expect(WALL_STYLES).toContain("standard");
    expect(WALL_STYLES).toContain("brick");
  });
});

describe("FLOOR_STYLES", () => {
  it("has at least 6 floor styles", () => {
    expect(FLOOR_STYLES.length).toBeGreaterThanOrEqual(6);
  });

  it("includes hardwood and tile", () => {
    expect(FLOOR_STYLES).toContain("hardwood");
    expect(FLOOR_STYLES).toContain("tile");
  });
});

describe("generateRoomPlanner", () => {
  it("generates valid Kotlin code for living room", () => {
    const code = generateRoomPlanner({ roomType: "living-room" });
    expect(code).toContain("package com.example.interior.planner");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
    expect(code).toContain("rememberModelLoader");
    expect(code).toContain("CubeNode");
  });

  it("includes room dimensions in output", () => {
    const code = generateRoomPlanner({ roomType: "bedroom", widthMeters: 3.5 });
    expect(code).toContain("3.5f");
  });

  it("generates wall toggles", () => {
    const code = generateRoomPlanner({ roomType: "kitchen" });
    expect(code).toContain("showWalls");
    expect(code).toContain("showFloor");
    expect(code).toContain("showCeiling");
    expect(code).toContain("Switch");
  });

  it("includes LightNode with named apply parameter", () => {
    const code = generateRoomPlanner({ roomType: "living-room" });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("intensity(");
  });

  it("generates AR code when ar=true", () => {
    const code = generateRoomPlanner({ roomType: "living-room", ar: true });
    expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("arsceneview:3.6.0");
    expect(code).toContain("android.permission.CAMERA");
  });

  it("AR version uses 1:10 scale", () => {
    const code = generateRoomPlanner({ roomType: "bedroom", ar: true });
    expect(code).toContain("0.1f");
    expect(code).toContain("1:10");
  });

  it("generates code for every room type", () => {
    for (const roomType of ROOM_TYPES) {
      const code = generateRoomPlanner({ roomType });
      expect(code).toContain("@Composable");
      expect(code).toContain("SceneView(");
    }
  });

  it("respects custom dimensions", () => {
    const code = generateRoomPlanner({
      roomType: "office",
      widthMeters: 6.0,
      lengthMeters: 8.0,
      heightMeters: 3.0,
    });
    expect(code).toContain("6f");
    expect(code).toContain("8f");
    expect(code).toContain("3f");
  });

  it("includes environment loader", () => {
    const code = generateRoomPlanner({ roomType: "living-room" });
    expect(code).toContain("rememberEnvironmentLoader");
    expect(code).toContain("createHDREnvironment");
  });

  it("generates walls with CubeNode", () => {
    const code = generateRoomPlanner({ roomType: "living-room" });
    expect(code).toContain("// Back wall");
    expect(code).toContain("// Left wall");
    expect(code).toContain("// Right wall");
  });
});
