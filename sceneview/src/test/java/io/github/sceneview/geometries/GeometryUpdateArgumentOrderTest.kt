package io.github.sceneview.geometries

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins that each [Geometry]'s `update()` rebuilds its vertices with the *same* arguments, in the
 * same order, that its `Builder.build()` used.
 *
 * This exists because of a real defect found while fixing #3194. `Cube.getVertices` is declared
 * `(size: Size, center: Position)`; the builder called it correctly, but `Cube.update()` called it
 * as `getVertices(center, size)`. `Size`, `Position` and `Direction` are all `typealias`es of
 * `Float3`, so a transposition is **type-correct** — the compiler cannot see it, and neither can
 * any test that calls `getVertices` directly with its own arguments (which is what
 * [CubeGeometryTest] does; note its header explicitly excludes `update`).
 *
 * The consequence was silent and shape-dependent: `cubeNode.updateGeometry(size = Size(3f))` on a
 * cube centred at the origin built a **zero-sized** box centred at `(3, 3, 3)` — it vanished from
 * the scene and from hit-testing, while every unit test stayed green. A cube built at the right
 * size and never resized was fine, which is why this survived so long.
 *
 * The invariant checked here is deliberately textual rather than behavioural, because the
 * behavioural version needs a Filament `Engine` (JNI, main thread) and so can only run
 * instrumented — see `ResizedNodeColliderTest`, which does cover it but does not run in CI. This
 * guard runs on every push and is what stops the class of bug coming back for the *next* geometry.
 *
 * The rule: within one geometry file, every call to `getVertices(...)` must pass an identical
 * argument list. Both call sites describe the same shape, so any difference between them is a
 * transposition, a dropped parameter, or a stale copy — never a legitimate variation.
 */
class GeometryUpdateArgumentOrderTest {

    private val geometriesDir = File("src/main/java/io/github/sceneview/geometries")

    /**
     * Every geometry that generates its vertices through a `getVertices` companion helper and can
     * be mutated in place afterwards. `Geometry.kt` itself declares no such helper.
     */
    private val geometries = listOf(
        "Capsule", "Cone", "Cube", "Cylinder", "Line", "Path", "Plane", "Shape", "Sphere", "Torus"
    )

    /**
     * Extracts the argument list of each `getVertices(` **call** in [source], ignoring the
     * declaration (`fun getVertices(`) and any occurrence inside a comment. Returns one
     * whitespace-normalised string per call site.
     */
    private fun getVerticesCallArguments(source: String): List<String> =
        source.lineSequence()
            .map { it.substringBefore("//") }
            .filter { it.contains("getVertices(") && !it.contains("fun getVertices(") }
            .map { line ->
                val start = line.indexOf("getVertices(") + "getVertices(".length
                // Walk to the matching close paren so nested calls stay intact.
                var depth = 1
                var end = start
                while (end < line.length && depth > 0) {
                    when (line[end]) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                    if (depth > 0) end++
                }
                line.substring(start, end).replace(Regex("\\s+"), " ").trim()
            }
            .toList()

    @Test
    fun `every geometry rebuilds its vertices with the arguments its builder used`() {
        geometries.forEach { geometry ->
            val source = File(geometriesDir, "$geometry.kt").readText()
            val calls = getVerticesCallArguments(source)

            assertTrue(
                "$geometry.kt must call getVertices from both its builder and its update() — " +
                    "found ${calls.size} call site(s). If this geometry genuinely has only one, " +
                    "remove it from the list above and say why.",
                calls.size >= 2
            )
            calls.forEach { arguments ->
                assertEquals(
                    "$geometry.update() must pass getVertices the same arguments, in the same " +
                        "order, as $geometry.Builder.build(). Size/Position/Direction are all " +
                        "Float3 typealiases, so a transposition compiles cleanly and silently " +
                        "builds the wrong shape — this is exactly the defect found in Cube " +
                        "while fixing #3194.",
                    calls.first(),
                    arguments
                )
            }
        }
    }

    @Test
    fun `Cube update passes size before center`() {
        // The specific regression. Kept as its own case so a failure names the real bug rather
        // than a generic mismatch, and so the declared order is pinned too: matching each other
        // is not enough if both call sites were transposed together.
        val source = File(geometriesDir, "Cube.kt").readText()

        val declaration = source
            .substringAfter("fun getVertices(", missingDelimiterValue = "")
            .substringBefore(")")
        assertTrue(
            "Cube.getVertices must still be declared (size, center) — this test pins the call " +
                "sites against that order",
            declaration.replace(Regex("\\s+"), " ").trim()
                .startsWith("size: Size, center: Position")
        )

        getVerticesCallArguments(source).forEach { arguments ->
            assertEquals(
                "Cube must build its vertices as getVertices(size, center). Reversed, " +
                    "updateGeometry(size = Size(3f)) produces a zero-sized cube at (3, 3, 3) " +
                    "that disappears from rendering and hit-testing (#3194).",
                "size, center",
                arguments
            )
        }
    }
}
