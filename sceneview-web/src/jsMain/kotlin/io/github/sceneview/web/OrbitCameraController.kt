package io.github.sceneview.web

import io.github.sceneview.web.bindings.Camera
import io.github.sceneview.web.bindings.float3
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Orbit camera controller for SceneView Web.
 *
 * Provides mouse/touch-based orbit, zoom, and pan controls similar to
 * three.js OrbitControls or Google model-viewer's camera-controls.
 *
 * Usage:
 * ```kotlin
 * val controller = OrbitCameraController(canvas, sceneView.camera)
 * controller.target(0.0, 0.0, 0.0)
 * controller.distance = 5.0
 * // Call update(deltaSeconds) each frame in the render loop
 * ```
 *
 * All self-driven motion — auto-rotation and the damping tail a released drag
 * leaves — is integrated against **elapsed time**, not against the frame count,
 * so the camera turns at the same speed on a 60 Hz panel and on a 120 Hz
 * ProMotion / Android display. See [update].
 *
 * A drag under the finger is not self-driven: the pointer handlers apply it to
 * [theta]/[phi] as it happens, so its gain is exactly the distance travelled ×
 * [rotateSensitivity] — no refresh rate in the expression at all. The velocity
 * they bank alongside it only seeds the inertia the release coasts on.
 */
