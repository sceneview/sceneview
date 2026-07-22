---
title: Demo-settings sheet — DemoScaffold v2 with ModalBottomSheet
description: Pattern for full-screen 3D scene + ModalBottomSheet controls — the DemoScaffold v2 contract used by every demo in samples/android-demo.
---

# Demo-settings sheet — `DemoScaffold` v2

**Intent:** "I want my 3D / AR scene to fill the screen, with controls tucked away in a Material 3 bottom sheet."

`DemoScaffold` v2 (shipped in PR [#1169](https://github.com/sceneview/sceneview/pull/1169) under issue [#1154](https://github.com/sceneview/sceneview/issues/1154)) is the shared scaffold every demo in `samples/android-demo` uses. It renders the 3D scene **full-screen** under the top bar, with a `Tune` FAB pinned bottom-right that opens a `ModalBottomSheet` containing the controls.

This recipe describes the contract so you can use the same pattern in your own app.

## API

```kotlin
@Composable
fun DemoScaffold(
    title: String,
    onBack: () -> Unit,
    controls: (@Composable ColumnScope.() -> Unit)? = null,
    bottomOverlay: (@Composable DemoBottomOverlayScope.() -> Unit)? = null,
    scene: @Composable BoxScope.() -> Unit,
)
```

- **`title`** — shown in the top app bar.
- **`onBack`** — back navigation. The top app bar surfaces a back arrow.
- **`controls`** — *optional* slot for the demo's controls. Rendered inside a vertically-scrolling `Column` so existing v1 side-panel `controls = { ... }` blocks port unchanged. `null` ⇒ no FAB, scene fills the whole viewport.
- **`bottomOverlay`** — *optional* slot for a floating bottom banner / status pill / answer card. See [Bottom overlays](#bottom-overlays) — put them here, never at a bare `Alignment.BottomCenter` inside `scene`.
- **`scene`** — the trailing-lambda slot for the 3D / AR scene. Receives a `BoxScope`.

## Bottom overlays

The `Tune` FAB is **scaffold chrome pinned bottom-end**, and whether it exists at
all depends on `controls`. An overlay the demo places itself at
`Alignment.BottomCenter` therefore has no way to know whether — or by how much —
it must get out of the way, and a long enough status string simply disappears
under the FAB. Pixel 9 device QA found exactly that, on several demos at once
([#2779](https://github.com/sceneview/sceneview/issues/2779)).

Put the overlay in the `bottomOverlay` slot instead. Its receiver,
`DemoBottomOverlayScope`, carries the one number needed:

```kotlin
DemoScaffold(
    title = stringResource(R.string.demo_my_title),
    onBack = onBack,
    controls = { /* … */ },              // ← decides whether a FAB exists at all
    bottomOverlay = {
        // Full-width card / banner: only its end edge can reach the FAB, so
        // only the end edge is inset.
        Surface(modifier = Modifier.fillMaxWidth().padding(end = settingsFabReservedSpace)) {
            Text(answer)
        }
    },
) { /* scene */ }
```

For a **centred, content-width pill**, inset *both* sides — a centred element
grows outwards from the middle, so reserving only the end side would shift it
off-centre without actually keeping its end edge out of the FAB's band:

```kotlin
bottomOverlay = {
    Text(
        text = status,
        modifier = Modifier
            .padding(horizontal = settingsFabReservedSpace)   // symmetric ⇒ stays centred
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}
```

`settingsFabReservedSpace` is `SETTINGS_FAB_RESERVED_SPACE` (88 dp = the 56 dp
FAB plus its 2 × 16 dp gutter) when the demo passes `controls`, and `0.dp` when
it does not. It is resolved **once, scaffold-side**, from the same condition that
composes the FAB — so a demo whose controls are themselves conditional
(`controls = if (DemoSettings.qaMode) { … } else null`) gets the right inset for
free, with no duplicated condition to drift out of sync.

## Use in your own demo

```kotlin
@Composable
fun MyDemo(onBack: () -> Unit) {
    var iblIntensity by remember { mutableFloatStateOf(5_000f) }
    var spinScene by remember { mutableStateOf(true) }

    DemoScaffold(
        title = stringResource(R.string.demo_my_title),
        onBack = onBack,
        controls = {
            // Same Column scope you'd use in any settings sheet.
            Text("IBL intensity: ${iblIntensity.toInt()} lux",
                 style = MaterialTheme.typography.labelLarge)
            Slider(
                value = iblIntensity,
                onValueChange = { iblIntensity = it },
                valueRange = 0f..10_000f,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Spin scene", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = spinScene, onCheckedChange = { spinScene = it })
            }
        },
    ) {
        // BoxScope — full-screen scene.
        SceneView(modifier = Modifier.fillMaxSize() /* ... */)
    }
}
```

## Gestures

- **Tap FAB** → opens the sheet at its partial detent.
- **Tap peek chip** ("Settings", floats above the FAB when the sheet is closed) → also opens the sheet. The chip exists because users couldn't discover the FAB on first launch (issue [#951](https://github.com/sceneview/sceneview/issues/951)).
- **Long-press peek chip** → toggles `DemoSettings.qaMode` for deterministic screenshot captures. Moved here from the top app bar title in v2 so the title carries the demo name verbatim.
- **Drag handle / outside tap / back gesture** → dismiss the sheet.
- **AR scenes** — opening the sheet does NOT pause the underlying `ARSceneView`. The sheet sits on top of the live AR feed; ARCore keeps tracking 6DOF underneath.

## Picker pattern (Stage 2 of [#1152](https://github.com/sceneview/sceneview/issues/1152))

A common pattern in the sample app: a horizontal chip row in the controls sheet picks between bundled / streamed assets. The `OrbitalARDemo` / `ModelViewerDemo` / `AnimationPhysicsDemo` / `MaterialsDemo` / `ARPlacementDemo` / `ARInstantPlacementDemo` all use it:

```kotlin
DemoScaffold(
    title = stringResource(R.string.demo_my_title),
    onBack = onBack,
    controls = {
        Text("Subject", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedSlug == null,
                onClick = { selectedSlug = null },
                label = { Text("Bundled") },
            )
            SampleAssets.byCategory["ar_placement"].orEmpty().forEach { slug ->
                FilterChip(
                    selected = selectedSlug?.uid == slug.uid,
                    onClick = { selectedSlug = slug },
                    label = { Text(slug.displayName) },
                )
            }
        }
    },
) {
    SceneView(modifier = Modifier.fillMaxSize() /* ... */)
}
```

See [Sketchfab streaming](./sketchfab-streaming.md) for the asset side.

## State preservation

The sheet state (`SheetValue.Expanded` vs `Hidden`) is `rememberSaveable` so it survives configuration changes (rotation, dark-mode flip). Your controls are inside a regular `Column` so any `remember` / `rememberSaveable` state inside them is also preserved.

## Discoverability — peek chip first launch

The peek chip ("Settings" pill above the FAB) is shown only while the sheet is hidden. It exists because pre-v2 users had no idea that the FAB opened controls — first-time use telemetry (issue [#951](https://github.com/sceneview/sceneview/issues/951)) showed a 25 % drop-off where users back-arrowed out of a demo because they didn't realize there were settings to discover.

## See also

- [`DemoScaffold.kt`](https://github.com/sceneview/sceneview/blob/main/samples/android-demo/src/main/java/io/github/sceneview/demo/DemoScaffold.kt) — implementation.
- [Sketchfab streaming](./sketchfab-streaming.md) — pairs naturally with the picker pattern above.
- Material 3 `ModalBottomSheet` reference — <https://developer.android.com/jetpack/compose/components/bottom-sheets>.
