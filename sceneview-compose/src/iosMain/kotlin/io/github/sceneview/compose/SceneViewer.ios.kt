package io.github.sceneview.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * iOS implementation — **not wired yet**.
 *
 * The renderer is RealityKit through `SceneViewSwift`, which stays the Apple renderer.
 * The missing piece is on the Swift side: `SceneViewSwift` is pure SwiftUI, and SwiftUI
 * types do not cross cinterop, so it first needs an `@objc` `UIView` façade (a
 * `UIHostingController` wrapper) for `UIKitView` to host. That façade also unblocks the
 * Flutter and React Native bridges.
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
        platform = "iOS",
        reason = "the SceneViewSwift @objc UIView façade does not exist yet",
        modifier = modifier,
    )
}
