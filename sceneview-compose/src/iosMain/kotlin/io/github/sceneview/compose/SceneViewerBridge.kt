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
 * Implement [SceneViewerViewFactory] in your own `iosMain` source set and register it
 * before any composable runs — typically in your `MainViewController` factory:
 *
 * ```kotlin
 * SceneViewerBridge.factory = object : SceneViewerViewFactory {
 *     override fun create(spec: SceneViewerSpec): UIView =
 *         MyRealityKitView(spec)                       // wraps SceneViewSwift.SceneView
 *     override fun update(view: UIView, spec: SceneViewerSpec) {
 *         (view as MyRealityKitView).apply(spec)       // mutate, never recreate
 *     }
 * }
 * ```
 *
 * Note it must be an explicit implementation: [SceneViewerViewFactory] has two members,
 * so it is not a `fun interface` and a Swift closure literal cannot stand in for it.
 *
 * The `UIView` itself is written in Swift, where `SceneViewSwift` is linked. You do not
 * have to write it: `SceneViewSwift` ships `SceneViewerHostView` (`SVSceneViewerHostView`
 * in Kotlin), an `@objc UIView` hosting `SceneViewSwift.SceneView` and driven entirely by
 * the primitives on `SVSceneViewerConfiguration` — so a factory is a field-by-field copy
 * of [SceneViewerSpec] plus the two callbacks. The module README has the full example, and
 * the platform differences it inherits from RealityKit.
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
     * The Kotlin default does nothing, which makes a `SceneViewer` render its initial
     * state and silently ignore every later change. Override it in any real
     * implementation.
     *
     * Note the default exists for Kotlin callers only: Kotlin/Native does not bridge
     * interface default bodies into the generated Objective-C protocol, so a Swift type
     * conforming to this interface **must** implement it — the compiler will say so.
     */
    public fun update(view: UIView, spec: SceneViewerSpec) {}
}

/**
 * A flat, Objective-C-friendly description of what to render.
 *
 * Deliberately primitive: every member crosses the Kotlin/Swift boundary, so this holds
 * no sealed types, no lambdas beyond the callbacks, and no Kotlin-only constructs.
 * Angles are in **degrees**, matching [CameraState] rather than RealityKit's radians —
 * the Swift side converts.
 *
 * ### Compared by value, and by *content* for the bytes
 *
 * [SceneViewer] rebuilds a spec on every composition and publishes it through
 * `rememberUpdatedState`, which only notifies when the new value is unequal. Identity
 * comparison — the default for a plain class — makes every rebuild unequal, so [update]
 * would be re-invoked on every recomposition, including the one each touch-move event
 * triggers through `CameraState`, each time handing the Swift side the same model to
 * apply again.
 *
 * [ModelSource.Bytes] already compares its array by content for exactly that reason, but
 * the benefit is lost the moment the array is unpacked into a field: [equals] on a
 * `ByteArray` is reference equality, so a caller re-wrapping the same image — the normal
 * shape of `ModelSource.Bytes(resource.readBytes())` — would produce an unequal spec.
 * [modelBytes] is therefore compared with `contentEquals`, restoring the guarantee the
 * source type makes.
 *
 * The callbacks are deliberately **excluded** from [equals] and [hashCode]. They are not
 * the app's lambdas: [SceneViewer] passes permanent forwarders that read the current
 * ones out of `rememberUpdatedState`, so they already dispatch to the latest handler
 * without the spec having to change. Including them would compare freshly allocated
 * closures and defeat the whole comparison.
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

    /**
     * Invoked by the Swift side when a model or environment fails to load.
     *
     * Call it with a short English description of what failed — it reaches the app as
     * [SceneViewerError.message]. A failed load has no pixels of its own on RealityKit
     * either: the viewport keeps showing the environment, so an implementation that never
     * calls this leaves the app unable to tell a failure from a slow load.
     */
    public val onError: (message: String) -> Unit,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SceneViewerSpec) return false
        return modelAssetPath == other.modelAssetPath &&
            modelUrl == other.modelUrl &&
            // Nullable receiver: two absent arrays compare equal, as they should.
            modelBytes.contentEquals(other.modelBytes) &&
            cameraTargetX == other.cameraTargetX &&
            cameraTargetY == other.cameraTargetY &&
            cameraTargetZ == other.cameraTargetZ &&
            cameraDistance == other.cameraDistance &&
            cameraAzimuthDegrees == other.cameraAzimuthDegrees &&
            cameraElevationDegrees == other.cameraElevationDegrees &&
            cameraGesturesEnabled == other.cameraGesturesEnabled &&
            lightDirectionX == other.lightDirectionX &&
            lightDirectionY == other.lightDirectionY &&
            lightDirectionZ == other.lightDirectionZ &&
            lightIntensity == other.lightIntensity &&
            ambientIntensity == other.ambientIntensity &&
            castShadows == other.castShadows &&
            environmentKind == other.environmentKind &&
            environmentRed == other.environmentRed &&
            environmentGreen == other.environmentGreen &&
            environmentBlue == other.environmentBlue &&
            environmentAlpha == other.environmentAlpha &&
            environmentHdrPath == other.environmentHdrPath &&
            environmentShowSkybox == other.environmentShowSkybox
    }

    override fun hashCode(): Int {
        // `contentHashCode`, matching the `contentEquals` above: two specs that compare
        // equal must never hash apart.
        var result = modelAssetPath?.hashCode() ?: 0
        result = 31 * result + (modelUrl?.hashCode() ?: 0)
        result = 31 * result + (modelBytes?.contentHashCode() ?: 0)
        result = 31 * result + cameraTargetX.hashCode()
        result = 31 * result + cameraTargetY.hashCode()
        result = 31 * result + cameraTargetZ.hashCode()
        result = 31 * result + cameraDistance.hashCode()
        result = 31 * result + cameraAzimuthDegrees.hashCode()
        result = 31 * result + cameraElevationDegrees.hashCode()
        result = 31 * result + cameraGesturesEnabled.hashCode()
        result = 31 * result + lightDirectionX.hashCode()
        result = 31 * result + lightDirectionY.hashCode()
        result = 31 * result + lightDirectionZ.hashCode()
        result = 31 * result + lightIntensity.hashCode()
        result = 31 * result + ambientIntensity.hashCode()
        result = 31 * result + castShadows.hashCode()
        result = 31 * result + environmentKind.hashCode()
        result = 31 * result + environmentRed.hashCode()
        result = 31 * result + environmentGreen.hashCode()
        result = 31 * result + environmentBlue.hashCode()
        result = 31 * result + environmentAlpha.hashCode()
        result = 31 * result + (environmentHdrPath?.hashCode() ?: 0)
        result = 31 * result + environmentShowSkybox.hashCode()
        return result
    }
}
