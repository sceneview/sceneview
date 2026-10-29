# SceneView Desktop Demo

Compose Desktop consumer of `sceneview-compose` `SceneViewer`. Filament is
behind the façade (filament-kmp, offscreen → Skia). **JDK 21+**.

```kotlin
SceneViewer(model = ModelSource.Bytes(glb), modifier = Modifier.fillMaxSize())
```

## Run

```bash
./gradlew :samples:desktop-demo:run
```

Gradle picks a JDK 21 toolchain for the run (and downloads one into `~/.gradle/jdks`
if none is installed). No JVM flag is needed: filament-kmp 0.7+ loads its natives
through JNI.

## Attribution

`src/commonMain/composeResources/files/models/Duck.glb` is the
[Khronos glTF sample Duck](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/Duck)
(© 2006 Sony Computer Entertainment Inc., SCEA Shared Source License 1.0).
