# Advanced guide: Lines, paths, and text labels

**Time:** ~15 minutes
**Level:** Intermediate
**What you'll build:** 3D line drawings and floating text labels in world space

---

## Overview

SceneView 3.2.0 adds three geometry primitives for annotation and visualization:

- **`LineNode`** — a single line segment between two points
- **`PathNode`** — a polyline through multiple points (open or closed)
- **`TextNode`** — a camera-facing text label rendered as a billboard

---

## Lines and paths

### Single line

```kotlin
SceneView(engine = engine, modelLoader = modelLoader) {
    val line = remember(engine) {
        LineNode(
            engine = engine,
            start = Position(x = -1f, y = 0f, z = 0f),
            end = Position(x = 1f, y = 1f, z = 0f)
        )
    }
    Node(node = line)
}
```

### Path (polyline)

```kotlin
val points = remember {
    (0..100).map { i ->
        val t = i / 100f * Math.PI.toFloat() * 4
        Position(
            x = t * 0.1f - 2f,
            y = sin(t) * 0.5f,
            z = cos(t) * 0.5f
        )
    }
}

SceneView(engine = engine, modelLoader = modelLoader) {
    val path = remember(engine, points) {
        PathNode(engine = engine, points = points)
    }
    Node(node = path)
}
```

### Closed path (polygon)

```kotlin
val triangle = listOf(
    Position(0f, 1f, 0f),
    Position(-1f, -0.5f, 0f),
    Position(1f, -0.5f, 0f)
)

val closedPath = remember(engine) {
    PathNode(engine = engine, points = triangle, closed = true)
}
```

---

## Text labels

`TextNode` renders text to a bitmap and displays it on a quad that turns toward the camera
when it is given a `cameraPositionProvider`.

```kotlin
val cameraNode = rememberCameraNode(engine)

SceneView(
    engine = engine,
    modelLoader = modelLoader,
    cameraNode = cameraNode
) {
    TextNode(
        text = "Hello 3D!",
        fontSize = 48f,
        textColor = android.graphics.Color.WHITE,
        backgroundColor = 0xCC000000.toInt(),
        typeface = Typeface.DEFAULT_BOLD,
        widthMeters = 0.6f,
        heightMeters = 0.2f,
        // Read by the node on each frame — no Compose state, no recomposition.
        cameraPositionProvider = { cameraNode.worldPosition }
    )
}
```

### Parameters

| Parameter | Effect |
|---|---|
| `text` | The string to display |
| `fontSize` | Font size in pixels for the bitmap texture |
| `textColor` | ARGB text colour |
| `backgroundColor` | ARGB background fill |
| `typeface` | `android.graphics.Typeface` of the text (default `Typeface.DEFAULT_BOLD`) |
| `widthMeters` / `heightMeters` | Size of the quad in world space (read once, at creation) |
| `position` / `scale` | Local transform of the label |
| `cameraPositionProvider` | Lambda returning camera position — label faces the camera. Without it the label does not turn |

The backing bitmap is 512 × 128 px. `bitmapWidth` / `bitmapHeight` are not parameters of the
composable: they belong to the constructor of the node class, `io.github.sceneview.node.TextNode`.

### Positioning labels

Set the label's position like any node:

```kotlin
TextNode(
    text = "Earth",
    position = Position(x = 0f, y = 2f, z = 0f),
    cameraPositionProvider = { cameraNode.worldPosition }
)
```

---

## Combining lines and labels

A common pattern is annotating a 3D scene with measurement lines and labels:

```kotlin
SceneView(engine = engine, modelLoader = modelLoader, cameraNode = cameraNode) {
    // Measurement line
    val measureLine = remember(engine) {
        LineNode(engine, start = Position(-1f, 0f, 0f), end = Position(1f, 0f, 0f))
    }
    Node(node = measureLine)

    // Label at midpoint
    TextNode(
        text = "2.0 m",
        fontSize = 36f,
        widthMeters = 0.4f,
        heightMeters = 0.15f,
        position = Position(0f, 0.2f, 0f),
        cameraPositionProvider = { cameraNode.worldPosition }
    )
}
```

---

## What's next

- See the `line-path` sample for animated sine/Lissajous curves with parameter sliders
- See the `text-labels` sample for an interactive solar system with label cycling
