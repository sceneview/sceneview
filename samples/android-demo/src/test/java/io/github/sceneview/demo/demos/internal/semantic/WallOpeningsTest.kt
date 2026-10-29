package io.github.sceneview.demo.demos.internal.semantic

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.ScanObjects
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Doors and windows found as holes in the walls of a scanned cloud: a 4 × 4 m room with a door
 * in its north wall and a window in its east one, a larger room beyond them seen through.
 */
class WallOpeningsTest {
    @Test
    fun `a door down to the floor and a window up the wall are found where they are`() {
        val scan = HoledRoomScan
        val openings = CloudWallOpenings().find(scan.cloud) { scan.objects.surfaceOf(it) }
        val summary = openings.joinToString {
            "${it.label} w=%.2f h=%.2f sill=%.2f at (%.2f, %.2f) %.2f".format(
                it.width, it.height, it.sill, it.box.centerX, it.box.centerZ, it.confidence,
            )
        }
        val door = openings.singleOrNull { it.label == SceneLabel.Door } ?: error("no single door: $summary")
        assertEquals("door width: $summary", 0.9f, door.width, TOLERANCE)
        assertEquals("door height: $summary", 2.05f, door.height, TOLERANCE)
        assertTrue("door sill: $summary", door.sill <= CloudWallOpenings.DOOR_SILL_M)
        assertEquals("door along the north wall: $summary", 0.45f, door.box.centerX, TOLERANCE)
        assertEquals("door in the north wall: $summary", -2f, door.box.centerZ, TOLERANCE)

        val window = openings.singleOrNull { it.label == SceneLabel.Window } ?: error("no single window: $summary")
        assertEquals("window width: $summary", 1.2f, window.width, TOLERANCE)
        assertEquals("window height: $summary", 1.2f, window.height, TOLERANCE)
        assertEquals("window sill: $summary", 0.9f, window.sill, TOLERANCE)
        assertEquals("window in the east wall: $summary", 2f, window.box.centerX, TOLERANCE)
        assertEquals("window along the east wall: $summary", 0f, window.box.centerZ, TOLERANCE)
        assertEquals("nothing else: $summary", 2, openings.size)
    }

    @Test
    fun `the closed synthetic room has no openings`() {
        val scan = SyntheticRoomScan
        val openings = CloudWallOpenings().find(scan.cloud) { scan.objects.surfaceOf(it) }
        assertTrue(openings.joinToString { "${it.label} ${it.box.describe()}" }, openings.isEmpty())
    }

    @Test
    fun `a cloud with no walls has no openings`() {
        assertTrue(CloudWallOpenings().find(DenseCloud(FloatArray(0), IntArray(0))) { error("no surfel") }.isEmpty())
    }

    @Test
    fun `a scan of one corner finds the door and the window in its two walls`() {
        // The phone saw the north and east walls, not the two across them, and the room beyond
        // through the door and the window. The walls across used to be guessed from whatever
        // stood on their side and to cut both walls' ends short: both openings ran off a wall's
        // end and the scan had none (#4796).
        val corner = HoledRoomScan.cloud.cropped { x, _, z -> x > -0.6f && z < 0.9f }
        val survey = survey(corner)
        assertEquals(survey.note, listOf(SceneLabel.Door, SceneLabel.Window), survey.openings.map { it.label }.sorted())
    }

    @Test
    fun `the room seen through a door is not taken for the wall it is cut in`() {
        // Half the room: the room beyond, seen through the door, stacks up as much as the
        // door's own wall — the wall is the inner one.
        val half = HoledRoomScan.cloud.cropped { x, _, _ -> x > -0.5f }
        val survey = survey(half)
        assertEquals(survey.note, listOf(SceneLabel.Door, SceneLabel.Window), survey.openings.map { it.label }.sorted())
        val door = survey.openings.single { it.label == SceneLabel.Door }
        assertEquals(survey.note, -2f, door.box.centerZ, TOLERANCE)
    }

    @Test
    fun `a scan with no opening says why`() {
        val none = CloudWallOpenings().survey(DenseCloud(FloatArray(0), IntArray(0))) { error("no surfel") }
        assertTrue(none.note, none.openings.isEmpty() && none.note.startsWith("no wall"))

        // Walls seen up to 1.9 m, no ceiling: the door and the window run up past what was seen.
        val low = survey(HoledRoomScan.cloud.cropped { _, y, _ -> y < 1.9f })
        assertTrue(low.note, low.openings.isEmpty())
        assertTrue(low.note, "no ceiling seen" in low.note && "2 up to the top of the scanned wall" in low.note)
    }

    @Test
    fun `the openings pass reads no keyframe and still finds the openings`() = runBlocking {
        // What the replay's first pass is: no detector, no photo — "0 keyframes" by design.
        val corner = HoledRoomScan.cloud.cropped { x, _, z -> x > -0.6f && z < 0.9f }
        val recognition = SceneRecognizer(NoDetector, maxFrames = 0).recognize(
            emptyList(), corner, ScanObjects.of(corner), image = { error("no photo is read") },
        )
        assertTrue(recognition.frames.isEmpty())
        assertEquals(
            recognition.openingsNote,
            listOf(SceneLabel.Door, SceneLabel.Window),
            recognition.items.map { it.label }.sorted(),
        )
        val line = recognitionLogLine(recognition)
        assertTrue(line, line.startsWith("wall openings") && "2 openings" in line && "keyframes" !in line)
    }

    private fun survey(cloud: DenseCloud): WallSurvey {
        val objects = ScanObjects.of(cloud)
        return CloudWallOpenings().survey(cloud) { objects.surfaceOf(it) }
    }

    /** The surfels of this cloud [keep] keeps: what a scan that saw less of the room has. */
    private fun DenseCloud.cropped(keep: (x: Float, y: Float, z: Float) -> Boolean): DenseCloud {
        val kept = (0 until count).filter { keep(positions[it * 3], positions[it * 3 + 1], positions[it * 3 + 2]) }
        fun pick(from: FloatArray) = FloatArray(kept.size * 3) { from[kept[it / 3] * 3 + it % 3] }
        return DenseCloud(pick(positions), IntArray(kept.size) { colors[kept[it]] }, normals?.let(::pick))
    }

    private companion object {
        const val TOLERANCE = 0.15f
    }
}
