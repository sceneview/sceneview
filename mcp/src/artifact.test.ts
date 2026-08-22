import { describe, expect, it } from "vitest";
import { FILAMENT_WEB_NPM_VERSION } from "./generated/version.js";
import {
  type ArtifactInput,
  formatArtifactResponse,
  generateArtifact,
  validateArtifactInput,
} from "./artifact.js";

// ─── validateArtifactInput ───────────────────────────────────────────────────

describe("validateArtifactInput", () => {
  it("accepts valid model-viewer input", () => {
    expect(validateArtifactInput({ type: "model-viewer" })).toBeNull();
  });

  it("accepts valid model-viewer with model URL", () => {
    expect(
      validateArtifactInput({
        type: "model-viewer",
        modelUrl: "https://example.com/model.glb",
      })
    ).toBeNull();
  });

  it("rejects invalid type", () => {
    const err = validateArtifactInput({ type: "invalid" as any });
    expect(err).toContain("Invalid type");
  });

  it("rejects chart-3d without data", () => {
    const err = validateArtifactInput({ type: "chart-3d" });
    expect(err).toContain("non-empty `data` array");
  });

  it("rejects chart-3d with empty data array", () => {
    const err = validateArtifactInput({ type: "chart-3d", data: [] });
    expect(err).toContain("non-empty `data` array");
  });

  it("rejects chart-3d data with missing label", () => {
    const err = validateArtifactInput({
      type: "chart-3d",
      data: [{ label: 123 as any, value: 100 }],
    });
    expect(err).toContain("string `label`");
  });

  it("rejects chart-3d data with missing value", () => {
    const err = validateArtifactInput({
      type: "chart-3d",
      data: [{ label: "Q1", value: "100" as any }],
    });
    expect(err).toContain("numeric `value`");
  });

  it("accepts valid chart-3d data", () => {
    expect(
      validateArtifactInput({
        type: "chart-3d",
        data: [
          { label: "Q1", value: 100 },
          { label: "Q2", value: 200 },
        ],
      })
    ).toBeNull();
  });

  it("rejects non-HTTP model URL", () => {
    const err = validateArtifactInput({
      type: "model-viewer",
      modelUrl: "ftp://example.com/model.glb",
    });
    expect(err).toContain("HTTP(S)");
  });

  it("accepts scene type", () => {
    expect(validateArtifactInput({ type: "scene" })).toBeNull();
  });

  it("accepts product-360 type", () => {
    expect(validateArtifactInput({ type: "product-360" })).toBeNull();
  });
});

// ─── generateArtifact — model-viewer ─────────────────────────────────────────

