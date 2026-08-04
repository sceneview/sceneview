package io.github.sceneview.compose

import platform.UIKit.UIView

/**
 * The iOS integration seam: how `SceneViewer` reaches RealityKit.
 *
 * ### Why an app-supplied factory rather than a direct dependency
 *
 * `SceneViewSwift` is a Swift Package. A Kotlin Multiplatform module cannot depend on
 * one at build time, and `SceneViewSwift`'s API is pure SwiftUI, which does not cross
 * cinterop. Rather than pretend otherwise, this module declares *what it needs* — a
 * factory that produces a `UIView` — and the iOS app supplies it once at launch, where
 * the Swift package is already linked.
 *
 * The cost is one registration call in your app. The benefit is that `commonMain` code
 * stays free of any renderer, this module needs no XCFramework or `.def` cinterop, and
 * the Apple renderer stays RealityKit.
 *
 * ### Registering
 *
 * Copy `SceneViewerBridgeView.swift` from the SceneView repository into your iOS app
 * (it wraps `SceneViewSwift.SceneView` in a `UIHostingController`), then register it
 * before any composable runs — typically in your `MainViewController` factory:
 *
 * ```swift
 * SceneViewerBridge.shared.factory = { spec in
 *     SceneViewerBridgeView(spec: spec)
 * }
 * ```
 *
 * Until a factory is registered, [SceneViewer] renders a visible notice explaining that
 * the bridge is missing — never a blank viewport, which would be indistinguishable from
 * a model that failed to load.
 */
public object SceneViewerBridge {

    /**
     * Produces the native view backing a [SceneViewer], or `null` if none is registered.
     *
     * Set this once, on the main thread, before the first `SceneViewer` composes.
     * Replacing it later does not retroactively rebuild views already on screen.
     */
    public var factory: SceneViewerViewFactory? = null

    /** Whether an iOS host has registered a renderer. */
    public val isRegistered: Boolean get() = factory != null
}

/** Creates and updates the native `UIView` that renders a [SceneViewer]. */
public interface SceneViewerViewFactory {

    /**
     * Builds a view for [spec].
     *
     * Called on the main thread when the composable enters composition. The returned
     * view is owned by Compose and released when the composable leaves.
     */
    public fun create(spec: SceneViewerSpec): UIView

    /**
     * Applies a new [spec] to a view previously returned by [create].
     *
     * Called on the main thread whenever any `SceneViewer` argument changes. Implement
     * it by mutating the existing view — recreating the scene on every recomposition
     * would reload the model and throw away the user's camera position.
     *
     * The default does nothing, which makes a `SceneViewer` render its initial state and
     * silently ignore every later change. Override it in any real implementation.
     */
    public fun update(view: UIView, spec: SceneViewerSpec) {}
}

/**
 * A flat, Objective-C-friendly description of what to render.
 *
 * Deliberately primitive: every member crosses the Kotlin/Swift boundary, so this holds
 * no sealed types, no lambdas beyond the tap callback, and no Kotlin-only constructs.
 * Angles are in **degrees**, matching [CameraState] rather than RealityKit's radians —
 * the Swift side converts.
 */
public class SceneViewerSpec internal constructor(
    /** Bundle-relative path when the source is an asset, otherwise `null`. */
    public val modelAssetPath: String?,
    /** Absolute http/https URL when the source is remote, otherwise `null`. */
    public val modelUrl: String?,
    /** Raw model bytes when the source is in memory, otherwise `null`. */
    public val modelBytes: ByteArray?,

    public val cameraTargetX: Float,
    public val cameraTargetY: Float,
    public val cameraTargetZ: Float,
    public val cameraDistance: Float,
    public val cameraAzimuthDegrees: Float,
    public val cameraElevationDegrees: Float,
    public val cameraGesturesEnabled: Boolean,

    public val lightDirectionX: Float,
    public val lightDirectionY: Float,
    public val lightDirectionZ: Float,
    public val lightIntensity: Float,
    public val ambientIntensity: Float,
    public val castShadows: Boolean,

    /** `"default"`, `"color"` or `"hdr"`. */
    public val environmentKind: String,
    public val environmentRed: Float,
    public val environmentGreen: Float,
    public val environmentBlue: Float,
    public val environmentAlpha: Float,
    public val environmentHdrPath: String?,
    public val environmentShowSkybox: Boolean,

    /**
     * Invoked by the Swift side when the user taps.
     *
     * `hit` is `false` when the tap missed the model, in which case the coordinates are
     * meaningless and must be ignored.
     */
    public val onTap: (hit: Boolean, x: Float, y: Float, z: Float, distance: Float) -> Unit,

    /**
     * Invoked by the Swift side after a gesture moved the camera, in degrees.
     *
     * This is what keeps [CameraState] truthful about what the user did: without it,
     * reads would return the values the app last wrote and silently ignore every drag.
     */
    public val onCameraMoved: (distance: Float, azimuthDegrees: Float, elevationDegrees: Float) -> Unit,
)
