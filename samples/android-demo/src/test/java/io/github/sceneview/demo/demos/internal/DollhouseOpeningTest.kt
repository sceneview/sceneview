package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.FIRST_FRAME_SCRIM_TIMEOUT_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DollhouseOpening] and [DollhouseFraming] (#4492): on a phone the dollhouse's 3D view showed two
 * or three frames from another camera before the framed one — the orbit camera's default pose,
 * inside the room's cloud, then a framing made for chrome that had not been measured yet.
 *
 * The frames here are played in `SceneView`'s order: the manipulator is updated, the frame is
 * drawn with the pose it holds, and only then `onFrame` runs.
 */
class DollhouseOpeningTest {

    private val fov = 46.4
    private val aspect = 0.46f
    private val bare = FramingInsets()
    private val chrome = FramingInsets(left = 0.04f, top = 0.14f, right = 0.04f, bottom = 0.22f)

    private val small = floatArrayOf(-1f, 0f, -1f, 1f, 2.3f, 1f)
    private val large = floatArrayOf(-3f, 0f, -4.5f, 3f, 2.6f, 4.5f)

    /** One view of one room: its camera, its framing, its opening, and the frames it drew. */
    private inner class View(bounds: FloatArray, var insets: FramingInsets, drift: Boolean = false) {
        val orbit = ArDebugOrbitCamera(drift = drift)
        val framing = DollhouseFraming(bounds, 40f, fov)
        val opening = DollhouseOpening()
        val drawn = mutableListOf<OrbitPose>()

        /** The frame on which the veil was lifted, counted from 1; 0 while it is down. */
        var shownOn = 0

        fun home() = ArDebugFraming.clamp(framing.home(orbit.home.azimuthDegrees, aspect, insets))

        /** The composition: runs before the first frame, and again when the chrome moves. */
        fun compose() = opening.aim(orbit, home())

        fun frame() {
            orbit.update(1f / 60f)
            drawn += orbit.pose
            if (opening.drawn(orbit, home())) shownOn = drawn.size
            opening.aim(orbit, home())
        }

        fun open() {
            while (!opening.shown) frame()
        }
    }

    @Test
    fun `the first frame is drawn with the room's framing, not the camera's default pose`() {
        val view = View(small, chrome)
        view.compose()
        view.frame()
        assertNotEquals(ArDebugFraming.DEFAULT_POSE, view.drawn.first())
        assertEquals(view.home(), view.drawn.first())
    }

    @Test
    fun `the veil lifts only after frames drawn with the framing`() {
        val view = View(small, chrome)
        view.compose()
        repeat(DollhouseOpening.STEADY_FRAMES - 1) {
            view.frame()
            assertFalse(view.opening.shown)
        }
        view.frame()
        assertTrue(view.opening.shown)
        assertEquals(DollhouseOpening.STEADY_FRAMES, view.shownOn)
        assertTrue(view.drawn.all { it == view.home() })
    }

    @Test
    fun `an orbit that does not drift lifts the veil on the third frame`() {
        // The dollhouse's orbit: `ArDebugOrbitCamera(drift = false)` in RoomDollhouseScreen.
        val view = View(small, chrome, drift = false)
        view.compose()
        repeat(DollhouseOpening.STEADY_FRAMES) { view.frame() }
        assertEquals(3, DollhouseOpening.STEADY_FRAMES)
        assertEquals(DollhouseOpening.STEADY_FRAMES, view.shownOn)
    }

    /**
     * The contract the opening rests on: its camera stands still while it follows. A drifting
     * orbit turns its own `home` every frame, so each frame is fitted for another heading, the
     * count starts again each time and [DollhouseOpening.drawn] never says shown. On the screen
     * the veil would then leave only by the scaffold's timeout, twelve seconds in, under a
     * "Still loading…" the room does not deserve.
     *
     * Whoever gives the dollhouse a drifting orbit has to change the opening with it — count
     * frames on the framing whatever its heading — and this test with it.
     */
    @Test
    fun `a drifting orbit never lifts the veil, so the dollhouse's orbit must not drift`() {
        val view = View(small, chrome, drift = true)
        view.compose()
        val untilTheScaffoldGivesUp = (FIRST_FRAME_SCRIM_TIMEOUT_MS * 60 / 1000).toInt()
        repeat(untilTheScaffoldGivesUp) { view.frame() }
        assertFalse(
            "A drifting orbit now lifts the veil: the opening learnt to wait on a moving " +
                "framing. Rewrite this contract for what it does now.",
            view.opening.shown,
        )
        assertEquals(0, view.shownOn)
        // Why: no two frames in a row were fitted for the same heading.
        assertTrue(view.drawn.zipWithNext().all { (a, b) -> a.azimuthDegrees != b.azimuthDegrees })
    }

    @Test
    fun `chrome measured after the first frames keeps the veil down until its framing is drawn`() {
        val view = View(small, bare)
        view.compose()
        view.frame()
        view.frame()
        // The bands land with the third frame's composition: the framing is another one.
        view.insets = chrome
        view.compose()
        val framed = view.home()
        assertNotEquals(view.drawn.last(), framed)
        view.frame()
        assertFalse("shown on a count that began under the other framing", view.opening.shown)
        view.open()
        // Every frame since the chrome moved, the one that lifted the veil included, is framed
        // for it: no ease from the first framing.
        assertTrue(view.drawn.drop(2).all { it == framed })
        assertEquals(2 + DollhouseOpening.STEADY_FRAMES, view.shownOn)
    }

    @Test
    fun `chrome that moves between a frame and its onFrame does not count that frame`() {
        val view = View(small, bare)
        view.compose()
        view.frame()
        // No composition in between: onFrame alone sees the new bands, after the frame was drawn.
        view.insets = chrome
        view.frame()
        view.frame()
        assertFalse(view.opening.shown)
        view.open()
        assertEquals(2 + DollhouseOpening.STEADY_FRAMES, view.shownOn)
        assertEquals(view.home(), view.drawn.last())
    }

    @Test
    fun `a frame drawn before the camera was aimed does not count`() {
        // What the view did before: aimed from onFrame only, so frame 1 is the default pose.
        val view = View(small, chrome)
        view.frame()
        assertEquals(ArDebugFraming.DEFAULT_POSE, view.drawn.first())
        view.open()
        assertEquals(1 + DollhouseOpening.STEADY_FRAMES, view.shownOn)
    }

    @Test
    fun `another room has an opening of its own, shown on its own framing`() {
        val first = View(small, chrome)
        first.compose()
        first.open()

        // The screen makes a camera and an opening per room; the chrome is already measured.
        val second = View(large, chrome)
        assertFalse(second.opening.shown)
        second.compose()
        second.open()
        val framed = second.home()
        assertTrue(second.drawn.all { it == framed })
        assertTrue("a larger room is seen from further", framed.distance > first.drawn.last().distance)
    }

    @Test
    fun `once shown, the camera eases to a framing that moves instead of jumping`() {
        val view = View(small, bare)
        view.compose()
        view.open()
        val before = view.drawn.last()
        view.insets = chrome
        view.compose()
        view.frame()
        assertNotEquals(before, view.drawn.last())
        assertNotEquals(view.home(), view.drawn.last())
    }

    @Test
    fun `a camera the user took is not put back, and does not hold the veil`() {
        val view = View(small, chrome)
        view.compose()
        view.orbit.grabBegin(10, 10, false)
        view.orbit.grabUpdate(160, 10)
        view.orbit.grabEnd()
        repeat(DollhouseOpening.STEADY_FRAMES) { view.frame() }
        assertTrue(view.opening.shown)
        assertFalse(view.orbit.following)
        assertTrue(view.drawn.none { it == view.home() })
    }

    @Test
    fun `a room at rest is fitted once, then only when the heading, the shape or the chrome change`() {
        val framing = DollhouseFraming(small, 40f, fov)
        val first = framing.home(30f, aspect, chrome)
        repeat(120) { assertSame(first, framing.home(30f, aspect, chrome)) }
        assertEquals(1, framing.fits)

        assertNotEquals(first, framing.home(45f, aspect, chrome))
        assertNotEquals(first, framing.home(45f, 2.17f, chrome))
        assertNotEquals(first, framing.home(45f, 2.17f, bare))
        assertEquals(4, framing.fits)
        // Each of them is the fit itself, not a pose kept from before.
        assertEquals(ArDebugFraming.fit(small, 30f, 40f, fov, aspect, chrome), framing.home(30f, aspect, chrome))
    }
}