describe("generateArtifact — model-viewer (Filament.js)", () => {
  it("generates valid HTML with Filament.js canvas", () => {
    const result = generateArtifact({ type: "model-viewer" });
    expect(result.type).toBe("model-viewer");
    expect(result.html).toContain("<!DOCTYPE html>");
    expect(result.html).toContain('<canvas id="viewer"');
    expect(result.html).toContain("Filament.init");
    expect(result.html).toContain("Powered by SceneView");
  });

  it("includes Filament.js CDN script", () => {
    const result = generateArtifact({ type: "model-viewer" });
    expect(result.html).toContain(`cdn.jsdelivr.net/npm/filament@${FILAMENT_WEB_NPM_VERSION}/filament.js`);
  });

  it("does NOT include model-viewer web component", () => {
    const result = generateArtifact({ type: "model-viewer" });
    expect(result.html).not.toContain("<model-viewer");
    expect(result.html).not.toContain("ajax.googleapis.com/ajax/libs/model-viewer");
  });

  it("uses default model when none provided", () => {
    const result = generateArtifact({ type: "model-viewer" });
    expect(result.html).toContain("DamagedHelmet.glb");
  });

  it("uses custom model URL", () => {
    const result = generateArtifact({
      type: "model-viewer",
      modelUrl: "https://example.com/car.glb",
    });
    expect(result.html).toContain("https://example.com/car.glb");
  });

  it("uses custom title", () => {
    const result = generateArtifact({
      type: "model-viewer",
      title: "My Helmet",
    });
    expect(result.title).toBe("My Helmet");
    expect(result.html).toContain("My Helmet");
  });

  it("includes auto-rotate by default", () => {
    const result = generateArtifact({ type: "model-viewer" });
    expect(result.html).toContain("autoRotate = true");
  });

  it("disables auto-rotate when option is false", () => {
    const result = generateArtifact({
      type: "model-viewer",
      options: { autoRotate: false },
    });
    expect(result.html).toContain("autoRotate = false");
  });

  it("uses custom background color", () => {
    const result = generateArtifact({
      type: "model-viewer",
      options: { backgroundColor: "#ff0000" },
    });
    // #ff0000 = [1, 0, 0, 1] in Filament
    expect(result.html).toContain("clearColor: [1,0,0,1]");
  });

  it("sets up orbit controls (mouse + touch)", () => {
    const result = generateArtifact({ type: "model-viewer" });
    expect(result.html).toContain("mousedown");
    expect(result.html).toContain("touchstart");
    expect(result.html).toContain("wheel");
  });

  it("sets up Filament engine, scene, renderer", () => {
    const result = generateArtifact({ type: "model-viewer" });
    expect(result.html).toContain("Filament.Engine.create");
    expect(result.html).toContain("engine.createScene");
    expect(result.html).toContain("engine.createRenderer");
  });

  it("sets up PBR lighting (sun + fill)", () => {
    const result = generateArtifact({ type: "model-viewer" });
    expect(result.html).toContain("LightManager$Type.SUN");
    expect(result.html).toContain("LightManager$Type.DIRECTIONAL");
  });

  it("has dark theme background (#0d1117)", () => {
    const result = generateArtifact({ type: "model-viewer" });
    expect(result.html).toContain("#0d1117");
  });

  it("includes render loop with requestAnimationFrame", () => {
    const result = generateArtifact({ type: "model-viewer" });
    expect(result.html).toContain("requestAnimationFrame(render)");
  });

  it("loads model via asset loader", () => {
    const result = generateArtifact({ type: "model-viewer" });
    expect(result.html).toContain("engine.createAssetLoader");
    expect(result.html).toContain("loader.createAsset");
    expect(result.html).toContain("asset.loadResources");
  });
});

// ─── generateArtifact — chart-3d ─────────────────────────────────────────────

describe("generateArtifact — chart-3d", () => {
  const chartInput: ArtifactInput = {
    type: "chart-3d",
    data: [
      { label: "Q1", value: 50000 },
      { label: "Q2", value: 75000 },
      { label: "Q3", value: 120000 },
    ],
  };

  it("generates HTML with all bar labels", () => {
    const result = generateArtifact(chartInput);
    expect(result.type).toBe("chart-3d");
    expect(result.html).toContain("Q1");
    expect(result.html).toContain("Q2");
    expect(result.html).toContain("Q3");
  });

  it("formats large numbers with K/M suffix", () => {
    const result = generateArtifact(chartInput);
    expect(result.html).toContain("50K");
    expect(result.html).toContain("75K");
    expect(result.html).toContain("120K");
  });

  it("shows total", () => {
    const result = generateArtifact(chartInput);
    expect(result.html).toContain("245K");
  });

  it("uses custom colors", () => {
    const result = generateArtifact({
      type: "chart-3d",
      data: [{ label: "Revenue", value: 100, color: "#ff6600" }],
    });
    expect(result.html).toContain("#ff6600");
  });

  it("uses default colors when none specified", () => {
    const result = generateArtifact({
      type: "chart-3d",
      data: [{ label: "Revenue", value: 100 }],
    });
    expect(result.html).toContain("#4285F4"); // first default color
  });

  it("includes chart title", () => {
    const result = generateArtifact({
      type: "chart-3d",
      title: "Revenue Dashboard",
      data: [{ label: "Q1", value: 100 }],
    });
    expect(result.html).toContain("Revenue Dashboard");
    expect(result.title).toBe("Revenue Dashboard");
  });

  it("has SceneView branding", () => {
    const result = generateArtifact(chartInput);
    expect(result.html).toContain("Powered by SceneView");
  });

  it("handles millions formatting", () => {
    const result = generateArtifact({
      type: "chart-3d",
      data: [{ label: "ARR", value: 2500000 }],
    });
    expect(result.html).toContain("2.5M");
  });

  it("renders valid HTML document", () => {
    const result = generateArtifact(chartInput);
    expect(result.html).toContain("<!DOCTYPE html>");
    expect(result.html).toContain("</html>");
  });

  it("does NOT use Filament.js (CSS 3D only)", () => {
    const result = generateArtifact(chartInput);
    expect(result.html).not.toContain("Filament.init");
    expect(result.html).not.toContain("filament.js");
  });
});

