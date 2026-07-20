package io.github.sceneview.node

import com.google.android.filament.Engine
import com.google.android.filament.Material
import dev.romainguy.kotlin.math.length
import io.github.sceneview.geometries.Plane
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Size

/**
 * The kind of real-world surface a [ContactShadowNode] grounds an object against (#2740).
 *
 * A contact shadow is not one shape: a lamp on a table casts a tight, crisp patch, while a TV
 * hanging on a wall reads as a soft haze pushed *below* the panel, because indoor light comes
 * from above and grazes the wall. Each context carries the gradient parameters that sell its
 * situation — the procedural equivalent of the per-context shadow textures Amazon "AR View"
 * bakes into its APK (`furnitureSpotlightTexture`, `tvWallSpotlightTexture`,
 * `tableTopSpotlightTexture`; see the teardown in #2740).
 *
 * All values are in the quad's normalised UV space, where `0.5` reaches the quad's edge.
 *
 * @property intensity Peak opacity at the darkest point of the pool, in `0..1`.
 * @property centerU   Horizontal offset of the pool centre, in `-0.5..0.5`.
 * @property centerV   Vertical offset of the pool centre, in `-0.5..0.5`. Negative pushes the
 *                     pool *down* the quad (see [ContactShadowNode] for the UV axis convention).
 * @property radiusU   Half-width of the fully-dark ellipse.
 * @property radiusV   Half-height of the fully-dark ellipse.
 * @property softness  Width of the fade beyond the ellipse. Small = a hard contact patch,
 *                     large = a diffuse ambient-occlusion haze.
 */
enum class ContactShadowContext(
    val intensity: Float,
    val centerU: Float,
    val centerV: Float,
    val radiusU: Float,
    val radiusV: Float,
    val softness: Float
) {
    /**
     * An object resting on the floor. Ceiling light puts the shadow directly underneath, so the
     * pool is centred and isotropic, and fairly dense — the object occludes most of the light
     * reaching the patch it covers.
     */
    Floor(
        intensity = 0.55f,
        centerU = 0.0f,
        centerV = 0.0f,
        radiusU = 0.30f,
        radiusV = 0.30f,
        softness = 0.55f
    ),

    /**
     * An object mounted flat against a wall (TV, framed art, mirror). Ceiling light grazes the
     * wall rather than facing it, so the shadow is *weaker*, *wider than it is tall*, and sits
     * **below** the object rather than behind its centre — hence the negative [centerV]. This is
     * the case a real shadow map cannot serve: a light coming from above is nearly parallel to
     * the wall, so it casts almost nothing onto it.
     */
    Wall(
        intensity = 0.38f,
        centerU = 0.0f,
        centerV = -0.10f,
        radiusU = 0.34f,
        radiusV = 0.20f,
        softness = 0.85f
    ),

    /**
     * A small object on a table or shelf. Closer to its surface and usually better lit from
     * several directions, so the patch is tighter and crisper than the [Floor] case.
     */
    TableTop(
        intensity = 0.60f,
        centerU = 0.0f,
        centerV = 0.0f,
        radiusU = 0.26f,
        radiusV = 0.26f,
        softness = 0.35f
    );
}

/**
 * A procedural contact shadow — the soft dark pool that makes an object read as *touching* a
 * surface instead of floating above it (#2740 sub-task C).
 *
 * Unlike [io.github.sceneview.ar.node.ShadowReceiverPlaneNode], this node does not depend on
 * Filament's shadow map. It renders its own elliptical gradient, so it works at any light angle,
 * on any surface orientation, for a fixed cost. That independence is the point: indoors, ARCore's
 * `ENVIRONMENTAL_HDR` estimate resolves to a light coming from the ceiling, which is nearly
 * parallel to a wall — a TV mounted flat against that wall casts essentially nothing onto it and
 * reads as floating. A real shadow map serves the floor case and fails the wall case; a
 * procedural pool serves both.
 *
 * Pick the [ContactShadowContext] that matches the surface and the node configures its gradient
 * accordingly.
 *
 * ### UV axis convention
 *
 * The gradient is positioned in the quad's UV space, whose orientation follows from how
 * [Plane] builds its vertices — the geometry is **not** rotated to match its `normal` parameter:
 *
 * | Quad built as | `size` | Plane | `u` grows with | `v` grows with |
 * |---|---|---|---|---|
 * | Floor / tabletop | `Size(x, 0f, z)` | XZ | local +X | local **−Z** |
 * | Wall | `Size(x, y, 0f)` | XY | local +X | local +Y |
 *
 * So on a wall quad a negative `centerV` moves the pool down, which is what
 * [ContactShadowContext.Wall] wants. On a floor quad `v` runs against local Z — irrelevant for
 * the centred floor presets, but worth knowing before offsetting one by hand.
 *
 * Declarative usage (inside a `SceneView { }` or `ARSceneView { }` content block):
 * ```kotlin
 * ContactShadow(
 *     size = Size(1.6f, 1.0f, 0f),               // XY quad → wall
 *     context = ContactShadowContext.Wall,
 *     normal = Direction(z = 1f),
 * )
 * ```
 *
 * @param engine         The Filament [Engine] shared with the parent scene.
 * @param materialLoader [MaterialLoader] used to load `contact_shadow.filamat`.
 * @param size           Quad size in metres. Make it generously larger than the object —
 *                       the gradient fades out well before the edge.
 * @param context        Which surface this shadow grounds against; sets the gradient shape.
 * @param normal         Facing direction of the quad, which also drives the surface lift.
 *                       Must match how [size] was built (`Direction(y = 1f)` for an XZ quad,
 *                       `Direction(z = 1f)` for an XY quad).
 * @param intensity      Overrides the context's peak opacity. Defaults to the context value.
 *
 * @see ContactShadowContext
 * @see io.github.sceneview.SceneScope.ContactShadow
 */
