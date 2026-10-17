package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ArDebugFraming.reveal] (#4505): a picked object's box sat partly under the replay's Camera
 * card in the 3D view, and under the stats card in the Map view. Where each corner is drawn is
 * asked of [scanCamera] — the pinhole the view draws and taps through, stage lift included — not
 * of the reveal's own algebra, so the two can disagree.
 */
class ArDebugFramingRevealTest {

    @Test
    fun `a box drawn clear of the chrome leaves the camera where it is`() {
        val pose = OrbitPose(Position(0f, 0.4f, 0f), azimuthDegrees = 35f, elevationDegrees = 32f, distance = 6f)
        val box = ScanBox(0f, 0f, baseY = 0f, yaw = 0.4f, length = 0.9f, depth = 0.5f, height = 0.7f)
        assertTrue("clear to begin with", isClear(pose, box, CHROME))
        assertSame(pose, reveal(pose, box, listOf(CHROME)))
    }

    @Test
    fun `a box under the map's stats card is slid out from under it, at the same height over the room`() {
        val pose = OrbitPose(Position(0f, 0f, 0f), 0f, ArDebugFraming.MAP_ELEVATION, distance = 9f)
        // The far end of the room: drawn at the top of a map, where the card is.
        val box = ScanBox(0.3f, -3.2f, baseY = 0f, yaw = 0f, length = 1.4f, depth = 0.6f, height = 0.8f)
        assertFalse("under the card to begin with", isClear(pose, box, CHROME))

        val revealed = reveal(pose, box, listOf(CHROME))
        assertTrue("still under the card", isClear(revealed, box, CHROME))
        assertEquals("no closer, no further", pose.distance, revealed.distance, 1e-4f)
        assertEquals(pose.azimuthDegrees, revealed.azimuthDegrees, 0f)
        assertEquals(pose.elevationDegrees, revealed.elevationDegrees, 0f)
        // Slid by what it took and no more: the box now touches the free area's top edge.
        val top = corners(box).minOf { camera(revealed).project(it)!!.second }
        assertEquals(CHROME.top * HEIGHT, top, PIXELS)
    }

    @Test
    fun `beside the camera card or under it, whichever moves the room least`() {
        val pose = OrbitPose(Position(0f, 0.4f, 0f), azimuthDegrees = 35f, elevationDegrees = 32f, distance = 6f)
        // Drawn in the view's top right corner, where the 3D view keeps its Camera card.
        val box = boxDrawnAt(pose, 0.8f * WIDTH, 0.3f * HEIGHT)
        assertFalse(isClear(pose, box, BESIDE_CARD))
        assertFalse(isClear(pose, box, UNDER_CARD))

        val revealed = reveal(pose, box, listOf(BESIDE_CARD, UNDER_CARD))
        assertTrue(isClear(revealed, box, BESIDE_CARD) || isClear(revealed, box, UNDER_CARD))
        val moved = moved(pose, revealed)
        assertTrue("moved $moved m", moved > 0f)
        assertTrue(moved <= moved(pose, reveal(pose, box, listOf(BESIDE_CARD))) + 1e-4f)
        assertTrue(moved <= moved(pose, reveal(pose, box, listOf(UNDER_CARD))) + 1e-4f)

        // A box drawn left of the card, level with it, is not under it: nothing moves.
        val beside = boxDrawnAt(pose, 0.3f * WIDTH, 0.3f * HEIGHT)
        assertFalse("in the card's band", isClear(pose, beside, UNDER_CARD))
        assertSame(pose, reveal(pose, beside, listOf(BESIDE_CARD, UNDER_CARD)))
    }

    @Test
    fun `a box the free area is too small for steps the camera back, never closer`() {
        val close = OrbitPose(Position(0f, 0.5f, 0f), azimuthDegrees = 20f, elevationDegrees = 30f, distance = 1.2f)
        val sofa = ScanBox(centerX = 0f, centerZ = 0f, baseY = 0f, yaw = 0f, length = 2f, depth = 0.9f, height = 0.8f)
        val revealed = reveal(close, sofa, listOf(CHROME))
        assertTrue(revealed.distance > close.distance)
        assertTrue(isClear(revealed, sofa, CHROME))

        // From far away the same sofa is small and clear: the camera does not come to it.
        val far = close.copy(distance = 12f)
        assertSame(far, reveal(far, sofa, listOf(CHROME)))
    }

    @Test
    fun `the free camera glides to the pose it is given, and a finger stops it`() {
        val start = OrbitPose(Position(0f, 0.4f, 0f), azimuthDegrees = 35f, elevationDegrees = 32f, distance = 6f)
        val to = start.copy(target = Position(0.5f, 0.1f, -0.4f), distance = 7f)
        val orbit = ArDebugOrbitCamera(initialPose = start, drift = false).apply { setViewport(WIDTH, HEIGHT) }

        // Following, the view frames through home: the glide is not taken.
        orbit.home = start
        orbit.glideTo(to)
        repeat(120) { orbit.update(1f / 60f) }
        assertEquals(start, orbit.pose)

        orbit.hold()
        orbit.glideTo(to)
        orbit.update(1f / 60f)
        assertTrue("it eases, it does not jump", orbit.pose != to && orbit.pose != start)
        repeat(240) { orbit.update(1f / 60f) }
        assertEquals(to, orbit.pose)

        orbit.glideTo(start)
        repeat(6) { orbit.update(1f / 60f) }
        orbit.fingerDown()
        val held = orbit.pose
        repeat(120) { orbit.update(1f / 60f) }
        assertEquals("stopped under the finger", held, orbit.pose)
        assertTrue(held != start)
    }

    @Test
    fun `a box a finger's orbit turned under the chrome is brought back once the spin has died`() {
        val start = OrbitPose(Position(0f, 0.4f, 0f), azimuthDegrees = 35f, elevationDegrees = 32f, distance = 6f)
        // On the floor behind the pivot: tilting the view over the room sends it up the screen.
        val box = boxDrawnAt(start, 0.5f * WIDTH, 0.3f * HEIGHT)
        assertTrue("clear to begin with", isClear(start, box, CHROME))
        val orbit = orbit(start).apply { hold() }
        val reveal = ScanReveal().apply { settled() }
        assertFalse("nothing moved: nothing to bring back", reveal.due(labelMeasured = true, moving = orbit.moving))

        orbit.grabBegin(WIDTH / 2, HEIGHT / 2, false)
        repeat(DRAG_STEPS) { step ->
            orbit.grabUpdate(WIDTH / 2, HEIGHT / 2 - DRAG_PIXELS * (step + 1))
            orbit.update(FRAME)
            assertFalse("not under the finger", reveal.due(labelMeasured = true, moving = orbit.moving))
        }
        orbit.grabEnd()
        assertTrue("the finger let go of a spin", orbit.moving)

        var frames = 0
        while (!reveal.due(labelMeasured = true, moving = orbit.moving)) {
            assertTrue("still spinning", orbit.moving)
            assertTrue("the spin dies", ++frames < SPIN_FRAMES)
            orbit.update(FRAME)
        }
        assertTrue("the spin went on after the finger", frames > 1)
        val left = orbit.pose
        assertFalse("turned under the cards", isClear(left, box, CHROME))

        orbit.glideTo(reveal(left, box, listOf(CHROME)))
        repeat(GLIDE_FRAMES) {
            orbit.update(FRAME)
            assertFalse("once a let go", reveal.due(labelMeasured = true, moving = orbit.moving))
        }
        assertTrue("whole in the free area again", isClear(orbit.pose, box, CHROME))
        assertEquals("the angle the finger chose", left.azimuthDegrees, orbit.pose.azimuthDegrees, 0f)
        assertEquals(left.elevationDegrees, orbit.pose.elevationDegrees, 0f)
    }

    @Test
    fun `a pinch is a finger at work until the gesture ends`() {
        val orbit = orbit(OrbitPose(Position(0f, 0.4f, 0f), 35f, 32f, distance = 6f)).apply { hold() }
        assertFalse(orbit.moving)
        orbit.scrollBegin(WIDTH / 2, HEIGHT / 2, 200f)
        orbit.scrollUpdate(WIDTH / 2, HEIGHT / 2, 200f, 260f)
        assertTrue(orbit.moving)
        // The gesture detector ends a pinch as it ends a drag.
        orbit.grabEnd()
        assertFalse(orbit.moving)
    }

    @Test
    fun `the label waits for the slide to be decided, then is placed where the slide ends`() {
        val reveal = ScanReveal()
        assertFalse(reveal.pending)
        reveal.picked()
        repeat(ScanReveal.WAIT_FRAMES - 1) {
            assertFalse("the label has no height yet", reveal.due(labelMeasured = false, moving = false))
            assertTrue("nothing is written meanwhile", reveal.pending)
        }
        assertTrue("no longer waited for", reveal.due(labelMeasured = false, moving = false))
        assertFalse(reveal.pending)
        reveal.picked()
        assertTrue("measured: at once", reveal.due(labelMeasured = true, moving = false))
        assertFalse(reveal.pending)
        assertFalse("once a pick", reveal.due(labelMeasured = true, moving = false))

        val pose = OrbitPose(Position(0f, 0f, 0f), 0f, ArDebugFraming.MAP_ELEVATION, distance = 9f)
        val box = ScanBox(0.3f, -3.2f, baseY = 0f, yaw = 0f, length = 1.4f, depth = 0.6f, height = 0.8f)
        val orbit = orbit(pose).apply { hold() }
        assertEquals("no slide: where the camera is", pose, orbit.heading)
        val to = reveal(pose, box, listOf(CHROME))
        orbit.glideTo(to)
        assertEquals(to, orbit.heading)

        // Placed for the slide's end from its first frame: the same pixels until it is over.
        val born = ScanObjectMeshes.drawnSpan(box, orbit.scanCamera(orbit.heading))!!
        val now = ScanObjectMeshes.drawnSpan(box, orbit.scanCamera())!!
        assertTrue("it would have jumped ${born.second - now.second} px", born.second - now.second > PIXELS)
        orbit.update(FRAME)
        assertTrue("under way", orbit.pose != to)
        assertEquals(born, ScanObjectMeshes.drawnSpan(box, orbit.scanCamera(orbit.heading)))
        repeat(GLIDE_FRAMES) { orbit.update(FRAME) }
        assertEquals(to, orbit.pose)
        assertEquals(born, ScanObjectMeshes.drawnSpan(box, orbit.scanCamera()))
        assertEquals("the box's top, where the chrome ends", CHROME.top * HEIGHT, born.second, PIXELS)
    }

    @Test
    fun `on a small screen every corner of the box ends off the cards, the pill and the dock`() {
        val free = PHONE.free(WIDTH, HEIGHT, LABEL)
        // The way under the card is a band a few rows tall there; the one beside it is the room.
        assertFalse("under the card: no room", free.last().leaves(ArDebugFraming.REVEAL_MIN_FREE))
        assertTrue("beside the card: room", free.first().leaves(ArDebugFraming.REVEAL_MIN_FREE))

        var hidden = 0
        val drawnAt = ACROSS.flatMap { x -> DOWN.map { y -> x to y } }
        for (pose in poses()) for ((x, y) in drawnAt) {
            val box = boxDrawnAt(pose, x * WIDTH, y * HEIGHT)
            if (!offChrome(pose, box)) hidden++
            val revealed = reveal(pose, box, free)
            val at = "from $pose, drawn at $x × $y"
            assertTrue("a corner behind the chrome $at", offChrome(revealed, box))
            assertTrue("no closer $at", revealed.distance >= pose.distance)
            assertEquals(pose.azimuthDegrees, revealed.azimuthDegrees, 0f)
            assertEquals(pose.elevationDegrees, revealed.elevationDegrees, 0f)
        }
        assertTrue("$hidden of the boxes began behind the chrome", hidden > AZIMUTHS.size)
    }

    /** The room seen from all round, low and high, close and far. */
    private fun poses(): List<OrbitPose> = AZIMUTHS.flatMap { azimuth ->
        ELEVATIONS.flatMap { elevation ->
            DISTANCES.map { distance -> OrbitPose(Position(0f, 0.4f, 0f), azimuth, elevation, distance) }
        }
    }

    @Test
    fun `a box too large for the room beside the card is stepped back for, never left under it`() {
        val close = OrbitPose(Position(0f, 0.5f, 0f), azimuthDegrees = 20f, elevationDegrees = 30f, distance = 1.2f)
        val long = ScanBox(centerX = 0f, centerZ = 0f, baseY = 0f, yaw = 0.6f, length = 2f, depth = 1f, height = 0.9f)
        assertFalse(offChrome(close, long))
        val revealed = reveal(close, long, PHONE.free(WIDTH, HEIGHT, LABEL))
        assertTrue("stepped back", revealed.distance > close.distance)
        assertTrue("smaller, and whole", offChrome(revealed, long))
    }

    @Test
    fun `a box a two-finger pan pushed behind the dock is brought back when the fingers let go`() {
        val free = PHONE.free(WIDTH, HEIGHT, LABEL)
        val box = ScanBox(0f, 0f, baseY = 0f, yaw = 0.3f, length = 0.5f, depth = 0.4f, height = 0.45f)
        val start = reveal(OrbitPose(Position(0f, 0.4f, 0f), 35f, 32f, distance = 6f), box, free)
        assertTrue("clear to begin with", offChrome(start, box))
        val orbit = orbit(start).apply { hold() }
        val reveal = ScanReveal().apply { settled() }

        orbit.grabBegin(WIDTH / 2, HEIGHT / 2, true)
        repeat(PAN_STEPS) { step ->
            orbit.grabUpdate(WIDTH / 2, HEIGHT / 2 + DRAG_PIXELS * (step + 1))
            orbit.update(FRAME)
            assertFalse("not under the fingers", reveal.due(labelMeasured = true, moving = orbit.moving))
        }
        orbit.grabEnd()
        val left = letGo(orbit, reveal)
        assertFalse("pushed behind the bottom cards", offChrome(left, box))

        orbit.glideTo(reveal(left, box, free))
        repeat(GLIDE_FRAMES) { orbit.update(FRAME) }
        assertTrue("whole and clear again", offChrome(orbit.pose, box))
        assertEquals("no closer, no further", left.distance, orbit.pose.distance, 1e-4f)
    }

    @Test
    fun `a box pinched in on as far as the camera goes is stepped back for when the fingers let go`() {
        val free = PHONE.free(WIDTH, HEIGHT, LABEL)
        val box = ScanBox(0f, 0f, baseY = 0f, yaw = 0.3f, length = 0.5f, depth = 0.4f, height = 0.45f)
        val start = reveal(OrbitPose(Position(0f, 0.2f, 0f), 35f, 32f, distance = 6f), box, free)
        val orbit = orbit(start).apply { hold() }
        val reveal = ScanReveal().apply { settled() }

        var separation = 100f
        orbit.scrollBegin(WIDTH / 2, HEIGHT / 2, separation)
        repeat(PINCH_STEPS) {
            orbit.scrollUpdate(WIDTH / 2, HEIGHT / 2, separation, separation * PINCH_RATIO)
            separation *= PINCH_RATIO
            orbit.update(FRAME)
            assertFalse("not under the fingers", reveal.due(labelMeasured = true, moving = orbit.moving))
        }
        // The gesture detector ends a pinch as it ends a drag.
        orbit.grabEnd()
        val left = letGo(orbit, reveal)
        assertEquals("as close as the camera goes", ArDebugFraming.MIN_DISTANCE, left.distance, 1e-4f)
        assertFalse("larger than the view", offChrome(left, box))

        orbit.glideTo(reveal(left, box, free))
        repeat(GLIDE_FRAMES) { orbit.update(FRAME) }
        assertTrue("stepped back", orbit.pose.distance > left.distance)
        assertTrue("whole and clear again", offChrome(orbit.pose, box))
    }

    /** The pose the fingers left [orbit] at, once [reveal] says the camera is to be slid. */
    private fun letGo(orbit: ArDebugOrbitCamera, reveal: ScanReveal): OrbitPose {
        var frames = 0
        while (!reveal.due(labelMeasured = true, moving = orbit.moving)) {
            assertTrue("the camera comes to rest", ++frames < SPIN_FRAMES)
            orbit.update(FRAME)
        }
        return orbit.pose
    }

    /**
     * Whether [box] is drawn from [pose] whole in the view and off what [PHONE] draws over it:
     * each of its eight corners, and the rectangle around them, clear of the cards across the
     * top, of the card in the corner and of everything from the pill down.
     */
    private fun offChrome(pose: OrbitPose, box: ScanBox): Boolean {
        val camera = camera(pose)
        val drawn = corners(box).map { camera.project(it) ?: return false }
        val left = drawn.minOf { it.first }
        val right = drawn.maxOf { it.first }
        val top = drawn.minOf { it.second }
        val bottom = drawn.maxOf { it.second }
        val card = PHONE.card!!
        val inView = left >= 0f && right <= WIDTH && top >= 0f && bottom <= HEIGHT
        val underCards = top < PHONE.top - card.height
        val underCorner = top < PHONE.top && right > WIDTH - card.width
        val underBottom = bottom > HEIGHT - PHONE.bottom
        return inView && !underCards && !underCorner && !underBottom
    }

    private fun reveal(pose: OrbitPose, box: ScanBox, insets: List<FramingInsets>): OrbitPose {
        val orbit = orbit(pose)
        val corners = box.corners().map { Position(it.x, it.y, it.z) }
        return ArDebugFraming.reveal(pose, corners, orbit.verticalFovDegrees, orbit.aspect, insets, LIFT)
    }

    private fun orbit(pose: OrbitPose) =
        ArDebugOrbitCamera(initialPose = pose, drift = false, lift = LIFT).apply { setViewport(WIDTH, HEIGHT) }

    private fun camera(pose: OrbitPose) = orbit(pose).scanCamera()

    private fun corners(box: ScanBox) = box.corners()

    /** Whether every corner of [box] is drawn from [pose] inside what [insets] leave free. */
    private fun isClear(pose: OrbitPose, box: ScanBox, insets: FramingInsets): Boolean {
        val camera = camera(pose)
        return corners(box).all { corner ->
            val (x, y) = camera.project(corner) ?: return false
            x >= insets.left * WIDTH - PIXELS && x <= (1f - insets.right) * WIDTH + PIXELS &&
                y >= insets.top * HEIGHT - PIXELS && y <= (1f - insets.bottom) * HEIGHT + PIXELS
        }
    }

    /** A stool standing on the floor where [pose] draws pixel ([x], [y]). */
    private fun boxDrawnAt(pose: OrbitPose, x: Float, y: Float): ScanBox {
        val camera = camera(pose)
        val ray = camera.ray(x, y)
        val at = camera.eye + ray * (-camera.eye.y / ray.y)
        return ScanBox(at.x, at.z, baseY = 0f, yaw = 0.3f, length = 0.5f, depth = 0.4f, height = 0.45f)
    }

    private fun moved(from: OrbitPose, to: OrbitPose): Float {
        val slide = to.target - from.target
        return Vec3(slide.x, slide.y, slide.z).length() + (to.distance - from.distance)
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2340
        const val LIFT = 0.07f
        const val PIXELS = 1.5f
        const val FRAME = 1f / 60f

        /** A drag up the screen that stops short of the steepest tilt, so it is let go spinning. */
        const val DRAG_STEPS = 9
        const val DRAG_PIXELS = 20
        const val SPIN_FRAMES = 600
        const val GLIDE_FRAMES = 240

        /** A header and a stats card above, a filmstrip and a dock below. */
        val CHROME = FramingInsets(left = 0.03f, top = 0.2f, right = 0.03f, bottom = 0.3f)

        /** The 3D view's two ways round its Camera card, which hangs under the stats card. */
        val BESIDE_CARD = CHROME.copy(right = 0.4f)
        val UNDER_CARD = CHROME.copy(top = 0.42f)

        /**
         * A small phone's replay, in pixels of [WIDTH] × [HEIGHT]: the cards and the Camera card
         * hanging under them reach half way down, the pill, the filmstrip and the dock take the
         * last two fifths — a few rows are left between the card and the pill.
         */
        val PHONE = ScanChrome(top = 1190, bottom = 900, card = ScanCard(396, 511), margin = 44, gap = 22)
        const val LABEL = 140

        val AZIMUTHS = floatArrayOf(-140f, -60f, 20f, 100f, 170f)
        val ELEVATIONS = floatArrayOf(20f, 32f, 60f)
        val DISTANCES = floatArrayOf(2.5f, 5f, 9f)
        val ACROSS = floatArrayOf(0.25f, 0.5f, 0.8f)
        val DOWN = floatArrayOf(0.45f, 0.55f, 0.68f)

        const val PAN_STEPS = 30
        const val PINCH_STEPS = 60
        const val PINCH_RATIO = 1.2f
    }
}
