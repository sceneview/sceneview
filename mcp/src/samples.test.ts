import { describe, expect, it } from "vitest";
import { LATEST_SCENEVIEW_RELEASE } from "./generated/version.js";
import { getSample, SAMPLE_IDS, SAMPLES } from "./samples.js";

const ANDROID_IDS = SAMPLE_IDS.filter(
  (id) => !SAMPLES[id].language || SAMPLES[id].language === "kotlin"
);
const IOS_IDS = SAMPLE_IDS.filter((id) => SAMPLES[id].language === "swift");

describe("SAMPLE_IDS", () => {
  it("contains all 28 Android sample directories", () => {
    expect(ANDROID_IDS).toContain("model-viewer");
    expect(ANDROID_IDS).toContain("ar-model-viewer");
    expect(ANDROID_IDS).toContain("ar-augmented-image");
    expect(ANDROID_IDS).toContain("ar-cloud-anchor");
    expect(ANDROID_IDS).toContain("ar-point-cloud");
    expect(ANDROID_IDS).toContain("ar-face-mesh");
    expect(ANDROID_IDS).toContain("gltf-camera");
    expect(ANDROID_IDS).toContain("camera-manipulator");
    expect(ANDROID_IDS).toContain("camera-animation");
    expect(ANDROID_IDS).toContain("autopilot-demo");
    expect(ANDROID_IDS).toContain("physics-demo");
    expect(ANDROID_IDS).toContain("dynamic-sky");
    expect(ANDROID_IDS).toContain("line-path");
    expect(ANDROID_IDS).toContain("text-labels");
    expect(ANDROID_IDS).toContain("reflection-probe");
    expect(ANDROID_IDS).toContain("post-processing");
    expect(ANDROID_IDS).toContain("video-texture");
    expect(ANDROID_IDS).toContain("multi-model-scene");
    expect(ANDROID_IDS).toContain("gesture-interaction");
    expect(ANDROID_IDS).toContain("environment-lighting");
    expect(ANDROID_IDS).toContain("procedural-geometry");
    expect(ANDROID_IDS).toContain("compose-ui-3d");
    expect(ANDROID_IDS).toContain("node-hierarchy");
    expect(ANDROID_IDS).toContain("image-node");
    expect(ANDROID_IDS).toContain("billboard-sprite");
    expect(ANDROID_IDS).toContain("animation-state");
    expect(ANDROID_IDS).toContain("spring-animation");
    expect(ANDROID_IDS).toContain("ar-surface-cursor");
    expect(ANDROID_IDS).toHaveLength(28);
  });

  it("contains all 8 iOS samples", () => {
    expect(IOS_IDS).toContain("ios-model-viewer");
    expect(IOS_IDS).toContain("ios-ar-model-viewer");
    expect(IOS_IDS).toContain("ios-ar-augmented-image");
    expect(IOS_IDS).toContain("ios-geometry-shapes");
    expect(IOS_IDS).toContain("ios-lighting");
    expect(IOS_IDS).toContain("ios-physics");
    expect(IOS_IDS).toContain("ios-text-labels");
    expect(IOS_IDS).toContain("ios-video-player");
    expect(IOS_IDS).toHaveLength(8);
  });

  it("has 38 total samples (28 Android + 8 iOS + 2 Web)", () => {
    expect(SAMPLE_IDS).toHaveLength(38);
  });

  it("SAMPLE_IDS matches keys of SAMPLES", () => {
    expect(SAMPLE_IDS.sort()).toEqual(Object.keys(SAMPLES).sort());
  });
});

describe("every Android sample", () => {
  for (const id of ANDROID_IDS) {
    const sample = SAMPLES[id];

    it(`${id}: has all required fields`, () => {
      expect(sample.id).toBe(id);
      expect(sample.title).toBeTruthy();
      expect(sample.description).toBeTruthy();
      expect(sample.tags.length).toBeGreaterThan(0);
      expect(sample.dependency).toMatch(/^io\.github\.sceneview:/);
      expect(sample.prompt).toBeTruthy();
      expect(sample.code).toBeTruthy();
    });

    it(`${id}: code is non-empty Kotlin`, () => {
      expect(sample.code).toContain("@Composable");
      expect(sample.code).toContain("fun ");
    });

    it(`${id}: dependency is a valid sceneview artifact`, () => {
      expect([
        `io.github.sceneview:sceneview:${LATEST_SCENEVIEW_RELEASE}`,
        `io.github.sceneview:arsceneview:${LATEST_SCENEVIEW_RELEASE}`,
      ]).toContain(sample.dependency);
    });
  }
});

