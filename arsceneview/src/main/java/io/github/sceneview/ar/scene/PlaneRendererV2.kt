package io.github.sceneview.ar.scene

import android.util.Size
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.PlaneVisualizerV2
import io.github.sceneview.ar.arcore.fps
import io.github.sceneview.ar.arcore.getUpdatedPlanes
import io.github.sceneview.ar.arcore.isTracking
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setParameter
import io.github.sceneview.material.setTexture
import io.github.sceneview.math.Color
import io.github.sceneview.safeDestroyMaterialInstance
import io.github.sceneview.safeDestroyTexture
import io.github.sceneview.texture.ImageTexture

/**
 * Control rendering of ARCore planes — V2 implementation: a field of soft dots
 * ([#3507](https://github.com/sceneview/sceneview/issues/3507)).
 *
 * **V2 is an opt-in.** The default is [PlaneRendererBase.Version.V1], rendered by
 * [PlaneRenderer]. Opt into V2 with
 * `ARSceneView(planeRendererVersion = PlaneRendererBase.Version.V2)`.
 *
 * Every detected surface is drawn as small round dots lying on it, anchored to the world, that
 * fade out at the edges and with distance. A new surface is revealed by a bright front sweeping
 * out from its centre, and each extension ARCore makes is revealed the same way. The floor
 * under the centre of the screen is highlighted: brighter dots, a pool of light, a thin ring and
 * slow ripples where the camera points — the surface itself is the reticle. Floors, ceilings and
 * walls read apart without a legend: floors are white, ceilings warm and quieter, walls a soft
 * blue with a smaller staggered lattice ([planeMaterialPresetFor]).
 *
 * Setting [isEnabled] or [isVisible] to `false` fades the planes out instead of cutting them —
 * `ARSceneView(planeRenderer = false)` right after placing an object is the intended tidy exit.
 *
 * V2's first design (#2203: depth-driven PBR mesh, HDR reflections, a grid; v4.16.0) was
 * reverted as the default in v4.16.1 after on-device QA; #3507 replaced it with this one. The
 * PBR material parameters ([MATERIAL_METALLIC], [MATERIAL_ROUGHNESS], [MATERIAL_REFLECTANCE],
 * [MATERIAL_REFLECTION_FADE_IN], [MATERIAL_TEXTURE]) stay declared for compatibility but no
 * longer change the look.
 *
 * @see PlaneRendererBase
 * @see PlaneRenderer
 * @see io.github.sceneview.ar.PlaneVisualizerV2
 */
