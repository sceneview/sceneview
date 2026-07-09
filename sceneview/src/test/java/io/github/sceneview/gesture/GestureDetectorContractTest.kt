package io.github.sceneview.gesture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless source contract for [GestureDetector]'s live-event dispatch (#2629).
 *
 * The bug class: the move/rotate/scale listeners stored `xBeginEvent = e to touchedNode` and
 * dispatched with `xBeginEvent?.let { (e, node) -> node?.onX(detector, e) }` — the destructured
 * `e` SHADOWED the live callback parameter, so every mid-gesture dispatch delivered the
 * finger-DOWN MotionEvent (a framework-recycled reference, on top of being stale). In AR,
 * `PoseNode.onMove` re-hit-tested the down pixel every frame → a dragged model never moved.
 *
 * A real gesture pipeline can't run on the JVM (Context, MotionEvent, Choreographer), so this
 * pins the source-level contract the same way `ShadowReceiverPlaneContractTest` does — cheap,
 * honest, and it fails the build the moment the shadowing pattern reappears.
 */
class GestureDetectorContractTest {

    // JVM tests run with the module directory as CWD.
    private val detectorFile =
        File("src/main/java/io/github/sceneview/gesture/GestureDetector.kt")

    private val source: String by lazy {
        assertTrue("Expected ${detectorFile.absolutePath}", detectorFile.exists())
        detectorFile.readText()
    }

    @Test
    fun `no stale destructured event may shadow the live MotionEvent (#2629)`() {
        // The exact pattern that froze AR drag: destructuring a stored Pair as `(e, node)`
        // inside a callback whose live parameter is also named `e`.
        assertFalse(
            "GestureDetector must never destructure a stored begin event as `(e, node)` — " +
                "it shadows the live MotionEvent and re-dispatches the finger-DOWN event (#2629)",
            source.contains(Regex("""\{\s*\(\s*e\s*,\s*node\s*\)\s*->"""))
        )
        // And the begin MotionEvent must not be retained at all (framework recycles it).
        assertFalse(
            "GestureDetector must not retain begin MotionEvents (recycled by the framework) — " +
                "pin the gesture to the begin NODE only (#2629)",
            source.contains(Regex("""Pair<\s*MotionEvent\s*,\s*Node\??\s*>"""))
        )
    }

    @Test
    fun `mid-gesture dispatch passes the live event to the begin node (#2629)`() {
        // Each detector pins the gesture to the node it began on and forwards the LIVE `e`.
        listOf(
            "moveBeginNode?.onMove(detector, e)",
            "rotateBeginNode?.onRotate(detector, e)",
            "scaleBeginNode?.onScale(detector, e)",
        ).forEach { call ->
            assertTrue("GestureDetector must dispatch `$call` (#2629)", source.contains(call))
        }
        // The in-progress flags keep listener-level dispatch alive when the gesture began on
        // empty space (begin node == null) — the pre-fix Pair encoded that implicitly.
        listOf("moveInProgress", "rotateInProgress", "scaleInProgress").forEach { flag ->
            assertTrue(
                "GestureDetector must guard dispatch with `$flag` (#2629)",
                source.contains(flag)
            )
        }
    }
}
