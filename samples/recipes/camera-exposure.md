# Recipe: AR Camera Exposure

**Intent:** "Fix a washed-out or too-dark AR camera preview"

On some devices the AR camera preview looks overexposed (white / blown-out) or underexposed
compared to the device's native camera app. `ARSceneView(cameraExposure:)` overrides the
renderer-side exposure of the whole AR frame.

> ⚠️ **Semantics (Android, #1179):** `cameraExposure` is Filament's **absolute exposure
> scale** (the single-`Float` `setExposure` overload — `1.0 ≈ ISO 100 ≈ EV 0`). It is **NOT a
> signed EV-stop bias**: a negative value clamps to zero and renders a **fully black frame**.
> Realistic range is roughly `0.05`–`16`. The iOS `ARSceneView(cameraExposure:)` is a
> different mechanism (EV-stop post-process) — never copy values across platforms.

## Android (Kotlin + Jetpack Compose)

```kotlin
@Composable
fun ARWithExposureFix() {
    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        // Brighten a too-dark preview: > 1.0 = brighter, < 1.0 = darker, null = default
        cameraExposure = 2.0f
    ) {
        // your AR nodes here
    }
}
```

### Adjustable exposure at runtime

```kotlin
@Composable
fun ARWithAdjustableExposure() {
    // Absolute scale — keep the slider strictly positive (never negative).
    var exposure by remember { mutableStateOf(1f) }

    Column(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.weight(1f),
            cameraExposure = exposure
        ) { }

        Slider(
            value = exposure,
            onValueChange = { exposure = it },
            valueRange = 0.05f..8f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
        Text(
            text = "Exposure scale: ${"%.2f".format(exposure)}x",
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
```

## Key concepts

| Concept | Detail |
|---|---|
| Parameter | `cameraExposure: Float?` on `ARSceneView` |
| Unit | Absolute exposure scale (`1.0 ≈ ISO 100 ≈ EV 0`) — **not** EV stops |
| Default | `null` — uses SceneView's tuned AR camera defaults (recommended) |
| `1.0f` | Reference exposure |
| Values `> 1.0` | Brighter preview (e.g. `2.0f`, `4.0f`) |
| Values in `(0, 1)` | Darker preview (e.g. `0.5f`, `0.25f`) |
| Negative values | ⛔ Clamp to zero → fully black framebuffer (#1179) — never use |
| Realistic range | ~`0.05`–`16` |

## When to use this

- Camera preview looks **too dark** → try `cameraExposure = 2.0f`
- Camera preview looks **washed out / blown-out** → try `cameraExposure = 0.5f`
- Preview differs visually from the device's stock camera app

## When NOT to use this

Leave `cameraExposure = null` (the default) on devices where the preview already looks correct.
The override bypasses SceneView's per-device AR camera tuning (correct for both back- and
front-camera sessions), so only set it when you observe an actual problem.