class PlaneRendererV2(
    val engine: Engine,
    private val materialLoader: MaterialLoader,
    private val scene: Scene
) : PlaneRendererBase {

    override lateinit var viewSize: Size

    // Each visualizer together with the two MaterialInstances it was given: they are created
    // together and destroyed together, so live instances are always 2 × live visualizers.
    private val visualizers = OwnedVisualizers<Plane, PlaneVisualizerV2, MaterialInstance>(
        destroyVisualizer = { it.destroy() },
        destroyInstance = { engine.safeDestroyMaterialInstance(it) },
    )

    val planeTexture = ImageTexture.Builder()
        .bitmap(materialLoader.assets, "textures/plane_renderer.png")
        .build(engine)

    /**
     * Default material instance used to render the planes.
     */
    val planeMaterial = materialLoader.createMaterial(
        "materials/plane_renderer_v2.filamat"
    ).apply {
        defaultInstance.apply {
            setTexture(MATERIAL_TEXTURE, planeTexture)

            // Dots per metre along both surface axes: 10 → a dot every 10 cm.
            setParameter(MATERIAL_UV_SCALE, DOTS_PER_METRE, DOTS_PER_METRE)
            // Legacy tint, multiplied into `gridTint`; neutral unless an app overrides it.
            setParameter(MATERIAL_COLOR, Color(1.0f, 1.0f, 1.0f))
            // Unused since the surface is unlit (#3507), set so the parameters hold defined values.
            setParameter(MATERIAL_METALLIC, 0.0f)
            setParameter(MATERIAL_ROUGHNESS, 1.0f)
            setParameter(MATERIAL_REFLECTANCE, 0.5f)
            setParameter(MATERIAL_REFLECTION_FADE_IN, 1.0f)
            setParameter(MATERIAL_GRID_TINT, DEFAULT_DOT_TINT)
            setParameter(MATERIAL_GRID_ALPHA, DEFAULT_DOT_ALPHA)
            setParameter(MATERIAL_SURFACE_ALPHA, DEFAULT_SURFACE_ALPHA)
            // "Fully revealed, fully visible, not focused" — each plane animates its own
            // instance from there.
            setParameter(MATERIAL_SCAN_PROGRESS, 1.0f)
            setParameter(MATERIAL_SCAN_PLANE_RADIUS, 1.0f)
            setParameter("opacity", 1.0f)
            setParameter("focus", 0.0f)
        }
    }

    private var shadowMaterial = materialLoader.createMaterial(
        "materials/plane_renderer_shadow.filamat"
    )

    /**
     * Which tracked planes are drawn.
     *
     * - `RENDER_ALL` (the default): every tracked surface, the one under the centre of the
     *   screen highlighted.
     * - `RENDER_CENTER`: only the floor under the centre of the screen; the others fade out.
     */
    // PlaneRendererMode is nested inside the V1 PlaneRenderer class and reused as-is by V2.
    @Suppress("DEPRECATION")
    var planeRendererMode = PlaneRenderer.PlaneRendererMode.RENDER_ALL

    /**
     * Maximum number of plane-renderer updates per second.
     *
     * Polling ARCore's updated planes, picking the plane under the centre of the screen and
     * pushing plane geometry to Filament are gated to this rate. The fade, focus and reveal
     * animations are not: they advance every frame. Decrease if you don't need a very precise position update and
     * want to reduce frame consumption; increase for a more accurate positioning update.
     *
     * The name comes from the `Frame.hitTest` this gate used to throttle. That hit test is
     * gone (#3339), but the gate, its default and its meaning as a rate limit are unchanged,
     * so the name is kept for source and binary compatibility.
     */
    var maxHitTestPerSecond: Int = 10

    /**
     * ### Enable/disable the plane renderer.
     *
     * Disabling fades the planes out; enabling fades them back in.
     */
    override var isEnabled = true
        set(value) {
            if (field != value) {
                field = value
                visualizers.forEach { _, visualizer -> visualizer.setEnabled(value) }
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
                // Hiding is immediate for every plane; showing waits for the next update, which
                // knows the centre plane (RENDER_CENTER shows only that one).
                if (!value) visualizers.forEach { _, visualizer -> visualizer.setVisible(false) }
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
                visualizers.forEach { _, visualizer -> visualizer.setShadowReceiver(value) }
            }
        }

    private var isCameraTracking = false
        set(value) {
            if (field != value) {
                field = value
                visualizers.forEach { _, visualizer -> visualizer.setEnabled(isEnabled && value) }
            }
        }

    private var frame: Frame? = null

    override fun update(session: Session, frame: Frame) {
        if (isEnabled && frame.fps(this.frame) < maxHitTestPerSecond) {
            this.frame = frame
            isCameraTracking = frame.camera.isTracking
            try {
                frame.getUpdatedPlanes().forEach { renderPlane(it) }
                refreshFocusAndVisibility(frame)
                cleanupOldPlaneVisualizer()
            } catch (e: Exception) {
                android.util.Log.e("SceneView", "PlaneRendererV2 update error", e)
            }
        }
        // Every frame, enabled or not: planes keep fading out after `isEnabled = false`.
        val now = System.nanoTime()
        val visualizerList = visualizers.visualizerList
        for (i in visualizerList.indices) visualizerList[i].tick(now)
    }

    /**
     * Highlights the floor under the centre of the screen and, in `RENDER_CENTER`, hides the
     * others. #3339: the centre plane is found analytically by [CenterPlaneFinder] — a
     * `frame.hitTest` here made ARCore's native depth sub-test spam the log on every AR screen.
     */
    private fun refreshFocusAndVisibility(frame: Frame) {
        val centerPlane = if (isVisible) CenterPlaneFinder.find(frame, visualizers.keys) else null
        @Suppress("DEPRECATION")
        val renderAll = planeRendererMode == PlaneRenderer.PlaneRendererMode.RENDER_ALL
        visualizers.forEach { plane, visualizer ->
            val isCenter = plane == centerPlane
            visualizer.setFocus(if (isCenter) 1f else 0f)
            visualizer.setVisible(isVisible && (renderAll || isCenter))
        }
    }

    override fun destroy() {
        // Visualizers first: each one takes its entity out of the scene before its two
        // MaterialInstances are destroyed.
        visualizers.clear()
        materialLoader.destroyMaterial(planeMaterial)
        materialLoader.destroyMaterial(shadowMaterial)
        engine.safeDestroyTexture(planeTexture)
    }

    /** Refreshes (or lazily creates) the [PlaneVisualizerV2] for [plane]. */
    private fun renderPlane(plane: Plane) {
        val existing = visualizers[plane]
        val action = planeVisualizerAction(
            trackingState = plane.trackingState,
            isSubsumed = plane.subsumedBy != null,
            hasVisualizer = existing != null,
        )
        when (action) {
            PlaneVisualizerAction.SKIP -> Unit
            PlaneVisualizerAction.UPDATE -> existing?.updatePlane()
            PlaneVisualizerAction.CREATE -> createVisualizer(plane).updatePlane()
        }
    }

    private fun createVisualizer(plane: Plane): PlaneVisualizerV2 {
        val planeInstance = planeMaterial.createInstance()
        val shadowInstance = shadowMaterial.createInstance()
        val visualizer = PlaneVisualizerV2(engine, scene, plane).apply {
            setPlaneMaterial(planeInstance)
            setShadowMaterial(shadowInstance)
            setShadowReceiver(isShadowReceiver)
            // Shown by refreshFocusAndVisibility once the centre plane is known.
            setVisible(false)
            setEnabled(isEnabled && isCameraTracking)
        }
        visualizers.put(plane, visualizer, listOf(planeInstance, shadowInstance))
        return visualizer
    }

    /**
     * ### Remove plane visualizers for old planes that are no longer tracking
     *
     * Update the material parameters for all remaining planes.
     */
    private fun cleanupOldPlaneVisualizer() {
        // If this plane was subsumed by another plane or it has permanently stopped tracking,
        // remove it — the visualizer and its two MaterialInstances together.
        visualizers.removeIf { plane, _ ->
            plane.subsumedBy != null || plane.trackingState == TrackingState.STOPPED
        }
    }

    companion object {
        /**
         * Sampler kept for compatibility: the V2 dots are procedural, the texture is not sampled.
         */
        const val MATERIAL_TEXTURE = "texture"

        /** Float2 — dots per metre along the two surface axes (default 10 × 10). */
        const val MATERIAL_UV_SCALE = "uvScale"

        /**
         * Float3 material parameter — legacy RGB tint kept for binary/API compatibility,
         * multiplied into [MATERIAL_GRID_TINT].
         */
        const val MATERIAL_COLOR = "color"

        /** Float, kept for compatibility. Unused since the V2 surface is unlit (#3507). */
        const val MATERIAL_METALLIC = "metallic"

        /** Float, kept for compatibility. Unused since the V2 surface is unlit (#3507). */
        const val MATERIAL_ROUGHNESS = "roughness"

        /** Float, kept for compatibility. Unused since the V2 surface is unlit (#3507). */
        const val MATERIAL_REFLECTANCE = "reflectance"

        /** Float3 dot colour. Set per plane type by [planeMaterialPresetFor]. */
        const val MATERIAL_GRID_TINT = "gridTint"

        /** Float peak opacity of a dot. Set per plane type by [planeMaterialPresetFor]. */
        const val MATERIAL_GRID_ALPHA = "gridAlpha"

        /** Float opacity of the faint film between the dots. */
        const val MATERIAL_SURFACE_ALPHA = "surfaceAlpha"

        /** Float, kept for compatibility. Unused since the V2 surface is unlit (#3507). */
        const val MATERIAL_REFLECTION_FADE_IN = "reflectionFadeIn"

        /**
         * Float in `[0, 1]`: `1` = the plane is fully revealed; below `1` the reveal front is
         * drawn at [MATERIAL_SCAN_PLANE_RADIUS], `1 - scanProgress` bright. Animated per plane
         * by `PlaneVisualizerV2`.
         */
        const val MATERIAL_SCAN_PROGRESS = "scanProgress"

        /**
         * Float — plane-local radius, in metres, the reveal front has reached. Animated per
         * plane by `PlaneVisualizerV2`.
         */
        const val MATERIAL_SCAN_PLANE_RADIUS = "scanPlaneRadius"

        /** One dot every 10 cm — reads as a surface from a metre away, not as noise. */
        private const val DOTS_PER_METRE = 10.0f

        private val DEFAULT_DOT_TINT = Float3(1.0f, 1.0f, 1.0f)
        private const val DEFAULT_DOT_ALPHA = 0.85f
        private const val DEFAULT_SURFACE_ALPHA = 0.015f
    }
}

