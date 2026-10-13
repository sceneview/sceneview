package io.github.sceneview.node

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import io.github.sceneview.geometries.Cone
import io.github.sceneview.math.Position

/**
 * A node that renders a procedural cone geometry.
 *
 * The cone is centred at [center] with the given [radius] and [height], pointing upward along the
 * Y-axis. Subdivisions are controlled by [sideCount].
 *
 * Use the composable `SceneScope.ConeNode(...)` for declarative usage inside a `SceneView { }` block,
 * or instantiate this class directly for imperative code.
 *
 * @see io.github.sceneview.geometries.Cone
 * @see GeometryNode
 */
open class ConeNode private constructor(
    engine: Engine,
    override val geometry: Cone,
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
        geometry: Cone,
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
        radius: Float = Cone.DEFAULT_RADIUS,
        height: Float = Cone.DEFAULT_HEIGHT,
        center: Position = Cone.DEFAULT_CENTER,
        sideCount: Int = Cone.DEFAULT_SIDE_COUNT,
        materialInstance: MaterialInstance? = null,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine = engine,
        geometry = Cone.Builder()
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
