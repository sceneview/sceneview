package io.github.sceneview.node

import android.graphics.Path
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import io.github.sceneview.geometries.Shape
import io.github.sceneview.geometries.UvScale
import io.github.sceneview.math.Color
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position2

/**
 * A node that renders a procedural 2D shape extruded/triangulated into 3D geometry.
 *
 * The shape can be defined by a polygon path (list of 2D points or an Android [android.graphics.Path]),
 * with optional holes and Delaunay triangulation points. The resulting flat mesh faces the given
 * [normal] direction and supports UV scaling via [uvScale].
 *
 * Use this for custom 2D outlines, floor plans, or any arbitrary planar shape.
 *
 * @see io.github.sceneview.geometries.Shape
 * @see GeometryNode
 */
open class ShapeNode private constructor(
    engine: Engine,
    override val geometry: Shape,
    materialInstances: List<MaterialInstance?>,
    primitivesOffsets: List<IntRange>,
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
        geometry: Shape,
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
        geometry: Shape,
        materialInstances: List<MaterialInstance?>,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine = engine,
        geometry = geometry,
        materialInstances = materialInstances,
        primitivesOffsets = geometry.primitivesOffsets,
        builderApply = builderApply
    )

    constructor(
        engine: Engine,
        polygonPoints: List<Position2> = listOf(),
        polygonHoles: List<Int> = listOf(),
        delaunayPoints: List<Position2> = listOf(),
        normal: Direction = Shape.DEFAULT_NORMAL,
        uvScale: UvScale = UvScale(1.0f),
        color: Color? = null,
        materialInstances: List<MaterialInstance?>,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine = engine,
        geometry = Shape.Builder()
            .polygonPath(points = polygonPoints, holes = polygonHoles)
            .delaunayPoints(delaunayPoints)
            .normal(normal)
            .uvScale(uvScale)
            .color(color)
            .build(engine),
        materialInstances = materialInstances,
        builderApply = builderApply
    )

    constructor(
        engine: Engine,
        polygonPath: Path,
        stepSize: Float = 1.0f,
        polygonHoles: List<Int> = listOf(),
        delaunayPoints: List<Position2> = listOf(),
        normal: Direction = Shape.DEFAULT_NORMAL,
        uvScale: UvScale = UvScale(1.0f),
        color: Color? = null,
        materialInstances: List<MaterialInstance?>,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine = engine,
        geometry = Shape.Builder()
            .polygonPath(path = polygonPath, stepSize = stepSize, holes = polygonHoles)
            .delaunayPoints(delaunayPoints)
            .normal(normal)
            .uvScale(uvScale)
            .color(color)
            .build(engine),
        materialInstances = materialInstances,
        builderApply = builderApply
    )

    constructor(
        engine: Engine,
        polygonPath: List<Position2> = listOf(),
        polygonHoles: List<Int> = listOf(),
        delaunayPoints: List<Position2> = listOf(),
        normal: Direction = Shape.DEFAULT_NORMAL,
        uvScale: UvScale = UvScale(1.0f),
        color: Color? = null,
        materialInstance: MaterialInstance? = null,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine = engine,
        geometry = Shape.Builder()
            .polygonPath(polygonPath, polygonHoles)
            .delaunayPoints(delaunayPoints)
            .normal(normal)
            .uvScale(uvScale)
            .color(color)
            .build(engine),
        materialInstance = materialInstance,
        builderApply = builderApply
    )

    fun updateGeometry(
        polygonPath: List<Position2> = geometry.polygonPath,
        polygonHoles: List<Int> = geometry.polygonHoles,
        delaunayPoints: List<Position2> = geometry.delaunayPoints,
        normal: Direction = geometry.normal,
        uvScale: UvScale = geometry.uvScale,
        color: Color? = geometry.color
    ) {
        geometry.update(engine, polygonPath, polygonHoles, delaunayPoints, normal, uvScale, color)
    }
}