// ─── generateArtifact — scene ────────────────────────────────────────────────

describe("generateArtifact — scene (Filament.js)", () => {
  it("generates scene with Filament.js canvas", () => {
    const result = generateArtifact({ type: "scene" });
    expect(result.type).toBe("scene");
    expect(result.html).toContain('<canvas id="viewer"');
    expect(result.html).toContain("Filament.init");
    expect(result.html).toContain("Interactive 3D Scene");
  });

  it("does NOT include model-viewer web component", () => {
    const result = generateArtifact({ type: "scene" });
    expect(result.html).not.toContain("<model-viewer");
  });

  it("uses custom title", () => {
    const result = generateArtifact({ type: "scene", title: "My World" });
    expect(result.html).toContain("My World");
  });

  it("includes control hints", () => {
    const result = generateArtifact({ type: "scene" });
    expect(result.html).toContain("Drag to orbit");
    expect(result.html).toContain("Scroll to zoom");
  });

  it("includes PBR lighting", () => {
    const result = generateArtifact({ type: "scene" });
    expect(result.html).toContain("LightManager$Type.SUN");
    expect(result.html).toContain("LightManager$Type.DIRECTIONAL");
  });

  it("uses enhanced lighting for scenes", () => {
    const result = generateArtifact({ type: "scene" });
    expect(result.html).toContain("120000"); // higher sun intensity
    expect(result.html).toContain("35000"); // higher fill intensity
  });
});

// ─── generateArtifact — product-360 ──────────────────────────────────────────

describe("generateArtifact — product-360 (Filament.js)", () => {
  it("generates product viewer with Filament.js", () => {
    const result = generateArtifact({ type: "product-360" });
    expect(result.type).toBe("product-360");
    expect(result.html).toContain('<canvas id="viewer"');
    expect(result.html).toContain("Filament.init");
    expect(result.html).toContain("360");
  });

  it("does NOT include model-viewer web component", () => {
    const result = generateArtifact({ type: "product-360" });
    expect(result.html).not.toContain("<model-viewer");
  });

  it("renders hotspots when provided", () => {
    const result = generateArtifact({
      type: "product-360",
      hotspots: [
        {
          position: "0.5 1.2 0.3",
          normal: "0 1 0",
          label: "Camera Lens",
          description: "50MP main sensor",
        },
      ],
    });
    expect(result.html).toContain("Camera Lens");
    expect(result.html).toContain("50MP main sensor");
    expect(result.html).toContain("hotspot-0");
  });

  it("renders multiple hotspots", () => {
    const result = generateArtifact({
      type: "product-360",
      hotspots: [
        { position: "0 0 0", normal: "0 1 0", label: "Top" },
        { position: "1 0 0", normal: "0 1 0", label: "Side" },
      ],
    });
    expect(result.html).toContain("hotspot-0");
    expect(result.html).toContain("hotspot-1");
    expect(result.html).toContain("Top");
    expect(result.html).toContain("Side");
  });

  it("shows rotation hint when no hotspots", () => {
    const result = generateArtifact({ type: "product-360" });
    expect(result.html).toContain("Drag to rotate");
    expect(result.html).not.toContain("Tap hotspots");
  });

  it("shows hotspot hint when hotspots present", () => {
    const result = generateArtifact({
      type: "product-360",
      hotspots: [{ position: "0 0 0", normal: "0 1 0", label: "X" }],
    });
    expect(result.html).toContain("Tap hotspots for details");
  });

  it("always auto-rotates for product showcase", () => {
    const result = generateArtifact({ type: "product-360" });
    expect(result.html).toContain("autoRotate = true");
  });
});

// ─── formatArtifactResponse ──────────────────────────────────────────────────