/** What [PlaneRendererV2] does with one plane ARCore reports as updated this frame. */
internal enum class PlaneVisualizerAction { SKIP, CREATE, UPDATE }

/**
 * Decides whether an updated plane gets a new visualizer, a refresh, or nothing.
 *
 * A subsumed plane has been merged into a larger one, which is drawn instead, and a `STOPPED`
 * plane is gone for good: both are skipped, and `cleanupOldPlaneVisualizer` releases a
 * visualizer they may still have. Until this was a function the test read
 * `TRACKING || subsumedBy == null`, which let every subsumed plane through, so each gated update
 * built a visualizer (buffers, entity, two MaterialInstances) that the cleanup destroyed right
 * after. A `PAUSED` plane keeps being updated: `updatePlane` fades it out.
 */
internal fun planeVisualizerAction(
    trackingState: TrackingState,
    isSubsumed: Boolean,
    hasVisualizer: Boolean,
): PlaneVisualizerAction = when {
    isSubsumed || trackingState == TrackingState.STOPPED -> PlaneVisualizerAction.SKIP
    hasVisualizer -> PlaneVisualizerAction.UPDATE
    else -> PlaneVisualizerAction.CREATE
}

/**
 * Plane → visualizer bookkeeping for [PlaneRendererV2], holding the MaterialInstances each
 * visualizer was given. A visualizer and its instances are added together and released
 * together — visualizer first, so its entity leaves the scene before its materials go — so the
 * live instance count is always the instances-per-visualizer times [size].
 *
 * Generic so the ownership rule is unit-tested without a Filament Engine.
 */
