package io.github.sceneview.ar

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the [#1891](https://github.com/sceneview/sceneview/issues/1891) acceptance criteria:
 * the screen-coordinate [io.github.sceneview.ar.node.HitResultNode] overload (a.k.a. the
 * "reticle node" pattern surfaced by `ARPlacementDemo`) defaults to **plane-only** hits,
 * with a defensive [Float] `minCameraDistance` floor at 30 cm.
 *
 * Pixel 9 device-QA against the May 2026 AR sprint (#1882) surfaced that ARCore returns
 * depth / feature-point hits at every distance — including extremely close to the camera
 * before motion stereo has converged — so the previous wide-open defaults caused a child
 * placement disc / cylinder to render as a fullscreen overlay that blanked the camera feed
 * on session start.
 *
 * What this pins:
 *
 * 1. `HitResultNode` (the [io.github.sceneview.ar.node.HitResultNode] class constructor)
 *    defaults `point = false`, `depthPoint = false`, `instantPlacementPoint = false`.
 * 2. The same class constructor exposes `minCameraDistance: Float? = 0.3f`.
 * 3. The `ARSceneScope.HitResultNode` composable overload mirrors all four defaults.
 * 4. `ReticleNode` ([io.github.sceneview.ar.node.ReticleNode], #1882) is a **thin
 *    wrapper** — it `extends HitResultNode`, mirrors the same plane-only +
 *    `minCameraDistance` defaults, and does NOT re-implement hit-testing (no
 *    `frame.hitTest` call of its own, no `update()` override).
 *
 * Source-introspection ("strings + regex"), same style as
 * [ARCompletenessDefaultsTest]. Cheap, runs in plain JVM, and survives a Kotlin compiler
 * upgrade because we're not reflecting over erased default values.
 */
class ReticleNodeDefaultsTest {

    private val hitResultNodeFile =
        File("src/main/java/io/github/sceneview/ar/node/HitResultNode.kt")
    private val arSceneScopeFile =
        File("src/main/java/io/github/sceneview/ar/ARSceneScope.kt")
    private val reticleNodeFile =
        File("src/main/java/io/github/sceneview/ar/node/ReticleNode.kt")

    private val hitResultNodeSource: String by lazy {
        assertTrue(
            "Expected to find ${hitResultNodeFile.absolutePath} — JVM test must run from " +
                "arsceneview module root.",
            hitResultNodeFile.exists()
        )
        hitResultNodeFile.readText()
    }

    private val arSceneScopeSource: String by lazy {
        assertTrue(
            "Expected to find ${arSceneScopeFile.absolutePath} — JVM test must run from " +
                "arsceneview module root.",
            arSceneScopeFile.exists()
        )
        arSceneScopeFile.readText()
    }

    private val reticleNodeSource: String by lazy {
        assertTrue(
            "Expected to find ${reticleNodeFile.absolutePath} — JVM test must run from " +
                "arsceneview module root.",
            reticleNodeFile.exists()
        )
        reticleNodeFile.readText()
    }

    @Test
    fun `HitResultNode class constructor defaults point to false`() {
        val src = hitResultNodeSource
        val pattern = Regex("""point\s*:\s*Boolean\s*=\s*false""")
        assertTrue(
            "HitResultNode (class) must default `point: Boolean = false` (#1891). " +
                "Did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    @Test
    fun `HitResultNode class constructor defaults depthPoint to false`() {
        val src = hitResultNodeSource
        val pattern = Regex("""depthPoint\s*:\s*Boolean\s*=\s*false""")
        assertTrue(
            "HitResultNode (class) must default `depthPoint: Boolean = false` (#1891). " +
                "Did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    @Test
    fun `HitResultNode class constructor defaults instantPlacementPoint to false`() {
        val src = hitResultNodeSource
        val pattern = Regex("""instantPlacementPoint\s*:\s*Boolean\s*=\s*false""")
        assertTrue(
            "HitResultNode (class) must default `instantPlacementPoint: Boolean = false` (#1891). " +
                "Did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    @Test
    fun `HitResultNode class constructor exposes minCameraDistance Float floor at 30 cm`() {
        val src = hitResultNodeSource
        val pattern = Regex("""minCameraDistance\s*:\s*Float\?\s*=\s*0\.3f""")
        assertTrue(
            "HitResultNode (class) must expose `minCameraDistance: Float? = 0.3f` (#1891). " +
                "Did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    @Test
    fun `HitResultNode class hit-test rejects hits closer than minCameraDistance`() {
        val src = hitResultNodeSource
        // The constructor body's `takeIf { hit -> ... hit.distance >= minCameraDistance }`
        // filter is the runtime enforcement of the floor — pin its presence so a future
        // refactor that removes the floor must update this test, not silently regress.
        val pattern = Regex("""hit\.distance\s*>=\s*minCameraDistance""")
        assertTrue(
            "HitResultNode (class) must drop hits closer than `minCameraDistance` via a " +
                "`hit.distance >= minCameraDistance` guard (#1891). Did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    @Test
    fun `ARSceneScope HitResultNode composable defaults point to false`() {
        val src = arSceneScopeSource
        val pattern = Regex("""point\s*:\s*Boolean\s*=\s*false""")
        assertTrue(
            "ARSceneScope.HitResultNode must default `point: Boolean = false` (#1891). " +
                "Did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    @Test
    fun `ARSceneScope HitResultNode composable defaults depthPoint to false`() {
        val src = arSceneScopeSource
        val pattern = Regex("""depthPoint\s*:\s*Boolean\s*=\s*false""")
        assertTrue(
            "ARSceneScope.HitResultNode must default `depthPoint: Boolean = false` (#1891). " +
                "Did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    @Test
    fun `ARSceneScope HitResultNode composable defaults instantPlacementPoint to false`() {
        val src = arSceneScopeSource
        val pattern = Regex("""instantPlacementPoint\s*:\s*Boolean\s*=\s*false""")
        assertTrue(
            "ARSceneScope.HitResultNode must default `instantPlacementPoint: Boolean = false` " +
                "(#1891). Did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    @Test
    fun `ARSceneScope HitResultNode composable exposes minCameraDistance Float floor at 30 cm`() {
        val src = arSceneScopeSource
        val pattern = Regex("""minCameraDistance\s*:\s*Float\?\s*=\s*0\.3f""")
        assertTrue(
            "ARSceneScope.HitResultNode must expose `minCameraDistance: Float? = 0.3f` (#1891). " +
                "Did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    // ── ReticleNode — thin wrapper over HitResultNode (#1882) ─────────────────────────────────────

    @Test
    fun `ReticleNode extends HitResultNode`() {
        val src = reticleNodeSource
        // The whole point of the #1882 thin-wrapper redesign: ReticleNode is a
        // HitResultNode subclass, not a parallel implementation.
        val pattern = Regex("""class\s+ReticleNode\s*\([^)]*\)\s*:\s*HitResultNode\s*\(""", RegexOption.DOT_MATCHES_ALL)
        assertTrue(
            "ReticleNode must `extend HitResultNode` (thin-wrapper redesign, #1882). " +
                "Did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    @Test
    fun `ReticleNode does not re-implement hit-testing`() {
        val src = reticleNodeSource
        // A thin wrapper delegates the hit test to HitResultNode's View-coordinate
        // constructor. It must NOT call frame.hitTest itself nor override update() —
        // either would be a parallel hit-test implementation.
        assertTrue(
            "ReticleNode must NOT call `frame.hitTest(` — the hit test is delegated to " +
                "HitResultNode (#1882 thin wrapper).",
            !src.contains("frame.hitTest(")
        )
        assertTrue(
            "ReticleNode must NOT override `update(` — delegate the render-path update to " +
                "HitResultNode (#1882 thin wrapper).",
            !Regex("""override\s+fun\s+update\s*\(""").containsMatchIn(src)
        )
    }

    @Test
    fun `ReticleNode defaults point to false`() {
        val src = reticleNodeSource
        val pattern = Regex("""point\s*:\s*Boolean\s*=\s*false""")
        assertTrue(
            "ReticleNode must default `point: Boolean = false` (#1891 plane-only). " +
                "Did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    @Test
    fun `ReticleNode defaults depthPoint to false`() {
        val src = reticleNodeSource
        val pattern = Regex("""depthPoint\s*:\s*Boolean\s*=\s*false""")
        assertTrue(
            "ReticleNode must default `depthPoint: Boolean = false` (#1891 plane-only). " +
                "Did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    @Test
    fun `ReticleNode defaults instantPlacementPoint to false`() {
        val src = reticleNodeSource
        val pattern = Regex("""instantPlacementPoint\s*:\s*Boolean\s*=\s*false""")
        assertTrue(
            "ReticleNode must default `instantPlacementPoint: Boolean = false` (#1891). " +
                "Did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    @Test
    fun `ReticleNode exposes minCameraDistance Float floor at 30 cm`() {
        val src = reticleNodeSource
        val pattern = Regex("""minCameraDistance\s*:\s*Float\?\s*=\s*0\.3f""")
        assertTrue(
            "ReticleNode must expose `minCameraDistance: Float? = 0.3f` and pass it through to " +
                "HitResultNode (#1891). Did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    @Test
    fun `ARSceneScope ReticleNode composable defaults point to false`() {
        val src = arSceneScopeSource
        // The ReticleNode composable block must mirror the plane-only default. Scope to
        // the `fun ReticleNode(` signature so a HitResultNode match can't satisfy this.
        val block = reticleComposableSignature(src)
        val pattern = Regex("""point\s*:\s*Boolean\s*=\s*false""")
        assertTrue(
            "ARSceneScope.ReticleNode must default `point: Boolean = false` (#1891). " +
                "Did not match $pattern in the ReticleNode composable signature.",
            pattern.containsMatchIn(block)
        )
    }

    @Test
    fun `ARSceneScope ReticleNode composable exposes minCameraDistance Float floor at 30 cm`() {
        val src = arSceneScopeSource
        val block = reticleComposableSignature(src)
        val pattern = Regex("""minCameraDistance\s*:\s*Float\?\s*=\s*0\.3f""")
        assertTrue(
            "ARSceneScope.ReticleNode must expose `minCameraDistance: Float? = 0.3f` (#1891). " +
                "Did not match $pattern in the ReticleNode composable signature.",
            pattern.containsMatchIn(block)
        )
    }

    /**
     * Extracts the `fun ReticleNode(` parameter list from [ARSceneScope.kt] so a
     * ReticleNode-specific assertion can't be accidentally satisfied by the
     * `HitResultNode` composable that lives in the same file.
     */
    private fun reticleComposableSignature(src: String): String {
        val start = src.indexOf("fun ReticleNode(")
        assertTrue("Expected a `fun ReticleNode(` composable in ARSceneScope.kt (#1882).", start >= 0)
        val open = src.indexOf('(', start)
        // Walk to the matching close paren of the parameter list.
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        return src.substring(start)
    }
}