describe("formatArtifactResponse", () => {
  it("includes the HTML in a code block", () => {
    const result = generateArtifact({ type: "model-viewer" });
    const text = formatArtifactResponse(result);
    expect(text).toContain("```html");
    expect(text).toContain("<!DOCTYPE html>");
    expect(text).toContain("```");
  });

  it("includes usage instructions", () => {
    const result = generateArtifact({ type: "model-viewer" });
    const text = formatArtifactResponse(result);
    expect(text).toContain("Drag to orbit");
    expect(text).toContain("Filament.js");
  });

  it("includes SceneView branding", () => {
    const result = generateArtifact({ type: "model-viewer" });
    const text = formatArtifactResponse(result);
    expect(text).toContain("Powered by SceneView");
  });

  it("shows chart label for chart-3d type", () => {
    const result = generateArtifact({
      type: "chart-3d",
      data: [{ label: "Q1", value: 100 }],
    });
    const text = formatArtifactResponse(result);
    expect(text).toContain("3D chart");
  });

  it("shows content label for non-chart types", () => {
    const result = generateArtifact({ type: "scene" });
    const text = formatArtifactResponse(result);
    expect(text).toContain("3D content");
  });
});

// ─── HTML safety ─────────────────────────────────────────────────────────────

describe("HTML escaping", () => {
  it("escapes HTML in title", () => {
    const result = generateArtifact({
      type: "model-viewer",
      title: '<script>alert("xss")</script>',
    });
    expect(result.html).not.toContain("<script>alert");
    expect(result.html).toContain("&lt;script&gt;");
  });

  it("escapes HTML in chart labels", () => {
    const result = generateArtifact({
      type: "chart-3d",
      data: [{ label: "<b>Q1</b>", value: 100 }],
    });
    expect(result.html).not.toContain("<b>Q1</b>");
    expect(result.html).toContain("&lt;b&gt;Q1&lt;/b&gt;");
  });

  it("escapes HTML in hotspot labels", () => {
    const result = generateArtifact({
      type: "product-360",
      hotspots: [{ position: "0 0 0", normal: "0 1 0", label: '<img src=x onerror="alert(1)">' }],
    });
    expect(result.html).not.toContain("<img");
    expect(result.html).toContain("&lt;img");
  });
});

// ─── validateArtifactInput — geometry ────────────────────────────────────────

describe("validateArtifactInput — geometry", () => {
  it("accepts valid geometry input", () => {
    expect(
      validateArtifactInput({
        type: "geometry",
        shapes: [{ type: "cube", position: [0, 0, 0], scale: [1, 1, 1], color: [1, 0, 0] }],
      })
    ).toBeNull();
  });

  it("rejects geometry without shapes", () => {
    const err = validateArtifactInput({ type: "geometry" });
    expect(err).toContain("non-empty `shapes` array");
  });

  it("rejects geometry with empty shapes array", () => {
    const err = validateArtifactInput({ type: "geometry", shapes: [] });
    expect(err).toContain("non-empty `shapes` array");
  });

  it("rejects invalid shape type", () => {
    const err = validateArtifactInput({
      type: "geometry",
      shapes: [{ type: "pyramid" as any }],
    });
    expect(err).toContain("Invalid shape type");
  });

  it("rejects invalid position array", () => {
    const err = validateArtifactInput({
      type: "geometry",
      shapes: [{ type: "cube", position: [1, 2] as any }],
    });
    expect(err).toContain("position must be an array of 3 numbers");
  });

  it("rejects invalid scale array", () => {
    const err = validateArtifactInput({
      type: "geometry",
      shapes: [{ type: "cube", scale: [1] as any }],
    });
    expect(err).toContain("scale must be an array of 3 numbers");
  });

  it("rejects invalid color array", () => {
    const err = validateArtifactInput({
      type: "geometry",
      shapes: [{ type: "cube", color: [1, 0] as any }],
    });
    expect(err).toContain("color must be an array of 3 numbers");
  });

  it("accepts all valid shape types", () => {
    for (const shapeType of ["cube", "sphere", "cylinder", "plane", "line"] as const) {
      expect(
        validateArtifactInput({
          type: "geometry",
          shapes: [{ type: shapeType }],
        })
      ).toBeNull();
    }
  });

  it("accepts multiple shapes", () => {
    expect(
      validateArtifactInput({
        type: "geometry",
        shapes: [
          { type: "cube", position: [0, 0.5, 0], color: [1, 0, 0] },
          { type: "sphere", position: [0, 1.8, 0], color: [0, 0, 1] },
          { type: "line", position: [0, 1, 0], scale: [2, 0.05, 0.05] },
        ],
      })
    ).toBeNull();
  });
});

