/**
 * extra-guides.ts
 *
 * Material, collision, model optimization, and web rendering guides.
 */

import {
  FILAMENT_WEB_NPM_VERSION,
  FILAMENT_WEBSITE_VERSION,
  LATEST_SCENEVIEW_RELEASE,
} from "./generated/version.js";

// ─── Material Guide ─────────────────────────────────────────────────────────

export const MATERIAL_GUIDE = `# SceneView Material & Shader Guide

## PBR Material Properties

SceneView uses **Filament's PBR material model** — the same physically-based rendering used by Google Maps, Android Auto, and model-viewer. Materials are defined by these core properties:

| Property | Range | Description |
|----------|-------|-------------|
| **baseColor** | RGB [0-1] | Albedo color (diffuse color for non-metals) |
| **metallic** | 0.0–1.0 | 0 = dielectric (plastic, wood), 1 = metal (gold, chrome) |
| **roughness** | 0.0–1.0 | 0 = mirror, 1 = matte |
| **reflectance** | 0.0–1.0 | F0 reflectance for dielectrics (default 0.5 = 4% reflectance) |
| **emissive** | RGB [0-∞] | Self-illumination color (HDR values > 1 for bloom) |
| **ambientOcclusion** | 0.0–1.0 | Baked contact shadows |
| **normal** | RGB normal map | Surface detail without geometry |
| **clearCoat** | 0.0–1.0 | Lacquer/varnish layer (car paint, ceramic) |
| **clearCoatRoughness** | 0.0–1.0 | Roughness of the clear coat layer |

## Accessing Materials on ModelNode

\`\`\`kotlin
@Composable
fun CustomMaterialScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/chair.glb")

    SceneView(engine = engine) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 1.0f
            ) {
                // Access material instances on the model
                instance.materialInstances.forEach { materialInstance ->
                    // Change base color to red
                    materialInstance.setBaseColor(1f, 0f, 0f, 1f)
                    // Make it metallic
                    materialInstance.setMetallic(0.9f)
                    // Make it shiny
                    materialInstance.setRoughness(0.1f)
                }
            }
        }
    }
}
\`\`\`

## Applying Textures

\`\`\`kotlin
@Composable
fun TexturedModelScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/object.glb")

    SceneView(engine = engine) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 1.0f
            )
        }
    }
}
\`\`\`

## Common Material Recipes

### Glass / Transparent
\`\`\`kotlin
materialInstance.setBaseColor(0.9f, 0.9f, 1f, 0.3f) // transparent
materialInstance.setMetallic(0f)
materialInstance.setRoughness(0f) // perfectly smooth
materialInstance.setReflectance(0.5f)
\`\`\`

### Chrome / Mirror Metal
\`\`\`kotlin
materialInstance.setBaseColor(0.95f, 0.95f, 0.95f, 1f)
materialInstance.setMetallic(1f)
materialInstance.setRoughness(0.05f)
\`\`\`

### Gold
\`\`\`kotlin
materialInstance.setBaseColor(1f, 0.766f, 0.336f, 1f)
materialInstance.setMetallic(1f)
materialInstance.setRoughness(0.3f)
\`\`\`

### Rubber / Matte Plastic
\`\`\`kotlin
materialInstance.setBaseColor(0.2f, 0.2f, 0.2f, 1f)
materialInstance.setMetallic(0f)
materialInstance.setRoughness(0.9f)
\`\`\`

### Car Paint (Clear Coat)
\`\`\`kotlin
materialInstance.setBaseColor(0.8f, 0.0f, 0.0f, 1f) // red car
materialInstance.setMetallic(0.0f)
materialInstance.setRoughness(0.4f)
materialInstance.setClearCoat(1f)
materialInstance.setClearCoatRoughness(0.1f)
\`\`\`

## Environment Lighting for Materials

For PBR materials to look correct, you MUST have **IBL (Image-Based Lighting)**. Without it, metallic surfaces appear black and reflections are missing.

SceneView Android loads a default neutral IBL automatically. For custom environments:

\`\`\`kotlin
val engine = rememberEngine()
val environmentLoader = rememberEnvironmentLoader(engine)

SceneView(
    engine = engine,
    environment = environmentLoader.createHDREnvironment("environments/studio_2k.hdr")!!
) {
    // Your nodes here
}
\`\`\`

## Tips

1. **Always check IBL first** — if materials look flat/dark, you're missing environment lighting
2. **Metallic = 0 or 1** — intermediate values are physically rare (use only for worn metal)
3. **Roughness matters most** — it controls the "feel" more than any other property
4. **Normal maps are free detail** — use them instead of adding geometry
5. **Keep textures power-of-2** — 1024x1024, 2048x2048 for best GPU performance
`;

