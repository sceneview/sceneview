# Recipe: Contact Shadow (procedural, works on walls)

**Intent:** "Ground an object against a surface — including a wall — with a soft contact
shadow, without a shadow-casting light aimed at that surface."

A model without a shadow reads as floating. On a **floor**, `ShadowReceiverPlane` catches a
*real* cast shadow — but it needs a light pointing at the floor. Indoors that light comes from
the ceiling: it faces the floor and merely *grazes* a wall, so a TV mounted flat against a wall
casts essentially nothing onto it and still reads as floating.

`ContactShadow` sidesteps the light entirely. It draws its own elliptical gradient **in the
shader** — no shadow map, no light dependency, deterministic at any angle and any surface
orientation. It is the procedural equivalent of the per-context shadow textures Amazon "AR View"
bakes into its app, done in-shader so no texture ships. It lives in `sceneview` (not
`arsceneview`): nothing about it is AR-specific, and a plain 3D scene grounds a model the same way.

## Android (Kotlin + Jetpack Compose)

```kotlin
import io.github.sceneview.node.ContactShadowContext
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size

@Composable
fun WallMountedTv() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val tv = rememberModelInstance(modelLoader, "models/tv.glb")

    SceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
    ) {
        // Your wall-mounted model, placed at the wall pose.
        tv?.let { ModelNode(modelInstance = it) }

        // The grounding pool — this is the whole recipe. A wide, faint haze pushed *below*
        // the panel, exactly where a grazing ceiling light would leave one. A real shadow
        // map cannot serve this case; a procedural pool can.
        ContactShadow(
            size = Size(x = 2.4f, y = 1.6f, z = 0f),   // XY quad (z = 0) → a WALL
            context = ContactShadowContext.Wall,
            normal = Direction(z = 1f),                // faces +Z, matching the XY quad
            position = Position(x = 0f, y = 1.3f, z = -1.99f),  // just in front of the wall
        )
    }
}
```

**Pick the context, not the numbers.** `ContactShadowContext` carries the gradient shape for
each situation — you rarely tune anything by hand:

| Context | Shape | When |
|---|---|---|
| `Floor` | centred, dense, round | object resting on the floor (light faces the surface) |
| `Wall` | fainter, wider-than-tall, pushed *below* | flat-mounted TV / framed art / mirror (light grazes the surface) |
| `TableTop` | tight, crisp | small object close to a table or shelf |

`intensity` overrides the peak opacity if you need to; everything else follows the context.

**Match `size` to `normal` — the one real footgun.** `Plane` does **not** rotate its geometry to
match its `normal` parameter, so the quad's plane is decided by which `size` component is zero:

| Surface | `size` | Plane | `normal` |
|---|---|---|---|
| Floor / tabletop | `Size(x, 0f, z)` | XZ | `Direction(y = 1f)` |
| Wall | `Size(x, y, 0f)` | XY | `Direction(z = 1f)` |

The node lifts itself off the surface along `normal` (5 mm, to avoid z-fighting), so a mismatched
`size` / `normal` pair either z-fights with the wall or floats visibly off it. Size the quad
generously — the gradient fades out well before the edge.

## iOS (Swift + SwiftUI)

RealityKit has its **own** grounding shadow — a projected `GroundingShadowComponent`, toggled
with `ARSceneView(groundingShadows:)` (on by default for placed models):

```swift
ARSceneView(groundingShadows: true)   // projected grounding shadow, on by default
```

That is a different mechanism: a real *projected* shadow whose look still depends on the scene
lighting — **not** this procedural, light-independent, per-context pool. So the Android
`ContactShadow` API is **not mirrored on iOS yet**; use the native `groundingShadows` toggle there.

## When to use which

| Need | Use |
|---|---|
| A **real** shadow with correct light-driven shape on a **floor** | `ShadowReceiverPlane` — see `samples/recipes/ground-shadow-catcher.md` |
| Grounding that survives **any** light direction — always on a **wall**, cheap deterministic fallback elsewhere | `ContactShadow` |

They compose: nothing stops a floor from catching a real shadow while a wall gets a procedural
one. The non-AR `ContactShadowPreviewDemo` (runs on any emulator, no ARCore) makes the value
visible: two identical boxes hop side by side — one grounded by a height-responsive contact
pool, one floating without it — plus a wall-mounted TV with switchable per-surface presets.

> **Platform status.** Android: available now. iOS: has `GroundingShadowComponent` (a different,
> projected mechanism); this procedural per-context pool is not mirrored yet. Web: coming soon.
