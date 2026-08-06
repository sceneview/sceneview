package io.github.sceneview.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Desktop implementation — **not wired yet**.
 *
 * The renderer is Filament through the FFM binding vendored in
 * `third_party/filament-kmp/`, which still needs its native build chain (Filament
 * prebuilt download, CMake for the C wrapper, `jextract` for the bindings) hooked into
 * this build, plus a Windows CI leg. Tracked in `docs/docs/compose-multiplatform.md`.
 *
 * Until then this renders [UnsupportedPlatformPlaceholder] rather than an empty box, so
 * an unimplemented platform is obvious on screen instead of looking like a scene that
 * failed to load.
 */
@Composable
public actual fun SceneViewer(
    model: ModelSource,
    modifier: Modifier,
    camera: CameraState,
    lighting: Lighting,
    environment: EnvironmentSource,
    onTap: ((ModelHit?) -> Unit)?,
    onFrame: ((frameTimeNanos: Long) -> Unit)?,
    // Never invoked while this is a placeholder: nothing is loaded, so nothing can fail
    // to load. An unwired platform is not an error the app should surface as one — it is
    // stated on screen instead, by the placeholder below.
    onError: ((SceneViewerError) -> Unit)?,
) {
    UnsupportedPlatformPlaceholder(
        platform = "Desktop",
        reason = "the vendored Filament binding is not wired into the build yet",
        modifier = modifier,
    )
}
