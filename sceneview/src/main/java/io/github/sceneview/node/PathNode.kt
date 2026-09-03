package io.github.sceneview.node

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import io.github.sceneview.geometries.Path
import io.github.sceneview.math.Position

/**
 * A node that renders a polyline through an ordered list of 3D points.
 *
 * Internally builds a [Path] geometry with [RenderableManager.PrimitiveType.LINES] so that each
 * consecutive pair of points is connected by a line segment.  When [closed] is `true` the last
 * point is also connected back to the first.
 *
 * **This draws one-device-pixel hairlines.** GL/Vulkan mobile backends expose no line width, so
 * at 420 dpi the polyline is ~0.4 dp wide and effectively invisible (#3397). Use it for debug
 * overlays and gizmos; for a polyline a user has to see, sweep the same points with
 * [TubeNode][io.github.sceneview.node.TubeNode], which has a radius in meters.
 *
 * ```kotlin
 * SceneView {
 *     val material = remember(materialLoader) {
 *         materialLoader.createColorInstance(Color.Green)
 *     }
 *     PathNode(
 *         points = spiralPoints,
 *         materialInstance = material
 *     )
 * }
 * ```
 */
open class PathNode private constructor(
    engine: Engine,
    override val geometry: Path,
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
        geometry: Path,
        materialInstance: MaterialInstance? = null,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine = engine,
        geometry = geometry,
        materialInstances = listOf(materialInstance),
        primitivesOffsets = listOf(0..geometry.primitivesOffsets.last().last),
        builderApply = builderApply
    )

    constructor(
        engine: Engine,
        points: List<Position> = Path.DEFAULT_POINTS,
        closed: Boolean = false,
        materialInstance: MaterialInstance? = null,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine = engine,
        geometry = Path.Builder()
            .points(points)
            .closed(closed)
            .build(engine),
        materialInstance = materialInstance,
        builderApply = builderApply
    )

    /** Updates the point list and/or closed flag, re-uploading geometry to the GPU. */
    fun updateGeometry(
        points: List<Position> = geometry.points,
        closed: Boolean = geometry.closed
    ) = setGeometry(geometry.update(engine, points, closed))
}
