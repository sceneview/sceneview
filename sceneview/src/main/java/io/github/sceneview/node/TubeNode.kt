package io.github.sceneview.node

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import io.github.sceneview.geometries.Tube
import io.github.sceneview.math.Position

/**
 * A node that renders a polyline as a **tube with a real width** — the visible alternative to
 * [LineNode] / [PathNode].
 *
 * `LineNode` and `PathNode` use Filament's `PrimitiveType.LINES`, rasterised at one device pixel
 * with no width control: at phone density that hairline is effectively invisible (#3397). A
 * `TubeNode` sweeps a circular cross-section of [radius] metres along the same points, so the
 * stroke is ordinary lit geometry — visible, depth-sorted and anti-aliased like everything else.
 *
 * Use the composable `SceneScope.TubeNode(...)` for declarative usage inside a `SceneView { }`
 * block, or instantiate this class directly for imperative code.
 *
 * ```kotlin
 * val route = TubeNode(
 *     engine = engine,
 *     points = catmullRomSpline(controlPoints, segments = 24),
 *     radius = 0.015f,
 *     closed = true,
 *     materialInstance = material
 * )
 * ```
 *
 * @see io.github.sceneview.geometries.Tube
 * @see GeometryNode
 */
open class TubeNode private constructor(
    engine: Engine,
    override val geometry: Tube,
    materialInstances: List<MaterialInstance?>,
    primitivesOffsets: List<IntRange> = geometry.primitivesOffsets,
    builderApply: RenderableManager.Builder.() -> Unit = {}
) : GeometryNode(
    engine = engine,
    geometry = geometry,
    materialInstances = materialInstances,
    primitivesOffsets = primitivesOffsets,
    builderApply = builderApply
) {

    /** Creates a node that renders an existing [geometry]. */
    constructor(
        engine: Engine,
        geometry: Tube,
        materialInstance: MaterialInstance? = null,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine,
        geometry = geometry,
        materialInstances = listOf(materialInstance),
        primitivesOffsets = listOf(0..geometry.primitivesOffsets.last().last),
        builderApply = builderApply
    )

    /**
     * Creates a tube from [points]. [radii] optionally supplies one radius per point, while
     * [capacity] reserves point slots for growth and [capStyle] chooses the open-end shape.
     */
    constructor(
        engine: Engine,
        points: List<Position> = Tube.DEFAULT_POINTS,
        radius: Float = Tube.DEFAULT_RADIUS,
        radialSegments: Int = Tube.DEFAULT_RADIAL_SEGMENTS,
        closed: Boolean = Tube.DEFAULT_CLOSED,
        capStyle: Tube.CapStyle = Tube.DEFAULT_CAP_STYLE,
        radii: List<Float>? = null,
        capacity: Int = 0,
        materialInstance: MaterialInstance? = null,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine = engine,
        geometry = Tube.Builder()
            .points(points)
            .radius(radius)
            .radii(radii)
            .radialSegments(radialSegments)
            .closed(closed)
            .capStyle(capStyle)
            .capacity(capacity)
            .build(engine),
        materialInstance = materialInstance,
        builderApply = builderApply
    )

    /**
     * Binary bridge for code compiled against the flat-cap constructor. It keeps the JVM
     * signature and the original `caps = true` default, so an old call that omitted `caps` still
     * gets flat caps. Hidden so it does not compete with the `capStyle` constructor in source.
     */
    @Deprecated("Use capStyle", level = DeprecationLevel.HIDDEN)
    @Suppress("DEPRECATION")
    constructor(
        engine: Engine,
        points: List<Position> = Tube.DEFAULT_POINTS,
        radius: Float = Tube.DEFAULT_RADIUS,
        radialSegments: Int = Tube.DEFAULT_RADIAL_SEGMENTS,
        closed: Boolean = Tube.DEFAULT_CLOSED,
        caps: Boolean = Tube.DEFAULT_CAPS,
        materialInstance: MaterialInstance? = null,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine, points, radius, radialSegments, closed, Tube.legacyCapStyle(caps),
        null, 0, materialInstance, builderApply,
    )

    /**
     * Source-compatible flat-cap constructor: `true` is flat caps, `false` none. `caps` is
     * nullable only so its JVM signature differs from the hidden binary bridge; `null` means the
     * old default, flat caps.
     */
    @Deprecated(
        "Use capStyle",
        ReplaceWith("TubeNode(engine, points, radius, radialSegments, closed, if (caps) Tube.CapStyle.Flat else Tube.CapStyle.None, materialInstance = materialInstance, builderApply = builderApply)"),
    )
    constructor(
        engine: Engine,
        points: List<Position> = Tube.DEFAULT_POINTS,
        radius: Float = Tube.DEFAULT_RADIUS,
        radialSegments: Int = Tube.DEFAULT_RADIAL_SEGMENTS,
        closed: Boolean = Tube.DEFAULT_CLOSED,
        caps: Boolean?,
        materialInstance: MaterialInstance? = null,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine, points, radius, radialSegments, closed, Tube.legacyCapStyle(caps != false),
        null, 0, materialInstance, builderApply,
    )

    /**
     * Re-sweeps the tube and re-uploads it. Cheap enough to call every frame while the point
     * count stays within [capacity] — see [Tube.update] for what is and is not reallocated.
     * [radii] is one radius per point or `null` for [radius]; [capStyle] is ignored when closed.
     */
    fun updateGeometry(
        points: List<Position> = geometry.points,
        radius: Float = geometry.radius,
        radialSegments: Int = geometry.radialSegments,
        closed: Boolean = geometry.closed,
        capStyle: Tube.CapStyle = geometry.capStyle,
        radii: List<Float>? = geometry.radii,
        capacity: Int = 0,
    ) {
        geometry.update(engine, points, radius, radialSegments, closed, capStyle, radii, capacity)
    }

    /**
     * Binary bridge for code compiled against the flat-cap API. It keeps the JVM name and the
     * original default (`caps` defaults to the current caps), so an old call that omitted `caps`
     * keeps them. Hidden so it does not compete with the `capStyle` overload in source.
     */
    @Deprecated("Use capStyle", level = DeprecationLevel.HIDDEN)
    fun updateGeometry(
        points: List<Position> = geometry.points,
        radius: Float = geometry.radius,
        radialSegments: Int = geometry.radialSegments,
        closed: Boolean = geometry.closed,
        caps: Boolean = geometry.capStyle != Tube.CapStyle.None,
    ) = updateGeometry(
        points, radius, radialSegments, closed,
        Tube.legacyCapStyle(caps, geometry.capStyle), geometry.radii, 0,
    )

    /** Source-compatible flat-cap update: `true` keeps the current caps, or adds flat ones. */
    @Deprecated(
        "Use capStyle",
        ReplaceWith("updateGeometry(points, radius, radialSegments, closed, if (caps) Tube.CapStyle.Flat else Tube.CapStyle.None)"),
    )
    @JvmName("updateGeometryWithCaps")
    fun updateGeometry(
        points: List<Position> = geometry.points,
        radius: Float = geometry.radius,
        radialSegments: Int = geometry.radialSegments,
        closed: Boolean = geometry.closed,
        caps: Boolean,
    ) = updateGeometry(
        points, radius, radialSegments, closed,
        Tube.legacyCapStyle(caps, geometry.capStyle), geometry.radii, 0,
    )
}
