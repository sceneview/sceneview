package io.github.sceneview.ar.arcore

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-contract test for the fallback ordering inside `ARSession.configure`.
 *
 * `ARSession.configure` exists to keep an unsupported combination from ever reaching ARCore's
 * `nativeConfigure`. Four fallbacks do that work — depth mode, flash mode, front-camera light
 * estimation, scene semantics — and every one of them is worthless unless it runs **before**
 * `super.configure(config)`. A fallback applied afterwards can only ever fix the *next* call,
 * which never happens when the current one throws and takes session creation down with it.
 *
 * That is not hypothetical. Measured on a Pixel 4a (2026-08-14): `super.configure(config)` was
 * the first statement of the method, so a front-camera Augmented Faces session died with
 * `UnsupportedConfigurationException` from ARCore's `lighting_estimation_hdr.cc` three lines
 * above the front-camera light-estimation guard written to prevent exactly that, and the
 * `ar-face` demo rendered a black viewport behind "The front camera could not be started on
 * this device".
 *
 * The ordering is invisible at the type level and a one-line move reverts it, so it is pinned
 * at the source level — the same technique [FrameSemanticsTest] uses for the JNI-bound
 * accessors that cannot be mocked under a pure-JVM test.
 */
class ArSessionConfigureOrderingTest {

    private val configureBody: String by lazy {
        val source = File("src/main/java/io/github/sceneview/ar/arcore/ArSession.kt")
            .readText()
        val start = source.indexOf("override fun configure(config: Config)")
        if (start < 0) {
            throw AssertionError("Could not find `override fun configure(config: Config)`")
        }
        // The method ends at the first line that closes it at method indentation (4 spaces).
        val end = source.indexOf("\n    }\n", start).let {
            if (it < 0) source.length else it
        }
        // Comments are stripped, deliberately. The method opens on a comment block that names
        // `super.configure(config)` to explain why nothing may precede it — matching that
        // sentence as if it were the call would make this test grade the prose, not the code.
        source.substring(start, end)
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }

    private fun indexOfGuard(needle: String): Int =
        configureBody.indexOf(needle).also {
            assertTrue(
                "`ARSession.configure` must still contain the guard matching `$needle` — if the " +
                    "guard was renamed or moved, update this test rather than deleting it. Its " +
                    "position relative to `super.configure` is the whole contract.",
                it >= 0
            )
        }

    @Test
    fun `every fallback runs before super_configure hands the config to ARCore`() {
        val superCall = configureBody.indexOf("super.configure(config)")
        assertTrue(
            "`ARSession.configure` must call `super.configure(config)` exactly once — it is the " +
                "point of no return where the config reaches ARCore.",
            superCall >= 0
        )
        assertTrue(
            "`super.configure(config)` must appear exactly once in `ARSession.configure`.",
            configureBody.indexOf("super.configure(config)", superCall + 1) < 0
        )

        val guards = mapOf(
            "depth mode" to "config.depthMode = Config.DepthMode.DISABLED",
            "flash mode" to "config.flashMode = resolveFlashMode(",
            "front-camera light estimation" to
                "config.lightEstimationMode = Config.LightEstimationMode.DISABLED",
            "scene semantics" to "config.semanticMode = resolveSemanticMode(",
        )

        guards.forEach { (name, needle) ->
            val at = indexOfGuard(needle)
            assertTrue(
                "The $name fallback must run BEFORE `super.configure(config)`. Applied after, " +
                    "it can only fix the next call — and there is no next call when ARCore " +
                    "throws on this one and takes session creation down with it.",
                at < superCall
            )
        }
    }
}
