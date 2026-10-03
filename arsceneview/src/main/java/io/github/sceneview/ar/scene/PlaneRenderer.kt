package io.github.sceneview.ar.scene

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.PlaneVisualizer
import io.github.sceneview.ar.arcore.fps
import io.github.sceneview.ar.arcore.getUpdatedPlanes
import io.github.sceneview.ar.arcore.isTracking
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setParameter
import io.github.sceneview.safeDestroyMaterialInstance
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the surfaces ARCore detects: a field of soft marks lying on each one
 * ([#3507](https://github.com/sceneview/sceneview/issues/3507),
 * [#4307](https://github.com/sceneview/sceneview/issues/4307)).
 *
 * This is the one plane renderer of `ARSceneView`; `ARSceneView(planeRenderer = true)` is all an
 * app needs.
 *
 * - **The mark says what the surface is.** Floors and tables carry round white dots, walls
 *   upright blue dashes on a staggered lattice, ceilings warm hollow rings
 *   ([planeMaterialPresetFor]). The shape differs as well as the colour, so a wall reads as a
 *   wall in a dark room, in daylight and for a colour-blind user.
 * - **Anchored to the world.** The marks stay put while ARCore re-centres and grows a plane, and
 *   fade out at the plane's edges and with distance.
 * - **Reveal.** A new surface is revealed by a bright front sweeping out from its centre, and
 *   each extension ARCore makes is revealed the same way.
 * - **Focus.** The floor under the centre of the screen is highlighted: brighter marks, a pool
 *   of light, a thin ring and slow ripples where the camera points — the surface itself is the
 *   reticle.
 * - **Only real surfaces.** A plane smaller than [isPlaneLargeEnough] allows is not drawn until
 *   it grows: ARCore reports many short-lived slivers on bags, door edges and furniture fronts.
 *
 * Setting [isEnabled] or [isVisible] to `false` fades the planes out instead of cutting them —
 * `ARSceneView(planeRenderer = false)` right after placing an object is the intended tidy exit.
 * Plane *detection* keeps running: hit tests, anchors and gestures on the placed object work
 * exactly as they do while the planes are drawn.
 *
 * @see io.github.sceneview.ar.PlaneVisualizer
 */
class PlaneRenderer(
    val engine: Engine,
    private val materialLoader: MaterialLoader,
    private val scene: Scene
) {

    /** Which tracked planes [PlaneRenderer] draws. */
    enum class PlaneRendererMode {
        /** Every tracked surface, the one under the centre of the screen highlighted. */
        RENDER_ALL,

        /** Only the surface under the centre of the screen; the others fade out. */
        RENDER_CENTER,
    }

    // Each visualizer together with the two MaterialInstances it was given: they are created
    // together and destroyed together, so live instances are always 2 × live visualizers.
    private val visualizers = OwnedVisualizers<Plane, PlaneVisualizer, MaterialInstance>(
        destroyVisualizer = { it.destroy() },
        destroyInstance = { engine.safeDestroyMaterialInstance(it) },
    )

    /**
     * The material every plane is drawn with (`plane_renderer.mat`). Each plane gets its own
     * instance; parameters set on [com.google.android.filament.Material.getDefaultInstance]
     * here are the defaults those instances start from.
     */
    val planeMaterial = materialLoader.createMaterial(
        "materials/plane_renderer.filamat"
    ).apply {
        defaultInstance.apply {
            // Marks per metre along both surface axes: 10 → a mark every 10 cm.
            setParameter(MATERIAL_UV_SCALE, MARKS_PER_METRE, MARKS_PER_METRE)
            setParameter(MATERIAL_SURFACE_KIND, PlaneSurfaceKind.FLOOR.shaderValue)
            setParameter(MATERIAL_GRID_TINT, DEFAULT_MARK_TINT)
            setParameter(MATERIAL_GRID_ALPHA, DEFAULT_MARK_ALPHA)
            setParameter(MATERIAL_SURFACE_ALPHA, DEFAULT_SURFACE_ALPHA)
            setParameter(MATERIAL_CONTRAST, DEFAULT_CONTRAST)
            // "Fully revealed, fully visible, not focused" — each plane animates its own
            // instance from there.
            setParameter(MATERIAL_SCAN_PROGRESS, 1.0f)
            setParameter(MATERIAL_SCAN_PLANE_RADIUS, 1.0f)
            setParameter(MATERIAL_OPACITY, 1.0f)
            setParameter(MATERIAL_FOCUS, 0.0f)
        }
    }

    private var shadowMaterial = materialLoader.createMaterial(
        "materials/plane_renderer_shadow.filamat"
    )

    /**
     * Which tracked planes are drawn: every one of them ([PlaneRendererMode.RENDER_ALL], the
     * default) or only the one under the centre of the screen.
     */
    var planeRendererMode = PlaneRendererMode.RENDER_ALL

    /**
     * Maximum number of plane updates per second.
     *
     * Polling ARCore's updated planes, picking the plane under the centre of the screen and
     * pushing plane geometry to Filament are gated to this rate. The fade, focus and reveal
     * animations are not: they advance every frame. Lower it to spend less of the frame on
     * planes; raise it to follow ARCore's plane growth more closely.
     */
    var maxUpdatesPerSecond: Int = 10

    /**
     * ### Enable/disable the plane renderer.
     *
     * Disabling fades the planes out; enabling fades them back in.
     */
    var isEnabled = true
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
    var isVisible = true
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
    var isShadowReceiver = true
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

    /**
     * Called by `ARSceneView` on every AR frame, on the render thread: refreshes the planes
     * ARCore updated (at most [maxUpdatesPerSecond] times a second) and advances every plane's
     * animations.
     */
    @Suppress("UNUSED_PARAMETER", "UnusedParameter")
    fun update(session: Session, frame: Frame) {
        if (isEnabled && frame.fps(this.frame) < maxUpdatesPerSecond) {
            this.frame = frame
            isCameraTracking = frame.camera.isTracking
            try {
                frame.getUpdatedPlanes().forEach { renderPlane(it) }
                refreshFocusAndVisibility(frame)
                cleanupOldPlaneVisualizer()
            } catch (e: Exception) {
                android.util.Log.e("SceneView", "PlaneRenderer update error", e)
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
        val renderAll = planeRendererMode == PlaneRendererMode.RENDER_ALL
        visualizers.forEach { plane, visualizer ->
            val isCenter = plane == centerPlane
            visualizer.setFocus(if (isCenter) 1f else 0f)
            visualizer.setVisible(isVisible && (renderAll || isCenter))
        }
    }

    /** Releases every plane visualizer and the two materials. Called by `ARSceneView`. */
    fun destroy() {
        // Visualizers first: each one takes its entity out of the scene before its two
        // MaterialInstances are destroyed.
        visualizers.clear()
        materialLoader.destroyMaterial(planeMaterial)
        materialLoader.destroyMaterial(shadowMaterial)
    }

    /** Refreshes (or lazily creates) the [PlaneVisualizer] for [plane]. */
    private fun renderPlane(plane: Plane) {
        val existing = visualizers[plane]
        val action = planeVisualizerAction(
            trackingState = plane.trackingState,
            isSubsumed = plane.subsumedBy != null,
            hasVisualizer = existing != null,
            // Only read the extents of a plane that has no visualizer yet.
            isLargeEnough = existing != null ||
                isPlaneLargeEnough(plane.type, plane.extentX, plane.extentZ),
        )
        when (action) {
            PlaneVisualizerAction.SKIP -> Unit
            PlaneVisualizerAction.UPDATE -> existing?.updatePlane()
            PlaneVisualizerAction.CREATE -> createVisualizer(plane).updatePlane()
        }
    }

    private fun createVisualizer(plane: Plane): PlaneVisualizer {
        val planeInstance = planeMaterial.createInstance()
        val shadowInstance = shadowMaterial.createInstance()
        val visualizer = PlaneVisualizer(engine, scene, plane).apply {
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
        /** Float2 — marks per metre along the two surface axes (default 10 × 10). */
        const val MATERIAL_UV_SCALE = "uvScale"

        /**
         * Float — which mark is drawn: `0` round dots (floor), `1` upright dashes on a
         * staggered lattice (wall), `2` hollow rings (ceiling). Set per plane type by the
         * visualizer.
         */
        const val MATERIAL_SURFACE_KIND = "surfaceKind"

        /** Float3 mark colour. Set per plane type by the visualizer. */
        const val MATERIAL_GRID_TINT = "gridTint"

        /** Float peak opacity of a mark. Set per plane type by the visualizer. */
        const val MATERIAL_GRID_ALPHA = "gridAlpha"

        /** Float opacity of the faint film between the marks. */
        const val MATERIAL_SURFACE_ALPHA = "surfaceAlpha"

        /**
         * Float in `[0, 1]`: opacity of the soft dark halo around each mark and around the
         * reticle ring. The halo is what keeps a white dot readable on a sunlit floor or a
         * white wall; `0` removes it.
         */
        const val MATERIAL_CONTRAST = "contrast"

        /**
         * Float in `[0, 1]`: `1` = the plane is fully revealed; below `1` the reveal front is
         * drawn at [MATERIAL_SCAN_PLANE_RADIUS], `1 - scanProgress` bright. Animated per plane
         * by the visualizer.
         */
        const val MATERIAL_SCAN_PROGRESS = "scanProgress"

        /**
         * Float — plane-local radius, in metres, the reveal front has reached. Animated per
         * plane by the visualizer.
         */
        const val MATERIAL_SCAN_PLANE_RADIUS = "scanPlaneRadius"

        /** Float in `[0, 1]`: whole-plane fade. Animated per plane by the visualizer. */
        const val MATERIAL_OPACITY = "opacity"

        /**
         * Float in `[0, 1]`: emphasis of the plane under the centre of the screen. Animated per
         * plane by the visualizer.
         */
        const val MATERIAL_FOCUS = "focus"

        /** One mark every 10 cm — reads as a surface from a metre away, not as noise. */
        private const val MARKS_PER_METRE = 10.0f

        private val DEFAULT_MARK_TINT = Float3(1.0f, 1.0f, 1.0f)
        private const val DEFAULT_MARK_ALPHA = 0.85f
        private const val DEFAULT_SURFACE_ALPHA = 0.015f
        private const val DEFAULT_CONTRAST = 0.80f
    }
}

/** What [PlaneRenderer] does with one plane ARCore reports as updated this frame. */
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
 *
 * [isLargeEnough] only gates the *creation*: a plane that already has a visualizer keeps it
 * when ARCore refines it back under the threshold, so a surface never flickers in and out.
 */
internal fun planeVisualizerAction(
    trackingState: TrackingState,
    isSubsumed: Boolean,
    hasVisualizer: Boolean,
    isLargeEnough: Boolean = true,
): PlaneVisualizerAction = when {
    isSubsumed || trackingState == TrackingState.STOPPED -> PlaneVisualizerAction.SKIP
    hasVisualizer -> PlaneVisualizerAction.UPDATE
    isLargeEnough -> PlaneVisualizerAction.CREATE
    else -> PlaneVisualizerAction.SKIP
}

/**
 * Whether a plane of [type] whose bounding rectangle measures [extentX] × [extentZ] metres is
 * worth drawing ([#4307](https://github.com/sceneview/sceneview/issues/4307)).
 *
 * ARCore reports a plane as soon as it has a handful of coplanar feature points, so the front
 * of a bag, a speaker or a door edge each become a "wall" a few centimetres wide for a second
 * or two. Drawing them makes the scan look wrong; nobody places anything on them. The floor
 * threshold is deliberately low — a small table top or a stool is a real surface — and the
 * wall threshold higher, because slivers are overwhelmingly vertical.
 *
 * Purely a rendering decision: hit tests and anchors still see every plane.
 */
// `Plane.Type` is exhaustive at this ARCore version; an unknown future type is treated as a
// horizontal surface rather than crashing the render thread.
@Suppress("REDUNDANT_ELSE_IN_WHEN")
internal fun isPlaneLargeEnough(type: Plane.Type, extentX: Float, extentZ: Float): Boolean {
    val longest = max(extentX, extentZ)
    val shortest = min(extentX, extentZ)
    return when (type) {
        Plane.Type.VERTICAL ->
            longest >= MIN_WALL_LONGEST_M && shortest >= MIN_WALL_SHORTEST_M
        else ->
            longest >= MIN_HORIZONTAL_LONGEST_M && shortest >= MIN_HORIZONTAL_SHORTEST_M
    }
}

internal const val MIN_HORIZONTAL_LONGEST_M = 0.20f
internal const val MIN_HORIZONTAL_SHORTEST_M = 0.10f
internal const val MIN_WALL_LONGEST_M = 0.40f
internal const val MIN_WALL_SHORTEST_M = 0.20f

/**
 * Plane → visualizer bookkeeping for [PlaneRenderer], holding the MaterialInstances each
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
 * The mark `plane_renderer.mat` draws on a surface — its `surfaceKind` parameter.
 *
 * @property shaderValue The value written to [PlaneRenderer.MATERIAL_SURFACE_KIND].
 */
internal enum class PlaneSurfaceKind(val shaderValue: Float) {
    /** Round dots on a square lattice. */
    FLOOR(0f),

    /** Upright dashes on a staggered lattice. */
    WALL(1f),

    /** Hollow rings on a square lattice. */
    CEILING(2f),
}

/**
 * Per-`plane.type` style applied by [io.github.sceneview.ar.PlaneVisualizer] on top of
 * [PlaneRenderer.planeMaterial]'s shared defaults: same single `Material`, one
 * [com.google.android.filament.MaterialInstance] per plane.
 *
 * @param kind The mark drawn on the surface.
 * @param gridR Red component of the mark colour, `[0, 1]`.
 * @param gridG Green component of the mark colour, `[0, 1]`.
 * @param gridB Blue component of the mark colour, `[0, 1]`.
 * @param markAlpha Peak opacity of a mark, `[0, 1]`.
 */
internal data class PlaneMaterialPreset(
    val kind: PlaneSurfaceKind,
    val gridR: Float,
    val gridG: Float,
    val gridB: Float,
    val markAlpha: Float,
)

/**
 * Maps a [com.google.ar.core.Plane.Type] to its [PlaneMaterialPreset]
 * ([#4307](https://github.com/sceneview/sceneview/issues/4307)).
 *
 * | `plane.type`                 | role    | mark                                             |
 * |------------------------------|---------|--------------------------------------------------|
 * | `HORIZONTAL_UPWARD_FACING`   | floor   | round dots, white, the strongest                 |
 * | `VERTICAL`                   | wall    | upright dashes, staggered rows, blue             |
 * | `HORIZONTAL_DOWNWARD_FACING` | ceiling | hollow rings, warm, the quietest                 |
 *
 * Shape first, colour second: a tint alone is lost on a coloured wall or under a warm lamp, and
 * the first version of this renderer (a faint blue on the same dots) could not tell a wall from
 * a floor on a device. The floor is the surface people place things on, so it reads first; the
 * other two stay recognisable without competing with it. Unknown future ARCore plane types fall
 * back to the floor style rather than crashing.
 *
 * The wall and ceiling colours are deep on purpose (linear luminance under 0.5): walls and
 * ceilings are white more often than not, a mark cannot be brighter than white paint in
 * daylight, and the view's filmic tone mapper turns any light tint into white up there. So
 * they are darker than the wall, and still bright against a dark room. The floor stays white
 * and relies on the dark halo of the material (`contrast`) over a light floor.
 */
// `Plane.Type` is exhaustive at this ARCore version; the `else` arm is future-proofing — a
// render-thread crash on an unknown enum value is strictly worse than a slightly-wrong mark.
@Suppress("REDUNDANT_ELSE_IN_WHEN")
internal fun planeMaterialPresetFor(type: Plane.Type): PlaneMaterialPreset =
    when (type) {
        Plane.Type.HORIZONTAL_UPWARD_FACING -> FLOOR_PRESET
        Plane.Type.HORIZONTAL_DOWNWARD_FACING -> CEILING_PRESET
        Plane.Type.VERTICAL -> WALL_PRESET
        else -> FLOOR_PRESET
    }

private val FLOOR_PRESET = PlaneMaterialPreset(
    kind = PlaneSurfaceKind.FLOOR, gridR = 1.0f, gridG = 1.0f, gridB = 1.0f, markAlpha = 0.85f,
)
private val WALL_PRESET = PlaneMaterialPreset(
    kind = PlaneSurfaceKind.WALL, gridR = 0.04f, gridG = 0.22f, gridB = 0.90f, markAlpha = 0.80f,
)
private val CEILING_PRESET = PlaneMaterialPreset(
    kind = PlaneSurfaceKind.CEILING, gridR = 0.90f, gridG = 0.35f, gridB = 0.04f, markAlpha = 0.70f,
)
