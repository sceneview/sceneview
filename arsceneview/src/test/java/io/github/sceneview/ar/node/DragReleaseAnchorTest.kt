package io.github.sceneview.ar.node

import io.github.sceneview.ar.PlacementTrackableKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What an editable [AnchorNode] re-anchors on when the finger lifts. A model dragged right after
 * placement and released at once must stay where it was left when the phone turns: a plane
 * anchor when the drag ended on a tracked plane, a frozen world pose otherwise, and never an
 * approximate Instant Placement anchor that rides the camera.
 */
class DragReleaseAnchorTest {

    @Test
    fun `a drag ending on a tracked plane anchors on that plane`() {
        assertEquals(
            DragReleaseAnchor.PLANE,
            dragReleaseAnchor(PlacementTrackableKind.PLANE_IN_POLYGON, lastHitTracking = true, cameraTracking = true),
        )
    }

    @Test
    fun `no plane under the release freezes the world pose`() {
        // No hit moved the node: a release straight after placement, before any plane exists.
        assertEquals(DragReleaseAnchor.WORLD_POSE, dragReleaseAnchor(null, lastHitTracking = false, cameraTracking = true))
        // A plane the node left the polygon of, or one ARCore stopped tracking.
        assertEquals(
            DragReleaseAnchor.WORLD_POSE,
            dragReleaseAnchor(PlacementTrackableKind.PLANE_OUT_OF_POLYGON, lastHitTracking = true, cameraTracking = true),
        )
        assertEquals(
            DragReleaseAnchor.WORLD_POSE,
            dragReleaseAnchor(PlacementTrackableKind.PLANE_IN_POLYGON, lastHitTracking = false, cameraTracking = true),
        )
        // A feature or depth point: a world anchor, not one tied to a fleeting point.
        assertEquals(
            DragReleaseAnchor.WORLD_POSE,
            dragReleaseAnchor(PlacementTrackableKind.OTHER, lastHitTracking = true, cameraTracking = true),
        )
    }

    @Test
    fun `an instant placement hit never yields an approximate anchor on release`() {
        for (tracking in listOf(true, false)) {
            assertEquals(
                DragReleaseAnchor.WORLD_POSE,
                dragReleaseAnchor(PlacementTrackableKind.INSTANT_PLACEMENT, lastHitTracking = tracking, cameraTracking = true),
            )
        }
        // The outcome set itself has no Instant Placement member to fall back to.
        assertEquals(
            listOf("PLANE", "WORLD_POSE", "WAIT"),
            DragReleaseAnchor.entries.map { it.name },
        )
    }

    @Test
    fun `a release while the camera is not tracking waits instead of dropping the anchor`() {
        val kinds = PlacementTrackableKind.entries + listOf(null)
        for (kind in kinds) {
            assertEquals(DragReleaseAnchor.WAIT, dragReleaseAnchor(kind, lastHitTracking = true, cameraTracking = false))
        }
    }

    @Test
    fun `the release path never hit-tests instant placement`() {
        val sources = listOf("AnchorNode.kt", "PoseNode.kt").map {
            File("src/main/java/io/github/sceneview/ar/node/$it").readText()
        }
        for (source in sources) {
            assertFalse(source.contains("hitTestInstantPlacement"))
        }
        val onMoveEnd = sources[0].replace(Regex("""\s+"""), "")
            .substringAfter("overridefunonMoveEnd")
            .substringBefore("overridefun")
        assertTrue("AnchorNode.onMoveEnd must re-anchor through anchorAtRelease", onMoveEnd.contains("anchorAtRelease()"))
    }
}
