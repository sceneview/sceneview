@file:OptIn(ExperimentalSceneViewApi::class)

package io.github.sceneview.camera

import androidx.compose.runtime.snapshots.Snapshot
import dev.romainguy.kotlin.math.length
import io.github.sceneview.Aabb
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CameraPathPlayer]: frame-rate independence, play / pause / seek, loop and the end of a move. */
class CameraPathPlayerTest {

    private val path = CameraPosePath(
        keyframes = listOf(
            CameraPoseKeyframe(CameraPose.lookingAt(Position(0f, 1f, 4f), Position()), 0f),
            CameraPoseKeyframe(CameraPose.lookingAt(Position(3f, 2f, 1f), Position()), 1f),
            CameraPoseKeyframe(CameraPose.lookingAt(Position(-1f, 1f, -3f), Position()), 3f),
        ),
    )

    private fun playing(timeline: CameraTimeline = path, loop: Boolean = timeline.loop) =
        CameraPathPlayer(timeline, loop).apply { play() }

    @Test
    fun theSameElapsedTimeGivesTheSamePoseWhateverTheFrameRate() {
        val at30 = playing().apply { repeat(60) { advance(1f / 30f) } }
        val at120 = playing().apply { repeat(240) { advance(1f / 120f) } }
        val jittery = playing().apply {
            val steps = floatArrayOf(0.004f, 0.031f, 0.017f, 0.05f, 0.008f)
            var total = 0.0
            var i = 0
            while (total + steps[i % steps.size] < 2.0) {
                advance(steps[i % steps.size])
                total += steps[i % steps.size]
                i++
            }
            advance((2.0 - total).toFloat())
        }
        val expected = path.poseAt(2f)
        for (player in listOf(at30, at120, jittery)) {
            assertEquals(2f, player.seconds, 1e-5f)
            assertTrue(length(player.pose.position - expected.position) < 1e-4f)
        }
    }

    @Test
    fun advanceFrameUsesTheTimeBetweenFrames() {
        val player = playing()
        player.advanceFrame(1_000_000_000L)
        assertEquals("the first frame only records its time", 0f, player.seconds, 0f)
        player.advanceFrame(1_500_000_000L)
        assertEquals(0.5f, player.seconds, 1e-6f)
        player.pause()
        player.advanceFrame(9_000_000_000L)
        assertEquals("paused", 0.5f, player.seconds, 1e-6f)
        player.play()
        player.advanceFrame(10_000_000_000L)
        assertEquals("the pause does not count", 0.5f, player.seconds, 1e-6f)
        player.advanceFrame(10_250_000_000L)
        assertEquals(0.75f, player.seconds, 1e-6f)
    }

    @Test
    fun aMoveThatDoesNotLoopStopsAtTheEndAndPlayRestartsIt() {
        val player = playing()
        player.advance(10f)
        assertEquals(3f, player.seconds, 0f)
        assertFalse(player.isPlaying)
        assertTrue(player.isFinished)
        assertEquals(path.end, player.pose)
        player.play()
        assertEquals(0f, player.seconds, 0f)
        assertTrue(player.isPlaying)
    }

    @Test
    fun aLoopingPlayerWraps() {
        val player = playing(loop = true)
        player.advance(7.5f)
        assertEquals(1.5f, player.seconds, 1e-5f)
        assertTrue(player.isPlaying)
        assertFalse(player.isFinished)
    }

    @Test
    fun seekClampsAndDoesNotChangePlayback() {
        val player = CameraPathPlayer(path)
        player.seekTo(1.25f)
        assertEquals(1.25f, player.seconds, 0f)
        assertFalse(player.isPlaying)
        assertEquals(path.poseAt(1.25f), player.pose)
        player.seekTo(-4f)
        assertEquals(0f, player.seconds, 0f)
        player.seekTo(99f)
        assertEquals(3f, player.seconds, 0f)
        player.seekTo(Float.NaN)
        assertEquals(3f, player.seconds, 0f)
        assertEquals(1f, player.progress, 0f)
        player.advance(1f)
        assertEquals("not playing", 3f, player.seconds, 0f)
    }

    @Test
    fun negativeSpeedPlaysBackwardToTheStart() {
        val player = CameraPathPlayer(path).apply {
            seekTo(2f)
            speed = -1f
            play()
        }
        player.advance(0.5f)
        assertEquals(1.5f, player.seconds, 1e-6f)
        player.advance(5f)
        assertEquals(0f, player.seconds, 0f)
        assertFalse(player.isPlaying)
    }

    @Test
    fun speedZeroHoldsThePlayheadWithoutFinishing() {
        val player = playing().apply { speed = 0f }
        player.advance(0.5f)
        assertEquals(0f, player.seconds, 0f)
        assertTrue("still playing at speed 0", player.isPlaying)
        player.seekTo(1f)
        player.advance(0.5f)
        assertTrue(player.isPlaying)
        player.speed = 1f
        player.advance(0.5f)
        assertEquals(1.5f, player.seconds, 1e-6f)
    }

    @Test
    fun aNonFiniteSpeedIsRejected() {
        val player = playing()
        for (bad in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { player.speed = bad }
        }
        assertEquals(1f, player.speed, 0f)
    }

    @Test
    fun loopFollowsANewTimeline() {
        val player = CameraPathPlayer(path)
        assertFalse(player.loop)
        val orbit = CameraPosePath.orbit(Aabb(Position(0f, 0.5f, 0f), Position(0.5f, 0.5f, 0.5f)), 45.0, 1.0)
        player.timeline = orbit
        assertTrue(player.loop)
        assertEquals(orbit.durationSeconds, player.durationSeconds, 0f)
        player.timeline = path
        assertFalse(player.loop)
    }

    @Test
    fun anEquivalentNewTimelineInvalidatesNothing() {
        fun build() = CameraPosePath.orbit(Aabb(Position(0f, 0.5f, 0f), Position(0.5f, 0.5f, 0.5f)), 45.0, 1.0)
        val player = CameraPathPlayer(build())
        // What a timeline bar reads.
        val reads = mutableSetOf<Any>()
        Snapshot.observe(readObserver = { reads.add(it) }) {
            player.durationSeconds
            player.loop
            player.progress
            player.isPlaying
        }
        assertTrue(reads.isNotEmpty())
        val changed = mutableSetOf<Any>()
        val handle = Snapshot.registerApplyObserver { states, _ -> changed.addAll(states) }
        try {
            Snapshot.sendApplyNotifications()
            changed.clear()
            // A composition that builds its path in the body hands the player a new object
            // every time. Nothing a reader observed may change, or it would recompose forever.
            val rebuilt = build()
            player.timeline = rebuilt
            Snapshot.sendApplyNotifications()
            assertSame(rebuilt, player.timeline)
            assertTrue("reads invalidated: ${changed.intersect(reads)}", changed.intersect(reads).isEmpty())
            // A real change still reaches the readers.
            player.timeline = CameraPosePath.orbit(
                Aabb(Position(0f, 0.5f, 0f), Position(0.5f, 0.5f, 0.5f)), 45.0, 1.0, durationSeconds = 20f,
            )
            Snapshot.sendApplyNotifications()
            assertTrue(changed.intersect(reads).isNotEmpty())
        } finally {
            handle.dispose()
        }
    }

    @Test
    fun badFrameTimesAreIgnored() {
        val player = playing()
        player.advance(0.5f)
        for (delta in floatArrayOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY)) player.advance(delta)
        assertEquals(0.5f, player.seconds, 0f)
    }
}