// ─── generateArtifact — geometry ────────────────────────────────────────────

describe("generateArtifact — geometry (WebGL PBR)", () => {
  const geoInput: ArtifactInput = {
    type: "geometry",
    shapes: [
      { type: "cube", position: [0, 0.5, 0], scale: [1, 1, 1], color: [1, 0, 0] },
      { type: "sphere", position: [0, 1.8, 0], scale: [0.6, 0.6, 0.6], color: [0, 0, 1] },
    ],
  };

  it("generates valid HTML with WebGL canvas", () => {
    const result = generateArtifact(geoInput);
    expect(result.type).toBe("geometry");
    expect(result.html).toContain("<!DOCTYPE html>");
    expect(result.html).toContain('<canvas id="c"');
    expect(result.html).toContain("webgl2");
  });

  it("does NOT use Filament.js (pure WebGL)", () => {
    const result = generateArtifact(geoInput);
    expect(result.html).not.toContain("filament.js");
    expect(result.html).not.toContain("Filament.init");
  });

  it("includes PBR shader code", () => {
    const result = generateArtifact(geoInput);
    expect(result.html).toContain("D_GGX");
    expect(result.html).toContain("F_Schlick");
    expect(result.html).toContain("G_Smith");
  });

  it("includes all geometry generators", () => {
    const result = generateArtifact(geoInput);
    expect(result.html).toContain("genSphere");
    expect(result.html).toContain("genCube");
    expect(result.html).toContain("genCyl");
    expect(result.html).toContain("genPlane");
  });

  it("embeds shapes as JSON data", () => {
    const result = generateArtifact(geoInput);
    // The shapes should be serialized into the HTML
    expect(result.html).toContain('"cube"');
    expect(result.html).toContain('"sphere"');
  });

  it("includes orbit controls", () => {
    const result = generateArtifact(geoInput);
    expect(result.html).toContain("pointerdown");
    expect(result.html).toContain("pointermove");
    expect(result.html).toContain("wheel");
  });

  it("includes render loop", () => {
    const result = generateArtifact(geoInput);
    expect(result.html).toContain("requestAnimationFrame(render)");
  });

  it("uses custom title", () => {
    const result = generateArtifact({
      type: "geometry",
      title: "My Building",
      shapes: [{ type: "cube" }],
    });
    expect(result.title).toBe("My Building");
    expect(result.html).toContain("My Building");
  });

  it("uses default title when none given", () => {
    const result = generateArtifact({
      type: "geometry",
      shapes: [{ type: "cube" }],
    });
    expect(result.title).toBe("3D Geometry");
  });

  it("shows shape count", () => {
    const result = generateArtifact(geoInput);
    expect(result.html).toContain("2 shapes");
  });

  it("shows singular for single shape", () => {
    const result = generateArtifact({
      type: "geometry",
      shapes: [{ type: "sphere" }],
    });
    expect(result.html).toContain("1 shape");
    expect(result.html).not.toContain("1 shapes");
  });

  it("auto-rotates by default", () => {
    const result = generateArtifact(geoInput);
    expect(result.html).toContain("autoR=true");
  });

  it("can disable auto-rotate", () => {
    const result = generateArtifact({
      type: "geometry",
      shapes: [{ type: "cube" }],
      options: { autoRotate: false },
    });
    expect(result.html).toContain("autoR=false");
  });

  it("uses custom background color", () => {
    const result = generateArtifact({
      type: "geometry",
      shapes: [{ type: "cube" }],
      options: { backgroundColor: "#1a1a2e" },
    });
    // #1a1a2e = rgb(26, 26, 46) => 0.102, 0.102, 0.180
    expect(result.html).toContain("0.102");
  });

  it("includes SceneView branding", () => {
    const result = generateArtifact(geoInput);
    expect(result.html).toContain("Powered by SceneView");
  });

  it("includes grid floor", () => {
    const result = generateArtifact(geoInput);
    expect(result.html).toContain("genGrid");
  });

  it("handles all five shape types", () => {
    const result = generateArtifact({
      type: "geometry",
      shapes: [
        { type: "cube", position: [0, 0.5, 0] },
        { type: "sphere", position: [2, 1, 0] },
        { type: "cylinder", position: [-2, 0.5, 0] },
        { type: "plane", position: [0, 0, 0] },
        { type: "line", position: [0, 2, 0] },
      ],
    });
    expect(result.html).toContain("5 shapes");
    expect(result.html).toContain('"cube"');
    expect(result.html).toContain('"sphere"');
    expect(result.html).toContain('"cylinder"');
    expect(result.html).toContain('"plane"');
    expect(result.html).toContain('"line"');
  });

  it("applies default values for missing shape properties", () => {
    const result = generateArtifact({
      type: "geometry",
      shapes: [{ type: "cube" }], // no position, scale, color
    });
    // Defaults should be applied: position [0,0,0], scale [1,1,1], color [0.8,0.8,0.8]
    expect(result.html).toContain("[0,0,0]");
    expect(result.html).toContain("[1,1,1]");
    expect(result.html).toContain("[0.8,0.8,0.8]");
  });

  it("preserves metallic and roughness values", () => {
    const result = generateArtifact({
      type: "geometry",
      shapes: [{ type: "sphere", metallic: 0.9, roughness: 0.1 }],
    });
    expect(result.html).toContain('"metallic":0.9');
    expect(result.html).toContain('"roughness":0.1');
  });

  it("escapes HTML in title", () => {
    const result = generateArtifact({
      type: "geometry",
      title: '<script>alert("xss")</script>',
      shapes: [{ type: "cube" }],
    });
    expect(result.html).not.toContain("<script>alert");
    expect(result.html).toContain("&lt;script&gt;");
  });
});

