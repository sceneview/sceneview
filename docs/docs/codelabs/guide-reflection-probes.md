# Advanced guide: Reflection probes

**Time:** ~15 minutes
**Level:** Intermediate
**What you'll build:** A scene with zone-based environment reflections on metallic objects

---

## Overview

`ReflectionProbeNode` lets you override the scene's image-based lighting (IBL) for specific
zones. This is essential for realistic metallic or glossy materials — a chrome sphere should
reflect the environment around it, not a generic sky.

---

## Step 1 — Global reflection probe

The simplest use: replace the scene's default IBL with a specific environment.

```kotlin
@Composable
fun ReflectionScreen() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    val environment = rememberEnvironment(environmentLoader) {
        environmentLoader.createHDREnvironment("environments/studio.hdr")
            ?: createEnvironment(environmentLoader)
    }

    var cameraPosition by remember { mutableStateOf(Position()) }
    val cameraNode = rememberCameraNode(engine) {
        position = Position(z = 3f)
    }

    SceneView(
        engine = engine,
        modelLoader = modelLoader,
        cameraNode = cameraNode,
        environment = environment,
        onFrame = { cameraPosition = cameraNode.worldPosition }
    ) {
        // Global probe — always active (radius = 0)
        ReflectionProbeNode(
            filamentScene = scene,
            environment = environment,
            cameraPosition = cameraPosition
        )

        rememberModelInstance(modelLoader, "models/metallic_sphere.glb")?.let {
            ModelNode(modelInstance = it)
        }
    }
}
```

---

## Step 2 — Local zone probes

Use `radius` to create zones with different reflections — e.g., indoor vs outdoor:

```kotlin
val outdoorEnv = rememberEnvironment(environmentLoader) {
    environmentLoader.createHDREnvironment("environments/sky.hdr")
        ?: createEnvironment(environmentLoader)
}
val indoorEnv = rememberEnvironment(environmentLoader) {
    environmentLoader.createHDREnvironment("environments/office.hdr")
        ?: createEnvironment(environmentLoader)
}

SceneView(
    engine = engine,
    modelLoader = modelLoader,
    environment = outdoorEnv,
    onFrame = { cameraPosition = cameraNode.worldPosition }
) {
    // Outdoor — global fallback
    ReflectionProbeNode(
        filamentScene = scene,
        environment = outdoorEnv,
        cameraPosition = cameraPosition
    )

    // Indoor zone — active within 3m of (0, 1, -5)
    ReflectionProbeNode(
        filamentScene = scene,
        environment = indoorEnv,
        position = Position(x = 0f, y = 1f, z = -5f),
        radius = 3f,
        priority = 1,  // wins over global when camera is inside
        cameraPosition = cameraPosition
    )
}
```

### How zone priority works

When the camera is inside multiple probe zones, the one with the highest `priority` wins.
If priorities are equal, the probe added most recently wins.

---

## Step 3 — Toggle probes reactively

Since probes are composables, toggling is just an `if` statement:

```kotlin
var probeEnabled by remember { mutableStateOf(true) }

SceneView(...) {
    if (probeEnabled) {
        ReflectionProbeNode(
            filamentScene = scene,
            environment = studioEnv,
            cameraPosition = cameraPosition
        )
    }
}
```

---

## Parameters reference

| Parameter | Effect |
|---|---|
| `filamentScene` | The Filament Scene whose indirect light is overridden |
| `environment` | Environment containing the IndirectLight to apply |
| `position` | Centre of the reflection zone in world space |
| `radius` | Zone radius in metres. `0` = always active (global) |
| `priority` | Higher value wins when zones overlap |
| `cameraPosition` | Updated each frame from `onFrame` callback |

---

## What's next

- See the `reflection-probe` sample for material switching (Chrome, Gold, Copper, Rough)
- Combine with `FogNode` and `DynamicSkyNode` for complete atmospheric scenes