class OrbitCameraController(
    private val canvas: HTMLCanvasElement,
    private val camera: Camera
) {
    companion object {
        /**
         * World-space distance below which a frame-to-frame eye/target change is
         * treated as floating-point noise or a settled damping tail rather than a
         * genuine move (#2332). At a typical few-metre framing this is far below
         * one screen pixel, so the render gate stops repainting once the camera
         * has visually settled — yet auto-rotate (≈0.5°/frame at 60 Hz) and any
         * real drag move the eye well beyond it, so live interaction always
         * repaints.
         */
        private const val MOVE_EPSILON: Double = 1e-6

        /**
         * Longest frame, in seconds, [update] still treats as real time.
         *
         * This is a *hitch detector*, not a clamp on the integration step. A
         * frame longer than this — a model landing on the main thread, a shader
         * compiling, or a background tab that rAF stopped ticking for a minute
         * — pauses the self-driven motion for the length of the hitch instead
         * of leaping across it. Without it, returning to a backgrounded tab
         * would hand `update` a multi-second `deltaSeconds` and snap the camera
         * a third of the way round the model in one frame.
         *
         * Every frame at or below it is integrated at its **true** length. The
         * distinction is the whole point: truncating the step instead (what
         * this constant did until #3742, at 0.05 s) silently reintroduced the
         * frame-rate dependence #3711 had just removed — on a software
         * rasteriser at ~8 fps *every* frame exceeded the bound, so the
         * turntable ran at 12.5°/s instead of its stated 30°/s, and a drag's
         * banked travel was divided by a frame count of at most 3 however long
         * the frame really was, inflating the inertia the release handed over.
         *
         * 0.25 s sits an order of magnitude below the shortest gap worth
         * calling a hitch and well above the slowest sustained rate a WebGL
         * canvas plausibly runs at (4 fps).
         *
         * Kept in step with `MAX_FRAME_STEP` in the web demo's
         * `samples/web-demo/site/js/sceneview.js`: both viewers must agree on
         * what counts as a hitch. iOS `CameraControls.maxMotionStep` still
         * truncates at 0.05 s and carries the same defect — tracked separately,
         * out of scope for this web fix.
         */
        const val MAX_FRAME_STEP: Double = 0.25

        /**
         * Reference rate the damping model is expressed against: [dampingFactor]
         * is "velocity retained per 1/60 s", whatever rate [update] actually
         * runs at. Keeping the unit pinned to 60 Hz means the shipped default
         * (and any value a consumer already tuned by eye) produces exactly the
         * same inertia it always did on a 60 Hz panel.
         */
        private const val DAMPING_REFERENCE_HZ: Double = 60.0

        /**
         * Ceiling applied to [dampingFactor] before it is used. `1.0` would be
         * a velocity that never decays — an inertia that never ends — and it
         * also makes the closed-form travel below divide by zero. Matches iOS
         * `CameraControls.applyInertia`.
         */
        private const val MAX_DAMPING_FACTOR: Double = 0.999
    }

    // Spherical coordinates — defaults match model-viewer's "45deg 70deg 2.5m"
    var theta = 45.0 * PI / 180.0   // horizontal angle (radians) — 45° like model-viewer
    var phi = 70.0 * PI / 180.0     // vertical angle (radians) — 70° like model-viewer
    var distance = 5.0              // distance from target

    // Target point (orbit center)
    var targetX = 0.0
    var targetY = 0.0
    var targetZ = 0.0

    // Limits
    var minDistance = 0.5
    var maxDistance = 50.0
    var minPhi = 0.1              // prevent looking straight down
    var maxPhi = PI - 0.1         // prevent looking straight up

    // Sensitivity
    var rotateSensitivity = 0.005
    var zoomSensitivity = 0.1
    var panSensitivity = 0.003

    // Auto-rotation
    var autoRotate = false

    /**
     * Auto-rotation angular speed in **radians per second**.
     *
     * Default is model-viewer's 30°/s. Because the speed is integrated against
     * elapsed time, the turntable completes a revolution in 12 s on every
     * display — 60 Hz, 90 Hz, 120 Hz ProMotion — and a dropped frame costs
     * smoothness, never travel.
     *
     * Same name and same unit as iOS `CameraControls.autoRotateSpeed` (whose
     * own default is 0.3 rad/s); Android expresses orbit speed through
     * Filament's `CameraManipulator.orbitSpeed`.
     */
    var autoRotateSpeed = 30.0 * PI / 180.0  // 30°/sec ≈ 0.5236 rad/s

    // Damping (inertia) — higher factor = smoother, more model-viewer-like
    var enableDamping = true

    /**
     * Share of the orbit velocity retained per 1/60 s of inertia — `0.95`
     * keeps 95 % of it every 60th of a second, wherever the frame boundaries
     * actually fall. [update] raises it to the power of the elapsed 60 Hz
     * frames, so the tail lasts the same wall-clock time at any refresh rate.
     *
     * Values are clamped to `0 … 0.999`: `1.0` is an inertia that never ends.
     */
    var dampingFactor = 0.95

    /**
     * Orbit velocity a drag left behind, in **radians per 1/60 s**. Paired with
     * [dampingFactor], which decays at the same reference rate.
     *
     * It is the seed of the inertia tail a *released* drag coasts on — never
     * the drag itself. While the button is down the pointer handlers write
     * [theta]/[phi] directly, so the gesture's gain is exactly
     * `pixels × rotateSensitivity` whatever the refresh rate.
     */
    private var velocityTheta = 0.0
    private var velocityPhi = 0.0

    /**
     * Orbit the pointer handlers applied since the velocity was last resampled,
     * in radians. [update] divides it by the elapsed reference frames to get a
     * velocity in the unit above — i.e. pointer travel per unit *time*, not per
     * pointer event. A 144 Hz panel splits the same flick into more, smaller
     * `mousemove`s than a 60 Hz one; dividing by elapsed time is what makes the
     * release hand the same inertia to the damping model at either rate.
     */
    private var dragTravelTheta = 0.0
    private var dragTravelPhi = 0.0

    /**
     * Length of the most recent frame, counted in 1/60 s reference frames.
     *
     * Used to convert the drag travel that arrives *after* the last frame — a
     * flick that ends between two `update()` calls — into the same velocity
     * unit on release. Starts at one reference frame, so a press-move-release
     * that never saw a frame at all is credited at the documented 60 Hz rate.
     */
    private var lastFrameCount = 1.0

    // Mouse state
    private var isDragging = false
    private var isRightDragging = false
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastPinchDistance = -1.0

    /** Tracks every (type, handler) pair so [dispose] can detach all of them. */
    private val listeners = mutableListOf<Pair<String, EventListener>>()
    private var disposed = false

    // Reusable float3 scratch arrays for the per-frame [update] lookAt call (#2274).
    // Filament.js reads these synchronously inside lookAt, so mutating them in place
    // every frame is safe and eliminates 3 array allocations per requestAnimationFrame
    // tick (the GC sawtooth, worst on iOS Safari). `up` is the constant world-up.
    private val eyeScratch: dynamic = float3(0.0, 0.0, 0.0)
    private val centerScratch: dynamic = float3(0.0, 0.0, 0.0)
    private val upScratch: dynamic = float3(0.0, 1.0, 0.0)

    // Last eye/target [update] resolved, so the render gate (#2332) can tell
    // whether this frame's camera actually moved. `hasUpdated` forces the very
    // first frame to count as moved (there is no prior pose to compare against).
    private var hasUpdated = false
    private var lastEyeX = 0.0
    private var lastEyeY = 0.0
    private var lastEyeZ = 0.0
    private var lastTargetX = 0.0
    private var lastTargetY = 0.0
    private var lastTargetZ = 0.0

    init {
        setupEventListeners()
    }

    fun target(x: Double, y: Double, z: Double) {
        targetX = x; targetY = y; targetZ = z
    }

    /**
     * Update camera position from spherical coordinates.
     * Call this every frame in the render loop.
     *
     * Converts spherical coordinates (theta, phi, distance) to Cartesian
     * and calls camera.lookAt() with float3 arrays as required by Filament.js.
     *
     * Every self-driven motion is integrated against the **true**
     * [deltaSeconds], so the camera behaves identically whatever rate the
     * host's `requestAnimationFrame` fires at — including the low sustained
     * rates a software rasteriser produces. A frame longer than
     * [MAX_FRAME_STEP] is read as a hitch — a tab returning from the background
     * with a multi-second gap — and pauses the motion rather than leaping
     * across it.
     *
     * @param deltaSeconds Seconds elapsed since the previous frame. Pass `0.0`
     *   on the very first frame (there is no previous timestamp to subtract):
     *   nothing self-driven advances and the camera renders exactly on its
     *   authored pose. Negative values are treated as `0.0`.
     * @return `true` if the resolved eye or target moved since the previous
     *   frame (auto-rotate, a damping tail, or a fresh drag/zoom/pan) — the
     *   signal the render gate uses to decide whether to repaint (#2332). The
     *   `lookAt` itself still runs every frame, so the Filament camera always
     *   reflects the current pose even on frames the gate skips drawing.
     */
    fun update(deltaSeconds: Double): Boolean {
        // The true elapsed time — a frame is never shortened to make it fit.
        // A first frame (dt = 0) advances nothing at all; a frame longer than
        // MAX_FRAME_STEP is a hitch and pauses the self-driven motion.
        val dt = max(deltaSeconds, 0.0)
        val hitch = dt > MAX_FRAME_STEP

        // Apply auto-rotation — rad/s integrated over the elapsed time.
        if (autoRotate && !isDragging && !hitch) {
            theta += autoRotateSpeed * dt
        }

        if (dt > 0.0) {
            lastFrameCount = dt * DAMPING_REFERENCE_HZ
            // While the button is down, this frame's pointer travel — already
            // applied to theta/phi by the handlers — becomes the velocity the
            // release will coast on, expressed per 1/60 s. Dividing by the
            // frame's own length is what keeps the tail rate-independent: the
            // same flick is one 12 px `mousemove` per frame at 60 Hz and two
            // 6 px ones at 120 Hz, and both must read as the same speed.
            //
            // Across a hitch that division is meaningless — the pointer did not
            // travel for the whole of a backgrounded minute, the handlers just
            // banked whatever arrived before the tab froze. Crediting it at any
            // rate at all invents a flick, so the bank is dropped instead. (A
            // truncated divisor, the pre-#3742 behaviour, inflated it: the same
            // travel over a 1 s frame read as a 0.05 s flick, 20× too fast.)
            if (isDragging) {
                if (hitch) {
                    velocityTheta = 0.0
                    velocityPhi = 0.0
                } else {
                    velocityTheta = dragTravelTheta / lastFrameCount
                    velocityPhi = dragTravelPhi / lastFrameCount
                }
                dragTravelTheta = 0.0
                dragTravelPhi = 0.0
            }
        }

        // Apply damping — the tail of a RELEASED drag, never the live one. A
        // held drag writes theta/phi straight from the pointer handlers, so
        // running this while `isDragging` would multiply the gesture's own gain
        // by `travel` below (×1.95 at 30 Hz, ×0.42 at 144 Hz) and make the drag
        // depend on the refresh rate — the very defect this class fixes.
        //
        // `velocityTheta`/`velocityPhi` are per 1/60 s and `dampingFactor`
        // decays per 1/60 s, so first express the step in those reference
        // frames, then integrate the decaying velocity in closed form: a
        // velocity shrinking by `damping` each reference frame travels
        // `(1 - damping^frames) / (1 - damping)` times its current value over
        // `frames` of them. At exactly 60 Hz, frames = 1 and the travel is 1 —
        // the expression collapses to the previous `theta += velocityTheta;
        // velocityTheta *= dampingFactor`, so 60 Hz behaviour is bit-for-bit
        // what it was. Mirrors iOS `CameraControls.applyInertia(dt:)`.
        //
        // This one runs on the true `dt` even across a hitch, and needs no
        // guard of its own: `travel` is `(1 - decay) / (1 - damping)` with
        // `decay` in [0, 1), so however long the frame, the tail advances at
        // most `velocity / (1 - damping)` — 20× the current velocity at the
        // shipped 0.95, about a third of a second of coasting — and lands
        // exactly where it would have had the frames arrived on time. It is
        // the tail finishing, not a leap across the gap.
        if (enableDamping && !isDragging) {
            val frames = dt * DAMPING_REFERENCE_HZ
            val damping = min(max(dampingFactor, 0.0), MAX_DAMPING_FACTOR)
            val decay = damping.pow(frames)
            val travel = (1.0 - decay) / (1.0 - damping)
            theta += velocityTheta * travel
            phi += velocityPhi * travel
            velocityTheta *= decay
            velocityPhi *= decay
        }

        // Clamp phi
        phi = max(minPhi, min(maxPhi, phi))
        distance = max(minDistance, min(maxDistance, distance))

        // Convert spherical to cartesian
        val eyeX = targetX + distance * sin(phi) * sin(theta)
        val eyeY = targetY + distance * cos(phi)
        val eyeZ = targetZ + distance * sin(phi) * cos(theta)

        // Filament.js Camera.lookAt takes float3 arrays, not 9 separate doubles.
        // Mutate the reusable scratch arrays in place instead of allocating fresh
        // ones every frame (#2274). lookAt reads them synchronously, so reuse is safe.
        eyeScratch[0] = eyeX; eyeScratch[1] = eyeY; eyeScratch[2] = eyeZ
        centerScratch[0] = targetX; centerScratch[1] = targetY; centerScratch[2] = targetZ
        camera.lookAt(eyeScratch, centerScratch, upScratch)

        // Did the pose actually change? Sub-MOVE_EPSILON deltas are floating-point
        // noise / a settled damping tail and must NOT keep the gate awake forever.
        val moved = !hasUpdated ||
            abs(eyeX - lastEyeX) > MOVE_EPSILON ||
            abs(eyeY - lastEyeY) > MOVE_EPSILON ||
            abs(eyeZ - lastEyeZ) > MOVE_EPSILON ||
            abs(targetX - lastTargetX) > MOVE_EPSILON ||
            abs(targetY - lastTargetY) > MOVE_EPSILON ||
            abs(targetZ - lastTargetZ) > MOVE_EPSILON
        lastEyeX = eyeX; lastEyeY = eyeY; lastEyeZ = eyeZ
        lastTargetX = targetX; lastTargetY = targetY; lastTargetZ = targetZ
        hasUpdated = true
        return moved
    }

    /**
     * Remove every DOM event listener this controller registered on the canvas.
     *
     * Must be called when destroying the SceneView — the listener lambdas
     * capture `this` (and therefore the Filament [Camera]), so leaving them
     * attached pins the destroyed controller + camera to a canvas that
     * typically outlives the SceneView, and stale `wheel`/`mousemove` events
     * keep mutating a dead controller (#1698).
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        listeners.forEach { (type, handler) ->
            // Options must match the add call; `wheel`/`touch*` were registered
            // with {passive: false}. removeEventListener ignores a mismatch
            // silently, so always pass the same options object shape.
            canvas.removeEventListener(type, handler, js("{passive: false}"))
        }
        listeners.clear()
    }

    /**
     * A pointer took hold of the camera. Any inertia still running belongs to
     * the previous flick: drop it, so grabbing a coasting model stops it dead
     * under the finger instead of letting the old tail keep turning it — and so
     * a motionless press followed by a release cannot resurrect it.
     */
    private fun beginDrag() {
        isDragging = true
        velocityTheta = 0.0
        velocityPhi = 0.0
        dragTravelTheta = 0.0
        dragTravelPhi = 0.0
    }

    /**
     * Orbit by the angles a pointer move just covered.
     *
     * The move lands on [theta]/[phi] immediately — a gesture's gain is the
     * distance the finger travelled, never a function of how often the host
     * calls [update] — and is banked in the drag travel so [update] can turn it
     * into the velocity a release will coast on.
     */
    private fun orbitBy(deltaTheta: Double, deltaPhi: Double) {
        theta += deltaTheta
        phi += deltaPhi
        dragTravelTheta += deltaTheta
        dragTravelPhi += deltaPhi
    }

    /**
     * The pointer let go. Travel that arrived since the last frame — a flick
     * that ends between two [update] calls, which is most of them — has not
     * been turned into velocity yet; credit it at the most recent frame's
     * length so the tail it seeds is the same at any refresh rate.
     */
    private fun endDrag() {
        if (dragTravelTheta != 0.0 || dragTravelPhi != 0.0) {
            velocityTheta = dragTravelTheta / lastFrameCount
            velocityPhi = dragTravelPhi / lastFrameCount
            dragTravelTheta = 0.0
            dragTravelPhi = 0.0
        }
        isDragging = false
    }

    /** Register [handler] on the canvas and record it for later removal. */
    private fun listen(type: String, handler: (Event) -> Unit) {
        val listener = EventListener { handler(it) }
        listeners.add(type to listener)
        canvas.addEventListener(type, listener, js("{passive: false}"))
    }

    @Suppress("LongMethod") // event listener setup registers many event types — splitting would hurt cohesion
    private fun setupEventListeners() {
        // Mouse down
        listen("mousedown") { event ->
            val e = event as MouseEvent
            when (e.button.toInt()) {
                0 -> beginDrag()                // Left button = orbit
                2 -> { isRightDragging = true } // Right button = pan
            }
            lastX = e.clientX.toDouble()
            lastY = e.clientY.toDouble()
            e.preventDefault()
        }

        // Mouse move
        listen("mousemove") { event ->
            val e = event as MouseEvent
            val dx = e.clientX.toDouble() - lastX
            val dy = e.clientY.toDouble() - lastY
            lastX = e.clientX.toDouble()
            lastY = e.clientY.toDouble()

            if (isDragging) {
                // Orbit
                orbitBy(-dx * rotateSensitivity, -dy * rotateSensitivity)
            } else if (isRightDragging) {
                // Pan
                targetX += dx * panSensitivity * distance
                targetY -= dy * panSensitivity * distance
            }
        }

        // Mouse up
        listen("mouseup") {
            endDrag()
            isRightDragging = false
        }

        listen("mouseleave") {
            endDrag()
            isRightDragging = false
        }

        // Scroll wheel = zoom
        listen("wheel") { event ->
            val e = event as WheelEvent
            val delta = if (e.deltaY > 0) 1.0 else -1.0
            distance *= 1.0 + delta * zoomSensitivity
            distance = max(minDistance, min(maxDistance, distance))
            e.preventDefault()
        }

        // Prevent context menu on right-click
        listen("contextmenu") { event ->
            event.preventDefault()
        }

        // Touch support
        listen("touchstart") { event ->
            val e = event.asDynamic()
            if (e.touches.length == 1) {
                beginDrag()
                lastX = (e.touches[0].clientX as Number).toDouble()
                lastY = (e.touches[0].clientY as Number).toDouble()
            }
            event.preventDefault()
        }

        listen("touchmove") { event ->
            val e = event.asDynamic()
            if (isDragging && e.touches.length == 1) {
                val dx = (e.touches[0].clientX as Number).toDouble() - lastX
                val dy = (e.touches[0].clientY as Number).toDouble() - lastY
                lastX = (e.touches[0].clientX as Number).toDouble()
                lastY = (e.touches[0].clientY as Number).toDouble()

                orbitBy(-dx * rotateSensitivity, -dy * rotateSensitivity)
            } else if (e.touches.length == 2) {
                // Pinch-to-zoom
                val dx = (e.touches[0].clientX as Number).toDouble() - (e.touches[1].clientX as Number).toDouble()
                val dy = (e.touches[0].clientY as Number).toDouble() - (e.touches[1].clientY as Number).toDouble()
                val pinchDistance = kotlin.math.sqrt(dx * dx + dy * dy)

                if (lastPinchDistance > 0) {
                    val delta = lastPinchDistance - pinchDistance
                    distance *= 1.0 + delta * 0.005
                    distance = max(minDistance, min(maxDistance, distance))
                }
                lastPinchDistance = pinchDistance
            }
            event.preventDefault()
        }

        listen("touchend") {
            endDrag()
            lastPinchDistance = -1.0
        }
    }
}
