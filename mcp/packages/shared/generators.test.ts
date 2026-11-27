import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { generateArShowroom } from "../automotive/src/ar-showroom.js";
import { SCENEVIEW_VERSION as automotiveVersion } from "../automotive/src/sceneview-version.js";
import { SCENEVIEW_VERSION as gamingVersion } from "../gaming/src/sceneview-version.js";
import { SCENEVIEW_VERSION as healthcareVersion } from "../healthcare/src/sceneview-version.js";
import { generateFurniturePlacement } from "../interior/src/furniture-placement.js";
import { generateLightingDesign } from "../interior/src/lighting-design.js";
import { generateRoomPlanner } from "../interior/src/room-planner.js";
import { SCENEVIEW_VERSION as interiorVersion } from "../interior/src/sceneview-version.js";
import { SCENEVIEW_VERSION as rerunVersion } from "../rerun/src/sceneview-version.js";

describe("shared vertical generator invariants", () => {
  it("only anchors floor-based models to tracked upward-facing plane polygons", () => {
    const templates = [
      generateArShowroom({ location: "driveway" }),
      generateFurniturePlacement({ category: "chair" }),
      generateRoomPlanner({ roomType: "living-room", ar: true }),
      generateLightingDesign({ lights: ["ambient"], ar: true }),
    ];

    for (const code of templates) {
      expect(code).toContain("import com.google.ar.core.Plane");
      expect(code).toContain("import com.google.ar.core.TrackingState");
      expect(code).toContain("Plane.Type.HORIZONTAL_UPWARD_FACING");
      expect(code).toContain("plane.trackingState == TrackingState.TRACKING");
      expect(code).toContain("plane.isPoseInPolygon(hit.hitPose)");
      expect(code).toContain("?.createAnchorOrNull()");
    }
  });

  it("matches every vertical package version to gradle.properties", () => {
    const properties = readFileSync(new URL("../../../gradle.properties", import.meta.url), "utf8");
    const repositoryVersion = properties.match(/^VERSION_NAME=(.+)$/m)?.[1];

    expect(repositoryVersion).toBeDefined();
    expect([
      automotiveVersion,
      gamingVersion,
      healthcareVersion,
      interiorVersion,
      rerunVersion,
    ]).toEqual(Array(5).fill(repositoryVersion));
  });
});
