import { describe, it, expect } from "vitest";
import {
  generateHudOverlay,
  HUD_ELEMENTS,
  HUD_STYLES,
} from "./hud-overlay.js";

describe("HUD_ELEMENTS", () => {
  it("has at least 6 elements", () => {
    expect(HUD_ELEMENTS.length).toBeGreaterThanOrEqual(6);
  });

  it("includes core HUD elements", () => {
    expect(HUD_ELEMENTS).toContain("speedometer");
    expect(HUD_ELEMENTS).toContain("navigation");
    expect(HUD_ELEMENTS).toContain("alerts");
    expect(HUD_ELEMENTS).toContain("fuel-gauge");
  });
});

describe("HUD_STYLES", () => {
  it("has at least 4 styles", () => {
    expect(HUD_STYLES.length).toBeGreaterThanOrEqual(4);
  });

  it("includes core styles", () => {
    expect(HUD_STYLES).toContain("minimal");
    expect(HUD_STYLES).toContain("sport");
    expect(HUD_STYLES).toContain("luxury");
  });
});

describe("generateHudOverlay", () => {
  it("generates valid Kotlin code with speedometer", () => {
    const code = generateHudOverlay({ elements: ["speedometer"] });
    expect(code).toContain("package com.example.automotive.hud");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
    expect(code).toContain("ViewNode");
  });

  it("renders speedometer with speed display", () => {
    const code = generateHudOverlay({ elements: ["speedometer"] });
    expect(code).toContain("speed");
    expect(code).toContain("km/h");
  });

  it("supports imperial units", () => {
    const code = generateHudOverlay({ elements: ["speedometer"], units: "imperial" });
    expect(code).toContain("mph");
  });

  it("includes navigation when requested", () => {
    const code = generateHudOverlay({ elements: ["navigation"] });
    expect(code).toContain("Turn right");
  });

  it("includes fuel gauge when requested", () => {
    const code = generateHudOverlay({ elements: ["fuel-gauge"] });
    expect(code).toContain("FUEL");
    expect(code).toContain("fuelLevel");
  });

  it("includes temperature when requested", () => {
    const code = generateHudOverlay({ elements: ["temperature"] });
    expect(code).toContain("TEMP");
    expect(code).toContain("engineTemp");
  });

  it("includes gear indicator when requested", () => {
    const code = generateHudOverlay({ elements: ["gear-indicator"] });
    expect(code).toContain("currentGear");
  });

  it("includes alerts when requested", () => {
    const code = generateHudOverlay({ elements: ["alerts"] });
    expect(code).toContain("alerts");
  });

  it("includes turn signals when requested", () => {
    const code = generateHudOverlay({ elements: ["turn-signals"] });
    expect(code).toContain("Turn signals");
  });

  it("includes lane assist when requested", () => {
    const code = generateHudOverlay({ elements: ["lane-assist"] });
    expect(code).toContain("Lane assist");
  });

  it("applies night mode color scheme", () => {
    const code = generateHudOverlay({ elements: ["speedometer"], nightMode: true });
    expect(code).toContain("0xFF00FF41");
  });

  it("applies day mode color scheme", () => {
    const code = generateHudOverlay({ elements: ["speedometer"], nightMode: false });
    expect(code).toContain("0xFF00BFFF");
  });

  it("generates AR code when ar=true", () => {
    const code = generateHudOverlay({ elements: ["speedometer"], ar: true });
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("android.permission.CAMERA");
  });

  it("includes LightNode with its type and named apply parameter", () => {
    const code = generateHudOverlay({ elements: ["speedometer"] });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("type = LightManager.Type.");
    expect(code).toContain("import com.google.android.filament.LightManager");
    expect(code).toContain("intensity(");
  });

  it("generates code for every HUD style", () => {
    for (const style of HUD_STYLES) {
      const code = generateHudOverlay({ elements: ["speedometer"], style });
      expect(code).toContain("@Composable");
    }
  });

  it("handles multiple elements together", () => {
    const code = generateHudOverlay({
      elements: ["speedometer", "navigation", "fuel-gauge", "alerts"],
    });
    expect(code).toContain("speed");
    expect(code).toContain("Turn right");
    expect(code).toContain("FUEL");
    expect(code).toContain("alerts");
  });

  it("uses monospace font family for HUD text", () => {
    const code = generateHudOverlay({ elements: ["speedometer"] });
    expect(code).toContain("FontFamily.Monospace");
  });

  // SceneScope.ViewNode takes `windowManager` as its first, required parameter, and the parent
  // scene needs the same instance as `viewNodeWindowManager`. Without the three pieces the
  // emitted Kotlin does not compile.
  describe.each([
    { mode: "3D", ar: false, scene: "SceneView(" },
    { mode: "AR", ar: true, scene: "ARSceneView(" },
  ])("ViewNode window manager ($mode)", ({ ar, scene }) => {
    const code = generateHudOverlay({ elements: HUD_ELEMENTS, ar });

    it("declares the window manager", () => {
      expect(code).toContain("import io.github.sceneview.rememberViewNodeManager");
      expect(code).toContain("val windowManager = rememberViewNodeManager()");
    });

    it("passes it to the scene", () => {
      const sceneCall = code.slice(code.indexOf(`        ${scene}`));
      const args = sceneCall.slice(0, sceneCall.indexOf(") {"));
      expect(args).toContain("viewNodeWindowManager = windowManager");
    });

    it("passes it to every ViewNode", () => {
      const calls = code.match(/\bViewNode\(/g) ?? [];
      const wired = code.match(/\bViewNode\(\s*windowManager = windowManager,/g) ?? [];
      expect(calls.length).toBeGreaterThan(0);
      expect(wired.length).toBe(calls.length);
    });

    it("gives the HUD content an explicit width", () => {
      // The ViewNode window is WRAP_CONTENT: a root fillMaxWidth() resolves to the display width.
      expect(code).toMatch(/Column\([^)]*?\.width\(320\.dp\)/);
      expect(code).not.toMatch(/Column\(\s*modifier = Modifier\s*\.fillMaxWidth\(\)/);
    });
  });
});
