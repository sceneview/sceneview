import { describe, it, expect } from "vitest";
import {
  generateLightingDesign,
  LIGHT_TYPES,
  COLOR_TEMPERATURES,
  type LightType,
} from "./lighting-design.js";

describe("LIGHT_TYPES", () => {
  it("has at least 10 light types", () => {
    expect(LIGHT_TYPES.length).toBeGreaterThanOrEqual(10);
  });

  it("includes core light types", () => {
    expect(LIGHT_TYPES).toContain("ambient");
    expect(LIGHT_TYPES).toContain("spot");
    expect(LIGHT_TYPES).toContain("accent");
    expect(LIGHT_TYPES).toContain("pendant");
    expect(LIGHT_TYPES).toContain("recessed");
    expect(LIGHT_TYPES).toContain("natural");
  });
});

describe("COLOR_TEMPERATURES", () => {
  it("has at least 4 color temperatures", () => {
    expect(COLOR_TEMPERATURES.length).toBeGreaterThanOrEqual(4);
  });

  it("includes warm-white and daylight", () => {
    expect(COLOR_TEMPERATURES).toContain("warm-white");
    expect(COLOR_TEMPERATURES).toContain("daylight");
    expect(COLOR_TEMPERATURES).toContain("candlelight");
  });
});

describe("generateLightingDesign", () => {
  describe.each([false, true])("fixture types with ar=%s", (ar) => {
    const fixtures: [LightType, string, string | null][] = [
      ["ambient", "POINT", "0f, 2.5f, 0f"],
      ["spot", "SPOT", "0f, 2.4f, 0f"],
      ["accent", "SPOT", "1.5f, 1.5f, -1.5f"],
      ["pendant", "POINT", "0f, 2.2f, 0f"],
      ["recessed", "SPOT", "0f, 2.6f, 0f"],
      ["track", "SPOT", "-1f, 2.4f, 0f"],
      ["sconce", "POINT", "2f, 1.8f, -2f"],
      ["floor-lamp", "POINT", "1.5f, 1.4f, 1.5f"],
      ["table-lamp", "POINT", "0.8f, 0.9f, -0.5f"],
      ["chandelier", "POINT", "0f, 2.0f, 0f"],
      ["strip-led", "POINT", "0f, 2.5f, -2f"],
      ["natural", "DIRECTIONAL", null],
    ];

    it.each(fixtures)("emits %s as %s", (light, type, position) => {
      const code = generateLightingDesign({ lights: [light], ar });
      expect(code.match(/type = LightManager.Type.\w+/g)).toEqual([
        `type = LightManager.Type.${type}`,
      ]);
      if (position) {
        expect(code).toContain("import io.github.sceneview.math.Position");
        expect(code).toContain(`position = Position(${position})`);
      } else {
        expect(code).not.toContain("position = Position(");
      }
      if (type === "SPOT") {
        expect(code).toContain("spotLightCone(0.5f, 0.8f)");
      } else {
        expect(code).not.toContain("spotLightCone(");
      }
      if (type === "POINT") {
        expect(code).not.toContain("direction(");
      } else {
        expect(code).toContain("direction(0f, -1f, 0f)");
      }
    });

    it("keeps the types and positions of a mixed lighting setup", () => {
      const code = generateLightingDesign({ lights: LIGHT_TYPES, ar });
      expect(code.match(/type = LightManager.Type.\w+/g)).toEqual(
        fixtures.map(([, type]) => `type = LightManager.Type.${type}`),
      );
      expect(code.match(/position = Position\(/g)).toHaveLength(11);
      expect(code.match(/spotLightCone\(/g)).toHaveLength(4);
    });
  });

  it("generates valid Kotlin code for ambient + spot", () => {
    const code = generateLightingDesign({ lights: ["ambient", "spot"] });
    expect(code).toContain("package com.example.interior.lighting");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
  });

  it("generates LightNode for each light type", () => {
    const code = generateLightingDesign({
      lights: ["ambient", "spot", "accent"],
    });
    expect(code).toContain("Ambient light");
    expect(code).toContain("Spot light");
    expect(code).toContain("Accent light");
  });

  it("uses LightNode with its type and named apply parameter", () => {
    const code = generateLightingDesign({ lights: ["pendant"] });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("type = LightManager.Type.");
    expect(code).toContain("import com.google.android.filament.LightManager");
    expect(code).toContain("intensity(");
  });

  it("includes dimmer sliders when dimmable=true", () => {
    const code = generateLightingDesign({
      lights: ["ambient", "spot"],
      dimmable: true,
    });
    expect(code).toContain("Slider");
    expect(code).toContain("light0Intensity");
    expect(code).toContain("light1Intensity");
  });

  it("excludes dimmer sliders when dimmable=false", () => {
    const code = generateLightingDesign({
      lights: ["ambient"],
      dimmable: false,
    });
    expect(code).not.toContain("light0Intensity");
  });

  it("uses warm-white color by default", () => {
    const code = generateLightingDesign({ lights: ["ambient"] });
    expect(code).toContain("warm-white");
    expect(code).toContain("2700K");
  });

  it("uses candlelight color when specified", () => {
    const code = generateLightingDesign({
      lights: ["table-lamp"],
      colorTemperature: "candlelight",
    });
    expect(code).toContain("candlelight");
    expect(code).toContain("1900K");
  });

  it("uses daylight color when specified", () => {
    const code = generateLightingDesign({
      lights: ["natural"],
      colorTemperature: "daylight",
    });
    expect(code).toContain("daylight");
    expect(code).toContain("6500K");
  });

  it("generates AR code when ar=true", () => {
    const code = generateLightingDesign({
      lights: ["ambient", "spot"],
      ar: true,
    });
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
    expect(code).toContain("android.permission.CAMERA");
  });

  it("handles loading state", () => {
    const code = generateLightingDesign({ lights: ["pendant"] });
    expect(code).toContain("CircularProgressIndicator");
    expect(code).toContain("modelInstance == null");
  });

  it("uses custom room model path", () => {
    const code = generateLightingDesign({
      lights: ["ambient"],
      roomModel: "models/my_room.glb",
    });
    expect(code).toContain("models/my_room.glb");
  });

  it("generates code for every light type individually", () => {
    for (const light of LIGHT_TYPES) {
      const code = generateLightingDesign({ lights: [light] });
      expect(code).toContain("@Composable");
      expect(code).toContain("LightNode");
    }
  });

  it("uses neutral-white color temperature (4000K)", () => {
    const code = generateLightingDesign({
      lights: ["ambient"],
      colorTemperature: "neutral-white",
    });
    expect(code).toContain("neutral-white");
    expect(code).toContain("4000K");
  });

  it("uses cool-white color temperature (5000K)", () => {
    const code = generateLightingDesign({
      lights: ["track"],
      colorTemperature: "cool-white",
    });
    expect(code).toContain("cool-white");
    expect(code).toContain("5000K");
  });

  it("uses default room model when none specified", () => {
    const code = generateLightingDesign({ lights: ["sconce"] });
    expect(code).toContain("models/room/interior_scene.glb");
  });

  it("COLOR_TEMPERATURES has 5 options", () => {
    expect(COLOR_TEMPERATURES.length).toBe(5);
    expect(COLOR_TEMPERATURES).toContain("neutral-white");
    expect(COLOR_TEMPERATURES).toContain("cool-white");
  });

  it("LIGHT_TYPES includes track, sconce, chandelier, strip-led", () => {
    expect(LIGHT_TYPES).toContain("track");
    expect(LIGHT_TYPES).toContain("sconce");
    expect(LIGHT_TYPES).toContain("chandelier");
    expect(LIGHT_TYPES).toContain("strip-led");
  });
});