describe("every iOS sample", () => {
  for (const id of IOS_IDS) {
    const sample = SAMPLES[id];

    it(`${id}: has all required fields`, () => {
      expect(sample.id).toBe(id);
      expect(sample.title).toBeTruthy();
      expect(sample.description).toBeTruthy();
      expect(sample.tags.length).toBeGreaterThan(0);
      expect(sample.tags).toContain("ios");
      expect(sample.tags).toContain("swift");
      expect(sample.prompt).toBeTruthy();
      expect(sample.code).toBeTruthy();
    });

    it(`${id}: language is swift`, () => {
      expect(sample.language).toBe("swift");
    });

    it(`${id}: code contains Swift markers`, () => {
      expect(sample.code).toContain("import SwiftUI");
      expect(sample.code).toContain("import SceneViewSwift");
    });

    it(`${id}: has SPM dependency URL`, () => {
      expect(sample.spmDependency).toBe("https://github.com/sceneview/sceneview");
    });
  }
});

describe("AR samples", () => {
  const androidArIds = ANDROID_IDS.filter((id) => SAMPLES[id].tags.includes("ar"));
  const iosArIds = IOS_IDS.filter((id) => SAMPLES[id].tags.includes("ar"));

  it("all Android AR samples use arsceneview dependency", () => {
    for (const id of androidArIds) {
      expect(SAMPLES[id].dependency).toBe(
        `io.github.sceneview:arsceneview:${LATEST_SCENEVIEW_RELEASE}`
      );
    }
  });

  it("all Android AR samples contain ARScene in code", () => {
    for (const id of androidArIds) {
      expect(SAMPLES[id].code).toContain("ARScene");
    }
  });

  it("all iOS AR samples contain ARSceneView in code", () => {
    for (const id of iosArIds) {
      expect(SAMPLES[id].code).toContain("ARSceneView");
    }
  });

  it("has 6 Android AR samples", () => {
    expect(androidArIds).toHaveLength(6);
  });

  it("has 2 iOS AR samples", () => {
    expect(iosArIds).toHaveLength(2);
  });
});

describe("3D samples", () => {
  const android3dIds = ANDROID_IDS.filter((id) => SAMPLES[id].tags.includes("3d"));
  const ios3dIds = IOS_IDS.filter((id) => SAMPLES[id].tags.includes("3d"));

  it("all Android 3D samples use sceneview dependency", () => {
    for (const id of android3dIds) {
      expect(SAMPLES[id].dependency).toBe(
        `io.github.sceneview:sceneview:${LATEST_SCENEVIEW_RELEASE}`
      );
    }
  });

  it("all Android 3D samples contain SceneView in code", () => {
    for (const id of android3dIds) {
      expect(SAMPLES[id].code).toContain("SceneView(");
    }
  });

  it("all iOS 3D samples contain SceneView in code", () => {
    for (const id of ios3dIds) {
      expect(SAMPLES[id].code).toContain("SceneView");
    }
  });

  it("has 22 Android pure-3D samples", () => {
    expect(android3dIds).toHaveLength(22);
  });

  it("has 6 iOS 3D samples", () => {
    expect(ios3dIds).toHaveLength(6);
  });
});

describe("getSample", () => {
  it("returns the correct sample by ID", () => {
    const s = getSample("model-viewer");
    expect(s).toBeDefined();
    expect(s!.id).toBe("model-viewer");
    expect(s!.title).toBe("3D Model Viewer");
  });

  it("returns an iOS sample by ID", () => {
    const s = getSample("ios-model-viewer");
    expect(s).toBeDefined();
    expect(s!.id).toBe("ios-model-viewer");
    expect(s!.language).toBe("swift");
  });

  it("returns undefined for an unknown ID", () => {
    expect(getSample("nonexistent-scenario")).toBeUndefined();
  });

  it("returns all samples without undefined", () => {
    for (const id of SAMPLE_IDS) {
      expect(getSample(id)).toBeDefined();
    }
  });
});