// ─── Collision & Physics Guide ──────────────────────────────────────────────

export const COLLISION_GUIDE = `# SceneView Collision & Physics Guide

## Hit Testing (Tap on 3D Objects)

SceneView supports hit testing via \`onTouchEvent\` on nodes. The \`hitResult\` gives you the exact 3D point where the user tapped.

### Basic Node Tapping

\`\`\`kotlin
@Composable
fun TappableModelScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/button.glb")
    var tapped by remember { mutableStateOf(false) }

    SceneView(engine = engine) {
        modelInstance?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 0.5f,
                centerOrigin = Position(y = -0.5f)
            ) {
                // Make node respond to touch
                isTouchable = true
                onTouchEvent = { hitResult, motionEvent ->
                    if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                        tapped = true
                    }
                    true
                }
            }
        }
    }
}
\`\`\`

## AR Hit Testing (Tap to Place)

In AR, hit testing detects real-world surfaces:

\`\`\`kotlin
@Composable
fun ARTapToPlaceScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/chair.glb")

    ARSceneView(
        engine = engine,
        sessionConfiguration = { session, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        },
        onSessionUpdated = { session, frame ->
            // frame.hitTest() gives real-world surface intersections
        }
    ) {
        // Place model at anchor when user taps
    }
}
\`\`\`

## Collision Detection (KMP Core)

SceneView's KMP core module (\`sceneview-core\`) provides cross-platform collision detection:

### Ray-Box Intersection
\`\`\`kotlin
import io.github.sceneview.collision.Ray
import io.github.sceneview.collision.Box
import io.github.sceneview.collision.Intersections

val ray = Ray(
    origin = Float3(0f, 1f, 5f),
    direction = Float3(0f, 0f, -1f)
)

val box = Box(
    center = Float3(0f, 0f, 0f),
    halfExtent = Float3(1f, 1f, 1f)
)

val hit = Intersections.rayBox(ray, box)
if (hit != null) {
    println("Hit at distance: \${hit.distance}")
    println("Hit point: \${hit.point}")
}
\`\`\`

### Ray-Sphere Intersection
\`\`\`kotlin
import io.github.sceneview.collision.Sphere

val sphere = Sphere(
    center = Float3(0f, 1f, 0f),
    radius = 0.5f
)

val hit = Intersections.raySphere(ray, sphere)
\`\`\`

## Physics Simulation (KMP Core)

The \`sceneview-core\` module includes basic rigid body physics:

\`\`\`kotlin
import io.github.sceneview.physics.PhysicsWorld
import io.github.sceneview.physics.RigidBody

val world = PhysicsWorld(gravity = Float3(0f, -9.81f, 0f))

val ball = RigidBody(
    position = Float3(0f, 5f, 0f),
    mass = 1f,
    restitution = 0.8f // bounciness
)
world.addBody(ball)

// In your render loop:
world.step(deltaTime)
// ball.position is updated by physics
\`\`\`

## Node Bounding Box

Every node has a bounding box for collision:

\`\`\`kotlin
ModelNode(modelInstance = instance) {
    // Access bounding box after model loads
    val box = boundingBox
    println("Size: \${box.halfExtent * 2f}")
    println("Center: \${box.center}")
}
\`\`\`

## Tips

1. **Use \`isTouchable = true\`** — nodes are not touchable by default
2. **AR hit test with \`frame.hitTest()\`** — returns surfaces sorted by distance
3. **BoundingBox for broad-phase** — check box overlap before expensive ray tests
4. **Physics is optional** — import \`sceneview-core\` separately from \`sceneview\`
`;

// ─── Model Optimization Guide ───────────────────────────────────────────────

