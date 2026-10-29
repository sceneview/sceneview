@file:Suppress("DEPRECATION")

package io.github.sceneview.ar.scene

import android.util.Size
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.ar.core.Frame
import com.google.ar.core.Session
import io.github.sceneview.loaders.MaterialLoader

/**
 * Former name of [PlaneRenderer].
 *
 * The dots renderer became the only plane renderer in 4.57.0
 * ([#4307](https://github.com/sceneview/sceneview/issues/4307)) and took the name
 * [PlaneRenderer]. This class stays so that code compiled against an earlier 4.x keeps linking:
 * it draws with a [PlaneRenderer] and forwards every member to it.
 */
@Deprecated(
    "Renamed: the dots renderer is now the only one, PlaneRenderer.",
    ReplaceWith("PlaneRenderer(engine, materialLoader, scene)", "io.github.sceneview.ar.scene.PlaneRenderer"),
)
class PlaneRendererV2(
    val engine: Engine,
    materialLoader: MaterialLoader,
    scene: Scene
) : PlaneRendererBase {

    private val renderer = PlaneRenderer(engine, materialLoader, scene)

    /** See [PlaneRenderer.planeMaterial]. */
    val planeMaterial: Material get() = renderer.planeMaterial

    /** See [PlaneRenderer.planeTexture]: a placeholder, not used by the dots renderer. */
    @Suppress("DEPRECATION")
    val planeTexture: Texture get() = renderer.planeTexture

    /** See [PlaneRenderer.planeRendererMode]. */
    var planeRendererMode: PlaneRenderer.PlaneRendererMode
        get() = renderer.planeRendererMode
        set(value) {
            renderer.planeRendererMode = value
        }

    /** See [PlaneRenderer.maxUpdatesPerSecond]. */
    var maxHitTestPerSecond: Int
        get() = renderer.maxUpdatesPerSecond
        set(value) {
            renderer.maxUpdatesPerSecond = value
        }

    /** Unused, see [PlaneRendererBase.viewSize]. */
    override lateinit var viewSize: Size

    override var isEnabled: Boolean
        get() = renderer.isEnabled
        set(value) {
            renderer.isEnabled = value
        }

    override var isVisible: Boolean
        get() = renderer.isVisible
        set(value) {
            renderer.isVisible = value
        }

    override var isShadowReceiver: Boolean
        get() = renderer.isShadowReceiver
        set(value) {
            renderer.isShadowReceiver = value
        }

    override fun update(session: Session, frame: Frame) = renderer.update(session, frame)

    override fun destroy() = renderer.destroy()

    /** Material parameter names of the former dots material. Prefer [PlaneRenderer]'s. */
    companion object {
        /** No longer a material parameter. */
        const val MATERIAL_TEXTURE = PlaneRenderer.MATERIAL_TEXTURE

        /** See [PlaneRenderer.MATERIAL_UV_SCALE]. */
        const val MATERIAL_UV_SCALE = PlaneRenderer.MATERIAL_UV_SCALE

        /** No longer a material parameter: use [PlaneRenderer.MATERIAL_GRID_TINT]. */
        const val MATERIAL_COLOR = PlaneRenderer.MATERIAL_COLOR

        /** No longer a material parameter: the plane material is unlit. */
        const val MATERIAL_METALLIC = "metallic"

        /** No longer a material parameter: the plane material is unlit. */
        const val MATERIAL_ROUGHNESS = "roughness"

        /** No longer a material parameter: the plane material is unlit. */
        const val MATERIAL_REFLECTANCE = "reflectance"

        /** See [PlaneRenderer.MATERIAL_GRID_TINT]. */
        const val MATERIAL_GRID_TINT = PlaneRenderer.MATERIAL_GRID_TINT

        /** See [PlaneRenderer.MATERIAL_GRID_ALPHA]. */
        const val MATERIAL_GRID_ALPHA = PlaneRenderer.MATERIAL_GRID_ALPHA

        /** See [PlaneRenderer.MATERIAL_SURFACE_ALPHA]. */
        const val MATERIAL_SURFACE_ALPHA = PlaneRenderer.MATERIAL_SURFACE_ALPHA

        /** No longer a material parameter: the plane material has no reflection to fade in. */
        const val MATERIAL_REFLECTION_FADE_IN = "reflectionFadeIn"

        /** See [PlaneRenderer.MATERIAL_SCAN_PROGRESS]. */
        const val MATERIAL_SCAN_PROGRESS = PlaneRenderer.MATERIAL_SCAN_PROGRESS

        /** See [PlaneRenderer.MATERIAL_SCAN_PLANE_RADIUS]. */
        const val MATERIAL_SCAN_PLANE_RADIUS = PlaneRenderer.MATERIAL_SCAN_PLANE_RADIUS
    }
}
