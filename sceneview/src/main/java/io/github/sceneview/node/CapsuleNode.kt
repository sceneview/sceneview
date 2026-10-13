package io.github.sceneview.node

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import io.github.sceneview.geometries.Capsule
import io.github.sceneview.math.Position

/**
 * A node that renders a procedural capsule geometry (cylinder with hemispherical caps).
 *
 * The capsule is centred at [center] with the given [radius] for the hemispheres and [height]
 * for the cylindrical section (total height = height + 2 * radius). Subdivisions are controlled
 * by [capStacks] and [sideSlices].
 *
 * Use the composable `SceneScope.CapsuleNode(...)` for declarative usage inside a `SceneView { }` block,
 * or instantiate this class directly for imperative code.
 *
 * @see io.github.sceneview.geometries.Capsule
 * @see GeometryNode
 */
open class CapsuleNode private constructor(
    engine: Engine,
    override val geometry: Capsule,
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
        geometry: Capsule,
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
        radius: Float = Capsule.DEFAULT_RADIUS,
        height: Float = Capsule.DEFAULT_HEIGHT,
        center: Position = Capsule.DEFAULT_CENTER,
        capStacks: Int = Capsule.DEFAULT_CAP_STACKS,
        sideSlices: Int = Capsule.DEFAULT_SIDE_SLICES,
        materialInstance: MaterialInstance? = null,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine = engine,
        geometry = Capsule.Builder()
            .radius(radius)
            .height(height)
            .center(center)
            .capStacks(capStacks)
            .sideSlices(sideSlices)
            .build(engine),
        materialInstance = materialInstance,
        builderApply = builderApply
    )

    fun updateGeometry(
        radius: Float = geometry.radius,
        height: Float = geometry.height,
        center: Position = geometry.center,
        capStacks: Int = geometry.capStacks,
        sideSlices: Int = geometry.sideSlices
    ) {
        geometry.update(engine, radius, height, center, capStacks, sideSlices)
    }
}
