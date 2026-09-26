package io.github.sceneview

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.lookAt
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM pins for the motion-continuity rules in `MotionContinuity.kt`: what `SceneView`'s
 * render loop, `NodeAnimationDelegate` and `SceneAutoCenterState` rely on so that nothing on
 * screen jumps between two frames when a camera manipulator is swapped, a frame arrives late, or
 * a second model re-centres the scene.
 */
class MotionContinuityTest {

    private val frame = 16_666_667L

    private fun pose(eye: Position, target: Position = Position(0f, 0f, 0f)): Transform =
        Transform(lookAt(eye = eye, target = target, up = Float3(0f, 1f, 0f)))

    private fun distance(a: Position, b: Position): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    // ── frameDeltaSeconds ────────────────────────────────────────────────────────────────────

    @Test
    fun noPreviousFrameIsOneNominalFrameNotTheTimeSinceZero() {
        // The first frame after an on-demand park: `intervalSeconds(null)` returned the whole
        // uptime here (1 h in this case) and the manipulator was updated by that much.
        val uptime = 3_600_000_000_000L
        assertEquals(NOMINAL_FRAME_SECONDS, frameDeltaSeconds(uptime, null), 1e-12)
        assertEquals(NOMINAL_FRAME_SECONDS, frameDeltaSeconds(uptime, 0L), 1e-12)
    }

    @Test
    fun aNormalFrameIsMeasuredExactly() {
        assertEquals(frame / 1e9, frameDeltaSeconds(10 * frame, 9 * frame), 1e-9)
    }

    @Test
    fun aStallIsCappedSoMotionResumesInsteadOfJumping() {
        val stalled = frameDeltaSeconds(1_000_000_000L + 800_000_000L, 1_000_000_000L)
        assertEquals(MAX_FRAME_DELTA_SECONDS, stalled, 1e-12)
    }

    @Test
    fun aTimestampGoingBackwardsAdvancesNothing() {
        assertEquals(0.0, frameDeltaSeconds(5 * frame, 6 * frame), 0.0)
    }

    // ── easeOutCubic ─────────────────────────────────────────────────────────────────────────

    @Test
    fun easeOutCubicIsClampedAndMonotonic() {
        assertEquals(0f, easeOutCubic(-1f), 0f)
        assertEquals(1f, easeOutCubic(2f), 0f)
        var previous = 0f
        for (i in 1..100) {
            val value = easeOutCubic(i / 100f)
            assertTrue("ease-out must never go backwards", value >= previous)
            previous = value
        }
        assertTrue("ease-out front-loads the motion", easeOutCubic(0.5f) > 0.5f)
    }

    // ── TransformBlend ───────────────────────────────────────────────────────────────────────

    @Test
    fun blendStartsExactlyOnThePoseOnScreenAndLandsOnTheLivePose() {
        val shown = pose(Position(0f, 0.4f, 2.75f))
        val live = pose(Position(1.5f, 1f, 4f))
        val blend = TransformBlend(from = shown, durationSeconds = 0.6f)

        val first = blend.sample(live)
        assertEquals(0f, distance(first.position, shown.position), 1e-4f)

        repeat(100) { blend.advance(1f / 60f) }
        assertTrue(blend.isFinished)
        assertEquals(0f, distance(blend.sample(live).position, live.position), 1e-5f)
    }

    @Test
    fun blendFollowsATargetThatKeepsMoving() {
        val blend = TransformBlend(from = pose(Position(0f, 0f, 3f)), durationSeconds = 0.6f)
        repeat(36) { blend.advance(1f / 60f) }
        // Past the end, the blend hands back whatever the target is now — not a captured copy.
        val movedAgain = pose(Position(-2f, 0.5f, 1f))
        assertEquals(0f, distance(blend.sample(movedAgain).position, movedAgain.position), 1e-5f)
    }

    // ── CameraSwapContinuity ─────────────────────────────────────────────────────────────────

    @Test
    fun theFirstManipulatorOfAFreshSceneIsNotBlended() {
        val continuity = CameraSwapContinuity()
        val live = pose(Position(0f, 0f, 3f))
        val out = continuity.resolve(
            manipulator = Any(),
            livePose = live,
            deltaSeconds = 1f / 60f,
            canBlend = false,
            shownPose = { error("nothing is on screen yet, the shown pose must not be read") }
        )
        assertEquals(live, out)
        assertFalse(continuity.isBlending)
    }