internal class OwnedVisualizers<K : Any, V : Any, M : Any>(
    private val destroyVisualizer: (V) -> Unit,
    private val destroyInstance: (M) -> Unit,
) {
    private class Slot<V, M>(val visualizer: V, val instances: List<M>)

    private val slots = LinkedHashMap<K, Slot<V, M>>()

    /** The live visualizers as a list, for the per-frame tick to walk by index. */
    val visualizerList = ArrayList<V>()

    /** The planes that currently have a visualizer. */
    val keys: Set<K> get() = slots.keys

    val size: Int get() = slots.size

    /** MaterialInstances held by live visualizers. */
    val instanceCount: Int get() = slots.values.sumOf { it.instances.size }

    operator fun get(key: K): V? = slots[key]?.visualizer

    fun put(key: K, visualizer: V, instances: List<M>) {
        slots.put(key, Slot(visualizer, instances))?.let { release(it) }
        visualizerList += visualizer
    }

    fun forEach(action: (K, V) -> Unit) {
        for ((key, slot) in slots) action(key, slot.visualizer)
    }

    fun removeIf(predicate: (K, V) -> Boolean) {
        val iterator = slots.entries.iterator()
        while (iterator.hasNext()) {
            val (key, slot) = iterator.next()
            if (predicate(key, slot.visualizer)) {
                iterator.remove()
                release(slot)
            }
        }
    }

    fun clear() {
        slots.values.forEach { release(it) }
        slots.clear()
    }

    private fun release(slot: Slot<V, M>) {
        visualizerList.remove(slot.visualizer)
        destroyVisualizer(slot.visualizer)
        slot.instances.forEach(destroyInstance)
    }
}

