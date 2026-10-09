import { describe, it, expect } from "vitest";
import {
  generateCarPaintShader,
  PAINT_FINISHES,
} from "./car-paint-shader.js";

describe("PAINT_FINISHES", () => {
  it("includes the core four finishes", () => {
    expect(PAINT_FINISHES).toContain("solid");
    expect(PAINT_FINISHES).toContain("metallic");
    expect(PAINT_FINISHES).toContain("pearlescent");
    expect(PAINT_FINISHES).toContain("matte");
  });
});

describe("generateCarPaintShader", () => {
  it("returns a template with the expected shape", () => {
    const result = generateCarPaintShader();
    expect(result).toHaveProperty("materialDefinition");
    expect(result).toHaveProperty("kotlinUsage");
    expect(result).toHaveProperty("description");
    expect(result).toHaveProperty("dependencies");
    expect(Array.isArray(result.dependencies)).toBe(true);
  });

  it("generates a Filament .mat definition with the lit shading model", () => {
    const { materialDefinition } = generateCarPaintShader();
    expect(materialDefinition).toContain("material {");
    expect(materialDefinition).toContain("shadingModel : lit");
    expect(materialDefinition).toContain("name : carPaint");
  });

  it("declares all PBR + clearcoat parameters", () => {
    const { materialDefinition } = generateCarPaintShader();
    expect(materialDefinition).toContain("baseColor");
    expect(materialDefinition).toContain("metallic");
    expect(materialDefinition).toContain("roughness");
    expect(materialDefinition).toContain("clearCoat");
    expect(materialDefinition).toContain("clearCoatRoughness");
  });

  it("includes a clearcoat lacquer layer in the fragment shader", () => {
    const { materialDefinition } = generateCarPaintShader();
    expect(materialDefinition).toContain("material.clearCoat = materialParams.clearCoat");
    expect(materialDefinition).toContain(
      "material.clearCoatRoughness = materialParams.clearCoatRoughness",
    );
  });

  it("includes metallic flakes when finish is metallic", () => {
    const { materialDefinition } = generateCarPaintShader({ finish: "metallic" });
    expect(materialDefinition).toContain("Metallic flakes");
    expect(materialDefinition).toContain("flakeIntensity");
  });

  it("includes metallic flakes when finish is pearlescent", () => {
    const { materialDefinition } = generateCarPaintShader({ finish: "pearlescent" });
    expect(materialDefinition).toContain("Metallic flakes");
  });

  it("omits flakes for solid finish", () => {
    const { materialDefinition } = generateCarPaintShader({ finish: "solid" });
    expect(materialDefinition).not.toContain("Metallic flakes");
    expect(materialDefinition).toContain("Solid finish");
  });

  it("omits clearcoat gloss for matte finish", () => {
    const { kotlinUsage } = generateCarPaintShader({ finish: "matte" });
    // Matte = no clearcoat, so the parameter should be zero.
    expect(kotlinUsage).toContain('setParameter("clearCoat", 0.000f)');
    // And metallic should be forced to zero.
    expect(kotlinUsage).toContain('setParameter("metallic", 0.000f)');
  });

  it("emits a Kotlin SceneView snippet that loads the compiled filamat", () => {
    const { kotlinUsage } = generateCarPaintShader();
    expect(kotlinUsage).toContain("@Composable");
    expect(kotlinUsage).toContain("rememberEngine()");
    expect(kotlinUsage).toContain("rememberMaterialLoader");
    expect(kotlinUsage).toContain("rememberModelInstance");
    expect(kotlinUsage).toContain('materialLoader.createMaterial("materials/car_paint.filamat")');
    expect(kotlinUsage).toContain("ModelNode(");
  });

  it("wires each PBR parameter in the Kotlin snippet", () => {
    const { kotlinUsage } = generateCarPaintShader({ baseColorHex: "#0055AA" });
    expect(kotlinUsage).toContain('setParameter("baseColor"');
    expect(kotlinUsage).toContain('setParameter("metallic"');
    expect(kotlinUsage).toContain('setParameter("roughness"');
    expect(kotlinUsage).toContain('setParameter("clearCoat"');
    expect(kotlinUsage).toContain('setParameter("clearCoatRoughness"');
  });

  it("converts hex base color to linear RGB in the Kotlin snippet", () => {
    // Pure red in linear space is still ~1.0, 0.0, 0.0.
    const { kotlinUsage } = generateCarPaintShader({ baseColorHex: "#FF0000" });
    expect(kotlinUsage).toMatch(/setParameter\("baseColor", 1\.\d+f, 0\.0+f, 0\.0+f\)/);
  });

  it("uses the main-thread-safe rememberMaterialLoader pattern", () => {
    const { kotlinUsage } = generateCarPaintShader();
    expect(kotlinUsage).toContain("rememberMaterialLoader(engine)");
    // No background coroutine calls to createMaterial.
    expect(kotlinUsage).not.toMatch(/launch\s*{[^}]*createMaterial/);
  });

  it("lists SceneView 4.0.x as a dependency", () => {
    const { dependencies } = generateCarPaintShader();
    expect(dependencies.some((d) => d.includes("sceneview:4.53.0"))).toBe(true);
  });

  it("description reflects the chosen finish and base color", () => {
    const { description } = generateCarPaintShader({
      finish: "pearlescent",
      baseColorHex: "#D4AF37",
    });
    expect(description).toContain("pearlescent");
    expect(description).toContain("#D4AF37");
  });
});
