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
         * Longest step, in seconds, [update] integrates at once.
         *
         * A frame that arrives late — a model landing on the main thread, a
         * shader compiling, or a background tab that rAF stopped ticking for a
         * minute — then pauses the motion for the length of the hitch instead
         * of leaping across it. Without this bound, returning to a backgrounded
         * tab would hand `update` a multi-second `deltaSeconds` and snap the
         * camera a third of the way round the model in one frame.
         *
         * Same value as iOS `CameraControls.maxMotionStep`, so a hitch reads
         * the same on both platforms.
         */
        const val MAX_MOTION_STEP: Double = 0.05

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
     * Orbit velocity left by a drag, in **radians per 1/60 s** — the unit the
     * pointer handlers below write it in ([rotateSensitivity] × pixels moved).
     * Paired with [dampingFactor], which decays at the same reference rate.
     */
    private var velocityTheta = 0.0
    private var velocityPhi = 0.0

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
     * Every self-driven motion is integrated against [deltaSeconds], so the
     * camera behaves identically whatever rate the host's `requestAnimationFrame`
     * fires at. The step is clamped to [MAX_MOTION_STEP] so a hitch — or a tab
     * returning from the background with a multi-second gap — pauses the motion
     * rather than leaping across it.
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
        // A late frame pauses the motion for the length of the hitch; a first
        // frame (dt = 0) advances nothing at all.
        val step = min(max(deltaSeconds, 0.0), MAX_MOTION_STEP)

        // Apply auto-rotation — rad/s integrated over the elapsed time.
        if (autoRotate && !isDragging) {
            theta += autoRotateSpeed * step
        }

        // Apply damping. `velocityTheta`/`velocityPhi` are per 1/60 s and
        // `dampingFactor` decays per 1/60 s, so first express the step in those
        // reference frames, then integrate the decaying velocity in closed form:
        // a velocity shrinking by `damping` each reference frame travels
        // `(1 - damping^frames) / (1 - damping)` times its current value over
        // `frames` of them. At exactly 60 Hz, frames = 1 and the travel is 1 —
        // the expression collapses to the previous `theta += velocityTheta;
        // velocityTheta *= dampingFactor`, so 60 Hz behaviour is bit-for-bit
        // what it was. Mirrors iOS `CameraControls.applyInertia(dt:)`.
        if (enableDamping) {
            val frames = step * DAMPING_REFERENCE_HZ
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
                0 -> { isDragging = true }    // Left button = orbit
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
                if (enableDamping) {
                    velocityTheta = -dx * rotateSensitivity
                    velocityPhi = -dy * rotateSensitivity
                } else {
                    theta -= dx * rotateSensitivity
                    phi -= dy * rotateSensitivity
                }
            } else if (isRightDragging) {
                // Pan
                targetX += dx * panSensitivity * distance
                targetY -= dy * panSensitivity * distance
            }
        }

        // Mouse up
        listen("mouseup") {
            isDragging = false
            isRightDragging = false
        }

        listen("mouseleave") {
            isDragging = false
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
                isDragging = true
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

                if (enableDamping) {
                    velocityTheta = -dx * rotateSensitivity
                    velocityPhi = -dy * rotateSensitivity
                } else {
                    theta -= dx * rotateSensitivity
                    phi -= dy * rotateSensitivity
                }
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
            isDragging = false
            lastPinchDistance = -1.0
        }
    }
}
