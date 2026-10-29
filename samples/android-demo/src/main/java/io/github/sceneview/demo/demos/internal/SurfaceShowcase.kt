package io.github.sceneview.demo.demos.internal

/**
 * What the Surfaces demo does with its surfaces and its controls once something is placed
 * (#4307) — pure, so the rules have a JVM test pointed at them.
 *
 * The rule the screen used to break: placing the car hid the surfaces **and** left the car
 * frozen where it landed, with a banner that kept spinning. Now the surfaces only fade; the
 * car stays movable, the surfaces come back for the length of a drag so the finger can see
 * where it may land, and the dock keeps the two actions on screen the whole time.
 */
internal data class SurfaceShowcaseState(
    /** The car stands on a surface. */
    val placed: Boolean = false,
    /** What the Surfaces toggle in the dock says. Placing turns it off, removing back on. */
    val surfacesShown: Boolean = true,
    /** A finger is dragging the car. */
    val moving: Boolean = false,
) {
    /**
     * `ARSceneView(planeRenderer = …)`. A drag shows the surfaces whatever the toggle says:
     * the car can only land on one, and the finger needs to see where they end.
     */
    val planesVisible: Boolean get() = surfacesShown || moving

    /** The dock's Remove item: nothing to remove until the car is down. */
    val removeEnabled: Boolean get() = placed

    /** First placement: the surfaces step aside so the car is what the eye lands on. */
    fun onPlaced(): SurfaceShowcaseState = copy(placed = true, surfacesShown = false)

    /** Tap elsewhere while placed: the car jumps there, the toggle keeps its setting. */
    fun onRePlaced(): SurfaceShowcaseState = this

    /** Back to the scan: surfaces on again, whatever the toggle said. */
    fun onRemoved(): SurfaceShowcaseState = SurfaceShowcaseState()

    fun onSurfacesToggled(): SurfaceShowcaseState = copy(surfacesShown = !surfacesShown)

    /** Only a placed car can be dragged; a stray move before that changes nothing. */
    fun onMoveBegin(): SurfaceShowcaseState = if (placed) copy(moving = true) else this

    fun onMoveEnd(): SurfaceShowcaseState = copy(moving = false)
}

/**
 * The one sentence under the scene. Only [SCANNING] spins: once a surface is found the
 * phone is no longer working on anything, it is waiting for the user — a spinner there
 * reads as "still loading" (#4307).
 */
internal enum class SurfaceShowcaseBanner(val spinner: Boolean) {
    SCANNING(spinner = true),
    FOUND(spinner = false),
    PLACED(spinner = false),
    MOVE_PHONE(spinner = false),
}

/**
 * Which sentence to show, or `null` when something else on screen is already speaking: the
 * SDK's "AR unavailable" card (#3341) or its coaching card (one voice at a time).
 */
internal fun surfaceShowcaseBanner(
    arUnavailable: Boolean,
    coaching: Boolean,
    trackingLost: Boolean,
    surfaceFound: Boolean,
    placed: Boolean,
): SurfaceShowcaseBanner? = when {
    arUnavailable || coaching -> null
    trackingLost -> SurfaceShowcaseBanner.MOVE_PHONE
    placed -> SurfaceShowcaseBanner.PLACED
    surfaceFound -> SurfaceShowcaseBanner.FOUND
    else -> SurfaceShowcaseBanner.SCANNING
}
