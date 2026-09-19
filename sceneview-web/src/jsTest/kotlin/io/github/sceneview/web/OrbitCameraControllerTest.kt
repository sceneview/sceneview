@file:Suppress(
    "VariableNaming",   // EPS is a test constant — uppercase matches the math convention
    // deltaY in dispatchWheel, eventType/clientX/clientY in dispatchMouse: all
    // read inside a js("...") string literal, which the compiler cannot see.
    "UnusedParameter",
)

package io.github.sceneview.web

import io.github.sceneview.web.bindings.Camera
import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [OrbitCameraController] — the orbit/zoom/pan camera math used by
 * SceneView Web. The controller's `update()` is the only place spherical
 * coordinates are converted to a Cartesian eye position and fed to Filament's
 * `Camera.lookAt`, so these tests pin that conversion plus the clamping,
 * damping, and auto-rotate behaviour.
 *
 * Filament's `Camera` is an `external class`, so a plain JS object whose
 * `lookAt` records its `eye` argument is `unsafeCast` into one — no WebGL
 * context or Filament WASM module is required. The canvas is a real DOM
 * element (Karma + ChromeHeadless), so the constructor's `addEventListener`
 * wiring runs unmodified.
 */
class OrbitCameraControllerTest {

    /** A fake Filament Camera that records the last `lookAt(eye, center, up)` call. */
    private class FakeCamera {
        var eye: DoubleArray = DoubleArray(3)
        var center: DoubleArray = DoubleArray(3)
        var up: DoubleArray = DoubleArray(3)
        var lookAtCalls: Int = 0

        @Suppress("UNUSED_PARAMETER", "unused")
        fun toCamera(): Camera {
            val self = this
            val obj = js("{}")
            obj.lookAt = { e: dynamic, c: dynamic, u: dynamic ->
                self.eye = doubleArrayOf(
                    (e[0] as Number).toDouble(),
                    (e[1] as Number).toDouble(),
                    (e[2] as Number).toDouble(),
                )
                self.center = doubleArrayOf(
                    (c[0] as Number).toDouble(),
                    (c[1] as Number).toDouble(),
                    (c[2] as Number).toDouble(),
                )
                self.up = doubleArrayOf(
                    (u[0] as Number).toDouble(),
                    (u[1] as Number).toDouble(),
                    (u[2] as Number).toDouble(),
                )
                self.lookAtCalls += 1
                Unit
            }
            return obj.unsafeCast<Camera>()
        }
    }

    private fun newCanvas(): HTMLCanvasElement =
        document.createElement("canvas").unsafeCast<HTMLCanvasElement>()

    private fun controller(camera: FakeCamera = FakeCamera()): Pair<OrbitCameraController, FakeCamera> {
        val c = OrbitCameraController(newCanvas(), camera.toCamera())
        return c to camera
    }

    private val EPS = 1e-9

    /** One 60 Hz frame, in seconds — the rate every pre-existing test assumed. */
    private val FRAME_60 = 1.0 / 60.0

    /** One 120 Hz frame — a ProMotion Mac or a 120 Hz Android panel. */
    private val FRAME_120 = 1.0 / 120.0

    @Test
    fun defaultsMatchModelViewer() {
        val (controller, _) = controller()
        assertEquals(45.0 * PI / 180.0, controller.theta, EPS, "default theta should be 45°")
        assertEquals(70.0 * PI / 180.0, controller.phi, EPS, "default phi should be 70°")
        assertEquals(5.0, controller.distance)
    }

    @Test
    fun updateConvertsSphericalToCartesianEye() {
        val (controller, cam) = controller()
        // Pick angles with a clean closed form: theta=0, phi=90° -> eye on +z axis.
        controller.target(0.0, 0.0, 0.0)
        controller.theta = 0.0
        controller.phi = PI / 2.0
        controller.distance = 5.0
        controller.enableDamping = false
        controller.autoRotate = false
        controller.update(FRAME_60)

        // eye = target + distance * [sin(phi)sin(theta), cos(phi), sin(phi)cos(theta)]
        //     = [0, 0, 5]
        assertEquals(0.0, cam.eye[0], 1e-9)
        assertEquals(0.0, cam.eye[1], 1e-9)
        assertEquals(5.0, cam.eye[2], 1e-9)
        // center is always the orbit target, up is +y.
        assertEquals(0.0, cam.center[0]); assertEquals(0.0, cam.center[1]); assertEquals(0.0, cam.center[2])
        assertEquals(0.0, cam.up[0]); assertEquals(1.0, cam.up[1]); assertEquals(0.0, cam.up[2])
    }