open class ContactShadowNode private constructor(
    engine: Engine,
    private val materialLoader: MaterialLoader,
    private val material: Material,
    size: Size,
    normal: Direction,
    context: ContactShadowContext,
    intensity: Float
) : PlaneNode(
    engine = engine,
    size = size,
    normal = normal,
    materialInstance = material.defaultInstance,
    builderApply = {
        // A contact shadow is a decal: it neither casts nor receives. Skipping the shadow path
        // entirely also sidesteps the degenerate-AABB crash a flat receiver hits (#2620) — that
        // failure lives in the cascaded-shadow-map code this node never enters.
        castShadows(false)
        receiveShadows(false)
        // A flat quad is never worth frustum-culling, and culling one feeds a zero-thickness
        // volume into Filament's culling math — the same reasoning as ShadowReceiverPlaneNode.
        culling(false)
    }
) {
    constructor(
        engine: Engine,
        materialLoader: MaterialLoader,
        size: Size = DEFAULT_SIZE,
        context: ContactShadowContext = ContactShadowContext.Floor,
        normal: Direction = Direction(y = 1.0f),
        intensity: Float = context.intensity
    ) : this(
        engine = engine,
        materialLoader = materialLoader,
        material = materialLoader.createMaterial(MATERIAL_FILE_LOCATION),
        size = size,
        normal = normal,
        context = context,
        intensity = intensity
    )

    /** Peak opacity of the pool, in `0..1`. Updating it takes effect on the next frame. */
    var intensity: Float = intensity.coerceIn(0.0f, 1.0f)
        set(value) {
            val coerced = value.coerceIn(0.0f, 1.0f)
            if (field != coerced) {
                field = coerced
                materialInstances.firstOrNull()
                    ?.setParameter(MATERIAL_INTENSITY, coerced)
            }
        }

    /** The surface context driving the gradient shape. Changing it re-applies every parameter. */
    var context: ContactShadowContext = context
        set(value) {
            if (field != value) {
                field = value
                applyContext(value)
            }
        }

    init {
        applyContext(context)
        // An invisible-by-design decal must never win touch resolution: SceneView routes gestures
        // to the first touchable hit, so a touchable shadow silently swallows every tap that
        // starts on the surface it covers — the #2630 regression, avoided here by construction.
        isTouchable = false
    }

    private fun applyContext(context: ContactShadowContext) {
        val instance = materialInstances.firstOrNull() ?: return
        instance.setParameter(MATERIAL_INTENSITY, intensity.coerceIn(0.0f, 1.0f))
        instance.setParameter(MATERIAL_CENTER, context.centerU, context.centerV)
        instance.setParameter(MATERIAL_RADIUS, context.radiusU, context.radiusV)
        instance.setParameter(MATERIAL_SOFTNESS, context.softness)
        val offset = surfaceOffsetFor(geometry.normal)
        instance.setParameter(MATERIAL_SURFACE_OFFSET, offset.x, offset.y, offset.z)
    }

    override fun destroy() {
        // Children and the renderable first — never destroy a Material while an instance of it is
        // still bound to a live renderable.
        super.destroy()
        materialLoader.destroyMaterial(material)
    }

    companion object {
        /** Asset location of the compiled contact-shadow material. */
        const val MATERIAL_FILE_LOCATION = "materials/contact_shadow.filamat"

        /** Name of the material's peak-opacity parameter. */
        const val MATERIAL_INTENSITY = "intensity"

        /** Name of the material's gradient-centre parameter (`float2`, UV space). */
        const val MATERIAL_CENTER = "center"

        /** Name of the material's ellipse half-axes parameter (`float2`, UV space). */
        const val MATERIAL_RADIUS = "radius"

        /** Name of the material's fade-width parameter. */
        const val MATERIAL_SOFTNESS = "softness"

        /** Name of the material's model-space lift parameter (`float3`). */
        const val MATERIAL_SURFACE_OFFSET = "surfaceOffset"

        /** Default quad size — 1 m², comfortably larger than a typical placed object. */
        val DEFAULT_SIZE = Size(x = 1.0f, y = 0.0f, z = 1.0f)

        /**
         * How far (metres) the quad is lifted off the surface it sits on, to avoid z-fighting with
         * the real floor/wall or with a plane visualiser drawn at the same pose. Matches
         * `shadow_receiver.mat`'s proven 5 mm.
         */
        const val SURFACE_OFFSET_METERS = 0.005f

        /**
         * The model-space lift vector for a quad facing [normal] — `normalize(normal) * distance`.
         *
         * This is a vector rather than a scalar because [Plane] does not rotate its geometry to
         * match its `normal`: a floor quad is built in the XZ plane (normal +Y) while a wall quad
         * is built in the XY plane (normal +Z). `shadow_receiver.mat`'s hardcoded `pos.y += 0.005`
         * is therefore correct only for the floor case — on a wall quad it slides the mesh up its
         * own face instead of off the wall, and z-fights. Computing the offset from the actual
         * normal makes one material serve every orientation.
         *
         * A zero-length [normal] yields a zero offset (no lift) rather than `NaN`, so a
         * mis-configured quad degrades to z-fighting instead of vanishing from the scene.
         */
        fun surfaceOffsetFor(
            normal: Direction,
            distance: Float = SURFACE_OFFSET_METERS
        ): Direction {
            val len = length(normal)
            if (len <= 0.0f || !len.isFinite()) return Direction(0.0f, 0.0f, 0.0f)
            val scale = distance / len
            return Direction(normal.x * scale, normal.y * scale, normal.z * scale)
        }
    }
}
