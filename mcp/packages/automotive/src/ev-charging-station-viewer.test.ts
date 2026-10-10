import { describe, it, expect } from "vitest";
import {
  generateEvChargingStationViewer,
  CHARGING_CONNECTORS,
  STATION_LAYOUTS,
} from "./ev-charging-station-viewer.js";

describe("CHARGING_CONNECTORS", () => {
  it("has at least 4 connector types", () => {
    expect(CHARGING_CONNECTORS.length).toBeGreaterThanOrEqual(4);
  });

  it("includes the big three fast-charge standards", () => {
    expect(CHARGING_CONNECTORS).toContain("ccs");
    expect(CHARGING_CONNECTORS).toContain("chademo");
    expect(CHARGING_CONNECTORS).toContain("tesla");
  });
});

describe("STATION_LAYOUTS", () => {
  it("has at least 3 layouts", () => {
    expect(STATION_LAYOUTS.length).toBeGreaterThanOrEqual(3);
  });

  it("includes single and bank layouts", () => {
    expect(STATION_LAYOUTS).toContain("single");
    expect(STATION_LAYOUTS).toContain("bank");
  });
});

describe("generateEvChargingStationViewer", () => {
  it("generates valid Kotlin code with SceneView composable setup", () => {
    const code = generateEvChargingStationViewer();
    expect(code).toContain("package com.example.automotive.ev");
    expect(code).toContain("@Composable");
    expect(code).toContain("rememberEngine()");
    expect(code).toContain("rememberModelLoader");
    expect(code).toContain("rememberModelInstance");
    // SceneView already defaults collisionSystem = rememberCollisionSystem(view)
    expect(code).not.toContain("rememberCollisionSystem");
    expect(code).toContain("ModelNode");
  });

  it("uses SceneView (non-AR) by default", () => {
    const code = generateEvChargingStationViewer();
    expect(code).toContain("import io.github.sceneview.SceneView");
    expect(code).toContain("SceneView(");
    expect(code).not.toContain("ARSceneView(");
  });

  it("switches to ARScene when ar=true", () => {
    const code = generateEvChargingStationViewer({ ar: true });
    expect(code).toContain("import io.github.sceneview.ar.ARSceneView");
    expect(code).toContain("ARSceneView(");
    // ARSceneView has no onTapAR — taps are hit-tested against the latest frame
    expect(code).not.toContain("onTapAR");
    expect(code).toContain("rememberOnGestureListener(");
    expect(code).toContain("latestFrame?.hitTest(e)");
  });

  it("includes LightNode with its type and named apply parameter (not a trailing lambda)", () => {
    const code = generateEvChargingStationViewer();
    expect(code).toContain("LightNode(");
    expect(code).toContain("apply = {");
    expect(code).toContain("type = LightManager.Type.");
    expect(code).toContain("import com.google.android.filament.LightManager");
    expect(code).toContain("intensity(");
  });

  it("handles null modelInstance while loading", () => {
    const code = generateEvChargingStationViewer();
    expect(code).toContain("modelInstance?.let");
    expect(code).toContain("CircularProgressIndicator");
  });

  it("renders the charging status overlay by default", () => {
    const code = generateEvChargingStationViewer();
    expect(code).toContain("ChargingStatusCard");
    expect(code).toContain("chargeLevelPercent");
    expect(code).toContain("baysAvailable");
    expect(code).toContain("estimatedMinutesToFull");
    expect(code).toContain("LinearProgressIndicator");
  });

  it("omits the overlay when overlay=false", () => {
    const code = generateEvChargingStationViewer({ overlay: false });
    expect(code).not.toContain("ChargingStatusCard");
    expect(code).not.toContain("LinearProgressIndicator");
  });

  it("uses the connector in the model path", () => {
    const code = generateEvChargingStationViewer({ connector: "chademo", layout: "bank" });
    expect(code).toContain("models/ev/bank_chademo_station.glb");
  });

  it("generates a composable name derived from layout + connector", () => {
    const code = generateEvChargingStationViewer({ connector: "tesla", layout: "canopy" });
    expect(code).toContain("fun CanopyTeslaChargingStationViewer");
  });

  it("generates code for every (layout, connector) combination", () => {
    for (const layout of STATION_LAYOUTS) {
      for (const connector of CHARGING_CONNECTORS) {
        const code = generateEvChargingStationViewer({ layout, connector });
        expect(code).toContain("@Composable");
        expect(code).toContain("rememberModelInstance");
        expect(code).toContain(`models/ev/${layout}_${connector}_station.glb`);
      }
    }
  });

  it("exposes the bay count constant", () => {
    const single = generateEvChargingStationViewer({ layout: "single" });
    const canopy = generateEvChargingStationViewer({ layout: "canopy" });
    expect(single).toContain("val baysTotal = 1");
    expect(canopy).toContain("val baysTotal = 8");
  });
});
