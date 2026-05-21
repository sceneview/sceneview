package io.github.sceneview.web

import io.github.sceneview.web.bindings.Camera
import io.github.sceneview.web.bindings.float3
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
 * // Call update() each frame in the render loop
 * ```
 */
class OrbitCameraController(
    private val canvas: HTMLCanvasElement,
    private val camera: Camera
) {
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

    // Auto-rotation — default speed matches model-viewer's 30deg/sec at 60fps
    var autoRotate = false
    var autoRotateSpeed = 30.0 * PI / 180.0 / 60.0  // 30°/sec ÷ 60fps ≈ 0.00873 rad/frame

    // Damping (inertia) — higher factor = smoother, more model-viewer-like
    var enableDamping = true
    var dampingFactor = 0.95
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
     */
    fun update() {
        // Apply auto-rotation
        if (autoRotate && !isDragging) {
            theta += autoRotateSpeed
        }

        // Apply damping
        if (enableDamping) {
            theta += velocityTheta
            phi += velocityPhi
            velocityTheta *= dampingFactor
            velocityPhi *= dampingFactor
        }

        // Clamp phi
        phi = max(minPhi, min(maxPhi, phi))
        distance = max(minDistance, min(maxDistance, distance))

        // Convert spherical to cartesian
        val eyeX = targetX + distance * sin(phi) * sin(theta)
        val eyeY = targetY + distance * cos(phi)
        val eyeZ = targetZ + distance * sin(phi) * cos(theta)

        // Filament.js Camera.lookAt takes float3 arrays, not 9 separate doubles
        camera.lookAt(
            float3(eyeX, eyeY, eyeZ),          // eye
            float3(targetX, targetY, targetZ),  // center
            float3(0.0, 1.0, 0.0)              // up
        )
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
