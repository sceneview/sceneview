package io.github.sceneview.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Desktop implementation — **not wired yet**.
 *
 * The renderer is Filament through an FFM binding taken from filament-kmp. The binding
 * is not vendored into this repository yet — that happens when the desktop spike starts,
 * so the copy is taken at a current upstream tag rather than ageing unused on `main`
 * (see `docs/docs/desktop-filament.md`). It then still needs its native build chain
 * (Filament prebuilt download, CMake for the C wrapper, `jextract` for the bindings)
 * hooked into this build, plus a Windows CI leg. Tracked in
 * `docs/docs/compose-multiplatform.md`.
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
) {
    UnsupportedPlatformPlaceholder(
        platform = "Desktop",
        reason = "the vendored Filament binding is not wired into the build yet",
        modifier = modifier,
    )
}
