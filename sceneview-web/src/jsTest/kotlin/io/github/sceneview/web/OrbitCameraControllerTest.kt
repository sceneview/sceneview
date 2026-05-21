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
        controller.update()

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
        controller.update()

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
        controller.update()
        assertEquals(0.1, controller.phi, EPS, "phi below minPhi must clamp up to minPhi")

        controller.phi = 99.0
        controller.update()
        assertEquals(PI - 0.1, controller.phi, EPS, "phi above maxPhi must clamp down to maxPhi")
    }

    @Test
    fun updateClampsDistanceWithinLimits() {
        val (controller, _) = controller()
        controller.enableDamping = false
        controller.minDistance = 0.5
        controller.maxDistance = 50.0

        controller.distance = 0.01
        controller.update()
        assertEquals(0.5, controller.distance, "distance below minDistance must clamp up")

        controller.distance = 9999.0
        controller.update()
        assertEquals(50.0, controller.distance, "distance above maxDistance must clamp down")
    }

    @Test
    fun autoRotateAdvancesThetaByOneStepPerFrame() {
        val (controller, _) = controller()
        controller.enableDamping = false
        controller.autoRotate = true
        controller.theta = 0.0
        val step = controller.autoRotateSpeed

        controller.update()
        assertEquals(step, controller.theta, EPS, "auto-rotate must advance theta by exactly one speed step")

        controller.update()
        assertEquals(2.0 * step, controller.theta, EPS, "two frames advance theta by two steps")
    }

    @Test
    fun autoRotateSpeedDefaultIs30DegPerSecAt60Fps() {
        val (controller, _) = controller()
        // 30°/sec ÷ 60fps = 0.5°/frame in radians.
        assertEquals(30.0 * PI / 180.0 / 60.0, controller.autoRotateSpeed, EPS)
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
        controller.update()
        controller.update()
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
        controller.update()
        controller.update()
        controller.update()
        assertEquals(3, cam.lookAtCalls, "every update() must push a fresh lookAt to the camera")
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
        controller.update()
        val eyeA = cam.eye.copyOf()

        controller.theta = 0.3 + 2.0 * PI
        controller.update()
        val eyeB = cam.eye.copyOf()

        for (i in 0..2) {
            assertTrue(
                abs(eyeA[i] - eyeB[i]) < 1e-6,
                "a full 2π orbit must land on the same eye position (axis $i: $eyeA vs $eyeB)",
            )
        }
    }
}
