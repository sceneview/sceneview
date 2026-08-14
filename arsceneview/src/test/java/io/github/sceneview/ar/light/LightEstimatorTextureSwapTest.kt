package io.github.sceneview.ar.light

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-contract test for the atomicity of the two cubemap-texture setters in
 * `LightEstimator`.
 *
 * `LightEstimator.destroy()` documents itself as safe to call repeatedly, and
 * `DisposableEffect` semantics require it. Until 2026-08-14 it delivered that with
 * null-safety alone — `runCatching { field?.let { engine.destroyTexture(it) } }` followed by
 * `field = value`. That is idempotent when called twice in sequence and unsafe when called
 * twice at once: it is a read-modify-write, so two threads read the same non-null `Texture`
 * and both hand it to `engine.destroyTexture`. A native double free.
 *
 * Measured on a Pixel 4a (2026-08-14): `SIGABRT` in `scudo::reportHeaderRace` beneath
 * `Java_com_google_android_filament_Engine_nDestroyTexture`, reached through
 * `LightEstimator.destroy` → `setCubeMapTexture`, killing the whole instrumentation process.
 *
 * **Why this file exists, and not just the concurrency suites.** Neither existing suite could
 * fail on this. The androidTest stress needs a device and a real `Engine`, so it never runs in
 * CI. The pure-JVM mirror `LightEstimatorConcurrentDestroyTest` models the setter — and its
 * model already used `getAndSet`, i.e. it modelled an implementation *more correct* than
 * production, which is precisely the condition under which a mirror cannot catch production's
 * bug. It stayed green for months. A source-level assertion is the only one of the three that
 * reads what the setter actually does.
 *
 * The same technique is used by [io.github.sceneview.ar.arcore.ArSessionConfigureOrderingTest]
 * and [io.github.sceneview.ar.arcore.FrameSemanticsTest] for invariants that are invisible at
 * the type level and that a one-line edit reverts.
 */
class LightEstimatorTextureSwapTest {

    private val source: String by lazy {
        File("src/main/java/io/github/sceneview/ar/light/LightEstimator.kt").readText()
    }

    /**
     * The setter body for a given property, comments stripped. The KDoc above these setters
     * quotes the old shape verbatim to explain why it was wrong — matching that quotation as
     * if it were code would make this test grade the prose.
     */
    private fun setterBody(property: String): String {
        val declaration = "private var $property: Texture?"
        val start = source.indexOf(declaration)
        assertTrue(
            "`LightEstimator` must still declare `$declaration`. If the property was renamed, " +
                "update this test rather than deleting it — the atomicity of its swap is the " +
                "contract, not the name.",
            start >= 0
        )
        val end = source.indexOf("\n        }\n", start).let { if (it < 0) source.length else it }
        return source.substring(start, end)
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }

    @Test
    fun `both cubemap setters swap atomically instead of reading then writing`() {
        listOf("cubeMapTexture", "cubeMapTextureSpecular").forEach { property ->
            val body = setterBody(property)

            assertTrue(
                "`$property`'s setter must swap through `getAndSet`. Freeing the old texture " +
                    "and writing the new one as two steps lets two concurrent callers both " +
                    "observe the same non-null texture and both free it — a native double " +
                    "free, which crashes the process rather than throwing.",
                body.contains("getAndSet(value)")
            )
            assertTrue(
                "`$property`'s setter must not fall back to the `field?.let { … }` " +
                    "read-modify-write shape. It reads as null-safe, and null-safety is not " +
                    "atomicity: it makes a repeated *sequential* call cheap and leaves a " +
                    "concurrent one free to double-free.",
                !body.contains("field?.let")
            )
        }
    }
}
