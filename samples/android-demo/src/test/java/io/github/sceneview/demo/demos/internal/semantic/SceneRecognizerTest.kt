package io.github.sceneview.demo.demos.internal.semantic

import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.ScanBox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole recognition on the synthetic room: a detector that names what each rendered frame
 * shows, its detections lifted into the fused cloud and fused across the walk.
 */
class SceneRecognizerTest {
    @Test
    fun `the synthetic room's sofa, table and cabinet are recognised once each, and the ball is not`() = runBlocking {
        val scan = SyntheticRoomScan
        val detector = TruthDetector(scan.frames, TruthDetector.ROOM)
        val keyframes = scan.keyframes(step = 3)
        val recognition = SceneRecognizer(detector, maxFrames = keyframes.size)
            .recognize(keyframes, scan.cloud, scan.objects, image = { detector.image(it.removePrefix("p").toInt()) })
        val summary = recognition.items.joinToString { "${it.label} ${it.views}v ${"%.2f".format(it.confidence)} ${it.box.describe()}" }
        val things = recognition.items.filterNot { it.label.isOpening }
        for ((name, truth) in TruthDetector.ROOM) {
            val label = SceneLabel.ofDetector(name)!!
            val found = things.filter { it.label == label }
            assertEquals("one $label in $summary", 1, found.size)
            val item = found.single()
            assertTrue("$label over its truth: $summary", SceneFusion.footprintIou(item.box, truth) >= 0.5f)
            assertTrue("$label seen from several keyframes: $summary", item.views >= 2)
            assertTrue("$label's top within 15 cm: $summary", kotlin.math.abs(item.box.baseY + item.box.height - truth.height) < 0.15f)
        }
        assertEquals("nothing but the three: $summary", 3, things.size)
        assertTrue("the ball is no furniture: $summary", things.none { SceneFusion.inside(it.box, -1.1f, 1f) })
        assertTrue("a closed room has no openings: $summary", recognition.items.none { it.label.isOpening })
        // Every keyframe is accounted for, with its timings.
        assertEquals(keyframes.size, recognition.frames.size)
        assertTrue(recognition.frames.any { it.lifted.isNotEmpty() })
        assertEquals("truth", recognition.detector)
    }

    @Test
    fun `no detector finds nothing, and the keyframes are still walked`() = runBlocking {
        val scan = SyntheticRoomScan
        val none = object : SceneDetector {
            override val name = "none"
            override fun detect(image: SceneImage) = emptyList<Detection2D>()
            override fun close() = Unit
        }
        val detector = TruthDetector(scan.frames, TruthDetector.ROOM)
        val keyframes = scan.keyframes(step = 30)
        val recognition = SceneRecognizer(none, maxFrames = 4)
            .recognize(keyframes, scan.cloud, scan.objects, image = { detector.image(it.removePrefix("p").toInt()) })
        assertTrue(recognition.items.none { !it.label.isOpening })
        assertEquals(4, recognition.frames.size)
    }

    @Test
    fun `furniture standing in an opening hides it, an opening clear of it stays`() {
        val sofa = RecognizedItem(SceneLabel.Sofa, TruthDetector.box(-1f, -2f, 0f, -1.2f, 0.9f), 0.7f, 3, intArrayOf(1, 2))
        val behindSofa = opening(SceneLabel.Window, ScanBox(-0.5f, -2f, 0.1f, 0f, 1f, 0.12f, 0.8f))
        val door = opening(SceneLabel.Door, ScanBox(1.5f, -2f, 0f, 0f, 0.9f, 0.12f, 2f))
        val merged = SceneRecognizer.merge(listOf(sofa), listOf(behindSofa, door))
        assertEquals(listOf(SceneLabel.Sofa, SceneLabel.Door), merged.map { it.label })
        assertTrue(merged.last().surfels.isEmpty())
    }

    @Test
    fun `the boxes' edges are twelve tubes each, rewritten into the same mesh`() {
        val box = TruthDetector.box(0f, 0f, 1f, 1f, 1f)
        val one = SceneBoxMeshes.edges(listOf(box), 0.01f).triangleCount
        assertTrue(one > 0)
        assertEquals(0, one % 12)
        val mesh = DebugMesh()
        SceneBoxMeshes.edges(listOf(box, box), 0.01f, mesh)
        assertEquals(2 * one, mesh.triangleCount)
        SceneBoxMeshes.edges(listOf(box), 0.01f, mesh)
        assertEquals(one, mesh.triangleCount)
        assertTrue(SceneBoxMeshes.edges(emptyList(), 0.01f, mesh).isEmpty)
    }

    @Test
    fun `an item is drawn once the replay has drawn its surfels, an opening once the whole scan is`() {
        val chair = RecognizedItem(SceneLabel.Chair, TruthDetector.box(0f, 0f, 1f, 1f, 1f), 0.6f, 2, intArrayOf(3, 9, 5))
        assertTrue(!SceneBoxMeshes.isShown(chair, 9, 100))
        assertTrue(SceneBoxMeshes.isShown(chair, 10, 100))
        val door = RecognizedItem(SceneLabel.Door, TruthDetector.box(0f, 0f, 1f, 0.1f, 2f), 0.6f, 0, IntArray(0))
        assertTrue(!SceneBoxMeshes.isShown(door, 99, 100))
        assertTrue(SceneBoxMeshes.isShown(door, 100, 100))
        assertTrue(!SceneBoxMeshes.isShown(door, 0, 0))
    }

    private fun opening(label: SceneLabel, box: ScanBox) =
        WallOpening(label, box, box.length, box.height, box.baseY, 0.6f)

    @Test
    fun `each keyframe takes the pose the trace had when its photo was taken`() {
        val trace = ArDebugTrace().apply {
            addImage(50_000_000L, "before-any-pose")
            for (i in 1..40) addPose(i * 100_000_000L, DebugPose(i * 0.1f, 1.5f, -i * 0.05f))
            for (i in 0..60) addImage(i * 70_000_000L + 130_000_000L, "p$i")
        }
        val keyframes = SceneRecognizer.keyframes(trace, ReplayLens.Default, maxFrames = 24)
        assertTrue("keyframes from the photos: ${keyframes.size}", keyframes.size >= 20)
        // The trace starts at its first event, the photo 50 ms before the first pose.
        assertTrue("the photo taken before any pose is skipped", keyframes.none { it.path == "before-any-pose" })
        assertNull("no pose before the first", SceneRecognizer.poseAt(trace, 0.02f))
        for (frame in keyframes) {
            assertEquals("pose at ${frame.time}", trace.frameAt(frame.time).camera, frame.camera.pose)
        }
        for (t in listOf(-1f, 0f, 0.05f, 0.1f, 0.15f, 2.0f, 3.95f, 4f, 99f)) {
            assertEquals("pose at $t", trace.frameAt(t).camera, SceneRecognizer.poseAt(trace, t))
        }
    }
}