    @Test
    fun updateRespectsArbitraryAnglesAndTarget() {
        val (controller, cam) = controller()
        val theta = 0.7
        val phi = 1.2
        val distance = 3.5
        controller.target(2.0, -1.0, 4.0)
        controller.theta = theta
        controller.phi = phi
        controller.distance = distance
        controller.enableDamping = false
        controller.update(FRAME_60)

        val expX = 2.0 + distance * sin(phi) * sin(theta)
        val expY = -1.0 + distance * cos(phi)
        val expZ = 4.0 + distance * sin(phi) * cos(theta)
        assertEquals(expX, cam.eye[0], 1e-9)
        assertEquals(expY, cam.eye[1], 1e-9)
        assertEquals(expZ, cam.eye[2], 1e-9)
    }

    @Test
    fun updateClampsPhiWithinLimits() {
        val (controller, _) = controller()
        controller.enableDamping = false
        controller.minPhi = 0.1
        controller.maxPhi = PI - 0.1

        controller.phi = -5.0
        controller.update(FRAME_60)
        assertEquals(0.1, controller.phi, EPS, "phi below minPhi must clamp up to minPhi")

        controller.phi = 99.0
        controller.update(FRAME_60)
        assertEquals(PI - 0.1, controller.phi, EPS, "phi above maxPhi must clamp down to maxPhi")
    }

    @Test
    fun updateClampsDistanceWithinLimits() {
        val (controller, _) = controller()
        controller.enableDamping = false
        controller.minDistance = 0.5
        controller.maxDistance = 50.0

        controller.distance = 0.01
        controller.update(FRAME_60)
        assertEquals(0.5, controller.distance, "distance below minDistance must clamp up")

        controller.distance = 9999.0
        controller.update(FRAME_60)
        assertEquals(50.0, controller.distance, "distance above maxDistance must clamp down")
    }

    @Test
    fun autoRotateAdvancesThetaBySpeedTimesElapsedTime() {
        val (controller, _) = controller()
        controller.enableDamping = false
        controller.autoRotate = true
        controller.theta = 0.0
        val step = controller.autoRotateSpeed * FRAME_60

        controller.update(FRAME_60)
        assertEquals(step, controller.theta, EPS, "auto-rotate must advance theta by speed × elapsed time")

        controller.update(FRAME_60)
        assertEquals(2.0 * step, controller.theta, EPS, "two 60 Hz frames advance theta by two such steps")
    }

    @Test
    fun autoRotateSpeedDefaultIs30DegPerSecond() {
        val (controller, _) = controller()
        // The unit is rad/SECOND (iOS `CameraControls.autoRotateSpeed` parity),
        // not rad/frame — a 60 Hz assumption is exactly the bug this pins shut.
        assertEquals(30.0 * PI / 180.0, controller.autoRotateSpeed, EPS)
        // And it still resolves to the historical 0.5°/frame at 60 Hz, so the
        // visible speed on a 60 Hz panel is unchanged by the unit switch.
        assertEquals(
            30.0 * PI / 180.0 / 60.0,
            controller.autoRotateSpeed * FRAME_60,
            EPS,
            "a 60 Hz frame must still advance the historical 0.5°",
        )
    }

    @Test
    fun dampingDecaysVelocityByDampingFactorEachFrame() {
        // With damping on, update() applies velocity to theta/phi then multiplies
        // both velocities by dampingFactor. Drive velocity directly through the
        // public dampingFactor and observe theta converging.
        val (controller, _) = controller()
        controller.enableDamping = true
        controller.autoRotate = false
        controller.dampingFactor = 0.5
        controller.theta = 0.0
        controller.phi = PI / 2.0

        // No mouse input -> velocities start at 0, so theta stays put and the
        // controller is a stable no-op frame to frame.
        controller.update(FRAME_60)
        controller.update(FRAME_60)
        assertEquals(0.0, controller.theta, EPS, "zero velocity -> theta unchanged under damping")
    }

    @Test
    fun dampingFactorBetweenZeroAndOneShrinksVelocityMagnitude() {
        // The damping invariant: each frame multiplies velocity by a factor < 1,
        // so successive frames can never increase the per-frame theta delta.
        val (controller, _) = controller()
        controller.enableDamping = true
        assertTrue(
            controller.dampingFactor > 0.0 && controller.dampingFactor < 1.0,
            "dampingFactor must be in (0,1) so inertia decays — was ${controller.dampingFactor}",
        )
    }

