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
        engine = engine,
        geometry = Tube.Builder()
            .points(points)
            .radius(radius)
            .radialSegments(radialSegments)
            .closed(closed)
            .caps(caps)
            .build(engine),
        materialInstance = materialInstance,
        builderApply = builderApply
    )

    /**
     * Re-sweeps the tube and re-uploads it. Cheap enough to call every frame while the point
     * count is unchanged — see [Tube.update] for what is and is not reallocated.
     */
    fun updateGeometry(
        points: List<Position> = geometry.points,
        radius: Float = geometry.radius,
        radialSegments: Int = geometry.radialSegments,
        closed: Boolean = geometry.closed,
        caps: Boolean = geometry.caps
    ) = setGeometry(geometry.update(engine, points, radius, radialSegments, closed, caps))
}
