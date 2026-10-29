package io.github.sceneview.ar.scene

import android.util.Size
import com.google.ar.core.Frame
import com.google.ar.core.Session

/**
 * Former shared contract of SceneView's two plane renderers.
 *
 * Since 4.57.0 there is one plane renderer, [PlaneRenderer]
 * ([#4307](https://github.com/sceneview/sceneview/issues/4307)): round dots on floors, upright
 * dashes on walls, hollow rings on ceilings. This interface stays so that code compiled against
 * an earlier 4.x keeps linking; [PlaneRenderer] and the deprecated [PlaneRendererV2] still
 * implement it. New code uses [PlaneRenderer] directly.
 */
@Deprecated(
    "There is one plane renderer since 4.57.0: use PlaneRenderer.",
    ReplaceWith("PlaneRenderer", "io.github.sceneview.ar.scene.PlaneRenderer"),
)
sealed interface PlaneRendererBase {

    /**
     * Display size (in pixels) of the surface the renderer draws into. Set by `ARSceneView`
     * whenever the surface is resized. Nothing in the SDK reads it since centre-plane selection
     * is computed from the camera pose (#3339).
     */
    var viewSize: Size

    /** Draw the planes. Turning it off fades them out; plane detection keeps running. */
    var isEnabled: Boolean

    /** When `false`, no planes are drawn; shadows can still be received ([isShadowReceiver]). */
    var isVisible: Boolean

    /** Whether scene renderables cast shadows onto the detected planes. */
    var isShadowReceiver: Boolean

    /** Per-frame update, called by `ARSceneView` with the live ARCore [Session] and [Frame]. */
    fun update(session: Session, frame: Frame)

    /** Releases every native resource owned by the renderer. Called once by `ARSceneView`. */
    fun destroy()

    /**
     * Former selector of the plane-renderer implementation, read by `ARSceneView`'s
     * `planeRendererVersion` parameter.
     *
     * **Ignored since 4.57.0**: both values draw with [PlaneRenderer], the only plane renderer.
     * Delete the `planeRendererVersion` argument.
     */
    @Deprecated("Ignored since 4.57.0: there is one plane renderer. Delete the planeRendererVersion argument.")
    enum class Version {
        /** Formerly the textured-grid renderer. Ignored: draws with [PlaneRenderer]. */
        V1,

        /** Formerly the dots renderer, which became [PlaneRenderer]. */
        V2
    }
}