    @Test
    fun updateInvokesCameraLookAtEveryFrame() {
        val (controller, cam) = controller()
        controller.enableDamping = false
        controller.update(FRAME_60)
        controller.update(FRAME_60)
        controller.update(FRAME_60)
        assertEquals(3, cam.lookAtCalls, "every update() must push a fresh lookAt to the camera")
    }

    @Test
    fun firstUpdateReportsMovedSoTheFirstFrameAlwaysPaints() {
        // #2332: the render gate keys off update()'s return — the very first
        // frame has no prior pose to compare against and must count as moved.
        val (controller, _) = controller()
        controller.enableDamping = false
        assertTrue(controller.update(FRAME_60), "the first update() must report the camera as moved")
    }

    @Test
    fun staticCameraReportsNotMovedSoTheGateCanIdle() {
        // #2332: with no input, no auto-rotate and no damping velocity, the pose
        // is identical frame to frame — update() must report NOT moved so the
        // render gate stops repainting a settled scene.
        val (controller, _) = controller()
        controller.enableDamping = false
        controller.autoRotate = false
        controller.update(FRAME_60) // first frame: moved (no prior pose)
        assertFalse(controller.update(FRAME_60), "an unchanged camera must report not-moved")
        assertFalse(controller.update(FRAME_60), "and stay not-moved while nothing changes")
    }

    @Test
    fun settledCameraReportsNotMovedUnderShippedDampingDefault() {
        // #2332: the sibling idle test above forces enableDamping = false, but the
        // PRODUCTION default is enableDamping = true (autoRotate = false). With no
        // pointer input the damping velocity is zero, so a settled camera's pose is
        // identical frame to frame — update() must STILL report not-moved under the
        // shipped config, otherwise the render gate would repaint a settled scene
        // forever for every default consumer. Pins the gate to the default, not
        // just the damping-off path.
        val (controller, _) = controller()
        assertTrue(controller.enableDamping, "test premise: damping is on by default in production")
        controller.autoRotate = false
        controller.update(FRAME_60) // first frame: moved (no prior pose)
        assertFalse(
            controller.update(FRAME_60),
            "a settled camera must report not-moved under the default damping = true",
        )
    }

    @Test
    fun autoRotateReportsMovedEveryFrame() {
        // #2332: auto-rotate advances theta each frame, so the camera genuinely
        // moves — update() must report moved so the gate keeps painting.
        val (controller, _) = controller()
        controller.enableDamping = false
        controller.autoRotate = true
        controller.theta = 0.0
        controller.update(FRAME_60) // first frame
        assertTrue(controller.update(FRAME_60), "auto-rotate must keep reporting the camera as moved")
        assertTrue(controller.update(FRAME_60), "auto-rotate must keep reporting the camera as moved")
    }

    /**
     * Dispatch a synthetic cancelable `wheel` event with the given `deltaY`
     * onto [canvas]. Built via `js` so `deltaY` is populated under ChromeHeadless.
     */
    private fun dispatchWheel(canvas: HTMLCanvasElement, deltaY: Double) {
        val event = js("new WheelEvent('wheel', { deltaY: deltaY, cancelable: true })")
        canvas.dispatchEvent(event.unsafeCast<org.w3c.dom.events.Event>())
    }

    @Test
    fun wheelEventChangesDistanceBeforeDispose() {
        // Sanity: while listeners are live a wheel event must zoom (change distance).
        val canvas = newCanvas()
        val controller = OrbitCameraController(canvas, FakeCamera().toCamera())
        val before = controller.distance
        dispatchWheel(canvas, 100.0)
        assertTrue(
            controller.distance != before,
            "a live wheel listener must change distance — was $before, still ${controller.distance}",
        )
    }

    @Test
    fun disposeDetachesWheelListenerSoEventsNoLongerMutateController() {
        // #1698: after dispose() the canvas must no longer drive the controller.
        val canvas = newCanvas()
        val controller = OrbitCameraController(canvas, FakeCamera().toCamera())
        controller.dispose()
        val frozen = controller.distance
        dispatchWheel(canvas, 100.0)
        dispatchWheel(canvas, -100.0)
        assertEquals(
            frozen,
            controller.distance,
            "after dispose() a wheel event must not mutate the dead controller's distance",
        )
    }

    @Test
    fun disposeIsIdempotent() {
        // Calling dispose() twice must not throw (e.g. double-destroy of a SceneView).
        val canvas = newCanvas()
        val controller = OrbitCameraController(canvas, FakeCamera().toCamera())
        controller.dispose()
        controller.dispose()
        dispatchWheel(canvas, 100.0)
        assertTrue(true, "dispose() must be safe to call twice")
    }

