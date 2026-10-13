package io.github.sceneview.node

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import io.github.sceneview.geometries.Cylinder
import io.github.sceneview.math.Position

/**
 * A node that renders a procedural cylinder geometry.
 *
 * The cylinder is centred at [center] with the given [radius], [height], and [sideCount]
 * subdivisions. Vertices include normals and UV coordinates.
 *
 * Use the composable `SceneScope.CylinderNode(...)` for declarative usage inside a `SceneView { }` block,
 * or instantiate this class directly for imperative code.
 *
 * @see io.github.sceneview.geometries.Cylinder
 * @see GeometryNode
 */
open class CylinderNode private constructor(
    engine: Engine,
    override val geometry: Cylinder,
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
        geometry: Cylinder,
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
        radius: Float = Cylinder.DEFAULT_RADIUS,
        height: Float = Cylinder.DEFAULT_HEIGHT,
        center: Position = Cylinder.DEFAULT_CENTER,
        sideCount: Int = Cylinder.DEFAULT_SIDE_COUNT,
        materialInstances: List<MaterialInstance?>,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine = engine,
        geometry = Cylinder.Builder()
            .radius(radius)
            .height(height)
            .center(center)
            .sideCount(sideCount)
            .build(engine),
        materialInstances = materialInstances,
        builderApply = builderApply
    )

    constructor(
        engine: Engine,
        radius: Float = Cylinder.DEFAULT_RADIUS,
        height: Float = Cylinder.DEFAULT_HEIGHT,
        center: Position = Cylinder.DEFAULT_CENTER,
        sideCount: Int = Cylinder.DEFAULT_SIDE_COUNT,
        materialInstance: MaterialInstance? = null,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine = engine,
        geometry = Cylinder.Builder()
            .radius(radius)
            .height(height)
            .center(center)
            .sideCount(sideCount)
            .build(engine),
        materialInstance = materialInstance,
        builderApply = builderApply
    )

    fun updateGeometry(
        radius: Float = geometry.radius,
        height: Float = geometry.height,
        center: Position = geometry.center,
        sideCount: Int = geometry.sideCount
    ) {
        geometry.update(engine, radius, height, center, sideCount)
    }
}