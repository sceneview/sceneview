package io.github.sceneview.ar.node

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Headless contracts for the `constructed` construction guard on every confirmed carrier of the
 * init-time open-dispatch bug class (#2624 — the class behind the 4.21.0
 * `ShadowReceiverPlaneNode` NPE, #2621).
 *
 * The pattern: a node constructor runs `init { trackable = <x> }`; `TrackableNode.trackable`'s
 * setter calls the **open** `update(trackable)`, virtually dispatched — so a subclass override
 * executes BEFORE the subclass's own fields are initialized. The guard gates each carrier's
 * class-specific `update()` tail behind a `constructed` flag and re-applies the initial state at
 * the end of `init`, keeping the construction end-state byte-for-byte identical.
 *
 * The runtime path cannot execute on the JVM (ARCore `Plane`/`AugmentedImage`/... and the
 * Filament `Engine` are device-bound), so — like [ShadowReceiverPlaneContractTest], the template
 * this file mirrors — the guard's structure is pinned at the source level: presence of the flag,
 * the gate, the flip, and the ordering (assignment → flip → re-apply; `super.update` before the
 * gate).
 */
class TrackableNodeConstructionGuardContractTest {

    /** The four confirmed carriers (#2624 audit table) — each assigns `trackable` in `init`. */
    private val guardedNodes = mapOf(
        "PlaneNode" to "plane",
        "AugmentedImageNode" to "augmentedImage",
        "AugmentedFaceNode" to "augmentedFace",
        "StreetscapeGeometryNode" to "streetscapeGeometry",
    )

    // JVM tests run with the module directory as CWD.
    private fun nodeSource(name: String): String {
        val file = File("src/main/java/io/github/sceneview/ar/node/$name.kt")
        assertTrue("Expected ${file.absolutePath}", file.exists())
        return file.readText()
    }

    @Test
    fun `every carrier declares the constructed flag before its init block`() {
        guardedNodes.keys.forEach { name ->
            val source = nodeSource(name)
            val flagIdx = source.indexOf("private var constructed = false")
            val initIdx = source.indexOf("init {")
            assertTrue(
                "$name must declare `private var constructed = false` (#2624 guard).",
                flagIdx >= 0
            )
            assertTrue("$name must have an init block assigning its trackable.", initIdx >= 0)
            assertTrue(
                "$name's `constructed` flag must be declared BEFORE the init block that " +
                    "assigns `trackable` — Kotlin initializes in declaration order, so a flag " +
                    "declared after the init would still be false-by-JVM-default but the intent " +
                    "must be explicit in source.",
                flagIdx < initIdx
            )
        }
    }

    @Test
    fun `every carrier flips constructed AFTER the trackable assignment and re-applies the initial state`() {
        guardedNodes.forEach { (name, trackableParam) ->
            val source = nodeSource(name)
            val initIdx = source.indexOf("init {")
            val initBody = source.substring(initIdx, source.indexOf("}", initIdx) + 1)
            val assignIdx = initBody.indexOf("trackable = $trackableParam")
            val flipIdx = initBody.indexOf("constructed = true")
            val applyIdx = initBody.indexOf("applyTrackableState()")
            assertTrue(
                "$name's init must assign `trackable = $trackableParam`.",
                assignIdx >= 0
            )
            assertTrue("$name's init must flip `constructed = true`.", flipIdx >= 0)
            assertTrue(
                "$name's init must flip `constructed = true` AFTER the trackable assignment " +
                    "(the assignment is what dispatches the gated update()).",
                assignIdx < flipIdx
            )
            assertTrue(
                "$name's init must re-apply the initial state (applyTrackableState()) AFTER " +
                    "flipping the flag — otherwise the gate would silently drop the " +
                    "construction-time pose/state application and change behavior.",
                applyIdx > flipIdx
            )
        }
    }

    @Test
    fun `every carrier gates its update tail behind the constructed flag after super update`() {
        guardedNodes.keys.forEach { name ->
            val source = nodeSource(name)
            val overrideIdx = source.indexOf("override fun update(trackable:")
            assertTrue("$name must override update(trackable).", overrideIdx >= 0)
            val body = source.substring(overrideIdx, minOf(overrideIdx + 600, source.length))
            val superIdx = body.indexOf("super.update(trackable)")
            val gateIdx = body.indexOf("if (!constructed) return")
            assertTrue("$name.update must call super.update(trackable).", superIdx >= 0)
            assertTrue(
                "$name.update must bail with `if (!constructed) return` — the #2620/#2621 " +
                    "guard pattern proven by ShadowReceiverPlaneNode.",
                gateIdx >= 0
            )
            assertTrue(
                "$name.update must run super.update BEFORE the gate, so the base tracking-state " +
                    "refresh still happens during construction (the ShadowReceiverPlaneNode " +
                    "precedent: only the class-specific tail is deferred).",
                superIdx < gateIdx
            )
            assertTrue(
                "$name.update's gated tail must delegate to applyTrackableState() so init can " +
                    "re-apply the identical logic once construction completes.",
                body.indexOf("applyTrackableState()") > gateIdx
            )
        }
    }

    @Test
    fun `TrackableNode update documents the subclassing hazard and the guard recipe`() {
        val source = nodeSource("TrackableNode")
        assertTrue(
            "TrackableNode.update's KDoc must warn subclass implementers about the init-time " +
                "virtual dispatch (#2624) — user subclasses cannot be protected library-side, " +
                "documentation is the only mitigation.",
            source.contains("Subclassing warning (#2624)")
        )
        assertTrue(
            "The KDoc must include the `constructed` guard recipe.",
            source.contains("private var constructed = false")
        )
    }

    @Test
    fun `the ShadowReceiverPlaneNode guard stays in place`() {
        // The original victim keeps its own guard (pinned in detail by
        // ShadowReceiverPlaneContractTest) — this cross-check just ensures a refactor of the
        // base PlaneNode guard never removes the subclass-side one, which protects the
        // subclass-declared meshNode/lastMeshSize fields.
        val source = nodeSource("ShadowReceiverPlaneNode")
        assertTrue(
            "ShadowReceiverPlaneNode must keep its own `if (!constructed) return` guard.",
            source.contains("if (!constructed) return")
        )
    }
}