describe("tag filtering (simulating list_samples tool)", () => {
  const filterByTag = (tag: string) =>
    Object.values(SAMPLES).filter((s) => s.tags.includes(tag as any));

  it("tag 'ar' returns only AR samples", () => {
    const results = filterByTag("ar");
    expect(results.length).toBe(8); // 6 Android + 2 iOS
    results.forEach((s) => {
      expect(s.tags).toContain("ar");
    });
  });

  it("tag '3d' returns only 3D samples", () => {
    const results = filterByTag("3d");
    expect(results.length).toBe(30); // 22 Android + 6 iOS + 2 Web
    results.forEach((s) => {
      expect(s.tags).toContain("3d");
    });
  });

  it("tag 'physics' returns physics samples", () => {
    const results = filterByTag("physics");
    expect(results).toHaveLength(2); // Android + iOS
    expect(results.map((s) => s.id).sort()).toEqual(["ios-physics", "physics-demo"]);
  });

  it("tag 'image-tracking' returns augmented image samples", () => {
    const results = filterByTag("image-tracking");
    expect(results).toHaveLength(2); // Android + iOS
  });

  it("tag 'reflection' returns only reflection-probe sample", () => {
    const results = filterByTag("reflection");
    expect(results).toHaveLength(1);
    expect(results[0].id).toBe("reflection-probe");
  });

  it("tag 'anchor' returns AR samples that use anchors", () => {
    const results = filterByTag("anchor");
    expect(results.length).toBeGreaterThan(0);
    results.forEach((s) => {
      expect(s.tags).toContain("anchor");
    });
  });

  it("tag 'ios' returns only iOS samples", () => {
    const results = filterByTag("ios");
    expect(results).toHaveLength(8);
    results.forEach((s) => {
      expect(s.language).toBe("swift");
    });
  });

  it("tag 'swift' returns only Swift samples", () => {
    const results = filterByTag("swift");
    expect(results).toHaveLength(8);
    results.forEach((s) => {
      expect(s.language).toBe("swift");
    });
  });

  it("tag 'video' returns video samples", () => {
    const results = filterByTag("video");
    expect(results).toHaveLength(2);
    expect(results.map((s) => s.id).sort()).toEqual(["ios-video-player", "video-texture"]);
  });

  it("tag 'lighting' returns lighting samples", () => {
    const results = filterByTag("lighting");
    expect(results).toHaveLength(4);
    expect(results.map((s) => s.id)).toContain("environment-lighting");
    expect(results.map((s) => s.id)).toContain("ios-lighting");
    expect(results.map((s) => s.id)).toContain("web-environment");
    expect(results.map((s) => s.id)).toContain("dynamic-sky");
  });

  it("tag 'face-tracking' returns face mesh sample", () => {
    const results = filterByTag("face-tracking");
    expect(results).toHaveLength(1);
    expect(results[0].id).toBe("ar-face-mesh");
  });

  it("tag 'multi-model' returns multi-model sample", () => {
    const results = filterByTag("multi-model");
    expect(results).toHaveLength(1);
    expect(results[0].id).toBe("multi-model-scene");
  });

  it("tag 'hierarchy' returns node hierarchy sample", () => {
    const results = filterByTag("hierarchy");
    expect(results).toHaveLength(1);
    expect(results[0].id).toBe("node-hierarchy");
  });

  it("tag 'compose-ui' returns compose UI in 3D sample", () => {
    const results = filterByTag("compose-ui");
    expect(results).toHaveLength(1);
    expect(results[0].id).toBe("compose-ui-3d");
  });

  it("tag 'gestures' returns gesture samples", () => {
    const results = filterByTag("gestures");
    expect(results.length).toBeGreaterThanOrEqual(2);
    expect(results.map((s) => s.id)).toContain("gesture-interaction");
    expect(results.map((s) => s.id)).toContain("camera-manipulator");
  });

  it("unknown tag returns empty array", () => {
    expect(filterByTag("nonexistent-tag")).toHaveLength(0);
  });
});

// #3054 — `"…${LATEST_SCENEVIEW_RELEASE}…"` in a DOUBLE-QUOTED string never
// interpolates, so 28 sample prompts shipped the placeholder verbatim to the
// caller: an AI reading `io.github.sceneview:sceneview:${LATEST_SCENEVIEW_RELEASE}`
// writes exactly that into build.gradle.kts. Biome's `noTemplateCurlyInString`
// had been reporting it since adoption, to a lint script no CI job ever ran.
// That rule is a WARNING, so `mcp-ts-check.yml` would NOT fail on a relapse —
// this test is what pins the fix.
describe("version interpolation (#3054)", () => {
  it("no sample field leaks the LATEST_SCENEVIEW_RELEASE identifier", () => {
    for (const id of SAMPLE_IDS) {
      for (const [field, value] of Object.entries(SAMPLES[id])) {
        if (typeof value !== "string") continue;
        expect(`${id}.${field} → ${value}`).not.toContain("LATEST_SCENEVIEW_RELEASE");
      }
    }
  });

  // Guards the assertion above against becoming vacuous: it would also pass if
  // the prompts stopped naming a dependency at all.
  it("every prompt naming a Maven coordinate carries the real version", () => {
    const withCoordinate = SAMPLE_IDS.filter((id) =>
      SAMPLES[id].prompt.includes("io.github.sceneview:")
    );
    expect(withCoordinate.length).toBeGreaterThan(0);
    for (const id of withCoordinate) {
      expect(SAMPLES[id].prompt).toContain(`:${LATEST_SCENEVIEW_RELEASE}`);
    }
  });
});
