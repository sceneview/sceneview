// PlaneRenderer (V1) is @Deprecated since #2203 PR #5. Its own internals legitimately
// reference the also-deprecated PlaneVisualizer (V1); a file-level suppression keeps the
// V1 path warning-free internally while callers of the V1 class still see the deprecation
// notice at their call site. Mirrors the @Suppress("DEPRECATION") pattern in ARScene.kt's
// V1 factory branch.
@file:Suppress("DEPRECATION")

package io.github.sceneview.ar.scene

import android.util.Size
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.PlaneVisualizer
import io.github.sceneview.ar.arcore.firstByTypeOrNull
import io.github.sceneview.ar.arcore.fps
import io.github.sceneview.ar.arcore.getUpdatedPlanes
import io.github.sceneview.ar.arcore.hitTest
import io.github.sceneview.ar.arcore.isTracking
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setParameter
import io.github.sceneview.material.setTexture
import io.github.sceneview.math.Color
import io.github.sceneview.safeDestroyMaterial
import io.github.sceneview.safeDestroyMaterialInstance
import io.github.sceneview.safeDestroyTexture
import io.github.sceneview.texture.ImageTexture

/**
 * Control rendering of ARCore planes — V1 implementation.
 *
 * Used to visualize detected planes and to control whether Renderables cast shadows on them.
 * Each detected plane is drawn as a flat polygon textured with a procedural soft grid
 * (`plane_renderer.mat`).
 *
 * ### Deprecated — V2 is the default
 *
 * As of [#2203](https://github.com/sceneview/sceneview/issues/2203) PR #5, V2 is the default
 * and this V1 class is `@Deprecated`. V2 ([PlaneRendererV2]) ships depth-driven micro-relief,
 * PBR materials lit by ARCore's HDR cubemap, type-aware shading (floor / ceiling / wall) and
 * an animated scan-in.
 *
 * V1 stays available behind `ARSceneView(planeRendererVersion = PlaneRendererBase.Version.V1)`
 * for one release cycle so apps that subclass [PlaneRenderer] or override `plane_renderer.mat`
 * can port their custom styling. It will be removed in a future release.
 *
 * @see PlaneRendererBase
 * @see PlaneRendererV2
 */
