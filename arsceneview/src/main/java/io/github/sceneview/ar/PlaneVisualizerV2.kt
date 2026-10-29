package io.github.sceneview.ar

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import io.github.sceneview.collision.Matrix
import io.github.sceneview.collision.TransformProvider

/**
 * Former name of [PlaneVisualizer].
 *
 * The dots renderer became the only plane renderer in 4.57.0
 * ([#4307](https://github.com/sceneview/sceneview/issues/4307)) and its per-plane visualizer took
 * the name [PlaneVisualizer]. This class stays so that code compiled against an earlier 4.x keeps
 * linking: it forwards every member to a [PlaneVisualizer].
 */
@Deprecated(
    "Renamed: use PlaneVisualizer.",
    ReplaceWith("PlaneVisualizer(engine, scene, plane)", "io.github.sceneview.ar.PlaneVisualizer"),
)
class PlaneVisualizerV2(
    engine: Engine,
    scene: Scene,
    plane: Plane
) : TransformProvider {

    private val visualizer = PlaneVisualizer(engine, scene, plane)

    companion object {
        /** No longer used: the plane mesh does not read ARCore depth (#3507). */
        const val DEPTH_REBUILD_INTERVAL_MS: Long = 200L

        /** See [PlaneVisualizer.SCAN_IN_DURATION_MS]. */
        const val SCAN_IN_DURATION_MS: Long = PlaneVisualizer.SCAN_IN_DURATION_MS

        /** No longer used: the plane surface is unlit and has no reflection to fade in (#3507). */
        const val REFLECTION_FADE_IN_MS: Long = 1000L
    }

    /** See [PlaneVisualizer.setEnabled]. */
    fun setEnabled(enabled: Boolean) = visualizer.setEnabled(enabled)

    /** See [PlaneVisualizer.setShadowReceiver]. */
    fun setShadowReceiver(shadowReceiver: Boolean) = visualizer.setShadowReceiver(shadowReceiver)

    /** See [PlaneVisualizer.setVisible]. */
    fun setVisible(visible: Boolean) = visualizer.setVisible(visible)

    /** See [PlaneVisualizer.setPlaneMaterial]. */
    fun setPlaneMaterial(materialInstance: MaterialInstance) = visualizer.setPlaneMaterial(materialInstance)

    /** See [PlaneVisualizer.setShadowMaterial]. */
    fun setShadowMaterial(materialInstance: MaterialInstance) = visualizer.setShadowMaterial(materialInstance)

    /** No longer used: the visualizer does not read the camera frame (#3507). */
    @Suppress("UNUSED_PARAMETER", "UnusedParameter")
    fun setFrame(frame: Frame?, camera: Camera?) = Unit

    override fun getTransformationMatrix(): Matrix = visualizer.getTransformationMatrix()

    /** See [PlaneVisualizer.updatePlane]. */
    fun updatePlane() = visualizer.updatePlane()

    /** See [PlaneVisualizer.destroy]. */
    fun destroy() = visualizer.destroy()
}
