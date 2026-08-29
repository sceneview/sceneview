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
 * Control rendering of ARCore planes — V2 implementation.
 *
 * **V2 is the default plane renderer as of this release.** See
 * [#2203](https://github.com/sceneview/sceneview/issues/2203) for the umbrella that delivered
 * it. The legacy V1 [PlaneRenderer] remains available behind
 * `ARSceneView(planeRendererVersion = PlaneRendererBase.Version.V1)` for one release cycle
 * and is now `@Deprecated`.
 *
 * **PR #4 status** ([#2203](https://github.com/sceneview/sceneview/issues/2203)): a floor,
 * a ceiling and a wall visible at once now read as three distinct surfaces.
 * [io.github.sceneview.ar.PlaneVisualizerV2] applies a per-instance material preset
 * keyed off `com.google.ar.core.Plane.Type` — same single `Material`, one
 * `MaterialInstance` per plane, three different `(metallic, roughness, gridTint)` triples:
 *
 * | `plane.type`                 | role    | metallic | roughness | gridTint     |
 * |------------------------------|---------|----------|-----------|--------------|
 * | `HORIZONTAL_UPWARD_FACING`   | floor   | `0.00`   | `0.35`    | cool white   |
 * | `HORIZONTAL_DOWNWARD_FACING` | ceiling | `0.00`   | `0.65`    | warm white   |
 * | `VERTICAL`                   | wall    | `0.00`   | `0.80`    | neutral grey |
 *
 * Unknown future ARCore plane types fall back to the floor preset rather than crashing.
 * Type re-classifications mid-tracking (ARCore can re-merge a plane into a different
 * normal direction) trigger a 3-`setParameter`-call re-apply on the next `updatePlane()`.
 *
 * **PR #3 carried over** ([#2203](https://github.com/sceneview/sceneview/issues/2203)): the
 * V2 plane is a real PBR surface lit by the real room. The material is `shadingModel: lit`,
 * declares `metallic` / `roughness` / `reflectance` slots, and samples Filament's IBL —
 * which `LightEstimator` continuously feeds from ARCore's `acquireEnvironmentalHdrCubeMap()`.
 * A glossy floor visibly reflects the ceiling lights and window glow; a matt wall picks
 * up the actual ambient irradiance. The depth-driven mesh ships smooth per-vertex
 * `TANGENTS` so PBR specular highlights wrap correctly across bumps and slopes. The first
 * time a plane is detected, an 800 ms scan-in ring expands outward from the polygon
 * centroid and a 1 s reflection fade-in ramps `metallic` + `reflectance` from 0 to their
 * configured values, masking ARCore's HDR cubemap stabilisation window.
 *
 * Without depth — device unsupported, first frames after resume, raw frame not yet ready —
 * V2 falls back transparently to the V1 flat-polygon mesh (with smooth `(0, 1, 0)` normals
 * so the lit shader still reads as a level surface). Never crashes, never blanks.
 *
 * **#2203 sprint — all 5 PRs landed**:
 *
 * | PR  | Effect                                                                       |
 * |-----|------------------------------------------------------------------------------|
 * | #2  | Depth-driven tessellation + polygon clip + slope-aware unlit grid. **DONE.** |
 * | #3  | PBR + HDR reflections + scan-in ring + reflection fade-in. **DONE.**         |
 * | #4  | Type-aware shading: floor / ceiling / wall get distinct identities. **DONE.** |
 * | #5  | V2 becomes the default. V1 gets `@Deprecated` for one release cycle. **DONE.** |
 *
 * V2 is now the default — no opt-in required. Pass
 * `ARSceneView(planeRendererVersion = PlaneRendererBase.Version.V1)` to fall back to V1
 * during the one-cycle deprecation window.
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

    private val visualizers = mutableMapOf<Plane, PlaneVisualizerV2>()

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

            val widthToHeightRatio =
                planeTexture.getWidth(0).toFloat() / planeTexture.getHeight(0).toFloat()
            val scaleX = BASE_UV_SCALE
            val scaleY = scaleX * widthToHeightRatio
            setParameter(MATERIAL_UV_SCALE, scaleX, scaleY)

            // Legacy `color` tint stays neutral white — `gridTint` is the new colour knob
            // PR #4 will vary per `plane.type`. Keep `color` at 1.0 so the multiplicative
            // chain `gridTint * color * slopeShade` reduces to `gridTint * slopeShade`
            // unless a custom MaterialInstance overrides it.
            setParameter(MATERIAL_COLOR, Color(1.0f, 1.0f, 1.0f))

            // ── PR #3 PBR + animation defaults ──────────────────────────────────────
            // These match the brief in `.claude/plans/plane-renderer-v2.md` (PR #3 row).
            // `metallic = 0` so the surface is a dielectric floor; `roughness = 0.35`
            // gives a slight gloss so the IBL reflection reads; `reflectance = 0.5`
            // is the canonical dielectric value (matches kMaterialDefaultReflectance
            // in MaterialInstance.kt).
            setParameter(MATERIAL_METALLIC, DEFAULT_METALLIC)
            setParameter(MATERIAL_ROUGHNESS, DEFAULT_ROUGHNESS)
            setParameter(MATERIAL_REFLECTANCE, DEFAULT_REFLECTANCE)
            setParameter(MATERIAL_GRID_TINT, DEFAULT_GRID_TINT)
            setParameter(MATERIAL_GRID_ALPHA, DEFAULT_GRID_ALPHA)
            setParameter(MATERIAL_SURFACE_ALPHA, DEFAULT_SURFACE_ALPHA)
            // Steady-state defaults — `reflectionFadeIn = 1.0` and `scanProgress = 1.0`
            // mean "no animation in progress". The per-visualizer overrides land via
            // `pushAnimationUniforms` on its own per-instance MaterialInstance.
            setParameter(MATERIAL_REFLECTION_FADE_IN, 1.0f)
            setParameter(MATERIAL_SCAN_PROGRESS, 1.0f)
            setParameter(MATERIAL_SCAN_PLANE_RADIUS, 1.0f)
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
    // PlaneRendererMode is nested inside the now-@Deprecated V1 PlaneRenderer class. The
    // enum itself is reused as-is by V2 (no V2-specific replacement was introduced); the
    // suppression is the textbook case for referencing a deprecated container of a
    // still-valid nested type. When V1 is removed in a future release, PlaneRendererMode
    // will be hoisted onto PlaneRendererBase.
    @Suppress("DEPRECATION")
    var planeRendererMode = PlaneRenderer.PlaneRendererMode.RENDER_CENTER

    /**
     * Maximum number of plane-renderer updates per second.
     *
     * The whole [update] pass is gated to this rate: polling ARCore's updated planes, picking
     * the centre plane in [PlaneRenderer.PlaneRendererMode.RENDER_CENTER], and pushing plane
     * geometry to Filament. Decrease if you don't need a very precise position update and
     * want to reduce frame consumption; increase for a more accurate positioning update.
     *
     * The name comes from the `Frame.hitTest` this gate used to throttle. That hit test is
     * gone (#3339), but the gate, its default and its meaning as a rate limit are unchanged,
     * so the name is kept for source and binary compatibility.
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
                    val camera = frame.camera
                    @Suppress("DEPRECATION")
                    if (planeRendererMode == PlaneRenderer.PlaneRendererMode.RENDER_ALL) {
                        updatedPlanes.forEach { renderPlane(it, frame = frame, camera = camera) }
                    } else if (planeRendererMode == PlaneRenderer.PlaneRendererMode.RENDER_CENTER) {
                        // AR10 (#2504): `updatedPlanes` is ARCore's JNI-backed list, so the
                        // per-visualizer `plane !in updatedPlanes` below was an O(M) linear scan —
                        // O(N×M) across all visualizers every frame. Hoist a `HashSet` once so the
                        // membership test is O(1). ARCore hands out a FRESH `Plane` wrapper per
                        // frame, so this works on `Plane`'s native-handle value equality (see
                        // `io.github.sceneview.ar.arcore.diffPlanes`), never on identity — set
                        // membership matches the list `contains` exactly and the visibility
                        // outcome is unchanged.
                        val updatedPlaneSet = updatedPlanes.toHashSet()
                        // Find the top most plane Trackable at the centre of the screen; only that
                        // one is rendered.
                        //
                        // #3339: this used to be a real `frame.hitTest(centreX, centreY)`. ARCore
                        // attempts a depth sub-test inside every `hitTest`, and on devices whose
                        // motion-stereo depth pipeline is unavailable that sub-test fails and its
                        // NATIVE logger spams `depth_hit_test.cc` / `AR_ERROR_ILLEGAL_STATE`
                        // warnings — here at the `maxHitTestPerSecond` rate, forever, on every AR
                        // screen. Nothing on the Kotlin side can filter a native log, and no
                        // `hitTest` overload accepts a trackable-type filter, so the only fix is
                        // not to make the call. [CenterPlaneFinder] answers the same question
                        // analytically, applying the same acceptance rules the discarded
                        // `firstByTypeOrNull(HORIZONTAL_UPWARD_FACING)` filter applied.
                        val centerPlane = if (isVisible) {
                            // Don't look for the center plane if we don't need to know it
                            CenterPlaneFinder.find(
                                frame = frame,
                                // Planes that did not change this frame are still tracked and
                                // still drawn, so they stay eligible: union the updated planes
                                // with the ones we currently visualize.
                                candidates = HashSet(updatedPlaneSet).apply {
                                    addAll(visualizers.keys)
                                }
                            )
                        } else null
                        updatedPlanes.forEach {
                            renderPlane(it, frame = frame, camera = camera, visible = it == centerPlane)
                        }
                        visualizers.forEach { (plane, visualizer) ->
                            if (plane !in updatedPlaneSet) {
                                visualizer.setVisible(isVisible && plane == centerPlane)
                            }
                        }
                    }

                    // Check for not tracking Plane-Trackables and remove them.
                    cleanupOldPlaneVisualizer()
                } catch (e: Exception) {
                    android.util.Log.e("SceneView", "PlaneRendererV2 update error", e)
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
     * Refreshes (or lazily creates) the [PlaneVisualizerV2] for [plane] and forwards the
     * current [frame] + [camera] so the depth path has everything it needs.
     */
    private fun renderPlane(
        plane: Plane,
        frame: Frame?,
        camera: com.google.ar.core.Camera?,
        visible: Boolean = true,
    ) {
        // Find the plane visualizer if it already exists.
        // If not, create a new plane visualizer for this plane.
        if (plane.trackingState == TrackingState.TRACKING || plane.subsumedBy == null) {
            val planeVisualizer = visualizers[plane]
                ?: PlaneVisualizerV2(engine, scene, plane).apply {
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
            // Hand the depth + camera context to the visualizer BEFORE updatePlane runs,
            // so the depth-driven rebuild can pull from this frame's depth image. Either
            // argument may be null — the visualizer falls back to the flat polygon then.
            planeVisualizer.setFrame(frame, camera)
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
         * Float3 material parameter — legacy RGB tint kept for binary/API compatibility. PR #3
         * adds [MATERIAL_GRID_TINT] as the canonical colour knob; the shader multiplies both.
         */
        const val MATERIAL_COLOR = "color"

        /**
         * Float PBR metallic, default `0.0` (dielectric floor). PR #4 varies per
         * `plane.type` via [planeMaterialPresetFor].
         */
        const val MATERIAL_METALLIC = "metallic"

        /**
         * Float PBR roughness, default `0.35` (slight gloss). PR #4 varies per
         * `plane.type` via [planeMaterialPresetFor] (floor `0.35` < ceiling `0.65` <
         * wall `0.80`).
         */
        const val MATERIAL_ROUGHNESS = "roughness"

        /** Float dielectric Fresnel reflectance, default `0.5`. */
        const val MATERIAL_REFLECTANCE = "reflectance"

        /** Float3 cool-white tint used by the procedural grid + scan ring (PR #3). */
        const val MATERIAL_GRID_TINT = "gridTint"

        /** Float peak per-pixel opacity of the grid lines (PR #3). */
        const val MATERIAL_GRID_ALPHA = "gridAlpha"

        /** Float base opacity of the lit surface between grid lines (PR #3). */
        const val MATERIAL_SURFACE_ALPHA = "surfaceAlpha"

        /**
         * Float in `[0, 1]` modulating the IBL contribution so the reflection fades in over
         * the first second of the plane's life (PR #3). `PlaneVisualizerV2` overrides this
         * per-instance from elapsed-since-detection.
         */
        const val MATERIAL_REFLECTION_FADE_IN = "reflectionFadeIn"

        /**
         * Float in `[0, 1]` driving the scan-in ring expansion (PR #3). `1.0` means the
         * animation has completed and the ring is no longer drawn.
         */
        const val MATERIAL_SCAN_PROGRESS = "scanProgress"

        /**
         * Float — max scan-ring radius in plane-local metres (≈ furthest polygon vertex
         * from the centroid). Set per-instance by `PlaneVisualizerV2` (PR #3).
         */
        const val MATERIAL_SCAN_PLANE_RADIUS = "scanPlaneRadius"

        /**
         * Used to control the UV Scale for the default texture.
         */
        private const val BASE_UV_SCALE = 8.0f

        // ── PR #3 PBR + grid defaults ───────────────────────────────────────────────
        // Inline constants live here rather than in PlaneVisualizerV2 because the
        // material defaults are a renderer-level concern (the visualizer overrides
        // only the animation uniforms per-instance).
        private const val DEFAULT_METALLIC = 0.0f
        private const val DEFAULT_ROUGHNESS = 0.35f
        private const val DEFAULT_REFLECTANCE = 0.5f
        private val DEFAULT_GRID_TINT = Float3(0.9f, 0.95f, 1.0f)
        private const val DEFAULT_GRID_ALPHA = 0.85f
        private const val DEFAULT_SURFACE_ALPHA = 0.10f
    }
}

/**
 * Per-`plane.type` material preset applied by [io.github.sceneview.ar.PlaneVisualizerV2]
 * on top of `PlaneRendererV2.planeMaterial`'s shared defaults (PR #4 of
 * [#2203](https://github.com/sceneview/sceneview/issues/2203)).
 *
 * Same single `Material`, one [com.google.android.filament.MaterialInstance] per plane,
 * three different `(metallic, roughness, gridTint)` triples — see
 * [planeMaterialPresetFor] for the type → preset mapping and
 * [PlaneRendererV2]'s class KDoc for the rationale.
 *
 * `internal` so [io.github.sceneview.ar.scene.PlaneRendererV2Test] can exercise the
 * mapping without spinning up an Engine.
 *
 * @param metallic PBR metallic in `[0, 1]`. Always `0.0` in PR #4 — every detected plane
 *                 is a dielectric surface (no real-world brushed-metal floors).
 * @param roughness PBR roughness in `[0, 1]`. Invariant `floor < ceiling < wall` so the
 *                  three roles look visibly distinct under the same IBL.
 * @param gridR Float `[0, 1]` red component of `gridTint`.
 * @param gridG Float `[0, 1]` green component of `gridTint`.
 * @param gridB Float `[0, 1]` blue component of `gridTint`.
 */
internal data class PlaneMaterialPreset(
    val metallic: Float,
    val roughness: Float,
    val gridR: Float,
    val gridG: Float,
    val gridB: Float,
)

/**
 * Maps a [com.google.ar.core.Plane.Type] to its [PlaneMaterialPreset] (PR #4 of
 * [#2203](https://github.com/sceneview/sceneview/issues/2203)).
 *
 * | `plane.type`                 | role    | metallic | roughness | gridTint     |
 * |------------------------------|---------|----------|-----------|--------------|
 * | `HORIZONTAL_UPWARD_FACING`   | floor   | `0.00`   | `0.35`    | cool white   |
 * | `HORIZONTAL_DOWNWARD_FACING` | ceiling | `0.00`   | `0.65`    | warm white   |
 * | `VERTICAL`                   | wall    | `0.00`   | `0.80`    | neutral grey |
 *
 * Unknown future ARCore plane types fall back to the floor preset rather than crashing —
 * the renderer must never blank because ARCore added a fourth enum entry.
 *
 * Pure function. `internal` so tests can exercise the mapping without a Filament Engine
 * or a real `Plane` instance.
 */
// `Plane.Type` is exhaustive at this ARCore version (HORIZONTAL_UPWARD_FACING,
// HORIZONTAL_DOWNWARD_FACING, VERTICAL). The `else` arm is deliberately kept as
// future-proofing for a hypothetical fourth value ARCore may add — a render-thread
// crash on an unknown enum value is strictly worse than a slightly-wrong shading.
// Same defensive stance as `PlaneVisualizerV2.rebuildDepthMesh()`'s flat-fallback
// on Throwable.
@Suppress("REDUNDANT_ELSE_IN_WHEN")
internal fun planeMaterialPresetFor(type: com.google.ar.core.Plane.Type): PlaneMaterialPreset =
    when (type) {
        com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING -> FLOOR_PRESET
        com.google.ar.core.Plane.Type.HORIZONTAL_DOWNWARD_FACING -> CEILING_PRESET
        com.google.ar.core.Plane.Type.VERTICAL -> WALL_PRESET
        else -> FLOOR_PRESET
    }

// Cool white — a freshly-mopped floor reading slightly cool under the typical room IBL.
// `metallic = 0.0` + `roughness = 0.35` gives a gentle gloss so reflections of ceiling
// lights register without turning the floor into a mirror.
private val FLOOR_PRESET = PlaneMaterialPreset(
    metallic = 0.0f,
    roughness = 0.35f,
    gridR = 0.85f, gridG = 0.92f, gridB = 1.0f,
)

// Warm white — drywall ceilings read warm under most indoor lighting (incandescent
// bounce dominates). `roughness = 0.65` keeps the ceiling matt-ish; you should see the
// IBL but not your own reflection in it.
private val CEILING_PRESET = PlaneMaterialPreset(
    metallic = 0.0f,
    roughness = 0.65f,
    gridR = 1.0f, gridG = 0.96f, gridB = 0.88f,
)

// Neutral grey — walls without a known finish (paint / wallpaper / panel) sit in the
// middle. `roughness = 0.80` so the wall absorbs the IBL almost fully — matches how a
// real matte wall looks in the camera passthrough.
private val WALL_PRESET = PlaneMaterialPreset(
    metallic = 0.0f,
    roughness = 0.80f,
    gridR = 0.92f, gridG = 0.92f, gridB = 0.92f,
)