    @Test
    fun fullOrbitReturnsToSameEyePosition() {
        // theta + 2π is geometrically identical -> eye position must match.
        val (controller, cam) = controller()
        controller.enableDamping = false
        controller.target(0.0, 0.0, 0.0)
        controller.phi = 1.0
        controller.distance = 4.0

        controller.theta = 0.3
        controller.update(FRAME_60)
        val eyeA = cam.eye.copyOf()

        controller.theta = 0.3 + 2.0 * PI
        controller.update(FRAME_60)
        val eyeB = cam.eye.copyOf()

        for (i in 0..2) {
            assertTrue(
                abs(eyeA[i] - eyeB[i]) < 1e-6,
                "a full 2π orbit must land on the same eye position (axis $i: $eyeA vs $eyeB)",
            )
        }
    }

    // ---------------------------------------------------------------------
    // Frame-rate independence
    //
    // The controller used to add one fixed increment per `update()` call —
    // both the auto-rotation step and the damping decay. On a 120 Hz panel
    // (a ProMotion Mac in Chrome, a 120 Hz Android display) rAF fires twice as
    // often, so the turntable spun twice as fast and a released drag's inertia
    // died in half the time. These pin the fix the way the iOS suite pins its
    // own (`CameraMotionContinuityTests.testCoastIsFrameRateIndependent`):
    // simulate the SAME wall-clock second at two refresh rates and require the
    // same final pose.
    // ---------------------------------------------------------------------

    /**
     * Dispatch a synthetic mouse event with the given type and client position.
     * Built via `js` so `clientX`/`clientY`/`button` are populated under
     * ChromeHeadless (the Kotlin `MouseEvent` constructor cannot set them).
     */
    private fun dispatchMouse(canvas: HTMLCanvasElement, eventType: String, clientX: Double, clientY: Double) {
        val event = js(
            "new MouseEvent(eventType, { clientX: clientX, clientY: clientY, button: 0, cancelable: true })"
        )
        canvas.dispatchEvent(event.unsafeCast<org.w3c.dom.events.Event>())
    }

    /**
     * Drive a real press-drag-release through the DOM listeners so the
     * controller's private inertia velocity is seeded exactly the way a user's
     * finger seeds it — no test-only setter, no reflection.
     *
     * Returns a controller left coasting, mid-range in phi so the coast never
     * reaches [OrbitCameraController.minPhi] (a clamp would flatten the very
     * difference these tests look for).
     */
    private fun coastingController(): OrbitCameraController {
        val canvas = newCanvas()
        val controller = OrbitCameraController(canvas, FakeCamera().toCamera())
        controller.theta = 0.0
        controller.phi = PI / 2.0
        dispatchMouse(canvas, "mousedown", 100.0, 100.0)
        dispatchMouse(canvas, "mousemove", 140.0, 106.0)
        dispatchMouse(canvas, "mouseup", 140.0, 106.0)
        return controller
    }

    /**
     * Horizontal travel, in pixels, the held-drag helper below covers in one
     * simulated second. Divisible by every rate it is replayed at (30, 60, 120,
     * 144), so each frame's step is a whole number of pixels —
     * `MouseEventInit.clientX` is an IDL `long` and would truncate a fraction,
     * quietly changing the total the test is pinning.
     */
    private val DRAG_PIXELS = 720

    /** Y the held drag stays on: horizontal-only, so phi never nears its clamp. */
    private val DRAG_Y = 100.0

    /** X the held drag starts from. */
    private val DRAG_START_X = 100.0

    /**
     * Drive a drag that is still HELD: `mousedown`, then one `mousemove` and
     * one `update()` per frame for one simulated second at [hz], covering
     * [DRAG_PIXELS] in total. No `mouseup` — the button is still down when this
     * returns.
     *
     * This is the trajectory [coastingController] never reaches: it fires
     * press-move-release before the first `update()`, so every inertia test
     * above starts from an already-released gesture and the live gesture — the
     * controller's most common state by far — went unexercised.
     *
     * The same pointer distance in the same wall-clock time must turn the
     * camera by the same angle at every rate: that is what "frame-rate
     * independent" means for the gesture the user is actually making.
     */
    private fun heldDragForOneSecond(hz: Int): Pair<OrbitCameraController, HTMLCanvasElement> {
        val canvas = newCanvas()
        val controller = OrbitCameraController(canvas, FakeCamera().toCamera())
        controller.theta = 0.0
        controller.phi = PI / 2.0
        val pixelsPerFrame = DRAG_PIXELS / hz
        dispatchMouse(canvas, "mousedown", DRAG_START_X, DRAG_Y)
        for (frame in 1..hz) {
            dispatchMouse(canvas, "mousemove", DRAG_START_X + (frame * pixelsPerFrame).toDouble(), DRAG_Y)
            controller.update(1.0 / hz)
        }
        return controller to canvas
    }

