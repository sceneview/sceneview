package io.github.sceneview.ar.node

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Size
import io.github.sceneview.node.PlaneNode as PlaneMeshNode

/**
 * An invisible shadow-catcher surface bound to a detected ARCore [Plane] (#2241 Sprint-1).
 *
 * Port of ARCore Depth Lab's `ShadowReceiverMeshShader` (Apache 2.0, Copyright 2020 Google LLC)
 * to Filament: the node mounts a flat quad on the tracked plane and renders it with the
 * `shadow_receiver` material (`shadingModel: unlit` + `shadowMultiplier: true` — Filament's
 * dedicated AR shadow-catcher feature, the idiomatic equivalent of the Unity original's
 * `Blend Zero SrcColor` multiplicative blending). The surface itself is invisible; it only
 * darkens the camera feed where a virtual object casts a shadow onto it, so placed models read
 * as grounded on the real floor instead of floating.
 *
 * This is distinct from [PlaneNode]'s plane *visualisation* role — no grid, no fill, no
 * decoration; the mesh is the shadow catcher only. It follows the plane's
 * [center pose][Plane.getCenterPose] every frame (via [PlaneNode]) and resizes its quad to the
 * plane's refined [extents][Plane.getExtentX] as ARCore grows the plane.
 *
 * The mesh **receives** shadows and never **casts** them, and — because the material is unlit —
 * it never occludes or lights anything by itself.
 *
 * **Lighting dependency:** shadows only appear if a shadow-casting directional light exists in
 * the scene. `ARSceneView`'s default `ENVIRONMENTAL_HDR` light estimation drives one; the placed
 * `ModelNode` casts by default. If no shadow shows, diagnose the estimated main light's
 * `isShadowCaster` before suspecting this node.
 *
 * Declarative usage (inside an `ARSceneView { }` content block):
 * ```kotlin
 * val planes by rememberDetectedPlanes(session = arSession)
 * planes.forEach { plane ->
 *     ShadowReceiverPlane(plane = plane)
 * }
 * ```
 *
 * @param engine          The Filament [Engine] shared with the parent AR scene.
 * @param materialLoader  [MaterialLoader] used to load the `shadow_receiver.filamat` material.
 * @param plane           The ARCore [Plane] this shadow catcher tracks.
 * @param shadowIntensity How much a fully-shadowed texel darkens the background, in `0..1`
 *                        (0 = shadows invisible, 1 = fully black). Default
 *                        [DEFAULT_SHADOW_INTENSITY] (= 0.6, Depth Lab's
 *                        `_GlobalShadowIntensity` default). Tunable at runtime via
 *                        [ShadowReceiverPlaneNode.shadowIntensity].
 * @param visibleTrackingStates States in which the node (and its shadow mesh) are rendered.
 * @param onTrackingStateChanged Callback when the plane's tracking state changes.
 * @param onUpdated       Callback invoked each frame the plane is updated by ARCore.
 *
 * @see io.github.sceneview.ar.ARSceneScope.ShadowReceiverPlane
 * @see PlaneNode
 */
