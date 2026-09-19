package io.github.sceneview.demo

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.lookAt
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The idle orbit gets the camera back **on the pose it shows** (#3642, #3640).
 *
 * [HeroOrbitCameraManipulator] used to drop its user-control manipulator once the user had been
 * idle for three seconds, which put the camera back on the authored pose within one frame — on
 * every demo sharing it. These tests drive the manipulator with a hand-cranked clock and a
 * stand-in for the stock user-control manipulator (the real one owns a native Filament object),
 * and compare the frame before each hand-over with the frame after it.
 */
class HeroOrbitResumeTest {

    /** What the user drives: an eye looking at a pivot, moved by the test as a drag would. */
    private class FakeUserControl(var eye: Position, var pivot: Position) :
        CameraGestureDetector.CameraManipulator {
        val calls = mutableListOf<String>()

        override fun setViewport(width: Int, height: Int) = Unit
        override fun getTransform(): Transform = lookAt(eye, pivot, Float3(0f, 1f, 0f))
        override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
            calls += "grabBegin"
        }

        override fun grabUpdate(x: Int, y: Int) = Unit
        override fun grabEnd() {
            calls += "grabEnd"
        }

        override fun scrollBegin(x: Int, y: Int, separation: Float) = Unit
        override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) = Unit
        override fun scrollEnd() = Unit
        override fun update(deltaTime: Float) = Unit
        override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) {
            calls += "doubleTapZoom($x,$y,$zoomIn)"
        }
    }

    // Never 0: inside the manipulator a zero timestamp means "no gesture has ended yet".
    private var now = 1_000_000_000L
    private var yaw = AUTHORED_YAW
    private var userControl: FakeUserControl? = null

    private fun manipulator(
        resume: HeroOrbitResume = HeroOrbitResume.KeepUserFraming,
        resumeAfterMillis: Long = RESUME_AFTER_MILLIS,
    ) = HeroOrbitCameraManipulator(
        yawProvider = { yaw },
        radius = RADIUS,
        yHeight = Y_HEIGHT,
        target = ORIGIN,
        resumeAfterMillis = resumeAfterMillis,
        resume = resume,
        resumeBlendMillis = BLEND_MILLIS,
        nanoTime = { now },
        userControlFactory = { eye, pivot -> FakeUserControl(eye, pivot).also { userControl = it } },
    )

    /** One frame, [millis] later. */
    private fun HeroOrbitCameraManipulator.advance(millis: Long) {
        now += millis * 1_000_000L
        update(millis / 1_000f)
    }

    /** A drag that leaves the camera at [eye], looking at [pivot]. */
    private fun HeroOrbitCameraManipulator.dragTo(eye: Position, pivot: Position = ORIGIN) {
        grabBegin(500, 900, false)
        val control = checkNotNull(userControl)
        control.eye = eye
        control.pivot = pivot
        grabUpdate(560, 940)
        grabEnd()
    }

    private fun authoredTransform(): Transform = manipulator().getTransform()

    /** How long the idle hand-back from [USER_EYE] really lasts when [BLEND_MILLIS] is asked for. */
    private fun pacedBlendMillis(): Long = resumeBlendMillisFor(
        orbitFramingOffset(
            user = orbitFramingOf(USER_EYE, ORIGIN),
            authored = orbitFramingOf(authoredTransform().position, ORIGIN),
        ),
        BLEND_MILLIS,
    )

    private fun distance(a: Position, b: Position): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun assertSamePicture(expected: Transform, actual: Transform, tolerance: Float = 1e-4f) {
        assertEquals("eye x", expected.position.x, actual.position.x, tolerance)
        assertEquals("eye y", expected.position.y, actual.position.y, tolerance)
        assertEquals("eye z", expected.position.z, actual.position.z, tolerance)
        // The camera looks down its own -Z: same Z column, same view direction.
        assertEquals("view x", expected.z.x, actual.z.x, tolerance)
        assertEquals("view y", expected.z.y, actual.z.y, tolerance)
        assertEquals("view z", expected.z.z, actual.z.z, tolerance)
    }

    // ── Taking the camera ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the first drag starts from the pose the idle orbit shows`() {
        val manipulator = manipulator()
        val idle = manipulator.getTransform()

        manipulator.grabBegin(500, 900, false)

        assertTrue(manipulator.isPaused())
        assertSamePicture(idle, manipulator.getTransform())
    }

    @Test
    fun `a tap on the idle orbit does not take the camera`() {
        val manipulator = manipulator()

        // All the detector sends for a tap is the closing grabEnd.
        manipulator.grabEnd()
        manipulator.advance(16)

        assertFalse(manipulator.isPaused())
        assertNull(userControl)
    }

    // ── KeepUserFraming ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the frame after the idle resume shows the frame before it`() {
        val manipulator = manipulator()
        manipulator.dragTo(USER_EYE)
        manipulator.advance(RESUME_AFTER_MILLIS - 10)
        assertTrue("still the user's camera just before the deadline", manipulator.isPaused())
        val before = manipulator.getTransform()

        manipulator.advance(20)

        assertFalse("the idle orbit has the camera back", manipulator.isPaused())
        assertSamePicture(before, manipulator.getTransform())
    }

    @Test
    fun `the turntable carries on from the user's azimuth, at the user's distance and height`() {
        val manipulator = manipulator()
        manipulator.dragTo(USER_EYE)
        manipulator.advance(RESUME_AFTER_MILLIS + 10)

        // A quarter turn later — and long after any ease would have run out.
        yaw += 90f
        manipulator.advance(60_000)

        // Yaw swings around +Y from +Z: a quarter turn takes (x, y, z) to (z, y, -x).
        val eye = manipulator.getTransform().position
        assertEquals(USER_EYE.z, eye.x, 1e-4f)
        assertEquals(USER_EYE.y, eye.y, 1e-4f)
        assertEquals(-USER_EYE.x, eye.z, 1e-4f)
    }

    @Test
    fun `a panned camera is handed back without moving either`() {
        val manipulator = manipulator()
        manipulator.dragTo(eye = Position(-0.7f, 1.6f, 0.9f), pivot = Position(0.5f, 0.2f, 0f))
        manipulator.advance(RESUME_AFTER_MILLIS - 10)
        val before = manipulator.getTransform()

        manipulator.advance(20)

        assertFalse(manipulator.isPaused())
        assertSamePicture(before, manipulator.getTransform())
    }

    @Test
    fun `a resume delay of zero leaves the camera with the user for good`() {
        val manipulator = manipulator(resumeAfterMillis = 0L)
        manipulator.dragTo(USER_EYE)

        manipulator.advance(60_000)

        assertTrue(manipulator.isPaused())
    }

    // ── ReturnToAuthoredPath ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the ease back starts on the user's pose, travels, and lands exactly on the authored one`() {
        val manipulator = manipulator(HeroOrbitResume.ReturnToAuthoredPath)
        manipulator.dragTo(USER_EYE)
        manipulator.advance(RESUME_AFTER_MILLIS - 10)
        val before = manipulator.getTransform()
        val authored = authoredTransform()
        val wayHome = distance(USER_EYE, authored.position)

        manipulator.advance(20)
        assertSamePicture(before, manipulator.getTransform())

        // The idle hand-back is paced by the way home (#3698): this far, longer than it was asked.
        val paced = pacedBlendMillis()
        assertTrue("a quarter turn away is not whipped home in $BLEND_MILLIS ms: $paced", paced > BLEND_MILLIS)

        manipulator.advance(paced / 2)
        val halfWay = distance(manipulator.getTransform().position, authored.position)
        assertTrue("half way home is closer than the start: $halfWay of $wayHome", halfWay < wayHome)
        assertTrue("and not home yet: $halfWay", halfWay > 0.05f)

        manipulator.advance(paced - paced / 2 + 1)
        assertEquals("landed: bit for bit the authored pose", authored, manipulator.getTransform())
    }

    @Test
    fun `a camera nobody was drawing from comes back on the authored pose`() {
        val manipulator = manipulator(HeroOrbitResume.ReturnToAuthoredPath)
        manipulator.dragTo(USER_EYE)

        // Swapped out for another manipulator, or the app in the background: no frame at all.
        now += 60_000L * 1_000_000L

        assertEquals(authoredTransform(), manipulator.getTransform())
        assertFalse(manipulator.isPaused())
    }

    @Test
    fun `a drag in the middle of the ease back starts from the pose on screen`() {
        val manipulator = manipulator(HeroOrbitResume.ReturnToAuthoredPath)
        manipulator.dragTo(USER_EYE)
        manipulator.advance(RESUME_AFTER_MILLIS + 10)
        manipulator.advance(BLEND_MILLIS / 3)
        val midEase = manipulator.getTransform()
        val firstControl = userControl

        manipulator.grabBegin(500, 900, false)

        assertTrue("a fresh user-control manipulator, built for this pose", userControl !== firstControl)
        assertSamePicture(midEase, manipulator.getTransform())
    }

    // ── resumeAuto: a demo starting a camera animation of its own ────────────────────────────────

    @Test
    fun `resumeAuto eases the user's framing away over the caller's own duration`() {
        val manipulator = manipulator(HeroOrbitResume.ReturnToAuthoredPath)
        manipulator.dragTo(USER_EYE)
        val before = manipulator.getTransform()

        manipulator.resumeAuto(blendMillis = 500L)

        assertFalse("the demo's providers drive the camera again", manipulator.isPaused())
        assertSamePicture(before, manipulator.getTransform())
        manipulator.advance(501)
        assertEquals(authoredTransform(), manipulator.getTransform())
    }

    @Test
    fun `resumeAuto with no duration cuts, as QA mode's instant flights need`() {
        val manipulator = manipulator()
        manipulator.dragTo(USER_EYE)

        manipulator.resumeAuto(blendMillis = 0L)

        assertEquals(authoredTransform(), manipulator.getTransform())
    }

    @Test
    fun `resumeAuto does not restart an ease that is already under way`() {
        val manipulator = manipulator(HeroOrbitResume.ReturnToAuthoredPath)
        manipulator.dragTo(USER_EYE)
        manipulator.advance(RESUME_AFTER_MILLIS + 10)
        val paced = pacedBlendMillis()
        manipulator.advance(paced / 2)
        val halfWay = manipulator.getTransform()

        manipulator.resumeAuto(blendMillis = 5_000L)

        assertSamePicture(halfWay, manipulator.getTransform())
        manipulator.advance(paced - paced / 2 + 1)
        assertEquals("home on the original schedule", authoredTransform(), manipulator.getTransform())
    }

    @Test
    fun `resumeAuto on an untouched camera changes nothing`() {
        val manipulator = manipulator()
        val idle = manipulator.getTransform()

        manipulator.resumeAuto()

        assertEquals(idle, manipulator.getTransform())
    }

    // ── Double-tap to zoom (#3641) ───────────────────────────────────────────────────────────────

    @Test
    fun `a double-tap zooms through the user-control manipulator, from the pose on screen`() {
        val manipulator = manipulator()
        val idle = manipulator.getTransform()

        manipulator.doubleTapZoom(540, 1_200, true)

        assertEquals(listOf("doubleTapZoom(540,1200,true)"), checkNotNull(userControl).calls)
        assertSamePicture(idle, manipulator.getTransform())
    }

    @Test
    fun `the idle orbit resumes after a double-tap like after any other gesture`() {
        val manipulator = manipulator()
        manipulator.doubleTapZoom(540, 1_200, true)
        // The second tap's finger lifting.
        manipulator.grabEnd()

        manipulator.advance(RESUME_AFTER_MILLIS + 10)

        assertFalse(manipulator.isPaused())
    }

    // ── Keeping the render loop alive across the countdown (#3108) ───────────────────────────────

    @Test
    fun `the countdown keeps the frame loop awake until the hand-back`() {
        val manipulator = manipulator()
        assertFalse("nothing pending before the first gesture", manipulator.isFrameActive)

        manipulator.dragTo(USER_EYE)

        // Under OnDemand the loop parks ~30 frames after the camera stops moving — half a second,
        // a sixth of the way to the deadline. `isFrameActive` is the only thing holding it open.
        assertTrue("the countdown has to hold the loop open", manipulator.isFrameActive)
        manipulator.advance(RESUME_AFTER_MILLIS / 2)
        assertTrue("still waiting, half-way through", manipulator.isFrameActive)

        manipulator.advance(RESUME_AFTER_MILLIS / 2 + 10)

        assertFalse("the hand-back happened", manipulator.isPaused())
        assertFalse("and nothing is pending any more, so the scene may park", manipulator.isFrameActive)
    }

    @Test
    fun `the ease home keeps the frame loop awake, then releases it`() {
        val manipulator = manipulator(resume = HeroOrbitResume.ReturnToAuthoredPath)
        manipulator.dragTo(USER_EYE)
        val paced = pacedBlendMillis()

        manipulator.advance(RESUME_AFTER_MILLIS + 10)

        assertFalse(manipulator.isPaused())
        assertTrue("the ease home is still running", manipulator.isFrameActive)

        // `over()` clears the ease when it lands, and it is only called from getTransform().
        manipulator.advance(paced + 10)
        manipulator.getTransform()

        assertFalse("landed on the authored path — park allowed", manipulator.isFrameActive)
    }

    @Test
    fun `a disabled resume parks instead of waiting forever`() {
        val manipulator = manipulator(resumeAfterMillis = 0L)

        manipulator.dragTo(USER_EYE)
        manipulator.advance(RESUME_AFTER_MILLIS + 10)

        // The user keeps the camera for good here, so there is no countdown to stay awake for.
        assertTrue(manipulator.isPaused())
        assertFalse("no resume is coming — holding the loop open would burn frames for nothing",
            manipulator.isFrameActive)
    }

    private companion object {
        const val RADIUS = 2f
        const val Y_HEIGHT = 0.5f
        const val AUTHORED_YAW = 30f
        const val RESUME_AFTER_MILLIS = 3_000L
        const val BLEND_MILLIS = 1_200L
        val ORIGIN = Position(0f, 0f, 0f)

        /** Round the other side, higher, and a touch further out than the authored pose. */
        val USER_EYE = Position(-1.2f, 1.4f, 0.9f)
    }
}