    @Test
    fun aHeldDragTurnsTheSameAngleAtEveryRefreshRate() {
        // The contract the user feels: N pixels of pointer travel is N ×
        // rotateSensitivity radians of orbit, on a 30 Hz tab and on a 144 Hz
        // panel alike. Nothing about the gesture may be scaled by how often the
        // host happens to call update().
        for (hz in listOf(30, 60, 120, 144)) {
            val (c, _) = heldDragForOneSecond(hz)
            val expected = -DRAG_PIXELS * c.rotateSensitivity
            assertEquals(
                expected,
                c.theta,
                1e-9,
                "a $DRAG_PIXELS px drag held for one second at $hz Hz must turn " +
                    "${expected * 180.0 / PI}°, not ${c.theta * 180.0 / PI}°",
            )
        }
    }

    @Test
    fun aReleasedDragLeavesTheSameInertiaAtEveryRefreshRate() {
        // And the tail the release leaves must match too: the velocity is
        // seeded from pointer travel per unit TIME, so the same flick hands the
        // same inertia to the damping model whatever rate sampled it.
        val coastByRate = mutableListOf<Pair<Int, Double>>()
        for (hz in listOf(30, 60, 120, 144)) {
            val (c, canvas) = heldDragForOneSecond(hz)
            dispatchMouse(canvas, "mouseup", DRAG_START_X + DRAG_PIXELS, DRAG_Y)
            val atRelease = c.theta
            repeat(hz) { c.update(1.0 / hz) } // one second of coasting
            coastByRate += hz to (c.theta - atRelease)
        }

        val at60 = coastByRate.first { it.first == 60 }.second
        // Guard against agreeing for the wrong reason — four controllers that
        // never coasted at all would also agree.
        assertTrue(abs(at60) > 1e-3, "the released drag must actually coast — travelled only $at60")
        for ((hz, coast) in coastByRate) {
            assertEquals(
                at60,
                coast,
                1e-9,
                "one second of inertia after the same flick must travel the same angle at " +
                    "$hz Hz ($coast) as at 60 Hz ($at60)",
            )
        }
    }

    @Test
    fun grabbingACoastingCameraCancelsTheOldInertia() {
        // A held pointer owns the camera: the tail of the PREVIOUS flick must
        // not keep turning the model under the finger, nor come back to life
        // when a motionless press is released.
        val canvas = newCanvas()
        val c = OrbitCameraController(canvas, FakeCamera().toCamera())
        c.theta = 0.0
        c.phi = PI / 2.0
        dispatchMouse(canvas, "mousedown", 100.0, 100.0)
        dispatchMouse(canvas, "mousemove", 140.0, 100.0)
        dispatchMouse(canvas, "mouseup", 140.0, 100.0)
        c.update(FRAME_60) // coasting now

        dispatchMouse(canvas, "mousedown", 200.0, 100.0) // grab it again, and hold still
        val grabbed = c.theta
        repeat(30) { c.update(FRAME_60) }
        assertEquals(grabbed, c.theta, EPS, "a held pointer must freeze the camera, not let it coast on")

        dispatchMouse(canvas, "mouseup", 200.0, 100.0)
        repeat(30) { c.update(FRAME_60) }
        assertEquals(grabbed, c.theta, 1e-9, "releasing a motionless press must not resume the old coast")
    }

    @Test
    fun autoRotationIsFrameRateIndependent() {
        val at60 = controller().first
        val at120 = controller().first
        for (c in listOf(at60, at120)) {
            c.enableDamping = false
            c.autoRotate = true
            c.theta = 0.0
        }

        // One simulated second, at each rate.
        repeat(60) { at60.update(FRAME_60) }
        repeat(120) { at120.update(FRAME_120) }

        assertEquals(
            at60.theta,
            at120.theta,
            1e-9,
            "one second of auto-rotation must travel the same angle at 60 Hz (${at60.theta}) " +
                "and at 120 Hz (${at120.theta})",
        )
    }

