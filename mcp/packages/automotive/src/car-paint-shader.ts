// ─── Car paint shader (Filament .mat) generator ──────────────────────────────

export type PaintFinish =
  | "solid"
  | "metallic"
  | "pearlescent"
  | "matte";

export const PAINT_FINISHES: PaintFinish[] = [
  "solid", "metallic", "pearlescent", "matte",
];

export interface CarPaintShaderOptions {
  /** Hex base color, e.g. "#CC0000". Default: "#CC0000" (racing red). */
  baseColorHex?: string;
  /** How much of the base is metal-like. 0.0..1.0. Default: 0.9. */
  metallic?: number;
  /** Base layer roughness. 0.0 = mirror, 1.0 = chalk. Default: 0.35. */
  roughness?: number;
  /** Clearcoat intensity. 0.0..1.0. Default: 1.0. */
  clearcoat?: number;
  /** Clearcoat smoothness. Lower = glossier lacquer. Default: 0.05. */
  clearcoatRoughness?: number;
  /** Paint finish style. Default: "metallic". */
  finish?: PaintFinish;
}

export interface CarPaintShaderTemplate {
  /** Filament `.mat` source ready to be fed to `matc`. */
  materialDefinition: string;
  /** Kotlin snippet showing how to load and apply the material in SceneView. */
  kotlinUsage: string;
  /** Human-readable description of the shader. */
  description: string;
  /** Build / runtime dependencies required to compile and run the shader. */
  dependencies: string[];
}

/**
 * Generates a realistic car-paint Filament material definition with a
 * clearcoat layer and optional metallic flakes, plus a tiny Kotlin snippet
 * showing how to load the compiled .filamat asset and apply it to a
 * ModelNode's material instance in SceneView.
 */
export function generateCarPaintShader(
  options: CarPaintShaderOptions = {},
): CarPaintShaderTemplate {
  const {
    baseColorHex = "#CC0000",
    metallic = 0.9,
    roughness = 0.35,
    clearcoat = 1.0,
    clearcoatRoughness = 0.05,
    finish = "metallic",
  } = options;

  const { r, g, b } = hexToLinearRgb(baseColorHex);
  const hasFlakes = finish === "metallic" || finish === "pearlescent";
  const flakeIntensity = finish === "pearlescent" ? 0.6 : 0.35;

  // Finish-specific overrides for realistic look.
  const effectiveMetallic = finish === "matte" ? 0.0 : metallic;
  const effectiveRoughness =
    finish === "matte" ? 0.85 : finish === "pearlescent" ? 0.25 : roughness;
  const effectiveClearcoat = finish === "matte" ? 0.0 : clearcoat;

  const materialDefinition = `// car_paint.mat — SceneView / Filament realistic car paint.
// Compile with: matc -p mobile -a opengl -o car_paint.filamat car_paint.mat
// Place the compiled .filamat in src/main/assets/materials/car_paint.filamat

material {
    name : carPaint,
    shadingModel : lit,
    requires : [ uv0, tangents ],
    parameters : [
        { type : float3, name : baseColor },
        { type : float,  name : metallic },
        { type : float,  name : roughness },
        { type : float,  name : clearCoat },
        { type : float,  name : clearCoatRoughness },
        { type : float,  name : flakeIntensity },
        { type : float,  name : flakeScale }
    ]
}

fragment {
    void material(inout MaterialInputs material) {
        prepareMaterial(material);

        // ── Base coat ────────────────────────────────────────────────────
        vec3 base = materialParams.baseColor;
${hasFlakes ? `
        // ── Metallic flakes — cheap procedural sparkle (no flake texture needed)
        vec2 flakeUv = getUV0() * materialParams.flakeScale;
        float flake = fract(sin(dot(floor(flakeUv), vec2(12.9898, 78.233))) * 43758.5453);
        float flakeMask = step(0.92, flake) * materialParams.flakeIntensity;
        base += vec3(flakeMask);
` : `
        // Solid finish — no flakes.
`}
        material.baseColor.rgb = base;
        material.metallic = materialParams.metallic;
        material.roughness = materialParams.roughness;

        // ── Clearcoat lacquer ────────────────────────────────────────────
        material.clearCoat = materialParams.clearCoat;
        material.clearCoatRoughness = materialParams.clearCoatRoughness;
    }
}
`;

  const kotlinUsage = `package com.example.automotive.paint

import androidx.compose.runtime.*
import com.google.android.filament.MaterialInstance
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/**
 * Applies the compiled car_paint.filamat to a loaded car model.
 *
 * Threading: materialLoader.createMaterial(...) must run on the main
 * thread. Call it from a composable (as below) or wrap it in
 * withContext(Dispatchers.Main) if you need to call it imperatively.
 */
@Composable
fun PaintedCarScene(modelAsset: String = "models/cars/sports_car.glb") {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    val modelInstance = rememberModelInstance(modelLoader, modelAsset)

    // Main-thread material creation — one instance of the compiled paint,
    // configured once and reused for every submesh.
    val paint: MaterialInstance = remember(materialLoader) {
        materialLoader.createInstance(
            materialLoader.createMaterial("materials/car_paint.filamat")
        ).also {
            // Base color (linear RGB, matches the hex passed to the generator).
            it.setParameter("baseColor", ${r.toFixed(4)}f, ${g.toFixed(4)}f, ${b.toFixed(4)}f)
            it.setParameter("metallic", ${effectiveMetallic.toFixed(3)}f)
            it.setParameter("roughness", ${effectiveRoughness.toFixed(3)}f)
            it.setParameter("clearCoat", ${effectiveClearcoat.toFixed(3)}f)
            it.setParameter("clearCoatRoughness", ${clearcoatRoughness.toFixed(3)}f)
            it.setParameter("flakeIntensity", ${hasFlakes ? flakeIntensity.toFixed(2) : "0.0"}f)
            it.setParameter("flakeScale", 320.0f)
        }
    }

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        environment = environmentLoader.createHDREnvironment(
            assetFileLocation = "environments/studio_hdr.ktx"
        )!!,
    ) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 2.5f,
                // Swap every submesh material with our custom paint.
                apply = { setMaterialInstance(paint) }
            )
        }
    }
}
`;

  const description =
    `Realistic ${finish} car paint Filament material with a clearcoat layer` +
    (hasFlakes ? " and procedural metallic flakes" : "") +
    `. Base color ${baseColorHex}, metallic ${effectiveMetallic}, roughness ${effectiveRoughness}, clearcoat ${effectiveClearcoat}.`;

  const dependencies = [
    "io.github.sceneview:sceneview:4.53.0",
    "Filament 1.72.x (matc compiler — must match the Filament version SceneView ships) to build the .mat into .filamat",
  ];

  return {
    materialDefinition,
    kotlinUsage,
    description,
    dependencies,
  };
}

/**
 * Converts a `#RRGGBB` hex string to linear-space RGB (0..1) the way
 * Filament expects it — sRGB gamma removed.
 */
function hexToLinearRgb(hex: string): { r: number; g: number; b: number } {
  const clean = hex.replace(/^#/, "");
  if (clean.length !== 6) {
    return { r: 0.8, g: 0.0, b: 0.0 };
  }
  const srgb = [
    parseInt(clean.slice(0, 2), 16) / 255,
    parseInt(clean.slice(2, 4), 16) / 255,
    parseInt(clean.slice(4, 6), 16) / 255,
  ].map((c) => (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)));
  return { r: srgb[0], g: srgb[1], b: srgb[2] };
}
