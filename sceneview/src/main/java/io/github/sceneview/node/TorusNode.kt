package io.github.sceneview.node

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import io.github.sceneview.geometries.Torus
import io.github.sceneview.math.Position

/**
 * A node that renders a procedural torus geometry.
 *
 * The torus is centred at [center] with the given [majorRadius] (distance from centre to tube
 * centre) and [minorRadius] (tube thickness). Subdivisions are controlled by [majorSegments]
 * and [minorSegments].
 *
 * Use the composable `SceneScope.TorusNode(...)` for declarative usage inside a `SceneView { }` block,
 * or instantiate this class directly for imperative code.
 *
 * @see io.github.sceneview.geometries.Torus
 * @see GeometryNode
 */
open class TorusNode private constructor(
    engine: Engine,
    override val geometry: Torus,
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
        geometry: Torus,
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
        majorRadius: Float = Torus.DEFAULT_MAJOR_RADIUS,
        minorRadius: Float = Torus.DEFAULT_MINOR_RADIUS,
        center: Position = Torus.DEFAULT_CENTER,
        majorSegments: Int = Torus.DEFAULT_MAJOR_SEGMENTS,
        minorSegments: Int = Torus.DEFAULT_MINOR_SEGMENTS,
        materialInstance: MaterialInstance? = null,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine = engine,
        geometry = Torus.Builder()
            .majorRadius(majorRadius)
            .minorRadius(minorRadius)
            .center(center)
            .majorSegments(majorSegments)
            .minorSegments(minorSegments)
            .build(engine),
        materialInstance = materialInstance,
        builderApply = builderApply
    )

    fun updateGeometry(
        majorRadius: Float = geometry.majorRadius,
        minorRadius: Float = geometry.minorRadius,
        center: Position = geometry.center,
        majorSegments: Int = geometry.majorSegments,
        minorSegments: Int = geometry.minorSegments
    ) {
        geometry.update(engine, majorRadius, minorRadius, center, majorSegments, minorSegments)
    }
}