export const MODEL_OPTIMIZATION_GUIDE = `# 3D Model Optimization for SceneView

## Polygon Budgets

| Device Tier | Total Scene Budget | Per-Model Budget |
|-------------|-------------------|-----------------|
| **High-end** (Pixel 8, Samsung S24) | 500K–1M triangles | 200K triangles |
| **Mid-range** (Pixel 6a, Samsung A54) | 200K–500K triangles | 100K triangles |
| **Low-end** | 50K–200K triangles | 50K triangles |
| **AR scenes** (battery + thermal) | 100K–300K triangles | 50K–100K triangles |

## File Size Targets

| Format | Good | Acceptable | Too Large |
|--------|------|------------|-----------|
| **GLB** | < 5 MB | 5–20 MB | > 20 MB |
| **With Draco** | < 2 MB | 2–10 MB | > 10 MB |
| **Textures** (total) | < 8 MB | 8–32 MB | > 32 MB |

## Compression

### Draco Mesh Compression
Reduces geometry size 5–10x. Supported natively by SceneView/Filament.

\`\`\`bash
# Using gltf-transform CLI
npx gltf-transform draco input.glb output.glb

# Aggressive compression
npx gltf-transform draco input.glb output.glb \\
  --quantize-position 14 \\
  --quantize-normal 10
\`\`\`

### Meshopt Compression
Alternative to Draco with faster decompression:

\`\`\`bash
npx gltf-transform meshopt input.glb output.glb
\`\`\`

### KTX2 Texture Compression (Basis Universal)
Reduces texture memory 4–6x. GPU-native — no decompression needed.

\`\`\`bash
# Convert all textures to KTX2
npx gltf-transform ktx2 input.glb output.glb --slots "baseColor,normal,emissive"

# With specific quality
npx gltf-transform ktx2 input.glb output.glb --quality 128
\`\`\`

## Optimization Pipeline

The recommended optimization pipeline for production models:

\`\`\`bash
# 1. Simplify high-poly meshes
npx gltf-transform simplify input.glb step1.glb --ratio 0.5

# 2. Merge duplicate meshes/materials
npx gltf-transform dedup step1.glb step2.glb

# 3. Compress textures to KTX2
npx gltf-transform ktx2 step2.glb step3.glb

# 4. Apply Draco compression
npx gltf-transform draco step3.glb output.glb

# 5. Verify
npx gltf-transform inspect output.glb
\`\`\`

## Texture Optimization

| Texture Type | Recommended Size | Format |
|-------------|-----------------|--------|
| **Base Color** | 1024x1024 | KTX2 (sRGB) |
| **Normal Map** | 1024x1024 | KTX2 (linear) |
| **ORM** (AO/Roughness/Metallic) | 512x512 | KTX2 (linear) |
| **Emissive** | 512x512 | KTX2 (sRGB) |

### Power of 2 Rule
Always use power-of-2 textures (256, 512, 1024, 2048). Non-power-of-2 wastes GPU memory because the driver pads them.

## LOD (Level of Detail)

For complex scenes, use multiple detail levels:

\`\`\`kotlin
ModelNode(
    modelInstance = when {
        distanceToCamera < 5f -> highDetailInstance
        distanceToCamera < 20f -> medDetailInstance
        else -> lowDetailInstance
    }
)
\`\`\`

## Quick Wins

1. **Remove invisible geometry** — interiors users can't see, back faces, underground parts
2. **Merge materials** — fewer materials = fewer draw calls = better performance
3. **Atlas textures** — combine multiple small textures into one atlas
4. **Bake lighting** — for static scenes, bake shadows into the AO map
5. **Use instancing** — for repeated objects (trees, buildings), use instanced rendering

## Tools

| Tool | Purpose | Link |
|------|---------|------|
| **gltf-transform** | CLI optimizer (Draco, KTX2, simplify) | npm install @gltf-transform/cli |
| **glTF Validator** | Check for errors | github.com/KhronosGroup/glTF-Validator |
| **gltf.report** | Web-based analyzer | gltf.report |
| **RapidCompact** | Auto-optimization service | rapidcompact.com |
| **Blender** | Manual editing + export | blender.org |
`;

// ─── Web Rendering Guide ────────────────────────────────────────────────────

