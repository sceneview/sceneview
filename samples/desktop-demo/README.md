# SceneView Desktop Demo

Compose Desktop consumer of `sceneview-compose` `SceneViewer`. Filament is
behind the façade (filament-kmp, offscreen → Skia). **JDK 22+**.

```kotlin
SceneViewer(model = ModelSource.Bytes(glb), modifier = Modifier.fillMaxSize())
```

## Run

```bash
./gradlew :samples:desktop-demo:run
```

`--enable-native-access=ALL-UNNAMED` is already set (FFM).
