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
    fun aLateFrameIsClampedInsteadOfLeaping() {
        // A tab returning from the background hands rAF a multi-second gap.
        // Unclamped, 90 s × 30°/s would spin the model seven times in one
        // frame. The motion pauses for the hitch instead.
        val c = controller().first
        c.enableDamping = false
        c.autoRotate = true
        c.theta = 0.0
        c.update(90.0)
        assertEquals(
            c.autoRotateSpeed * OrbitCameraController.MAX_MOTION_STEP,
            c.theta,
            EPS,
            "a 90 s gap must advance at most one clamped step, not ${c.theta} rad",
        )
        assertTrue(
            c.theta < 0.1,
            "the clamped step must stay far below a visible jump — was ${c.theta} rad",
        )
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
        var expected = 0.0
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
