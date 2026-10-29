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
    fun aLoopChosenOnThePlayerSurvivesARebuiltTimeline() {
        val box = Aabb(Position(0f, 0.5f, 0f), Position(0.5f, 0.5f, 0.5f))
        fun orbit() = CameraPosePath.orbit(box, 45.0, 1.0)

        // Turned off in the constructor, then the demo rebuilds the same orbit (bounds or aspect
        // changed): the player keeps its own choice.
        val fromConstructor = CameraPathPlayer(orbit(), loop = false)
        fromConstructor.timeline = orbit()
        assertFalse(fromConstructor.loop)

        // Turned off on the player afterwards: same.
        val fromSetter = CameraPathPlayer(orbit())
        fromSetter.loop = false
        fromSetter.timeline = orbit()
        assertFalse(fromSetter.loop)

        // A timeline that really changes the loop mode still sets it.
        fromSetter.timeline = path
        assertFalse(fromSetter.loop)
        fromSetter.timeline = orbit()
        assertTrue(fromSetter.loop)
    }

    @Test
    fun aShorterTimelineBringsThePlayheadIntoRangeAtOnce() {
        val box = Aabb(Position(0f, 0.5f, 0f), Position(0.5f, 0.5f, 0.5f))
        val long = CameraPosePath.orbit(box, 45.0, 1.0, durationSeconds = 20f)
        val short = CameraPosePath.orbit(box, 45.0, 1.0, durationSeconds = 10f)

        // Looping: 15 s into a 20 s orbit, switched to a 10 s one, goes on from 5 s.
        val looping = CameraPathPlayer(long).apply { seekTo(15f) }
        looping.timeline = short
        assertEquals(5f, looping.seconds, 1e-4f)
        assertEquals(0.5f, looping.progress, 1e-4f)
        assertTrue(length(short.poseAt(5f).position - looping.pose.position) < 1e-3f)

        // Not looping: clamped to the new end, and finished there.
        val once = CameraPathPlayer(long, loop = false).apply { seekTo(15f) }
        once.timeline = short
        assertFalse(once.loop)
        assertEquals(10f, once.seconds, 0f)
        assertTrue(once.isFinished)
    }

    /** Distance the camera moves on each of [frames] 60 fps frames. */
    private fun steps(player: CameraPathPlayer, frames: Int): FloatArray {
        var previous = player.pose.position
        return FloatArray(frames) {
            val position = player.advance(FRAME).position
            length(position - previous).also { previous = position }
        }
    }

    private fun assertNoJump(steps: FloatArray) {
        val mean = steps.average().toFloat()
        val largest = steps.max()
        assertTrue("largest step $largest against a mean of $mean", largest < 2f * mean)
    }

    @Test
    fun aWeekLongTourStepsAsOnItsFirstLap() {
        // 1.05 turns a lap: the turn of the laps played never comes back to a whole number
        // within a few laps. A float playhead froze and jumped after days (0 to 0.197 a frame).
        fun tour() = CameraPath.tour(CameraShot(Position(0f, 0.5f, 0f), 0f, 20f, 3f), durationSeconds = 25f, turns = 1.05f)
        val first = steps(playing(tour()), 3000)
        val player = playing(tour())
        repeat(7 * 24) { player.advance(3600f) } // a week, a whole number of 25 s laps
        assertEquals(0f, player.seconds, 0f)
        val week = steps(player, 3000) // two laps, a seam included
        for (i in first.indices) {
            assertEquals("frame $i", first[i], week[i], 0.05f * first[i])
        }
    }

    @Test
    fun aFractionalOrbitDoesNotJumpAtTheSeam() {
        val orbit = CameraPosePath.orbit(Aabb(Position(0f, 0.5f, 0f), Position(0.5f, 0.5f, 0.5f)), 45.0, 1.0, durationSeconds = 6f, turns = 1.5f)
        val player = playing(orbit)
        assertTrue(player.loop)
        assertNoJump(steps(player, (4 * 6f / FRAME).toInt()))
    }

    @Test
    fun aPlayerLoopForcedOnANonLoopingFractionalOrbitDoesNotJumpAtTheSeam() {
        // The player's loop, not the timeline's, decides whether the laps' turn applies: sampled
        // without it, lap two restarted from the orbit's start, 180° back, at every seam.
        val orbit = CameraPosePath.orbit(
            Aabb(Position(0f, 0.5f, 0f), Position(0.5f, 0.5f, 0.5f)), 45.0, 1.0,
            durationSeconds = 6f, turns = 1.5f, loop = false,
        )
        val player = playing(orbit, loop = true)
        assertNoJump(steps(player, (4 * 6f / FRAME).toInt()))
        assertTrue(player.isPlaying)
    }

    @Test
    fun seekingToTheEndShowsTheEndOnEveryLap() {
        val player = playing(oneAndAHalfTurnTour())
        for (lap in 0..2) {
            player.seekTo(6f)
            assertEquals("lap $lap", 6f, player.seconds, 0f)
            assertEquals("lap $lap", 1f, player.progress, 0f)
            val end = player.pose.position
            player.advance(FRAME) // into the next lap, without a jump
            assertTrue("lap $lap", length(player.pose.position - end) < 0.2f)
        }
    }

    @Test
    fun turningLoopOffKeepsTheLap() {
        val player = playing(oneAndAHalfTurnTour())
        player.advance(7f) // one second into the second lap, half a turn on
        val before = player.pose.position
        player.loop = false
        assertEquals(1f, player.seconds, 1e-4f)
        assertTrue(length(player.pose.position - before) < 1e-4f)
        player.advance(10f)
        assertTrue(player.isFinished)
        assertTrue(length(player.pose.position - oneAndAHalfTurnTour().poseAt(12f).position) < 1e-3f)
    }

    @Test
    fun aWeekLongOrbitStepsAsOnItsFirstLap() {
        fun orbit() = CameraPosePath.orbit(Aabb(Position(0f, 0.5f, 0f), Position(0.5f, 0.5f, 0.5f)), 45.0, 1.0, durationSeconds = 25f, turns = 1.05f)
        val first = steps(playing(orbit()), 3000)
        val player = playing(orbit())
        repeat(7 * 24) { player.advance(3600f) } // a week, a whole number of 25 s laps
        assertEquals(0f, player.seconds, 0f)
        val week = steps(player, 3000) // two laps, a seam included
        for (i in first.indices) {
            assertEquals("frame $i", first[i], week[i], 0.05f * first[i])
        }
    }

    @Test
    fun playingAFinishedPlayerStartsFromTheFirstLap() {
        for (timeline in listOf<CameraTimeline>(oneAndAHalfTurnTour(), oneAndAHalfTurnOrbit())) {
            val player = playing(timeline)
            player.advance(7f) // one second into the second lap, half a turn on
            player.loop = false
            player.advance(10f)
            assertTrue(player.isFinished)
            player.play()
            assertTrue(player.isPlaying)
            assertEquals(0f, player.seconds, 0f)
            assertTrue(length(player.pose.position - timeline.poseAt(0f).position) < 1e-4f)
            player.advance(1f)
            assertTrue(length(player.pose.position - timeline.poseAt(1f).position) < 1e-4f)
        }
    }

    @Test
    fun restartGoesBackToTheFirstLapWhileSeekingKeepsTheLap() {
        for (timeline in listOf<CameraTimeline>(oneAndAHalfTurnTour(), oneAndAHalfTurnOrbit())) {
            val player = playing(timeline)
            player.advance(7f) // lap two, half a turn on
            player.pause()
            player.seekTo(0f)
            // seekTo stays within the lap: the start of lap two, half a turn on from the start.
            val halfATurnOn = length(player.pose.position - timeline.poseAt(0f).position)
            assertTrue(halfATurnOn > 1f)
            player.restart()
            assertTrue(player.isPlaying)
            assertEquals(0f, player.seconds, 0f)
            assertTrue(length(player.pose.position - timeline.poseAt(0f).position) < 1e-4f)
            player.advance(1f)
            assertTrue(length(player.pose.position - timeline.poseAt(1f).position) < 1e-4f)
        }
    }

    @Test
    fun aHandMadeLoopShowsItsEndAtTheEndOfEveryLap() {
        // Ends farther than it starts: not its start turned by the lap, so it jumps at the seam,
        // after the end. Seeking to the end shows the last keyframe, turned by the laps played.
        val target = Position(0f, 0.5f, 0f)
        val handMade = CameraPath(
            keyframes = listOf(
                CameraKeyframe(CameraShot(target, 0f, 20f, 3f), 0f),
                CameraKeyframe(CameraShot(target, 90f, 20f, 4f), 2f),
                CameraKeyframe(CameraShot(target, 180f, 20f, 5f), 4f),
            ),
            loop = true,
        )
        assertTrue(length(handMade.poseAt(4f).position - CameraShot(target, 180f, 20f, 5f).toPose().position) < 1e-4f)
        val player = playing(handMade)
        for (lap in 0..2) {
            player.seekTo(4f)
            val end = CameraShot(target, 180f + lap * 180f, 20f, 5f).toPose().position
            assertTrue("lap $lap", length(player.pose.position - end) < 1e-4f)
            player.advance(1f) // into the next lap
        }
    }

    private fun oneAndAHalfTurnOrbit() =
        CameraPosePath.orbit(Aabb(Position(0f, 0.5f, 0f), Position(0.5f, 0.5f, 0.5f)), 45.0, 1.0, durationSeconds = 6f, turns = 1.5f)

    private fun oneAndAHalfTurnTour() =
        CameraPath.tour(CameraShot(Position(0f, 0.5f, 0f), 0f, 20f, 3f), durationSeconds = 6f, turns = 1.5f)

    @Test
    fun aTourOfOneAndAHalfTurnsDoesNotJumpAtTheSeam() {
        // Each lap of this tour starts half a turn on from the last. Sampled at the wrapped
        // playhead, the camera jumped 180° (5.88 units) back at every seam.
        val tour = CameraPath.tour(CameraShot(Position(0f, 0.5f, 0f), 0f, 20f, 3f), durationSeconds = 6f, turns = 1.5f)
        val player = playing(tour)
        assertTrue(player.loop)
        val step = 1f / 60f
        var previous = player.pose.position
        val steps = ArrayList<Float>()
        repeat((4 * 6f / step).toInt()) {
            val position = player.advance(step).position
            steps += length(position - previous)
            previous = position
            assertTrue(player.seconds in 0f..player.durationSeconds)
        }
        val mean = steps.average().toFloat()
        val largest = steps.max()
        assertTrue("largest step $largest against a mean of $mean", largest < 2f * mean)
    }

    @Test
    fun aRebuiltTourKeepsItsLap() {
        fun tour() = CameraPath.tour(CameraShot(Position(0f, 0.5f, 0f), 0f, 20f, 3f), durationSeconds = 6f, turns = 1.5f)
        val player = playing(tour())
        player.advance(7f) // one second into the second lap, half a turn on
        val before = player.pose.position
        player.timeline = tour()
        assertEquals(1f, player.seconds, 1e-4f)
        assertTrue(length(player.pose.position - before) < 1e-3f)
    }

    @Test
    fun aZeroLengthTimelineStopsPlaybackUntilPlayIsCalled() {
        val player = playing()
        player.advance(1f)
        player.timeline = CameraPosePath(listOf(CameraPoseKeyframe(CameraPose(Position(0f, 1f, 4f)), 0f)))
        assertEquals(0f, player.durationSeconds, 0f)
        assertEquals(0f, player.seconds, 0f)
        assertEquals(0f, player.progress, 0f)
        assertTrue(player.isPlaying)
        val pose = player.advance(0.1f)
        assertFalse(player.isPlaying)
        assertEquals(Position(0f, 1f, 4f), pose.position)

        player.timeline = path
        assertFalse(player.isPlaying)
        assertEquals(0f, player.seconds, 0f)
        player.play()
        player.advance(0.5f)
        assertEquals(0.5f, player.seconds, 1e-5f)
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

private const val FRAME = 1f / 60f