export const WEB_RENDERING_GUIDE = `# SceneView Web Rendering Guide (Filament.js)

## Architecture

SceneView Web uses **Filament.js v${FILAMENT_WEBSITE_VERSION}** (the runtime vendored with \`sceneview.js\`; the Kotlin/JS build pins the npm \`filament\` package at v${FILAMENT_WEB_NPM_VERSION}) — Google's Filament engine compiled to WebAssembly. This is the **same PBR rendering engine** as SceneView Android, ensuring visual parity.

\`\`\`
Browser → WebGL2 → Filament.js (WASM) → GPU
\`\`\`

**Bundle size:** ~215KB JS + 3.3MB WASM (~3.5MB total)

## Quick Start

### Using sceneview.js (npm or local)
\`\`\`html
<!-- Option 1: npm CDN -->
<script src="https://cdn.jsdelivr.net/npm/sceneview-web@${LATEST_SCENEVIEW_RELEASE}/sceneview-web.js"></script>

<!-- Option 2: local hosting (recommended for production) -->
<!-- Copy js/filament/ directory to your server for faster WASM loading -->
<script src="js/filament/filament.js"></script>
<script src="sceneview.js"></script>

<canvas id="viewer" style="width:100%;height:400px"></canvas>
<script>
  // One-liner: creates viewer with KTX IBL, orbit controls, auto-rotate
  sceneview.modelViewer("viewer", "model.glb");
</script>
\`\`\`

### Using Kotlin/JS (Gradle)
\`\`\`kotlin
SceneView.create(canvas) {
    camera {
        eye(0.0, 1.5, 5.0)
        target(0.0, 0.0, 0.0)
    }
    // IBL environment loaded automatically!
    model("models/helmet.glb")
    autoRotate(true)
}
\`\`\`

## IBL Environment Lighting

**Critical for PBR quality.** Without IBL, metallic surfaces appear black.

SceneView Web loads a **default neutral IBL** automatically (same as Android) using **KTX format** for real PBR reflections. You can override it:

\`\`\`kotlin
SceneView.create(canvas) {
    environment(
        iblUrl = "environments/studio_ibl.ktx",
        skyboxUrl = "environments/studio_skybox.ktx" // optional
    )
    model("model.glb")
}
\`\`\`

To disable IBL:
\`\`\`kotlin
SceneView.create(canvas) {
    noEnvironment()
    light { directional(); intensity(120_000.0) }
    model("model.glb")
}
\`\`\`

## Rendering Quality Settings

SceneView Web enables these quality features by default:

| Feature | Default | Effect |
|---------|---------|--------|
| **SSAO** | Enabled | Soft contact shadows between surfaces |
| **Bloom** | Enabled (subtle) | Glow on bright/emissive surfaces |
| **TAA** | Enabled | Temporal anti-aliasing for smooth edges |
| **IBL** | Neutral environment | Physically-correct reflections |

### Custom Quality Settings

Access the Filament View directly for advanced tuning:

\`\`\`kotlin
SceneView.create(canvas) { /* ... */ }.let { sv ->
    // Disable SSAO for low-end devices
    sv.view.setAmbientOcclusionOptions(js("({enabled: false})"))

    // Stronger bloom
    sv.view.setBloomOptions(js("({enabled: true, strength: 0.3, levels: 6})"))
}
\`\`\`

## Camera Exposure

Camera exposure controls overall brightness. The default is tuned for IBL-lit scenes:

| Scenario | Aperture | Shutter | ISO | Look |
|----------|----------|---------|-----|------|
| **Studio** (default) | 16.0 | 1/125 | 100 | Balanced, neutral |
| **Bright outdoor** | 16.0 | 1/250 | 100 | Darker, contrasty |
| **Indoor/dim** | 2.8 | 1/60 | 800 | Brighter, softer |
| **Night** | 1.4 | 1/30 | 3200 | Very bright |

\`\`\`kotlin
camera {
    exposure(aperture = 16.0, shutterSpeed = 1.0 / 125.0, sensitivity = 100.0)
}
\`\`\`

## Filament.js vs model-viewer

| Feature | SceneView (Filament.js) | model-viewer |
|---------|------------------------|--------------|
| **Engine** | Filament v${FILAMENT_WEBSITE_VERSION} WASM | Filament WASM (same engine) |
| **Bundle size** | ~215KB JS + 3.3MB WASM | ~800 KB (subset) |
| **Procedural geometry** | Yes (cubes, spheres, etc.) | No |
| **Custom materials** | Yes (full Filament API) | Limited |
| **Multi-model scenes** | Yes | No |
| **AR (WebXR)** | Yes | Yes |
| **Performance** | Full control | Optimized defaults |
| **API** | Programmatic (JS/Kotlin) | Web component (\`<model-viewer>\`) |

**When to use model-viewer:** Single model viewing with minimal code.
**When to use SceneView Web:** Complex scenes, procedural content, multi-model, custom materials, or Android visual parity.

## Performance Tips for Web

1. **Lazy-load WASM** — Filament.js is ~3.5MB total (215KB JS + 3.3MB WASM), defer loading until needed
2. **Use Draco-compressed GLB** — reduces download time significantly
3. **Limit draw calls** — fewer materials = fewer calls = better FPS
4. **Canvas size matters** — on mobile, render at \`devicePixelRatio * 0.75\` for 2x FPS
5. **Dispose when hidden** — call \`sceneView.destroy()\` when the viewer is off-screen
`;
