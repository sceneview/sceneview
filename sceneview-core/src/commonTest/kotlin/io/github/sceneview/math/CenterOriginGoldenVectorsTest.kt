package io.github.sceneview.math

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden-vector table for [centerOriginTranslation] (issue #2763).
 *
 * `{center, halfExtent, scale, origin} -> expected translation`. Because this file lives in
 * `sceneview-core`'s `commonTest`, it compiles and runs unmodified against BOTH the `android`
 * (JVM) and `js` (Karma/browser) KMP targets — a single Kotlin source proving Android and Web
 * compute identical `centerOrigin` offsets, closing the "no shared truth" gap called out in
 * #2763.
 *
 * `SceneViewSwift` cannot consume this KMP module (RealityKit has no dependency on
 * `sceneview-core`), so it reimplements the formula natively and duplicates this exact table as
 * literals in `ModelNodeTests.swift` — the same "expected string identical across suites"
 * pattern proven by `RerunWireFormatTest.kt` / `RerunWireFormatTests.swift`. A numeric
 * regression on ANY of the three platforms must fail that platform's suite (mutation-tested
 * manually per #2763's "Done" checklist: flipping the formula's sign turns every vector below
 * red).
 *
 * Android's existing `ModelNodeCenterOriginFormulaTest` (`sceneview` module) pins the first
 * three vectors below (`centered` / `bottom` / `top`) against the same fixture AABB — this table
 * is the superset, adding an off-pivot AABB and a non-uniform scale.
 */
class CenterOriginGoldenVectorsTest {

    private data class Vector(
        val name: String,
        val center: Position,
        val halfExtent: Size,
        val scale: Scale,
        val origin: Position,
        val expected: Position,
    )

    /**
     * `center = (1,2,1)`, `halfExtent = (1,2,1)` is a deliberately **non-centered** AABB
     * (`min = (0,0,0)`, `max = (2,4,2)`) — `center != 0` is the discriminant that catches a
     * formula which forgets to consult the AABB center. `scale = 0.5` mirrors
     * `scaleToUnitCube(2f)` on a `maxExtent = 4` asset.
     */
    private val vectors = listOf(
        Vector(
            name = "centered — origin (0,0,0) pulls the off-pivot AABB center onto the node origin",
            center = Position(1f, 2f, 1f),
            halfExtent = Size(1f, 2f, 1f),
            scale = Scale(0.5f),
            origin = Position(0f, 0f, 0f),
            expected = Position(-0.5f, -1f, -0.5f),
        ),
        Vector(
            name = "bottom-aligned — origin (0,-1,0) sits the model on the node origin",
            center = Position(1f, 2f, 1f),
            halfExtent = Size(1f, 2f, 1f),
            scale = Scale(0.5f),
            origin = Position(0f, -1f, 0f),
            expected = Position(-0.5f, 0f, -0.5f),
        ),
        Vector(
            name = "top-aligned — origin (0,1,0) hangs the model from the node origin",
            center = Position(1f, 2f, 1f),
            halfExtent = Size(1f, 2f, 1f),
            scale = Scale(0.5f),
            origin = Position(0f, 1f, 0f),
            expected = Position(-0.5f, -2f, -0.5f),
        ),
        Vector(
            name = "left | top aligned — origin (-1,1,0)",
            center = Position(1f, 2f, 1f),
            halfExtent = Size(1f, 2f, 1f),
            scale = Scale(0.5f),
            origin = Position(-1f, 1f, 0f),
            expected = Position(0f, -2f, -0.5f),
        ),
        Vector(
            name = "off-center AABB, unit scale, general origin",
            center = Position(-2f, 0.5f, 3f),
            halfExtent = Size(0.5f, 1.5f, 2f),
            scale = Scale(1f),
            origin = Position(1f, -1f, -1f),
            expected = Position(1.5f, 1f, -1f),
        ),
        Vector(
            name = "centered AABB, non-uniform scale, general origin",
            center = Position(0f, 0f, 0f),
            halfExtent = Size(2f, 3f, 4f),
            scale = Scale(2f, 0.5f, 1f),
            origin = Position(1f, 1f, -1f),
            expected = Position(-4f, -1.5f, 4f),
        ),
    )

    @Test
    fun centerOriginTranslationMatchesTheGoldenVectorTable() {
        for (v in vectors) {
            val actual = centerOriginTranslation(v.center, v.halfExtent, v.scale, v.origin)
            assertEquals(v.expected.x, actual.x, 1e-6f, "${v.name} (x)")
            assertEquals(v.expected.y, actual.y, 1e-6f, "${v.name} (y)")
            assertEquals(v.expected.z, actual.z, 1e-6f, "${v.name} (z)")
        }
    }
}
