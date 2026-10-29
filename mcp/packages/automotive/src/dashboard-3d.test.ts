import { describe, it, expect } from "vitest";
import {
  generateDashboard3d,
  GAUGE_TYPES,
  DASHBOARD_THEMES,
} from "./dashboard-3d.js";

describe("GAUGE_TYPES", () => {
  it("has at least 6 gauge types", () => {
    expect(GAUGE_TYPES.length).toBeGreaterThanOrEqual(6);
  });

  it("includes core gauges", () => {
    expect(GAUGE_TYPES).toContain("speedometer");
    expect(GAUGE_TYPES).toContain("tachometer");
    expect(GAUGE_TYPES).toContain("fuel");
    expect(GAUGE_TYPES).toContain("temperature");
  });
});

describe("DASHBOARD_THEMES", () => {
  it("has at least 4 themes", () => {
    expect(DASHBOARD_THEMES.length).toBeGreaterThanOrEqual(4);
  });

  it("includes core themes", () => {
    expect(DASHBOARD_THEMES).toContain("classic");
    expect(DASHBOARD_THEMES).toContain("digital");
    expect(DASHBOARD_THEMES).toContain("sport");
    expect(DASHBOARD_THEMES).toContain("electric");
  });
});

describe("generateDashboard3d", () => {
  it("generates valid Kotlin code with speedometer", () => {
    const code = generateDashboard3d({ gauges: ["speedometer"] });
    expect(code).toContain("package com.example.automotive.dashboard");
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
    expect(code).toContain("rememberModelInstance");
  });

  it("uses correct dashboard model path", () => {
    const code = generateDashboard3d({ gauges: ["speedometer"], theme: "sport" });
    expect(code).toContain("models/dashboard/sport_cluster.glb");
  });

  it("includes speedometer gauge", () => {
    const code = generateDashboard3d({ gauges: ["speedometer"] });
    expect(code).toContain("speed");
    expect(code).toContain("SPEED");
    expect(code).toContain("km/h");
  });

  it("includes tachometer gauge", () => {
    const code = generateDashboard3d({ gauges: ["tachometer"] });
    expect(code).toContain("rpm");
    expect(code).toContain("RPM");
  });

  it("includes fuel gauge state", () => {
    const code = generateDashboard3d({ gauges: ["fuel"] });
    expect(code).toContain("fuelLevel");
  });

  it("includes temperature gauge state", () => {
    const code = generateDashboard3d({ gauges: ["temperature"] });
    expect(code).toContain("coolantTemp");
  });

  it("includes oil pressure state", () => {
    const code = generateDashboard3d({ gauges: ["oil-pressure"] });
    expect(code).toContain("oilPressure");
  });

  it("includes battery state", () => {
    const code = generateDashboard3d({ gauges: ["battery"] });
    expect(code).toContain("batteryVoltage");
  });

  it("includes boost gauge state", () => {
    const code = generateDashboard3d({ gauges: ["boost"] });
    expect(code).toContain("boostPressure");
  });

  it("includes odometer state", () => {
    const code = generateDashboard3d({ gauges: ["odometer"] });
    expect(code).toContain("odometer");
  });

  it("includes animation when animated=true", () => {
    const code = generateDashboard3d({ gauges: ["speedometer"], animated: true });
    expect(code).toContain("animateFloatAsState");
    expect(code).toContain("spring");
  });

  it("excludes animation when animated=false", () => {
    const code = generateDashboard3d({ gauges: ["speedometer"], animated: false });
    expect(code).not.toContain("animateFloatAsState");
  });

  it("includes interactive controls when interactive=true", () => {
    const code = generateDashboard3d({ gauges: ["speedometer"], interactive: true });
    expect(code).toContain("DashboardControls");
    expect(code).toContain("Slider");
  });

  it("excludes interactive controls when interactive=false", () => {
    const code = generateDashboard3d({ gauges: ["speedometer"], interactive: false });
    expect(code).not.toContain("DashboardControls");
  });

  it("generates AR code when ar=true", () => {
    const code = generateDashboard3d({ gauges: ["speedometer"], ar: true });
    expect(code).toContain("ARSceneView(");
    expect(code).toContain("android.permission.CAMERA");
    expect(code).toContain("arsceneview:4.53.0");
  });

  it("includes LightNode with its type and named apply parameter", () => {
    const code = generateDashboard3d({ gauges: ["speedometer"] });
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("type = LightManager.Type.");
    expect(code).toContain("import com.google.android.filament.LightManager");
    expect(code).toContain("intensity(");
  });

  it("includes loading indicator", () => {
    const code = generateDashboard3d({ gauges: ["speedometer"] });
    expect(code).toContain("CircularProgressIndicator");
    expect(code).toContain("dashboardModel == null");
  });

  it("handles null model instance", () => {
    const code = generateDashboard3d({ gauges: ["speedometer"] });
    expect(code).toContain("dashboardModel?.let");
  });

  it("includes GaugeView with Canvas rendering", () => {
    const code = generateDashboard3d({ gauges: ["speedometer"] });
    expect(code).toContain("GaugeView");
    expect(code).toContain("Canvas");
    expect(code).toContain("drawArc");
  });

  it("supports red zone indicator", () => {
    const code = generateDashboard3d({ gauges: ["tachometer"] });
    expect(code).toContain("redZone");
  });

  it("uses ViewNode for gauge rendering", () => {
    const code = generateDashboard3d({ gauges: ["speedometer"] });
    expect(code).toContain("ViewNode");
    expect(code).toContain("Position(");
  });

  // SceneScope.ViewNode takes `windowManager` as its first, required parameter, and the parent
  // SceneView needs the same instance as `viewNodeWindowManager`. Without the three pieces the
  // emitted Kotlin does not compile.
  describe("ViewNode window manager", () => {
    const code = generateDashboard3d({ gauges: ["speedometer", "tachometer"] });

    it("declares the window manager", () => {
      expect(code).toContain("import io.github.sceneview.rememberViewNodeManager");
      expect(code).toContain("val windowManager = rememberViewNodeManager()");
    });

    it("passes it to SceneView", () => {
      const sceneCall = code.slice(code.indexOf("            SceneView("));
      const args = sceneCall.slice(0, sceneCall.indexOf(") {"));
      expect(args).toContain("viewNodeWindowManager = windowManager");
    });

    it("passes it to every ViewNode", () => {
      const calls = code.match(/\bViewNode\(/g) ?? [];
      const wired = code.match(/\bViewNode\(\s*windowManager = windowManager,/g) ?? [];
      expect(calls.length).toBe(2);
      expect(wired.length).toBe(calls.length);
    });

    it("gives every gauge face the same explicit size", () => {
      // One window manager sizes all its ViewNodes to the largest content.
      expect(code).toContain("modifier = Modifier.size(160.dp)");
    });

    it.each([
      { gauges: ["speedometer"] as const, faces: 1 },
      { gauges: ["tachometer"] as const, faces: 1 },
    ])("wires a single $gauges face", ({ gauges, faces }) => {
      const single = generateDashboard3d({ gauges: [...gauges] });
      expect(single).toContain("val windowManager = rememberViewNodeManager()");
      expect(single).toContain("viewNodeWindowManager = windowManager");
      expect(single.match(/\bViewNode\(\s*windowManager = windowManager,/g)).toHaveLength(faces);
    });

    it("emits no window manager when no gauge is drawn as a ViewNode", () => {
      const noFaces = generateDashboard3d({ gauges: ["fuel"] });
      expect(noFaces).not.toMatch(/\bViewNode\(/);
      expect(noFaces).not.toContain("rememberViewNodeManager()");
      expect(noFaces).not.toContain("viewNodeWindowManager");
    });
  });

  it("generates code for every dashboard theme", () => {
    for (const theme of DASHBOARD_THEMES) {
      const code = generateDashboard3d({ gauges: ["speedometer"], theme });
      expect(code).toContain("@Composable");
    }
  });
});
