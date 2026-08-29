package io.github.sceneview.ar.node

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the reticle hit-test throttle forwarding of
 * [#3391](https://github.com/sceneview/sceneview/issues/3391).
 *
 * [HitResultNode] has exposed an opt-in `refreshIntervalMs` rate-limit on its ARCore
 * [com.google.ar.core.Frame.hitTest] since #2328 — but [ReticleNode] and
 * [PlacementReticleNode] neither declared nor forwarded it, so every caller silently
 * inherited the `0` ("every updated frame") default. The knob was reachable in principle
 * (`refreshIntervalMs` is a public `var` on the base class, so `.apply { }` worked) yet
 * undiscoverable in practice: absent from both constructors, from both KDocs, and from
 * every reticle composable signature. A reticle is on screen for the *whole* placement
 * flow, which makes it the single node most likely to want a ceiling.
 *
 * What this pins:
 *
 * 1. Both reticle class constructors declare `refreshIntervalMs: Long = 0L` and forward
 *    it to their supertype — `0L` keeping the byte-for-byte original behaviour.
 * 2. Neither class re-implements the throttle: no timestamp field, no
 *    [hitResultRefreshThrottled] call of their own (the #1882 thin-wrapper contract).
 * 3. Both are documented — the fix is discoverability, so a missing `@param` is a
 *    regression of the fix itself.
 * 4. The three `ARSceneScope` composables that build these nodes
 *    (`HitResultNode` / `ReticleNode` / `PlacementReticle`) expose the parameter,
 *    pass it at construction, and re-apply it in a `SideEffect`.
 * 5. `refreshIntervalMs` is never a `remember` key — re-rating an existing reticle must
 *    never destroy and re-create the node mid-placement (the #2506 live-knob contract).
 *
 * Source-introspection ("strings + regex"), the same style as
 * [io.github.sceneview.ar.ReticleNodeDefaultsTest]: these node types need a Filament
 * `Engine` and cannot be instantiated on the JVM. The throttle *gate* itself is covered
 * behaviourally by [HitResultNodeThrottleTest].
 */
class ReticleThrottleForwardingTest {

    private val reticleNodeFile =
        File("src/main/java/io/github/sceneview/ar/node/ReticleNode.kt")
    private val placementReticleNodeFile =
        File("src/main/java/io/github/sceneview/ar/node/PlacementReticleNode.kt")
    private val arSceneScopeFile =
        File("src/main/java/io/github/sceneview/ar/ARSceneScope.kt")

    private val reticleNodeSource: String by lazy { read(reticleNodeFile) }
    private val placementReticleNodeSource: String by lazy { read(placementReticleNodeFile) }
    private val arSceneScopeSource: String by lazy { read(arSceneScopeFile) }

    // ── Class constructors ────────────────────────────────────────────────────────────

    @Test
    fun `ReticleNode constructor declares refreshIntervalMs defaulting to every frame`() {
        val pattern = Regex("""refreshIntervalMs\s*:\s*Long\s*=\s*0L""")
        assertTrue(
            "ReticleNode must declare `refreshIntervalMs: Long = 0L` so callers can rate-limit " +
                "the hit test (#3391). `0L` keeps the original every-frame behaviour. " +
                "Did not match $pattern.",
            pattern.containsMatchIn(reticleNodeSource)
        )
    }

    @Test
    fun `ReticleNode forwards refreshIntervalMs to HitResultNode`() {
        val supertypeCall = supertypeCall(reticleNodeSource, "HitResultNode")
        val pattern = Regex("""refreshIntervalMs\s*=\s*refreshIntervalMs""")
        assertTrue(
            "ReticleNode must pass `refreshIntervalMs = refreshIntervalMs` into its " +
                "`: HitResultNode(` supertype call — declaring the parameter without " +
                "forwarding it would silently drop it (#3391). Did not match $pattern.",
            pattern.containsMatchIn(supertypeCall)
        )
    }

    @Test
    fun `PlacementReticleNode constructor declares refreshIntervalMs defaulting to every frame`() {
        val pattern = Regex("""refreshIntervalMs\s*:\s*Long\s*=\s*0L""")
        assertTrue(
            "PlacementReticleNode must declare `refreshIntervalMs: Long = 0L` (#3391). " +
                "Did not match $pattern.",
            pattern.containsMatchIn(placementReticleNodeSource)
        )
    }

    @Test
    fun `PlacementReticleNode forwards refreshIntervalMs to ReticleNode`() {
        val supertypeCall = supertypeCall(placementReticleNodeSource, "ReticleNode")
        val pattern = Regex("""refreshIntervalMs\s*=\s*refreshIntervalMs""")
        assertTrue(
            "PlacementReticleNode must pass `refreshIntervalMs = refreshIntervalMs` into its " +
                "`: ReticleNode(` supertype call so the rate reaches HitResultNode two levels " +
                "down (#3391). Did not match $pattern.",
            pattern.containsMatchIn(supertypeCall)
        )
    }

    @Test
    fun `reticles do not re-implement the throttle`() {
        // The thin-wrapper contract (#1882): the rate-limit state and gate live in
        // HitResultNode. A private timestamp field or a hitResultRefreshThrottled call in a
        // subclass would be a second, divergent throttle running against the same hit test.
        listOf(
            "ReticleNode" to reticleNodeSource,
            "PlacementReticleNode" to placementReticleNodeSource
        ).forEach { (name, src) ->
            assertTrue(
                "$name must NOT keep its own hit-test timestamp — the throttle state belongs to " +
                    "HitResultNode (#1882 thin wrapper / #3391).",
                !src.contains("lastHitTestTimestampMs")
            )
            assertTrue(
                "$name must NOT call `hitResultRefreshThrottled(` — the gate is applied once, in " +
                    "HitResultNode.update (#1882 thin wrapper / #3391).",
                !src.contains("hitResultRefreshThrottled(")
            )
        }
    }

    @Test
    fun `both reticle classes document refreshIntervalMs`() {
        // The defect was discoverability, not reachability: `refreshIntervalMs` was already a
        // public var on the base class. An undocumented parameter re-creates the bug.
        val pattern = Regex("""@param\s+refreshIntervalMs\s""")
        assertTrue(
            "ReticleNode KDoc must carry an `@param refreshIntervalMs` — the #3391 fix is " +
                "discoverability. Did not match $pattern.",
            pattern.containsMatchIn(reticleNodeSource)
        )
        assertTrue(
            "PlacementReticleNode KDoc must carry an `@param refreshIntervalMs` (#3391). " +
                "Did not match $pattern.",
            pattern.containsMatchIn(placementReticleNodeSource)
        )
    }

    // ── ARSceneScope composables ──────────────────────────────────────────────────────

    @Test
    fun `ARSceneScope HitResultNode composable exposes refreshIntervalMs`() {
        assertComposableExposesRefreshInterval(HIT_RESULT_NODE)
    }

    @Test
    fun `ARSceneScope ReticleNode composable exposes refreshIntervalMs`() {
        assertComposableExposesRefreshInterval(RETICLE_NODE)
    }

    @Test
    fun `ARSceneScope PlacementReticle composable exposes refreshIntervalMs`() {
        assertComposableExposesRefreshInterval(PLACEMENT_RETICLE)
    }

    @Test
    fun `ARSceneScope composables pass refreshIntervalMs at construction`() {
        listOf(HIT_RESULT_NODE, RETICLE_NODE, PLACEMENT_RETICLE).forEach { composable ->
            val block = composableBlock(composable)
            val pattern = Regex("""refreshIntervalMs\s*=\s*refreshIntervalMs""")
            assertTrue(
                "ARSceneScope.${composable.functionName} must pass " +
                    "`refreshIntervalMs = refreshIntervalMs` into its node constructor so the " +
                    "very first frame is already rate-limited (#3391). Did not match $pattern.",
                pattern.containsMatchIn(block)
            )
        }
    }

    @Test
    fun `ARSceneScope composables re-apply refreshIntervalMs in a SideEffect`() {
        listOf(HIT_RESULT_NODE, RETICLE_NODE, PLACEMENT_RETICLE).forEach { composable ->
            val block = composableBlock(composable)
            val sideEffect = block.substringAfter("SideEffect {", "").substringBefore("}")
            val pattern = Regex("""node\.refreshIntervalMs\s*=\s*refreshIntervalMs""")
            assertTrue(
                "ARSceneScope.${composable.functionName} must re-apply " +
                    "`node.refreshIntervalMs = refreshIntervalMs` in a `SideEffect` — otherwise a " +
                    "recomposed rate would never reach the remembered node (#2506 / #3391). " +
                    "Did not match $pattern.",
                pattern.containsMatchIn(sideEffect)
            )
        }
    }

    @Test
    fun `refreshIntervalMs is never a remember key`() {
        // Making the rate a `remember` key would destroy and re-create the node on every
        // change — for a reticle that means losing the cursor mid-placement. The knob is a
        // live-writable var, so a changed rate must re-rate the node in place (#3391).
        listOf(HIT_RESULT_NODE, RETICLE_NODE, PLACEMENT_RETICLE).forEach { composable ->
            val block = composableBlock(composable)
            val keyLists = Regex("""remember\(([^)]*)\)\s*\{""").findAll(block)
                .map { it.groupValues[1] }
                .toList()
            assertTrue(
                "Expected at least one `remember(...) {` call in ARSceneScope." +
                    "${composable.functionName} — the extraction markers are stale.",
                keyLists.isNotEmpty()
            )
            keyLists.forEach { keys ->
                assertTrue(
                    "ARSceneScope.${composable.functionName} must NOT use refreshIntervalMs as a " +
                        "`remember` key: re-rating the hit test would destroy and re-create the " +
                        "node mid-placement (#3391). Found it in `remember($keys)`.",
                    !keys.contains("refreshIntervalMs")
                )
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────

    private fun assertComposableExposesRefreshInterval(composable: Composable) {
        val signature = composableSignature(composable)
        val pattern = Regex("""refreshIntervalMs\s*:\s*Long\s*=\s*0L""")
        assertTrue(
            "ARSceneScope.${composable.functionName} must expose " +
                "`refreshIntervalMs: Long = 0L` so the throttle is discoverable from the " +
                "composable signature (#3391). Did not match $pattern in the extracted " +
                "signature.",
            pattern.containsMatchIn(signature)
        )
    }

    /**
     * A composable in `ARSceneScope.kt`, addressed by its `fun` header and by the section
     * divider that follows it, so an assertion about one composable can never be satisfied
     * by a sibling declared in the same file.
     */
    private data class Composable(
        val functionName: String,
        val header: String,
        val endMarker: String
    )

    /** Everything between [Composable.header] and the next section divider. */
    private fun composableBlock(composable: Composable): String {
        val src = arSceneScopeSource
        assertTrue(
            "Expected a `${composable.header}` composable in ARSceneScope.kt (#3391).",
            src.contains(composable.header)
        )
        val afterHeader = src.substringAfter(composable.header)
        assertTrue(
            "Expected the `${composable.endMarker}` section divider after " +
                "`${composable.header}` in ARSceneScope.kt — the extraction markers are stale.",
            afterHeader.contains(composable.endMarker)
        )
        return afterHeader.substringBefore(composable.endMarker)
    }

    /**
     * The parameter list of [composable], by walking to the matching close paren of its
     * `fun` header — a body-level match (a constructor argument, say) must not satisfy an
     * assertion about the declared signature.
     */
    private fun composableSignature(composable: Composable): String {
        val src = arSceneScopeSource
        val start = src.indexOf(composable.header)
        assertTrue(
            "Expected a `${composable.header}` composable in ARSceneScope.kt (#3391).",
            start >= 0
        )
        return balancedParens(src, src.indexOf('(', start))
    }

    /**
     * The supertype constructor call of a class — the `(...)` following `: [superName](`
     * — so a "forwarded to the supertype" assertion can't be satisfied by the subclass's
     * own parameter list.
     */
    private fun supertypeCall(src: String, superName: String): String {
        val marker = Regex(""":\s*$superName\s*\(""").find(src)
        assertTrue(
            "Expected a `: $superName(` supertype call — the class hierarchy changed.",
            marker != null
        )
        return balancedParens(src, src.indexOf('(', marker!!.range.first))
    }

    /** Substring from [open] to its matching close paren, inclusive. */
    private fun balancedParens(src: String, open: Int): String {
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
        return src.substring(open)
    }

    private fun read(file: File): String {
        assertTrue(
            "Expected to find ${file.absolutePath} — JVM test must run from the arsceneview " +
                "module root.",
            file.exists()
        )
        return file.readText()
    }

    private companion object {
        /** The View-coordinate overload — the one that owns the hit test parameters. */
        val HIT_RESULT_NODE = Composable(
            functionName = "HitResultNode",
            header = "fun HitResultNode(",
            endMarker = "// ── ReticleNode"
        )
        val RETICLE_NODE = Composable(
            functionName = "ReticleNode",
            header = "fun ReticleNode(",
            endMarker = "// ── PlacementReticle"
        )
        val PLACEMENT_RETICLE = Composable(
            functionName = "PlacementReticle",
            header = "fun PlacementReticle(",
            endMarker = "// ── DepthHitResultNode"
        )
    }
}