    @Test
    fun aSwappedManipulatorGlidesInsteadOfCutting() {
        val continuity = CameraSwapContinuity()
        val old = Any()
        val oldPose = pose(Position(0f, 0.4f, 2.75f))
        var shown = continuity.resolve(old, oldPose, 1f / 60f, canBlend = false) { oldPose }

        // The documented "rebuild once the model has loaded" swap: a new instance, far pose.
        val new = Any()
        val newPose = pose(Position(0f, 1f, 8f))
        var maxStep = 0f
        var frames = 0
        do {
            val previous = shown
            val shownNow = shown
            shown = continuity.resolve(new, newPose, 1f / 60f, canBlend = true) { shownNow }
            maxStep = maxOf(maxStep, distance(previous.position, shown.position))
            frames++
        } while (continuity.isBlending && frames < 1_000)

        val total = distance(oldPose.position, newPose.position)
        assertTrue(
            "the swap must be spread over frames, not cut (largest step $maxStep of $total)",
            maxStep < total * 0.2f
        )
        assertEquals(0f, distance(shown.position, newPose.position), 1e-4f)
        assertTrue("0.6 s at 60 fps", frames in 30..45)
    }

    @Test
    fun aSecondSwapMidGlideContinuesFromTheBlendedPose() {
        val continuity = CameraSwapContinuity()
        val a = pose(Position(0f, 0f, 2f))
        var shown = continuity.resolve(Any(), a, 1f / 60f, canBlend = false) { a }
        val b = pose(Position(0f, 0f, 6f))
        val second = Any()
        repeat(10) {
            val s = shown
            shown = continuity.resolve(second, b, 1f / 60f, canBlend = true) { s }
        }
        assertTrue(continuity.isBlending)
        val midGlide = shown

        val c = pose(Position(3f, 0f, 3f))
        val s = shown
        val afterRetarget = continuity.resolve(Any(), c, 1f / 60f, canBlend = true) { s }
        assertEquals(
            "a re-target starts from the pose on screen, not from either manipulator",
            0f, distance(afterRetarget.position, midGlide.position), 1e-4f
        )
    }

    @Test
    fun theSameManipulatorWithNoGlidePassesItsPoseThrough() {
        val continuity = CameraSwapContinuity()
        val manipulator = Any()
        val first = pose(Position(0f, 0f, 3f))
        continuity.resolve(manipulator, first, 1f / 60f, canBlend = false) { first }
        val orbited = pose(Position(2f, 0f, 2f))
        val out = continuity.resolve(manipulator, orbited, 1f / 60f, canBlend = true) {
            error("no swap, the shown pose must not be read")
        }
        assertEquals(orbited, out)
    }

    @Test
    fun aManipulatorArrivingAfterNoneGlidesFromTheShownCamera() {
        val continuity = CameraSwapContinuity()
        continuity.clearSource()
        val shown = pose(Position(0f, 0.4f, 2.75f))
        val out = continuity.resolve(
            Any(), pose(Position(0f, 0f, 9f)), 1f / 60f, canBlend = true
        ) { shown }
        assertEquals(0f, distance(out.position, shown.position), 1e-4f)
        assertTrue(continuity.isBlending)
    }

    // ── PositionEase ─────────────────────────────────────────────────────────────────────────

    @Test
    fun positionEaseGlidesAndLandsExactly() {
        val ease = PositionEase(durationSeconds = 0.4f)
        val from = Position(0f, 0f, 0f)
        val to = Position(-1f, 0f, 0f)
        ease.start(from, to)
        assertTrue(ease.isActive)

        val firstStep = ease.advance(1f / 60f)
        assertNotNull(firstStep)
        assertTrue("one frame covers only part of the way", firstStep!!.x > -0.5f && firstStep.x < 0f)

        var last = firstStep
        var guard = 0
        while (ease.isActive && guard++ < 1_000) last = ease.advance(1f / 60f)
        assertEquals(to, last)
        assertNull("no ease in flight → nothing to write", ease.advance(1f / 60f))
    }

    @Test
    fun positionEaseToWhereItAlreadyIsDoesNothing() {
        val ease = PositionEase()
        ease.start(Position(1f, 2f, 3f), Position(1f, 2f, 3f))
        assertFalse(ease.isActive)
        assertNull(ease.advance(1f / 60f))
    }

    @Test
    fun aStalledFrameCannotFinishTheEaseInOneStep() {
        val ease = PositionEase(durationSeconds = 0.4f)
        ease.start(Position(0f, 0f, 0f), Position(0f, 0f, -2f))
        // A capped stall step, as the loop hands it.
        ease.advance(frameDeltaSeconds(900_000_000L, 100_000_000L).toFloat())
        assertTrue("0.05 s of a 0.4 s ease", ease.isActive)
    }
}
