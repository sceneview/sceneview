import { describe, expect, it } from "vitest";
import { buildPreviewUrl, formatPreviewResponse, validatePreviewInput } from "./preview.js";

// ─── buildPreviewUrl ──────────────────────────────────────────────────────────

describe("buildPreviewUrl", () => {
  it("builds a URL with a model URL", () => {
    const result = buildPreviewUrl({
      modelUrl: "https://example.com/model.glb",
    });
    expect(result.previewUrl).toContain("sceneview.github.io/preview?");
    expect(result.previewUrl).toContain("model=https%3A%2F%2Fexample.com%2Fmodel.glb");
    expect(result.modelUrl).toBe("https://example.com/model.glb");
    expect(result.hasCode).toBe(false);
    expect(result.title).toBe("3D Model Preview");
  });

  it("uses default model when only code snippet is provided", () => {
    const result = buildPreviewUrl({
      codeSnippet: "SceneView(engine = engine) { ModelNode(...) }",
    });
    expect(result.previewUrl).toContain("model=");
    expect(result.previewUrl).toContain("DamagedHelmet.glb");
    expect(result.previewUrl).toContain("code=");
    expect(result.hasCode).toBe(true);
    expect(result.title).toBe("SceneView Code Preview");
  });

  it("includes both model and code when both are provided", () => {
    const result = buildPreviewUrl({
      modelUrl: "https://example.com/car.glb",
      codeSnippet: "SceneView(engine = engine) { }",
    });
    expect(result.previewUrl).toContain("model=https%3A%2F%2Fexample.com%2Fcar.glb");
    expect(result.previewUrl).toContain("code=");
    expect(result.modelUrl).toBe("https://example.com/car.glb");
    expect(result.hasCode).toBe(true);
  });

  it("disables auto-rotate when autoRotate=false", () => {
    const result = buildPreviewUrl({
      modelUrl: "https://example.com/model.glb",
      autoRotate: false,
    });
    expect(result.previewUrl).toContain("rotate=false");
  });

  it("does not add rotate param when autoRotate=true (default)", () => {
    const result = buildPreviewUrl({
      modelUrl: "https://example.com/model.glb",
      autoRotate: true,
    });
    expect(result.previewUrl).not.toContain("rotate=");
  });

  it("disables AR when ar=false", () => {
    const result = buildPreviewUrl({
      modelUrl: "https://example.com/model.glb",
      ar: false,
    });
    expect(result.previewUrl).toContain("ar=false");
  });

  it("does not add ar param when ar=true (default)", () => {
    const result = buildPreviewUrl({
      modelUrl: "https://example.com/model.glb",
    });
    expect(result.previewUrl).not.toContain("ar=false");
  });

  it("includes custom title in URL", () => {
    const result = buildPreviewUrl({
      modelUrl: "https://example.com/model.glb",
      title: "My Custom Scene",
    });
    expect(result.previewUrl).toContain("title=My+Custom+Scene");
    expect(result.title).toBe("My Custom Scene");
  });

  it("trims whitespace-only code snippets", () => {
    const result = buildPreviewUrl({
      modelUrl: "https://example.com/model.glb",
      codeSnippet: "   ",
    });
    expect(result.hasCode).toBe(false);
    expect(result.previewUrl).not.toContain("code=");
  });
});

// ─── validatePreviewInput ─────────────────────────────────────────────────────

describe("validatePreviewInput", () => {
  it("returns error when both inputs are missing", () => {
    const err = validatePreviewInput(undefined, undefined);
    expect(err).toContain("At least one");
  });

  it("returns error when both inputs are empty strings", () => {
    const err = validatePreviewInput("", "");
    expect(err).toContain("At least one");
  });

  it("returns null for valid model URL", () => {
    expect(validatePreviewInput("https://example.com/model.glb")).toBeNull();
  });

  it("returns null for valid code snippet only", () => {
    expect(validatePreviewInput(undefined, "SceneView(engine) { }")).toBeNull();
  });

  it("returns error for non-HTTP URL", () => {
    const err = validatePreviewInput("ftp://example.com/model.glb");
    expect(err).toContain("HTTP(S)");
  });

  it("returns error for non-GLB/GLTF URL", () => {
    const err = validatePreviewInput("https://example.com/model.obj");
    expect(err).toContain(".glb or .gltf");
  });

  it("accepts .gltf URLs", () => {
    expect(validatePreviewInput("https://example.com/scene.gltf")).toBeNull();
  });

  it("accepts URLs with query parameters after .glb", () => {
    expect(validatePreviewInput("https://example.com/model.glb?token=abc")).toBeNull();
  });
});

// ─── formatPreviewResponse ──────────────────────────────────────────────────

describe("formatPreviewResponse", () => {
  it("includes the preview URL and model URL", () => {
    const text = formatPreviewResponse({
      previewUrl: "https://sceneview.github.io/preview?model=x",
      modelUrl: "https://example.com/model.glb",
      hasCode: false,
      title: "3D Model Preview",
    });
    expect(text).toContain("https://sceneview.github.io/preview?model=x");
    expect(text).toContain("https://example.com/model.glb");
    expect(text).toContain("3D Model Preview");
    expect(text).not.toContain("companion panel");
  });

  it("mentions companion panel when code is included", () => {
    const text = formatPreviewResponse({
      previewUrl: "https://sceneview.github.io/preview?model=x&code=y",
      modelUrl: "https://example.com/model.glb",
      hasCode: true,
      title: "SceneView Code Preview",
    });
    expect(text).toContain("companion panel");
  });

  it("includes SceneView branding", () => {
    const text = formatPreviewResponse({
      previewUrl: "https://sceneview.github.io/preview?model=x",
      modelUrl: "https://example.com/model.glb",
      hasCode: false,
      title: "Test",
    });
    expect(text).toContain("Powered by SceneView");
  });
});
