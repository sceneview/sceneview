# Recipe: Ground Shadow Catcher (3D scene)

**Intent:** "Show a soft contact shadow under a model in a non-AR `SceneView`, without a
visible floor"

A ground shadow makes a model read as *grounded* instead of floating in a void. In a 3D
scene you catch it with an **invisible flat quad** at the model's feet, rendered with a
shadow-only material — the quad writes just the shadow, never a visible surface.

> ⚠️ **The flat-quad trap (#2620 / #2699).** A flat (zero-Y) shadow *receiver* is a known
> foot-gun on real Filament Level-2+ devices: the geometry-derived bounding box has a **zero
> Y half-extent**, and that degenerate volume crashes the cascaded-shadow-map build on-device
> the first frame a caster overlaps it. The FL1 SwiftShader emulator never exercises that
> path, so it passes emulator QA and only bites a real user. Harden every flat catcher with
> the four calls below — the same recipe `arsceneview`'s `ShadowReceiverPlaneNode` uses for
> the AR case.

## Android (Kotlin + Jetpack Compose)

```kotlin
import com.google.android.filament.Box as FilamentBox
import com.google.android.filament.LightManager

@Composable
fun GroundShadowViewer() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val model = rememberModelInstance(modelLoader, "models/helmet.glb")

    // Shadow-only material: writes to the shadow buffer, no colour output, so the
    // quad itself is invisible. `plane_renderer_shadow.filamat` ships in arsceneview.
    val shadowMaterial = remember(materialLoader) {
        materialLoader.createMaterial("materials/plane_renderer_shadow.filamat")
            .createInstance()
    }
    DisposableEffect(shadowMaterial) {
        onDispose { materialLoader.destroyMaterialInstance(shadowMaterial) }
    }

    // Translucent render target so the shadow composites over whatever is behind
    // the SceneView (a shadow-only material only DARKENS the background).
    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        isOpaque = false,
        cameraManipulator = rememberCameraManipulator(),
    ) {
        model?.let { instance ->
            val he = instance.model.boundingBox.halfExtent   // [x, y, z] half-extents
            val groundY = -he[1]                             // model's bottom
            val planeSize = (maxOf(he[0], he[2]) * 6f).coerceAtLeast(1f)

            // A directional light with castShadows(true) is what actually produces
            // the shadow — no shadow-casting light, no shadow.
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                intensity = 80_000f,
                direction = Direction(x = -0.5f, y = -1f, z = -0.4f),
                apply = { castShadows(true) },
            )

            // The invisible catcher. MUST be an XZ (horizontal) quad — Size(x, 0f, z),
            // not XY — or the shadow material's forced `pos.y` collapses it to a line.
            PlaneNode(
                size = Size(x = planeSize, y = 0f, z = planeSize),
                materialInstance = shadowMaterial,
                position = Position(x = 0f, y = groundY, z = 0f),
                apply = {
                    // ── FL2+ flat-receiver hardening (do not skip any of the four) ──
                    isShadowCaster = false      // a flat catcher never casts
                    isShadowReceiver = true     // it only receives
                    setCulling(false)           // a zero-thickness quad must skip
                                                // frustum culling (degenerate volume)
                    // Non-degenerate AABB (node-local): span floor → model top so the
                    // box has a real Y extent AND encloses the caster above it.
                    val halfHeight = he[1].coerceAtLeast(0.5f)
                    axisAlignedBoundingBox = FilamentBox(
                        0f, halfHeight, 0f,               // center
                        planeSize / 2f, halfHeight, planeSize / 2f,  // half-extents
                    )
                },
            )

            ModelNode(modelInstance = instance)   // casts the shadow (castShadows on by default)
        }
    }
}
```

## iOS (Swift + SwiftUI)

RealityKit renders the contact shadow itself — no catcher geometry, and no flat-quad trap.
Attach a `GroundingShadowComponent` to the model (SceneViewSwift does this automatically for
placed models); there is nothing to harden.

```swift
struct GroundShadowViewer: View {
    @State private var model: ModelNode?

    var body: some View {
        SceneView { root in
            if let model { root.addChild(model.entity) }
        }
        .cameraControls(.orbit)
        .environment(.studio)
        .task {
            model = try? await ModelNode.load("models/helmet.usdz")
            // RealityKit adds GroundingShadowComponent(castsShadow: true) — no catcher quad.
        }
    }
}
```

## Key concepts

| Concept | Android (Filament) | iOS (RealityKit) |
|---|---|---|
| Shadow surface | Invisible `PlaneNode` + shadow-only material | Automatic `GroundingShadowComponent` |
| Casting light | `LightNode(DIRECTIONAL) { castShadows(true) }` | Scene environment light |
| Flat-quad hardening | `isShadowCaster=false` · `isShadowReceiver=true` · `setCulling(false)` · non-degenerate AABB | N/A (no catcher geometry) |
| Visible-through backdrop | `isOpaque = false` | Native compositing |

> **Why the AABB matters.** Filament culls and focuses the shadow cascade from renderable
> bounds. A flat quad's auto-derived box is degenerate in Y; overriding it with a box that
> spans floor → model top keeps the cascade build valid on-device (#2620) *and* keeps the
> caster inside the receiver's shadow-focus region. `arsceneview`'s `ShadowReceiverPlaneNode`
> bakes this same recipe in for the AR case — this recipe is its non-AR equivalent (#2699).
