package io.github.sceneview.ar.scene

import com.google.ar.core.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lifecycle of [PlaneRendererV2]'s per-plane visualizers, without a Filament Engine:
 *
 *  1. [planeVisualizerAction] never builds a visualizer for a subsumed or stopped plane. The
 *     test it replaces (`TRACKING || subsumedBy == null`) let every subsumed plane through, so
 *     each gated update built a visualizer the cleanup destroyed right after.
 *  2. [OwnedVisualizers] destroys a visualizer's MaterialInstances with it: live instances stay
 *     at 2 × live visualizers. They used to be released only when the whole renderer was.
 *  3. The ripple phase in `plane_renderer_v2.mat` is wrapped in highp, not read into a mediump
 *     `float` that steps by 0.25 s after 256 s of engine uptime.
 */
class PlaneRendererV2LifecycleTest {

    // ── 1. Which planes get a visualizer ─────────────────────────────────────────────────

    @Test
    fun `subsumed plane is skipped whatever its tracking state`() {
        for (state in TrackingState.values()) {
            for (hasVisualizer in listOf(false, true)) {
                assertEquals(
                    "state=$state hasVisualizer=$hasVisualizer",
                    PlaneVisualizerAction.SKIP,
                    planeVisualizerAction(state, isSubsumed = true, hasVisualizer = hasVisualizer),
                )
            }
        }
    }

    @Test
    fun `stopped plane is skipped`() {
        assertEquals(
            PlaneVisualizerAction.SKIP,
            planeVisualizerAction(TrackingState.STOPPED, isSubsumed = false, hasVisualizer = false),
        )
        assertEquals(
            PlaneVisualizerAction.SKIP,
            planeVisualizerAction(TrackingState.STOPPED, isSubsumed = false, hasVisualizer = true),
        )
    }

    @Test
    fun `tracking plane gets a visualizer once then updates`() {
        assertEquals(
            PlaneVisualizerAction.CREATE,
            planeVisualizerAction(TrackingState.TRACKING, isSubsumed = false, hasVisualizer = false),
        )
        assertEquals(
            PlaneVisualizerAction.UPDATE,
            planeVisualizerAction(TrackingState.TRACKING, isSubsumed = false, hasVisualizer = true),
        )
    }

    @Test
    fun `paused plane with a visualizer keeps updating so it can fade out`() {
        assertEquals(
            PlaneVisualizerAction.UPDATE,
            planeVisualizerAction(TrackingState.PAUSED, isSubsumed = false, hasVisualizer = true),
        )
    }

    // ── 2. MaterialInstances live and die with their visualizer ──────────────────────────

    private class FakePlane {
        var trackingState = TrackingState.TRACKING
        var isSubsumed = false
    }

    private class Fakes {
        var visualizersBuilt = 0
        var visualizersDestroyed = 0
        val liveInstances = mutableSetOf<String>()
        val destroyedInstances = mutableListOf<String>()

        val registry = OwnedVisualizers<FakePlane, String, String>(
            destroyVisualizer = { visualizersDestroyed++ },
            destroyInstance = {
                assertTrue("instance $it destroyed twice", liveInstances.remove(it))
                destroyedInstances += it
            },
        )

        /** Mirrors `PlaneRendererV2.update`: renderPlane for each updated plane, then cleanup. */
        fun gatedUpdate(updatedPlanes: List<FakePlane>) {
            for (plane in updatedPlanes) {
                val action = planeVisualizerAction(
                    plane.trackingState,
                    plane.isSubsumed,
                    hasVisualizer = registry[plane] != null,
                )
                if (action == PlaneVisualizerAction.CREATE) {
                    val id = visualizersBuilt++
                    val instances = listOf("plane-$id", "shadow-$id")
                    liveInstances += instances
                    registry.put(plane, "visualizer-$id", instances)
                }
            }
            registry.removeIf { plane, _ ->
                plane.isSubsumed || plane.trackingState == TrackingState.STOPPED
            }
        }

        fun assertInstancesMatchVisualizers() {
            assertEquals(2 * registry.size, registry.instanceCount)
            assertEquals(registry.instanceCount, liveInstances.size)
            assertEquals(registry.size, registry.visualizerList.size)
        }
    }

    @Test
    fun `instance count stays at two per live visualizer through merges and loss`() {
        val fakes = Fakes()
        val floor = FakePlane()
        val table = FakePlane()
        val wall = FakePlane()
        val all = listOf(floor, table, wall)

        fakes.gatedUpdate(all)
        assertEquals(3, fakes.registry.size)
        fakes.assertInstancesMatchVisualizers()

        // ARCore merges the table into the floor; the table stays in getUpdatedPlanes.
        table.isSubsumed = true
        // 10 gated updates = 1 s at the default maxHitTestPerSecond.
        repeat(10) {
            fakes.gatedUpdate(all)
            fakes.assertInstancesMatchVisualizers()
        }
        assertEquals(2, fakes.registry.size)
        assertEquals("no visualizer built for the subsumed plane", 3, fakes.visualizersBuilt)
        assertEquals(listOf("plane-1", "shadow-1"), fakes.destroyedInstances)

        wall.trackingState = TrackingState.STOPPED
        fakes.gatedUpdate(all)
        fakes.assertInstancesMatchVisualizers()
        assertEquals(1, fakes.registry.size)
        assertEquals(2, fakes.visualizersDestroyed)

        // Renderer destroy.
        fakes.registry.clear()
        fakes.assertInstancesMatchVisualizers()
        assertEquals(0, fakes.registry.size)
        assertTrue(fakes.liveInstances.isEmpty())
        assertEquals(3, fakes.visualizersDestroyed)
    }

    @Test
    fun `replacing a plane's visualizer releases the previous one and its instances`() {
        val fakes = Fakes()
        val plane = FakePlane()
        fakes.liveInstances += listOf("a", "b", "c", "d")
        fakes.registry.put(plane, "first", listOf("a", "b"))
        fakes.registry.put(plane, "second", listOf("c", "d"))

        assertEquals(1, fakes.registry.size)
        assertEquals(listOf("second"), fakes.registry.visualizerList)
        assertEquals(listOf("a", "b"), fakes.destroyedInstances)
        fakes.assertInstancesMatchVisualizers()
    }

    // ── 3. Ripple time precision ─────────────────────────────────────────────────────────

    @Test
    fun `ripple phase is wrapped in highp`() {
        // JVM tests run with the module directory as CWD.
        val source = File("src/main/materials/plane_renderer_v2.mat").readText()
        assertFalse(
            "a plain `float` is mediump under `-p mobile`: engine uptime loses sub-second " +
                "precision after 256 s",
            Regex("""(?m)^\s*float\s+\w+\s*=\s*getUserTime\(\)""").containsMatchIn(source),
        )
        assertTrue(
            Regex("""highp\s+float\s+ripplePhase\s*=\s*getUserTimeMod\(""").containsMatchIn(source),
        )
    }
}