/**
 * Per-`plane.type` dot style applied by [io.github.sceneview.ar.PlaneVisualizerV2] on top of
 * `PlaneRendererV2.planeMaterial`'s shared defaults: same single `Material`, one
 * [com.google.android.filament.MaterialInstance] per plane.
 *
 * `internal` so [io.github.sceneview.ar.scene.PlaneRendererV2Test] can exercise the mapping
 * without an Engine.
 *
 * @param gridR Red component of the dot colour, `[0, 1]`.
 * @param gridG Green component of the dot colour, `[0, 1]`.
 * @param gridB Blue component of the dot colour, `[0, 1]`.
 * @param dotAlpha Peak opacity of a dot, `[0, 1]`.
 */
internal data class PlaneMaterialPreset(
    val gridR: Float,
    val gridG: Float,
    val gridB: Float,
    val dotAlpha: Float,
)

/**
 * Maps a [com.google.ar.core.Plane.Type] to its [PlaneMaterialPreset] (#3507).
 *
 * | `plane.type`                 | role    | dots                     |
 * |------------------------------|---------|--------------------------|
 * | `HORIZONTAL_UPWARD_FACING`   | floor   | white, the strongest     |
 * | `HORIZONTAL_DOWNWARD_FACING` | ceiling | warm white, quieter      |
 * | `VERTICAL`                   | wall    | soft blue (and, in the shader, a smaller staggered lattice) |
 *
 * The floor is the surface people place things on, so it reads first; the other two stay
 * recognisable without competing with it. Unknown future ARCore plane types fall back to the
 * floor style rather than crashing.
 */
// `Plane.Type` is exhaustive at this ARCore version; the `else` arm is future-proofing — a
// render-thread crash on an unknown enum value is strictly worse than a slightly-wrong colour.
@Suppress("REDUNDANT_ELSE_IN_WHEN")
internal fun planeMaterialPresetFor(type: com.google.ar.core.Plane.Type): PlaneMaterialPreset =
    when (type) {
        com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING -> FLOOR_PRESET
        com.google.ar.core.Plane.Type.HORIZONTAL_DOWNWARD_FACING -> CEILING_PRESET
        com.google.ar.core.Plane.Type.VERTICAL -> WALL_PRESET
        else -> FLOOR_PRESET
    }

private val FLOOR_PRESET = PlaneMaterialPreset(gridR = 1.0f, gridG = 1.0f, gridB = 1.0f, dotAlpha = 0.85f)
private val CEILING_PRESET = PlaneMaterialPreset(gridR = 1.0f, gridG = 0.90f, gridB = 0.74f, dotAlpha = 0.55f)
private val WALL_PRESET = PlaneMaterialPreset(gridR = 0.66f, gridG = 0.82f, gridB = 1.0f, dotAlpha = 0.70f)