@Deprecated(
    message = "V1 renders detected planes as a flat unlit polygon. " +
        "V2 ships depth-driven mesh, PBR lighting, HDR reflection, " +
        "type-aware shading, and scan-in animation. Migrate to V2 " +
        "via ARScene(planeRendererVersion = PlaneRendererBase.Version.V2). " +
        "V1 will be removed in a future release. See #2203.",
    replaceWith = ReplaceWith("PlaneRendererV2"),
    level = DeprecationLevel.WARNING,
)
class PlaneRenderer(
    val engine: Engine,
    private val materialLoader: MaterialLoader,
    private val scene: Scene
) : PlaneRendererBase {

    override lateinit var viewSize: Size

    private val visualizers = mutableMapOf<Plane, PlaneVisualizer>()

    val planeTexture = ImageTexture.Builder()
        .bitmap(materialLoader.assets, "textures/plane_renderer.png")
        .build(engine)

    /**
     * Default material instance used to render the planes.
     */
    val planeMaterial = materialLoader.createMaterial(
        "materials/plane_renderer.filamat"
    ).apply {
        defaultInstance.apply {
            setTexture(MATERIAL_TEXTURE, planeTexture)

            val widthToHeightRatio =
                planeTexture.getWidth(0).toFloat() / planeTexture.getHeight(0).toFloat()
            val scaleX = BASE_UV_SCALE
            val scaleY = scaleX * widthToHeightRatio
            setParameter(MATERIAL_UV_SCALE, scaleX, scaleY)

            setParameter(MATERIAL_COLOR, Color(1.0f, 1.0f, 1.0f))
        }
    }

    private val materialInstances = mutableListOf<MaterialInstance>()

    private var shadowMaterial = materialLoader.createMaterial(
        "materials/plane_renderer_shadow.filamat"
    )

    /**
     * Determines how tracked planes should be visualized on the screen. Two options are available,
     * `RENDER_ALL` and `RENDER_TOP_MOST`.
     * To see all tracked planes which are visible to the camera set the PlaneRendererMode to
     * `RENDER_ALL`. This mode eats up quite a few resources and should only be set
     * with care. To optimize the rendering set the mode to `RENDER_TOP_MOST`.
     * In that case only the top most plane visible to a camera is rendered on the screen.
     * Especially on weaker smartphone models this improves the overall performance.
     *
     * The default mode is `RENDER_TOP_MOST`
     */
    var planeRendererMode = PlaneRendererMode.RENDER_CENTER

    /**
     * ### Adjust the max screen [ArFrame.hitTest] number per seconds
     *
     * Decrease if you don't need a very precise position update and want to reduce frame
     * consumption.
     * Increase for a more accurate positioning update.
     */
    var maxHitTestPerSecond: Int = 10

    /**
     * ### Enable/disable the plane renderer.
     */
    override var isEnabled = true
        set(value) {
            if (field != value) {
                field = value
                visualizers.values.forEach { it.setEnabled(value) }
            }
        }

    /**
     * Control visibility of plane visualization.
     *
     * If false - no planes are drawn. Note that shadow visibility is independent of plane
     * visibility.
     */
    override var isVisible = true
        set(value) {
            if (field != value) {
                field = value
                visualizers.values.forEach { it.setVisible(value) }
            }
        }

    /**
     * Control whether Renderables in the scene should cast shadows onto the planes
     *
     * If false - no planes receive shadows, regardless of the per-plane setting.
     */
    override var isShadowReceiver = true
        set(value) {
            if (field != value) {
                field = value
                visualizers.values.forEach { it.setShadowReceiver(value) }
            }
        }

    private var isCameraTracking = false
        set(value) {
            if (field != value) {
                field = value
                visualizers.values.forEach { it.setEnabled(isEnabled && value) }
            }
        }

    private var frame: Frame? = null

    override fun update(session: Session, frame: Frame) {
        if (isEnabled) {
            if (frame.fps(this.frame) < maxHitTestPerSecond) {
                this.frame = frame

                isCameraTracking = frame.camera.isTracking

                try {
                    val updatedPlanes = frame.getUpdatedPlanes()
                    if (planeRendererMode == PlaneRendererMode.RENDER_ALL) {
                        updatedPlanes.forEach { renderPlane(it) }
                    } else if (planeRendererMode == PlaneRendererMode.RENDER_CENTER) {
                        // Do a hitTest on the current frame. The result is used to render only
                        // the top most plane Trackable visible at the centre of the screen.
                        val centerPlane = if (isVisible) {
                            // Don't make the hit test if we don't need to know the center plane
                            frame.hitTest(viewSize.width / 2.0f, viewSize.height / 2.0f)
                                .firstByTypeOrNull(
                                    planeTypes = setOf(Plane.Type.HORIZONTAL_UPWARD_FACING)
                                )?.trackable as? Plane
                        } else null
                        updatedPlanes.forEach { renderPlane(it, visible = it == centerPlane) }
                        visualizers.forEach { (plane, visualizer) ->
                            if (plane !in updatedPlanes) {
                                visualizer.setVisible(isVisible && plane == centerPlane)
                            }
                        }
                    }

                    // Check for not tracking Plane-Trackables and remove them.
                    cleanupOldPlaneVisualizer()
                } catch (e: Exception) {
                    android.util.Log.e("SceneView", "PlaneRenderer update error", e)
                }
            }
        }
    }

    override fun destroy() {
        visualizers.forEach { (_, planeVisualizer) -> planeVisualizer.destroy() }

        materialInstances.forEach { engine.safeDestroyMaterialInstance(it) }
        materialLoader.destroyMaterial(planeMaterial)
        materialLoader.destroyMaterial(shadowMaterial)
        engine.safeDestroyTexture(planeTexture)
    }

    /**
     * This function is responsible to update the rendering
     * of a [PlaneVisualizer]. If for the given [Plane]
     * no [PlaneVisualizer] exists, create a new one and add
     * it to the [visualizers].
     *
     * @param plane [Plane]
     */
    private fun renderPlane(plane: Plane, visible: Boolean = true) {
        // Find the plane visualizer if it already exists.
        // If not, create a new plane visualizer for this plane.
        if (plane.trackingState == TrackingState.TRACKING || plane.subsumedBy == null) {
            val planeVisualizer = visualizers[plane]
                ?: PlaneVisualizer(engine, scene, plane).apply {
                    setPlaneMaterial(planeMaterial.createInstance().also {
                        materialInstances += it
                    })
                    setShadowMaterial(shadowMaterial.createInstance().also {
                        materialInstances += it
                    })
                    setShadowReceiver(isShadowReceiver)
                    setVisible(isVisible && visible)
                    setEnabled(isEnabled && isCameraTracking)
                }.also {
                    visualizers[plane] = it
                }
            // Update the plane visualizer.
            planeVisualizer.updatePlane()
        }
    }

    /**
     * ### Remove plane visualizers for old planes that are no longer tracking
     *
     * Update the material parameters for all remaining planes.
     */
    private fun cleanupOldPlaneVisualizer() {
        visualizers.entries.removeAll { (plane, planeVisualizer) ->
            // If this plane was subsumed by another plane or it has permanently stopped tracking,
            // remove it.
            if (plane.subsumedBy != null || plane.trackingState == TrackingState.STOPPED) {
                planeVisualizer.destroy()
                true
            } else {
                false
            }
        }
    }

    /**
     * Use this enum to configure the Plane Rendering.
     *
     * For performance reasons use `RENDER_TOP_MOST`.
     */
    enum class PlaneRendererMode {
        /**
         * Render all possible [Plane]s which are visible to the camera.
         */
        RENDER_ALL,

        /**
         * Render only the top most [Plane] which is visible to the camera.
         */
        RENDER_CENTER
    }

    companion object {
        /**
         * Material parameter that controls what texture is being used when rendering the planes.
         */
        const val MATERIAL_TEXTURE = "texture"

        /**
         * Float2 material parameter to control the X/Y scaling of the texture's UV coordinates. Can be
         * used to adjust for the texture's aspect ratio and control the frequency of tiling.
         */
        const val MATERIAL_UV_SCALE = "uvScale"

        /**
         * Float3 material parameter to control the RGB tint of the plane.
         */
        const val MATERIAL_COLOR = "color"

        /**
         * Used to control the UV Scale for the default texture.
         */
        private const val BASE_UV_SCALE = 8.0f
    }
}