package io.github.sceneview.node

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import io.github.sceneview.geometries.Line
import io.github.sceneview.math.Position

/**
 * A node that renders a single line segment between two 3D points.
 *
 * Uses Filament's [RenderableManager.PrimitiveType.LINES] primitive type so no triangle mesh is
 * needed — just two vertices and two indices.
 *
 * **This draws a one-device-pixel hairline.** GL/Vulkan mobile backends expose no line width, so
 * at 420 dpi the segment is ~0.4 dp wide and effectively invisible (#3397). Use it for debug
 * overlays and gizmos; for a line a user has to see, sweep the same points with
 * [TubeNode][io.github.sceneview.node.TubeNode], which has a radius in meters.
 *
 * ```kotlin
 * SceneView {
 *     val material = remember(materialLoader) {
 *         materialLoader.createColorInstance(Color.Red)
 *     }
 *     LineNode(
 *         start = Position(0f, 0f, 0f),
 *         end   = Position(1f, 0f, 0f),
 *         materialInstance = material
 *     )
 * }
 * ```
 */
open class LineNode private constructor(
    engine: Engine,
    override val geometry: Line,
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
        geometry: Line,
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
        start: Position = Line.DEFAULT_START,
        end: Position = Line.DEFAULT_END,
        materialInstance: MaterialInstance? = null,
        builderApply: RenderableManager.Builder.() -> Unit = {}
    ) : this(
        engine = engine,
        geometry = Line.Builder()
            .start(start)
            .end(end)
            .build(engine),
        materialInstance = materialInstance,
        builderApply = builderApply
    )

    /** Updates the start and/or end point, re-uploading the vertex buffer to the GPU. */
    fun updateGeometry(
        start: Position = geometry.start,
        end: Position = geometry.end
    ) = setGeometry(geometry.update(engine, start, end))
}