    @Test
    fun autoRotationTravelsThirtyDegreesPerSecondAtEveryRate() {
        // The honest statement of the default, independent of any refresh rate:
        // 30°/s means 30° of travel per wall-clock second, full stop.
        val thirtyDegrees = 30.0 * PI / 180.0
        // 60 Hz, 90 Hz, 120 Hz, 144 Hz — the panels this actually ships on.
        for (hz in listOf(60, 90, 120, 144)) {
            val c = controller().first
            c.enableDamping = false
            c.autoRotate = true
            c.theta = 0.0
            repeat(hz) { c.update(1.0 / hz) }
            assertEquals(
                thirtyDegrees,
                c.theta,
                1e-9,
                "one second at $hz Hz must travel 30°, not ${c.theta * 180.0 / PI}°",
            )
        }
    }

    @Test
    fun inertiaIsFrameRateIndependent() {
        val at60 = coastingController()
        val at120 = coastingController()

        repeat(60) { at60.update(FRAME_60) }
        repeat(120) { at120.update(FRAME_120) }

        assertEquals(
            at60.theta,
            at120.theta,
            1e-9,
            "a second of inertia must land on the same theta at 60 Hz (${at60.theta}) " +
                "and at 120 Hz (${at120.theta})",
        )
        assertEquals(
            at60.phi,
            at120.phi,
            1e-9,
            "a second of inertia must land on the same phi at 60 Hz (${at60.phi}) " +
                "and at 120 Hz (${at120.phi})",
        )
    }

    @Test
    fun inertiaActuallyMovesTheCameraAndThenSettles() {
        // Guards the test above against passing for the wrong reason: two
        // controllers that never moved at all would also agree.
        val c = coastingController()
        val start = c.theta
        c.update(FRAME_60)
        assertTrue(
            abs(c.theta - start) > 1e-4,
            "a released drag must actually coast — theta moved only ${c.theta - start}",
        )

        // …and the tail dies out rather than orbiting forever.
        repeat(600) { c.update(FRAME_60) }
        val settled = c.theta
        repeat(60) { c.update(FRAME_60) }
        assertTrue(
            abs(c.theta - settled) < 1e-6,
            "ten seconds in, the coast must be over — still moving by ${c.theta - settled}",
        )
    }

    @Test
    fun inertiaTailLastsTheSameWallClockTimeAtEveryRate() {
        // Not just the same endpoint after a fixed second: the *shape* of the
        // decay must match, so the inertia feels identical rather than merely
        // finishing in the same place.
        val at60 = coastingController()
        val at120 = coastingController()
        for (tenthOfASecond in 1..10) {
            repeat(6) { at60.update(FRAME_60) }
            repeat(12) { at120.update(FRAME_120) }
            assertEquals(
                at60.theta,
                at120.theta,
                1e-9,
                "at t = ${tenthOfASecond / 10.0}s the two rates must agree on theta",
            )
        }
    }

    @Test
    fun aFirstFrameWithZeroDeltaAdvancesNothing() {
        // The render loop has no previous timestamp on frame 1 and passes 0.0.
        // Nothing self-driven may advance — the scene must appear on exactly
        // the pose the caller authored.
        val c = coastingController()
        c.autoRotate = true
        val theta = c.theta
        val phi = c.phi
        c.update(0.0)
        assertEquals(theta, c.theta, EPS, "a zero-length first frame must not rotate")
        assertEquals(phi, c.phi, EPS, "a zero-length first frame must not coast")
        // …and it must not eat the pending inertia either.
        c.update(FRAME_60)
        assertTrue(abs(c.theta - theta) > 1e-4, "the coast must survive the zero-length frame")
    }

    @Test
    fun aLateFramePausesInsteadOfLeaping() {
        // A tab returning from the background hands rAF a multi-second gap.
        // Ungated, 90 s × 30°/s would spin the model seven times in one frame.
        // The self-driven motion pauses for the length of the hitch instead.
        val c = controller().first
        c.enableDamping = false
        c.autoRotate = true
        c.theta = 0.0
        c.update(90.0)
        assertEquals(
            0.0,
            c.theta,
            EPS,
            "a 90 s gap is a hitch and must advance nothing, not ${c.theta} rad",
        )
    }

    @Test
    fun aFiveSecondFrameAfterAPauseAdvancesNothing() {
        // The brief's case, and the shape of a real hitch: normal frames, a
        // stall, then one enormous frame. The stall must cost the turntable
        // exactly the stall — no catch-up, no leap.
        val c = controller().first
        c.enableDamping = false
        c.autoRotate = true
        c.theta = 0.0
        repeat(10) { c.update(FRAME_60) }
        val beforeTheHitch = c.theta
        c.update(5.0)
        assertEquals(
            beforeTheHitch,
            c.theta,
            EPS,
            "a 5 s frame must not advance the turntable at all",
        )
        // …and the very next normal frame must resume at the normal rate.
        c.update(FRAME_60)
        assertEquals(
            beforeTheHitch + c.autoRotateSpeed * FRAME_60,
            c.theta,
            EPS,
            "the frame after a hitch must be an ordinary frame again",
        )
    }