open class ShadowReceiverPlaneNode(
    engine: Engine,
    private val materialLoader: MaterialLoader,
    plane: Plane,
    shadowIntensity: Float = DEFAULT_SHADOW_INTENSITY,
    visibleTrackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onUpdated: ((Plane) -> Unit)? = null
) : PlaneNode(
    engine = engine,
    plane = plane,
    visibleTrackingStates = visibleTrackingStates,
    onTrackingStateChanged = onTrackingStateChanged,
    onUpdated = onUpdated
) {

    /**
     * The loaded shadow-receiver [com.google.android.filament.Material] — owned by this node and
     * destroyed with it (destroying the Material implicitly destroys its default instance).
     */
    private val material = materialLoader.createMaterial(MATERIAL_FILE_LOCATION)

    /**
     * The material instance applied to the shadow mesh. Exposed for advanced tweaking; prefer
     * the [shadowIntensity] property for the supported parameter.
     */
    val materialInstance = material.defaultInstance.apply {
        setParameter(MATERIAL_SHADOW_INTENSITY, shadowIntensity.coerceIn(0.0f, 1.0f))
    }

    /**
     * How much a fully-shadowed texel darkens the background, in `0..1`.
     *
     * `0.0` = received shadows are invisible, `1.0` = a fully-shadowed texel goes black.
     * Values outside `0..1` are coerced. Updating it takes effect on the next frame.
     */
    var shadowIntensity: Float = shadowIntensity.coerceIn(0.0f, 1.0f)
        set(value) {
            val coerced = value.coerceIn(0.0f, 1.0f)
            if (field != coerced) {
                field = coerced
                materialInstance.setParameter(MATERIAL_SHADOW_INTENSITY, coerced)
            }
        }

    /**
     * The child quad that actually receives the shadows — an XZ plane (up-facing) sized to the
     * tracked plane's extents, `receiveShadows = true` / `castShadows = false`.
     *
     * Exposed for advanced use (e.g. swapping the geometry); its size is owned by this node and
     * follows [Plane.getExtentX] / [Plane.getExtentZ] while the plane is tracking.
     */
    val meshNode = PlaneMeshNode(
        engine = engine,
        size = plane.meshSize(),
        materialInstance = materialInstance,
        builderApply = {
            // Mirror PlaneVisualizer's device-proven flat shadow-receiver recipe
            // (PlaneVisualizer.kt: castShadows(false).receiveShadows(true).culling(false)
            // .boundingBox(non-degenerate)). The original node had only the first two; missing
            // culling(false) + a valid AABB on a flat (zero-Y) quad crashed the FL2+ cascaded
            // shadow path on-device at plane detection (#2620) — the FL1 emulator never hit it.
            castShadows(false)
            receiveShadows(true)
            // A flat quad is never worth frustum-culling; culling it (default on) feeds a
            // zero-thickness volume into the shadow-focus math on real devices.
            culling(false)
        }
    ).also { addChildNode(it) }

    private var lastMeshSize = plane.meshSize()

    // The base PlaneNode's init runs `trackable = plane`, whose setter calls update(trackable) —
    // dispatched to THIS override — BEFORE the subclass properties above (meshNode, lastMeshSize)
    // are initialized. Accessing meshNode there NPE'd the instant a plane was detected on-device
    // (#2620). This flag gates update() until construction finishes; the base already applied the
    // pose by then, and the first real per-frame update runs normally.
    private var constructed = false

    init {
        // Hardening (secondary): a flat plane's geometry-derived AABB has a zero Y half-extent;
        // give the shadow receiver a non-degenerate box that also encloses placed models above the
        // floor, matching PlaneVisualizer's device-proven flat-receiver recipe.
        applyShadowReceiverBounds()
        // An INVISIBLE utility surface must never win touch resolution: the quad has a real
        // collision shape and SceneView routes gestures to the FIRST touchable hit, so a touchable
        // shadow catcher silently swallowed every tap/drag that started on the floor it covers —
        // placed models could not be dragged and floor taps died (#2630).
        isTouchable = false
        meshNode.isTouchable = false
        // Construction is complete — update() may now touch meshNode/lastMeshSize safely.
        constructed = true
    }

    /** Overrides the flat plane's degenerate AABB with a valid one (non-zero Y extent). */
    private fun applyShadowReceiverBounds() {
        val size = plane.meshSize()
        meshNode.axisAlignedBoundingBox = Box(
            0f, SHADOW_AABB_CENTER_Y, 0f,
            size.x / 2f, SHADOW_AABB_HALF_HEIGHT, size.z / 2f
        )
    }

    override fun update(trackable: Plane?) {
        super.update(trackable)

        // The base PlaneNode's `init { trackable = plane }` calls update() once BEFORE this
        // subclass's meshNode/lastMeshSize are initialized — reading meshNode there NPE'd the
        // instant a plane was detected on-device (#2620, the actual 4.21.0 crash). Bail until the
        // constructor has finished; super.update() above has already applied the pose.
        if (!constructed) return

        // Follow ARCore's refined plane extents — but only re-upload the quad's vertex buffer
        // when the extents actually changed, not on every frame update.
        if (plane.trackingState == TrackingState.TRACKING) {
            val meshSize = plane.meshSize()
            if (meshSize != lastMeshSize) {
                lastMeshSize = meshSize
                meshNode.updateGeometry(size = meshSize)
                // updateGeometry re-derives the flat (zero-Y) AABB — restore the valid one.
                applyShadowReceiverBounds()
            }
        }
    }

    override fun destroy() {
        // Children first (super destroys meshNode's renderable entity + geometry), then the
        // material — never destroy a MaterialInstance while its renderable is still registered.
        super.destroy()
        materialLoader.destroyMaterial(material)
    }

    companion object {
        /** Asset location of the compiled shadow-catcher material. */
        const val MATERIAL_FILE_LOCATION = "materials/shadow_receiver.filamat"

        /** Name of the material's shadow-strength parameter. */
        const val MATERIAL_SHADOW_INTENSITY = "shadowIntensity"

        /**
         * Default [shadowIntensity] — 0.6, matching both ARCore Depth Lab's
         * `_GlobalShadowIntensity` default and `plane_renderer_shadow.mat`'s baked 60 % alpha.
         */
        const val DEFAULT_SHADOW_INTENSITY = 0.6f

        /**
         * Quad size (metres) used until ARCore reports real extents (a plane that is not yet —
         * or no longer — tracking can report 0 extents; a zero-area quad catches no shadow).
         */
        const val DEFAULT_EXTENT_METERS = 1.0f

        /**
         * Y half-extent (metres) of the shadow receiver's overridden AABB. The quad is flat, but a
         * shadow **receiver** needs a non-degenerate bounding box or Filament crashes building the
         * shadow map on-device (#2620). 0.6 m (with [SHADOW_AABB_CENTER_Y]) spans roughly
         * floor→1.1 m, enclosing typical placed models above the surface.
         */
        const val SHADOW_AABB_HALF_HEIGHT = 0.6f

        /** Y centre (metres) of the overridden AABB — see [SHADOW_AABB_HALF_HEIGHT]. */
        const val SHADOW_AABB_CENTER_Y = 0.5f

        /** Up-facing XZ quad sized to the plane's bounding extents. */
        private fun Plane.meshSize() = Size(
            x = extentX.takeIf { it > 0.0f } ?: DEFAULT_EXTENT_METERS,
            y = 0.0f,
            z = extentZ.takeIf { it > 0.0f } ?: DEFAULT_EXTENT_METERS
        )
    }
}