// ─── formatArtifactResponse — geometry ──────────────────────────────────────

describe("formatArtifactResponse — geometry", () => {
  it("shows scene label for geometry type", () => {
    const result = generateArtifact({
      type: "geometry",
      shapes: [{ type: "cube" }],
    });
    const text = formatArtifactResponse(result);
    expect(text).toContain("3D scene");
  });

  it("mentions WebGL PBR for geometry type", () => {
    const result = generateArtifact({
      type: "geometry",
      shapes: [{ type: "cube" }],
    });
    const text = formatArtifactResponse(result);
    expect(text).toContain("WebGL PBR");
  });
});

// ─── Filament.js integration ─────────────────────────────────────────────────

describe("Filament.js integration", () => {
  it("all 3D types use Filament.js CDN", () => {
    for (const type of ["model-viewer", "scene", "product-360"] as const) {
      const result = generateArtifact({ type });
      expect(result.html).toContain(`cdn.jsdelivr.net/npm/filament@${FILAMENT_WEB_NPM_VERSION}/filament.js`);
    }
  });

  it("chart-3d does NOT use Filament.js", () => {
    const result = generateArtifact({
      type: "chart-3d",
      data: [{ label: "Q1", value: 100 }],
    });
    expect(result.html).not.toContain("filament");
  });

  it("all 3D types use canvas element", () => {
    for (const type of ["model-viewer", "scene", "product-360"] as const) {
      const result = generateArtifact({ type });
      expect(result.html).toContain('<canvas id="viewer"');
    }
  });

  it("all 3D types include render loop", () => {
    for (const type of ["model-viewer", "scene", "product-360"] as const) {
      const result = generateArtifact({ type });
      expect(result.html).toContain("requestAnimationFrame(render)");
    }
  });

  it("all types use dark theme (#0d1117)", () => {
    const mvResult = generateArtifact({ type: "model-viewer" });
    const sceneResult = generateArtifact({ type: "scene" });
    const prodResult = generateArtifact({ type: "product-360" });
    const chartResult = generateArtifact({ type: "chart-3d", data: [{ label: "Q1", value: 100 }] });
    const geoResult = generateArtifact({ type: "geometry", shapes: [{ type: "cube" }] });

    expect(mvResult.html).toContain("#0d1117");
    expect(sceneResult.html).toContain("#0d1117");
    expect(prodResult.html).toContain("#0d1117");
    expect(chartResult.html).toContain("#0d1117");
    expect(geoResult.html).toContain("#0d1117");
  });

  it("geometry uses WebGL2 (not Filament.js)", () => {
    const result = generateArtifact({ type: "geometry", shapes: [{ type: "cube" }] });
    expect(result.html).not.toContain("filament.js");
    expect(result.html).toContain("webgl2");
  });
});
