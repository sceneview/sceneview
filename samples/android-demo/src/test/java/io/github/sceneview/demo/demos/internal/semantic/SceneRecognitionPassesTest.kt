package io.github.sceneview.demo.demos.internal.semantic

import io.github.sceneview.demo.demos.internal.ScanBox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The doors and windows need no detector: a replay shows them at once, while the furniture
 * detector is still downloading — which may take minutes, or never end — and recognises the
 * furniture in a second pass once the detector is there.
 */
class SceneRecognitionPassesTest {
    private val scan = SyntheticRoomScan
    private val truth = TruthDetector(scan.frames, TruthDetector.ROOM)

    // The second pass walks the first [SceneRecognizer.MAX_FRAMES] of them: the first stop's turns.
    private val keyframes = scan.keyframes(step = 3)

    /** A door in the wall away from the synthetic room's furniture, as the openings finder sees it. */
    private val door = ScanBox(-1.0f, 1.9f, 0f, 0f, 0.9f, 0.12f, 2f).let { box ->
        WallOpening(SceneLabel.Door, box, box.length, box.height, box.baseY, 0.6f)
    }
    private val openings = WallOpenings { _, _ -> listOf(door) }

    private suspend fun recognize(recognizer: SceneRecognizer): SceneRecognition =
        recognizer.recognize(keyframes, scan.cloud, scan.objects, image = { truth.image(it.removePrefix("p").toInt()) })

    @Test
    fun `the openings come out before the detector is ready, the furniture once it is`() = runBlocking {
        val ready = CompletableDeferred<SceneDetector?>()
        val passes = Channel<SceneRecognition>(Channel.UNLIMITED)
        val recognizing = launch {
            recognizeInPasses(
                loadDetector = { ready.await() },
                recognize = ::recognize,
                onPass = { passes.trySend(it) },
                openings = openings,
            )
        }

        val first = withTimeout(TIMEOUT_MS) { passes.receive() }

        assertFalse("the openings waited for the detector", ready.isCompleted)
        assertEquals(listOf(SceneLabel.Door), first.items.map { it.label })
        assertTrue("a photo was read for the openings", first.frames.isEmpty())
        assertEquals(NoDetector.name, first.detector)
        assertNull("a second pass with no detector", passes.tryReceive().getOrNull())

        ready.complete(truth)
        val second = withTimeout(TIMEOUT_MS) { passes.receive() }
        recognizing.join()

        val labels = second.items.map { it.label }
        assertEquals("truth", second.detector)
        assertTrue("no keyframe walked with the detector", second.frames.isNotEmpty())
        assertTrue("no furniture once the detector is there: $labels", labels.any { !it.isOpening })
        assertTrue("the door went missing: $labels", SceneLabel.Door in labels)
        assertNull("a third pass", passes.tryReceive().getOrNull())
    }

    @Test
    fun `with no detector to be had, the openings are all there is`() = runBlocking {
        val passes = ArrayList<SceneRecognition>()

        recognizeInPasses(loadDetector = { null }, recognize = ::recognize, onPass = { passes += it }, openings = openings)

        assertEquals(1, passes.size)
        assertEquals(listOf(SceneLabel.Door), passes.single().items.map { it.label })
        assertEquals(NoDetector.name, passes.single().detector)
    }

    @Test
    fun `the detector is closed once the second pass is done`() = runBlocking {
        var closed = false
        val detector = object : SceneDetector by truth {
            override fun close() {
                closed = true
            }
        }
        val passes = ArrayList<SceneRecognition>()

        recognizeInPasses(loadDetector = { detector }, recognize = ::recognize, onPass = { passes += it }, openings = openings)

        assertEquals(listOf(NoDetector.name, "truth"), passes.map { it.detector })
        assertTrue("the detector was left open", closed)
    }

    private companion object {
        const val TIMEOUT_MS = 120_000L
    }
}
