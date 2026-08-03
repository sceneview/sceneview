package io.github.sceneview.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Displays a 3D model, from `commonMain`, on every supported platform.
 *
 * `SceneViewer` is the whole public surface of `sceneview-compose`: one composable
 * covering the *model viewer* case — load a model, orbit it, light it, tap it. That is
 * deliberate, and it is the entire contract. See the module README for why the scope
 * stops here.
 *
 * **One API does not mean one renderer.** Each platform keeps the renderer that is right
 * for it, and no renderer type ever appears in this API:
 *
 * | Platform | Renderer | Status |
 * |---|---|---|
 * | Android | Filament, via the existing `SceneView { }` composable | implemented |
 * | iOS | RealityKit, via `SceneViewSwift` | **placeholder — not wired yet** |
 * | Desktop (JVM) | Filament, via the vendored FFM binding | **placeholder — not wired yet** |
 *
 * On a platform still marked *placeholder*, this composable draws a visible notice
 * naming the platform and the reason instead of rendering a scene. It does not throw,
 * and it does not silently show an empty viewport.
 *
 * Because the renderers differ, so does the pixel output. Lighting values map
 * approximately between Filament and RealityKit rather than exactly, and materials
 * authored for one engine do not carry to the other. If you need engine-specific control
 * — custom materials, post-processing, or **anything AR** — use the platform-native API
 * (`io.github.sceneview.SceneView` on Android, `SceneViewSwift` on Apple) instead. This
 * façade will not grow to cover them.
 *
 * ```kotlin
 * SceneViewer(
 *     model = ModelSource.Asset("models/damaged_helmet.glb"),
 *     modifier = Modifier.fillMaxSize(),
 *     onTap = { hit -> if (hit != null) println("hit at ${hit.position}") },
 * )
 * ```
 *
 * @param model the model to display. Loading is asynchronous on every platform; the
 *   viewport renders the environment alone until the model is ready.
 * @param modifier the [Modifier] applied to the viewport.
 * @param camera orbit camera state. Hoist it with [rememberCameraState] to drive the
 *   camera yourself, or leave the default for a user-controlled orbit.
 * @param lighting the scene's key light and ambient level.
 * @param environment the background and image-based lighting source.
 * @param onTap invoked when the user taps the viewport, with the model hit under the
 *   touch point, or `null` when the tap missed the model.
 * @param onFrame invoked once per rendered frame with the frame time in nanoseconds.
 *   Called on the platform's render-driving thread — keep it allocation-free.
 */
@Composable
public expect fun SceneViewer(
    model: ModelSource,
    modifier: Modifier = Modifier,
    camera: CameraState = rememberCameraState(),
    lighting: Lighting = Lighting(),
    environment: EnvironmentSource = EnvironmentSource.Default,
    onTap: ((ModelHit?) -> Unit)? = null,
    onFrame: ((frameTimeNanos: Long) -> Unit)? = null,
)
