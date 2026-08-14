import { describe, expect, it } from "vitest";
import {
  COLLISION_GUIDE,
  MATERIAL_GUIDE,
  MODEL_OPTIMIZATION_GUIDE,
  WEB_RENDERING_GUIDE,
} from "./extra-guides.js";

// ─── Material Guide Tests ───────────────────────────────────────────────────

describe("MATERIAL_GUIDE", () => {
  it("contains PBR material properties table", () => {
    expect(MATERIAL_GUIDE).toContain("baseColor");
    expect(MATERIAL_GUIDE).toContain("metallic");
    expect(MATERIAL_GUIDE).toContain("roughness");
    expect(MATERIAL_GUIDE).toContain("reflectance");
    expect(MATERIAL_GUIDE).toContain("clearCoat");
    expect(MATERIAL_GUIDE).toContain("emissive");
  });

  it("contains material recipes", () => {
    expect(MATERIAL_GUIDE).toContain("Glass");
    expect(MATERIAL_GUIDE).toContain("Chrome");
    expect(MATERIAL_GUIDE).toContain("Gold");
    expect(MATERIAL_GUIDE).toContain("Rubber");
    expect(MATERIAL_GUIDE).toContain("Car Paint");
  });

  it("contains Kotlin code samples", () => {
    expect(MATERIAL_GUIDE).toContain("@Composable");
    expect(MATERIAL_GUIDE).toContain("materialInstance");
    expect(MATERIAL_GUIDE).toContain("setBaseColor");
    expect(MATERIAL_GUIDE).toContain("setMetallic");
    expect(MATERIAL_GUIDE).toContain("setRoughness");
  });

  it("mentions IBL requirement for materials", () => {
    expect(MATERIAL_GUIDE).toContain("IBL");
    expect(MATERIAL_GUIDE).toContain("Image-Based Lighting");
    expect(MATERIAL_GUIDE).toContain("metallic surfaces appear black");
  });

  it("includes practical tips", () => {
    expect(MATERIAL_GUIDE).toContain("Tips");
    expect(MATERIAL_GUIDE).toContain("power-of-2");
    expect(MATERIAL_GUIDE).toContain("Normal maps");
  });
});

// ─── Collision Guide Tests ──────────────────────────────────────────────────

describe("COLLISION_GUIDE", () => {
  it("contains hit testing section", () => {
    expect(COLLISION_GUIDE).toContain("Hit Testing");
    expect(COLLISION_GUIDE).toContain("onTouchEvent");
    expect(COLLISION_GUIDE).toContain("isTouchable");
  });

  it("contains AR hit testing", () => {
    expect(COLLISION_GUIDE).toContain("AR Hit Testing");
    expect(COLLISION_GUIDE).toContain("hitTest");
    expect(COLLISION_GUIDE).toContain("ARScene");
  });

  it("contains KMP core collision primitives", () => {
    expect(COLLISION_GUIDE).toContain("Ray");
    expect(COLLISION_GUIDE).toContain("Box");
    expect(COLLISION_GUIDE).toContain("Sphere");
    expect(COLLISION_GUIDE).toContain("Intersections");
    expect(COLLISION_GUIDE).toContain("raySphere");
  });

  it("contains physics section", () => {
    expect(COLLISION_GUIDE).toContain("Physics");
    expect(COLLISION_GUIDE).toContain("PhysicsWorld");
    expect(COLLISION_GUIDE).toContain("RigidBody");
    expect(COLLISION_GUIDE).toContain("gravity");
    expect(COLLISION_GUIDE).toContain("restitution");
  });

  it("contains bounding box info", () => {
    expect(COLLISION_GUIDE).toContain("boundingBox");
    expect(COLLISION_GUIDE).toContain("halfExtent");
  });

  it("includes practical tips", () => {
    expect(COLLISION_GUIDE).toContain("Tips");
    expect(COLLISION_GUIDE).toContain("isTouchable = true");
  });
});

// ─── Model Optimization Guide Tests ─────────────────────────────────────────

describe("MODEL_OPTIMIZATION_GUIDE", () => {
  it("contains polygon budget table by device tier", () => {
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("High-end");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("Mid-range");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("Low-end");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("triangles");
  });

  it("contains file size targets", () => {
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("File Size Targets");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("GLB");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("Draco");
  });

  it("contains compression techniques", () => {
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("Draco Mesh Compression");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("Meshopt");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("KTX2");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("Basis Universal");
  });

  it("contains gltf-transform CLI commands", () => {
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("gltf-transform");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("npx");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("simplify");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("dedup");
  });

  it("contains texture optimization section", () => {
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("Texture Optimization");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("1024x1024");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("power-of-2");
  });

  it("contains LOD section", () => {
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("LOD");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("Level of Detail");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("distanceToCamera");
  });

  it("contains tools table", () => {
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("gltf.report");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("Blender");
    expect(MODEL_OPTIMIZATION_GUIDE).toContain("glTF Validator");
  });
});

// ─── Web Rendering Guide Tests ──────────────────────────────────────────────

describe("WEB_RENDERING_GUIDE", () => {
  it("describes Filament.js WASM architecture", () => {
    expect(WEB_RENDERING_GUIDE).toContain("Filament.js");
    expect(WEB_RENDERING_GUIDE).toContain("WebAssembly");
    expect(WEB_RENDERING_GUIDE).toContain("WebGL2");
  });

  it("contains sceneview.js quick start", () => {
    expect(WEB_RENDERING_GUIDE).toContain("sceneview.js");
    expect(WEB_RENDERING_GUIDE).toContain("cdn.jsdelivr.net");
    expect(WEB_RENDERING_GUIDE).toContain("modelViewer");
  });

  it("contains IBL environment section", () => {
    expect(WEB_RENDERING_GUIDE).toContain("IBL Environment Lighting");
    expect(WEB_RENDERING_GUIDE).toContain("neutral IBL");
    expect(WEB_RENDERING_GUIDE).toContain("metallic surfaces appear black");
    expect(WEB_RENDERING_GUIDE).toContain("noEnvironment");
  });

  it("contains quality settings table", () => {
    expect(WEB_RENDERING_GUIDE).toContain("SSAO");
    expect(WEB_RENDERING_GUIDE).toContain("Bloom");
    expect(WEB_RENDERING_GUIDE).toContain("TAA");
  });

  it("contains camera exposure table", () => {
    expect(WEB_RENDERING_GUIDE).toContain("Camera Exposure");
    expect(WEB_RENDERING_GUIDE).toContain("Studio");
    expect(WEB_RENDERING_GUIDE).toContain("Aperture");
    expect(WEB_RENDERING_GUIDE).toContain("ISO");
  });

  it("contains Filament.js vs model-viewer comparison", () => {
    expect(WEB_RENDERING_GUIDE).toContain("model-viewer");
    expect(WEB_RENDERING_GUIDE).toContain("Procedural geometry");
    expect(WEB_RENDERING_GUIDE).toContain("Web component");
  });

  it("contains Kotlin/JS example", () => {
    expect(WEB_RENDERING_GUIDE).toContain("SceneView.create");
    expect(WEB_RENDERING_GUIDE).toContain("autoRotate");
  });

  it("contains web performance tips", () => {
    expect(WEB_RENDERING_GUIDE).toContain("Performance Tips");
    expect(WEB_RENDERING_GUIDE).toContain("devicePixelRatio");
    expect(WEB_RENDERING_GUIDE).toContain("destroy()");
  });
});