    @Test
    fun aSustainedLowFrameRateStillTurnsAtTheAuthoredSpeed() {
        // The #3711 regression this fix undoes (#3739). A software rasteriser
        // — a GPU-less CI runner, a low-end phone — settles at ~8 fps, i.e.
        // 0.125 s frames, every one of them longer than the old 0.05 s bound.
        // Truncating the *step* therefore truncated EVERY frame and the
        // turntable ran at 0.05/0.125 = 40 % of its authored speed: the frame
        // rate crept straight back into a speed expressed in seconds.
        //
        // One simulated second at 8 fps must turn exactly one second's worth.
        val slow = controller().first
        slow.enableDamping = false
        slow.autoRotate = true
        slow.theta = 0.0
        repeat(8) { slow.update(0.125) }

        assertEquals(
            slow.autoRotateSpeed * 1.0,
            slow.theta,
            1e-9,
            "8 fps for one second must turn one second's worth — was ${slow.theta} rad",
        )

        // …and it must agree with 60 Hz over the same wall-clock second, which
        // is the whole promise of expressing the speed in rad/s.
        val fast = controller().first
        fast.enableDamping = false
        fast.autoRotate = true
        fast.theta = 0.0
        repeat(60) { fast.update(FRAME_60) }
        assertEquals(fast.theta, slow.theta, 1e-9, "8 fps and 60 Hz must agree after one second")
    }

    @Test
    fun aReleaseAfterALongFrameDoesNotInflateTheCoast() {
        // The same truncation seen from the drag side. `update` banks the
        // frame's pointer travel and the release converts it with the last
        // frame's length as the divisor. With that length truncated to 0.05 s,
        // travel that really took 1 s read as a 20× faster flick, and letting
        // go of a stationary finger launched the model.
        //
        // Two identical gestures, one sampled on a 1 s frame and one on a
        // 1/60 s frame: the slow one must coast LESS, never more.
        fun coastAfter(frameLength: Double): Double {
            val canvas = newCanvas()
            val c = OrbitCameraController(canvas, FakeCamera().toCamera())
            c.theta = 0.0
            c.phi = PI / 2.0
            dispatchMouse(canvas, "mousedown", 100.0, DRAG_Y)
            c.update(frameLength)          // sets the divisor the release will use
            dispatchMouse(canvas, "mousemove", 140.0, DRAG_Y)
            dispatchMouse(canvas, "mouseup", 140.0, DRAG_Y)
            val atRelease = c.theta
            repeat(120) { c.update(FRAME_60) }   // let the tail run out
            return abs(c.theta - atRelease)
        }

        val afterASlowFrame = coastAfter(1.0)
        val afterANormalFrame = coastAfter(FRAME_60)
        assertTrue(
            afterASlowFrame < afterANormalFrame,
            "40 px sampled over a 1 s frame must coast less than over a 1/60 s one — " +
                "got $afterASlowFrame vs $afterANormalFrame rad",
        )
        // Pin the ratio, not just the ordering: a 1 s frame is 60 reference
        // frames, so the velocity — and the whole closed-form tail with it —
        // must be exactly 60× smaller. Truncation made it 20× (0.05 s → 3
        // reference frames) whatever the real frame length was.
        assertEquals(
            afterANormalFrame / 60.0,
            afterASlowFrame,
            1e-9,
            "the coast must scale with the TRUE frame length",
        )
    }

    @Test
    fun aHitchWhileDraggingDoesNotInventAFlick() {
        // Travel banked before a tab froze is not a gesture that happened over
        // the whole freeze. Crediting it at any rate invents a flick the user
        // never made, so the bank is dropped and the release coasts nowhere.
        val canvas = newCanvas()
        val c = OrbitCameraController(canvas, FakeCamera().toCamera())
        c.theta = 0.0
        c.phi = PI / 2.0
        dispatchMouse(canvas, "mousedown", 100.0, DRAG_Y)
        dispatchMouse(canvas, "mousemove", 140.0, DRAG_Y)
        c.update(30.0)   // the freeze: banked travel must be dropped here
        dispatchMouse(canvas, "mouseup", 140.0, DRAG_Y)
        val atRelease = c.theta
        repeat(120) { c.update(FRAME_60) }
        assertEquals(
            atRelease,
            c.theta,
            1e-9,
            "a release after a 30 s freeze must not coast — moved ${c.theta - atRelease} rad",
        )
    }

