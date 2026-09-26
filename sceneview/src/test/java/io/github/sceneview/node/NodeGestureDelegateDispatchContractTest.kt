package io.github.sceneview.node

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless source contract for [NodeGestureDelegate]'s onMove/onScale value dispatch (#3739).
 *
 * The bug class: [Node.onMove] and [Node.onScale] expose `open` 3-arg value overloads
 * (`onMove(detector, e, worldPosition)` / `onScale(detector, e, scaleFactor)`) meant to be
 * overridable by subclasses. But the 2-arg entry points in [NodeGestureDelegate] called their
 * own 3-arg functions directly (`onMove(detector, e, it.getWorldPosition())` /
 * `onScale(detector, e, detector.scaleFactor)`) instead of going through `node.onMove(...)` /
 * `node.onScale(...)` — so an overridden `Node.onMove`/`Node.onScale` was never actually invoked
 * during a live gesture. `onRotate` already dispatches correctly and is intentionally out of
 * scope here (tracked separately by #3735).
 *
 * A real gesture pipeline needs a live [android.view.MotionEvent]/[com.google.android.filament.Engine]
 * and can't run on the JVM, so — mirroring `GestureDetectorContractTest` — this pins the
 * source-level contract instead: cheap, honest, and it fails the build the moment the
 * bypass-the-node pattern reappears. `NodeGestureDispatchTest` (androidTest) additionally proves
 * the fix behaviorally against a real `Node` subclass.
 */
class NodeGestureDelegateDispatchContractTest {

    // JVM tests run with the module directory as CWD.
    private val delegateFile =
        File("src/main/java/io/github/sceneview/node/NodeGestureDelegate.kt")

    private val source: String by lazy {
        assertTrue("Expected ${delegateFile.absolutePath}", delegateFile.exists())
        delegateFile.readText()
    }

    @Test
    fun `onMove 2-arg entry point dispatches through node so overrides run (#3739)`() {
        assertTrue(
            "NodeGestureDelegate.onMove(detector, e) must dispatch via `node.onMove(detector, e, " +
                "worldPosition)` so an overridden Node.onMove actually runs (#3739)",
            source.contains("node.onMove(detector, e, it.getWorldPosition())")
        )
        assertFalse(
            "NodeGestureDelegate.onMove(detector, e) must not bypass the node by calling its own " +
                "3-arg onMove directly (#3739)",
            source.contains(Regex("""[^.]\bonMove\(detector, e, it\.getWorldPosition\(\)\)"""))
        )
    }

    @Test
    fun `onScale 2-arg entry point dispatches through node so overrides run (#3739)`() {
        assertTrue(
            "NodeGestureDelegate.onScale(detector, e) must dispatch via `node.onScale(detector, e, " +
                "detector.scaleFactor)` so an overridden Node.onScale actually runs (#3739)",
            source.contains("node.onScale(detector, e, detector.scaleFactor)")
        )
        assertFalse(
            "NodeGestureDelegate.onScale(detector, e) must not bypass the node by calling its own " +
                "3-arg onScale directly (#3739)",
            source.contains(Regex("""[^.]\bonScale\(detector, e, detector\.scaleFactor\)"""))
        )
    }
}
