package io.github.sceneview.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView

/**
 * iOS implementation: RealityKit, through `SceneViewSwift`.
 *
 * RealityKit stays the Apple renderer — it is what ARKit and visionOS align with, and
 * what the published App Store app uses. Reaching it requires one integration step from
 * the host app, because a Kotlin Multiplatform module cannot depend on a Swift Package:
 * see [SceneViewerBridge].
 *
 * With no bridge registered this draws a visible notice rather than an empty viewport.
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
    val factory = SceneViewerBridge.factory

    if (factory == null) {
        UnsupportedPlatformPlaceholder(
            platform = "iOS",
            reason = "no renderer is registered — set SceneViewerBridge.factory " +
                "from your iOS app, see the module README",
            modifier = modifier,
        )
        return
    }

    // Kept fresh without rebuilding the spec's identity: the callbacks are handed to the
    // Swift side once, at view creation, and must keep pointing at the latest lambdas.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentCamera by rememberUpdatedState(camera)

    val spec = SceneViewerSpec(
        modelAssetPath = (model as? ModelSource.Asset)?.path,
        modelUrl = (model as? ModelSource.Url)?.url,
        modelBytes = (model as? ModelSource.Bytes)?.bytes,

        cameraTargetX = camera.target.x,
        cameraTargetY = camera.target.y,
        cameraTargetZ = camera.target.z,
        cameraDistance = camera.distance,
        cameraAzimuthDegrees = camera.azimuth,
        cameraElevationDegrees = camera.elevation,
        cameraGesturesEnabled = camera.gesturesEnabled,

        lightDirectionX = lighting.direction.x,
        lightDirectionY = lighting.direction.y,
        lightDirectionZ = lighting.direction.z,
        lightIntensity = lighting.intensity,
        ambientIntensity = lighting.ambientIntensity,
        castShadows = lighting.castShadows,

        environmentKind = environment.kind,
        environmentRed = (environment as? EnvironmentSource.Color)?.red ?: 0f,
        environmentGreen = (environment as? EnvironmentSource.Color)?.green ?: 0f,
        environmentBlue = (environment as? EnvironmentSource.Color)?.blue ?: 0f,
        environmentAlpha = (environment as? EnvironmentSource.Color)?.alpha ?: 1f,
        environmentHdrPath = (environment as? EnvironmentSource.Hdr)?.path,
        environmentShowSkybox = (environment as? EnvironmentSource.Hdr)?.showSkybox ?: true,

        onTap = { hit, x, y, z, distance ->
            currentOnTap?.invoke(
                if (hit) {
                    ModelHit(
                        position = dev.romainguy.kotlin.math.Float3(x, y, z),
                        distance = distance,
                    )
                } else {
                    null
                },
            )
        },

        // Writes gestures back into CameraState so reads observe what the user did.
        // Without this the state would only ever report what the app last wrote, and
        // every drag would be invisible to the caller — the defect this API must avoid.
        onCameraMoved = { distance, azimuth, elevation ->
            currentCamera.distance = distance
            currentCamera.azimuth = azimuth
            currentCamera.elevation = elevation
        },
    )

    val currentSpec by rememberUpdatedState(spec)

    UIKitView(
        factory = { factory.create(currentSpec) },
        modifier = modifier,
        update = { view -> factory.update(view, currentSpec) },
    )

    // `onFrame` is intentionally not wired: SceneViewSwift exposes no per-frame callback
    // in its public API, and inventing one by polling would report times that are not
    // the renderer's. Declared unsupported in the KDoc and the module README rather than
    // silently never called. (An earlier version kept a `remember(onFrame)` here to
    // "avoid an unused-parameter warning" — Kotlin does not warn on an unused parameter
    // of an `actual` function, so it was a no-op justified by a false premise.)
}

/** Stable tag the Swift side switches on. */
private val EnvironmentSource.kind: String
    get() = when (this) {
        is EnvironmentSource.Default -> "default"
        is EnvironmentSource.Color -> "color"
        is EnvironmentSource.Hdr -> "hdr"
    }