    @Test
    fun noFrameLengthEverProducesANonFiniteOrOutOfBoundsPose() {
        // A blanket guard over the whole range a host can hand `update`: the
        // pose must stay finite and inside its clamps for every one of them.
        // `Camera.lookAt` with a NaN eye is what a blank canvas looks like.
        val lengths = listOf(
            0.0, -1.0, -0.0, 1e-9, FRAME_120, FRAME_60, 0.125, 0.25, 0.2501,
            1.0, 5.0, 90.0, 3600.0,
        )
        for (dt in lengths) {
            val canvas = newCanvas()
            val cam = FakeCamera()
            val c = OrbitCameraController(canvas, cam.toCamera())
            c.autoRotate = true
            c.enableDamping = true
            c.minPhi = 0.1
            c.maxPhi = PI - 0.1
            c.minDistance = 0.5
            c.maxDistance = 50.0
            // Seed a live gesture and a zoom so every branch of update() runs.
            dispatchMouse(canvas, "mousedown", 100.0, 100.0)
            dispatchMouse(canvas, "mousemove", 900.0, 900.0)
            dispatchWheel(canvas, -5000.0)
            repeat(5) { c.update(dt) }
            dispatchMouse(canvas, "mouseup", 900.0, 900.0)
            repeat(20) { c.update(dt) }

            assertTrue(c.theta.isFinite(), "theta must stay finite at dt = $dt — was ${c.theta}")
            assertTrue(c.phi.isFinite(), "phi must stay finite at dt = $dt — was ${c.phi}")
            assertTrue(
                c.distance.isFinite(),
                "distance must stay finite at dt = $dt — was ${c.distance}",
            )
            assertTrue(
                c.phi >= 0.1 - EPS && c.phi <= PI - 0.1 + EPS,
                "phi must stay inside [minPhi, maxPhi] at dt = $dt — was ${c.phi}",
            )
            assertTrue(
                c.distance >= 0.5 - EPS && c.distance <= 50.0 + EPS,
                "distance must stay inside [minDistance, maxDistance] at dt = $dt — " +
                    "was ${c.distance}",
            )
            for (axis in 0..2) {
                assertTrue(
                    cam.eye[axis].isFinite(),
                    "eye[$axis] handed to Filament must be finite at dt = $dt — was ${cam.eye[axis]}",
                )
            }
        }
    }

    @Test
    fun aNegativeDeltaIsTreatedAsZero() {
        // A host that subtracts timestamps in the wrong order (or a clock that
        // steps backwards) must not drive the camera in reverse.
        val c = controller().first
        c.enableDamping = false
        c.autoRotate = true
        c.theta = 0.0
        c.update(-1.0)
        assertEquals(0.0, c.theta, EPS, "a negative delta must advance nothing")
    }

    @Test
    fun sixtyHertzBehaviourIsUnchangedByTheUnitSwitch() {
        // The migration promise: on a 60 Hz panel this release looks exactly
        // like the last one. Auto-rotation advanced `30° / 60` per frame and
        // inertia was `theta += velocity; velocity *= dampingFactor` — both
        // reproduced here in closed form and compared against the controller.
        val c = coastingController()
        c.autoRotate = true
        c.enableDamping = true
        c.dampingFactor = 0.95
        val autoStep = 30.0 * PI / 180.0 / 60.0

        // Re-derive the legacy per-frame loop from the velocity the same drag
        // seeds: 40 px at the 0.005 default sensitivity.
        //
        // Baseline is the post-drag theta, not zero: the 40 px now land on
        // theta when the pointer moves instead of on the following frame. On a
        // 60 Hz panel that is a one-frame shift of the same displacement — with
        // real frames interleaved the legacy loop applied it on the next
        // update() — and it is the whole point of the held-drag fix above. What
        // this test pins is the COAST that follows, which must still reproduce
        // `theta += velocity; velocity *= dampingFactor` frame for frame.
        var expected = c.theta
        var velocity = -40.0 * c.rotateSensitivity
        repeat(120) {
            expected += autoStep
            expected += velocity
            velocity *= 0.95
        }

        repeat(120) { c.update(FRAME_60) }
        assertEquals(
            expected,
            c.theta,
            1e-10,
            "two seconds at 60 Hz must reproduce the legacy per-frame loop exactly",
        )
    }
}
