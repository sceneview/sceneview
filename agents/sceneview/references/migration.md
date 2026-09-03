# Migrating to SceneView 4.x

The full migration guide lives at
[`docs/docs/migration.md`](https://github.com/sceneview/sceneview/blob/main/docs/docs/migration.md)
in the repo — read that for the per-API rename map and version-by-version
breakers. This local pointer file just calls out the patterns AI agents most
often need to rewrite.

## Entrypoint rename (3.x → 4.x)

| Old | New |
| --- | --- |
| `Scene { … }` | `SceneView { … }` |
| `ArScene { … }` | `ARSceneView { … }` |

Artifacts unchanged: `io.github.sceneview:sceneview:4.34.0` for 3D-only,
`io.github.sceneview:arsceneview:4.34.0` for AR.

## Model loading

| Old (3.x) | New (4.x) |
| --- | --- |
| `modelLoader.createModel("model.glb")` (sync, blocks) | `rememberModelInstance(modelLoader, "model.glb")` (Composable, **nullable while loading**) |
| `modelLoader.createModelInstance(model)` | `rememberModelInstance(modelLoader, instance)` |

**Always handle the null:** the first recomposition pass returns `null`.

```kotlin
val helmet = rememberModelInstance(modelLoader, "models/helmet.glb")
helmet?.let { ModelNode(modelInstance = it, scaleToUnits = 0.3f) }
```

## Node hierarchy

| Old | New |
| --- | --- |
| Imperative `scene.addChild(node)` | Children are declared inside the parent's trailing lambda |
| `rememberNodes()` / `rememberChildNodes()` | Removed |
| Manual collision wiring | `rememberCollisionSystem(view)` — already the default `collisionSystem` parameter of `SceneView` / `ARSceneView`; use `collisionSystem.hitTest(...)` |

## AR

| Old | New |
| --- | --- |
| `arSession.config = Config().apply { … }` | Pass `sessionConfiguration = { session, config -> … }` to `ARSceneView` |
| `ArFragment` | Removed — use the `ARSceneView` composable |
| `node.addAnchor(anchor)` | `AnchorNode(anchor = …) { … }` |

## Light configuration

`LightNode` now accepts the common properties as top-level parameters
(`type`, `intensity`, `direction`, `position`, `color`). Filament-builder
extras (e.g. `falloff`, `spotLightCone`) go inside the `apply = { … }`
lambda.

```kotlin
LightNode(
    type = LightManager.Type.POINT,
    intensity = 30_000f,
    position = Position(1f, 1f, 1f),
    color = colorOf(1f, 0.95f, 0.8f),
    apply = { falloff(6f) },
)
```

## Common compile errors and fixes

1. `Type mismatch: expected Direction, got Position` — `LightNode.direction`
   takes a `Direction`, not a `Position`.
2. `null cannot be a value of a non-null type ModelInstance` — wrap with
   `?.let { … }` (`rememberModelInstance` is nullable while loading).
3. `Unresolved reference: rememberARSession` — no such helper exists; pass
   `sessionConfiguration = { session, config -> … }` directly to
   `ARSceneView`.
4. `Unresolved reference: AnchorNode.image / .face / .plane` — these factory
   functions are iOS-only via SceneViewSwift. On Android use
   `AugmentedImageNode`, `AugmentedFaceNode`, or
   `AnchorNode(anchor = hit.createAnchor())` from a plane hit-test.

## Apple parity

`SceneViewSwift` mirrors the entrypoints (`SceneView { }`, `ARSceneView { }`)
but uses SwiftUI's `@NodeBuilder` result-builder and modifier-style
configuration. **Don't translate a Kotlin snippet character-by-character to
Swift** — the result-builder + modifiers are syntactically different. See
[`docs/docs/cheatsheet-ios.md`](https://github.com/sceneview/sceneview/blob/main/docs/docs/cheatsheet-ios.md)
for the per-API mapping.
