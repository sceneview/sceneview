package io.github.sceneview.ar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import kotlin.math.abs
import kotlin.math.sqrt

/** Observable automatic-placement phases shared with the Apple placement controller. */
enum class PlacementPhase {
    /**
     * No *tracked* camera frame yet. Frames that arrive before tracking starts keep the
     * session here — they are the normal start-up, not a tracking loss. See
     * [AutoPlacementState.hasCameraFrame] for "has any frame arrived at all".
     */
    INITIALIZING,

    /** Camera tracking, request pending, searching for a usable surface. */
    SCANNING,

    /** [AutoPlacementState.NO_SURFACE_TIMEOUT_MS] of scanning without a surface. */
    NO_SURFACE,

    /** The object is anchored and its anchor is tracking. */
    PLACED,

    /** A surface-constrained gesture is active. */
    ADJUSTING,

    /** The camera is not tracking (or a QA failure is forced). */
    TRACKING_LOST,

    /** Camera back, but the placement's anchor has not re-tracked yet. */
    RECOVERING,

    /** [AutoPlacementState.RECOVERY_TIMEOUT_MS] of recovering — offer *Scan again*. */
    RECOVERY_FAILED,

    /** The session never delivered a single frame past the init scrim's timeout. */
    CAMERA_ERROR,
}

/**
 * The generations an asynchronous asset result was issued under. A result is only allowed
 * to populate the scene when both still match the controller's current ones — see
 * [AutoPlacementState.acceptsAsset].
 */
data class AssetTicket(val session: Int, val selection: Int)

/** What the session must do after one frame has been fed to the controller. */
enum class FrameEffect {
    NONE,

    /** Create the anchor on the surface that was offered, and tick `medium()`. */
    PLACE,

    /** Tracking was just lost — `warning()` once, never per frame. */
    TRACKING_LOST,
}

/**
 * One frame's worth of facts, already reduced to booleans by the session layer.
 *
 * @param nowMillis monotonic clock.
 * @param tracking `frame.camera.trackingState == TRACKING` and no forced failure.
 * @param surfaceAvailable a usable surface was found this frame (only evaluated by the
 *   session when [AutoPlacementState.wantsSurface] is true, so the hit test is not run
 *   at 60 Hz for nothing).
 * @param anchorTracking the placement's anchor tracking state, `null` when nothing is placed.
 * @param estimatedAvailable the session can place on the estimated floor this frame (instant
 *   placement or a floor guess), used once [AutoPlacementState.estimatedFallbackMillis] ran out.
 * @param anchorSettled the placement's anchor is fully tracked (a plane, or an instant-placement
 *   point ARCore has resolved), `null` when nothing is placed or it is unknown.
 */
data class FrameInput(
    val nowMillis: Long,
    val tracking: Boolean,
    val surfaceAvailable: Boolean,
    val anchorTracking: Boolean? = null,
    val estimatedAvailable: Boolean = false,
    val anchorSettled: Boolean? = null,
)

/** What an automatic placement stands on. */
enum class PlacementSource {
    /** A tracked plane (or a wall) under the viewport: the anchor is settled from the start. */
    SURFACE,

    /**
     * The estimated floor, after [AutoPlacementState.estimatedFallbackMillis] without a surface:
     * an instant-placement point at the expected depth, settled once ARCore tracks it fully.
     */
    ESTIMATED,
}

class AutoPlacementState {

    /** Current multiplier of the selected preview/authored base size. */
    var scaleFactor: Float by mutableStateOf(1f)
        internal set

    internal var moveAction: ((Float, Float) -> Boolean)? = null
    internal var rotateAction: ((Float) -> Unit)? = null
    internal var scaleAction: ((Float) -> Unit)? = null
    internal var scaleToggleAction: (() -> Unit)? = null
    internal var recenterAction: (() -> Boolean)? = null
    internal var touchDownAction: (() -> Unit)? = null

    /** Gestures that began off the object: [AutoPlacementScene] hands them to the placed pivot. */
    internal var screenGestures: ScreenGestures? = null

    /** Moves the placed object onto a new candidate ([recenter]); set by the placed pivot. */
    internal var relocateAction: ((AutoPlacementCandidate) -> Boolean)? = null

    /**
     * Draw the placement ring, a flat line on the floor around the placed [AutoPlacementModel]:
     * dashed while the spot is estimated, closed once tracked, wider under the fingers. It shows
     * as the object lands and while it is handled, then fades out ([PlacementRingVisibility]).
     * On by default; set `false` to draw your own chrome from [phase] and [isSettled].
     */
    var showsPlacementRing: Boolean by mutableStateOf(true)

    @Deprecated("The torus selection ring is now the flat placement ring.", ReplaceWith("showsPlacementRing"))
    var showsSelectionRing: Boolean
        get() = showsPlacementRing
        set(value) { showsPlacementRing = value }

    /**
     * How long the camera tracks without a usable surface before the object places itself on the
     * estimated floor, [PlacementFeel.ESTIMATED_FALLBACK_MS] by default. `null` waits for a surface
     * however long it takes, as before.
     */
    var estimatedFallbackMillis: Long? by mutableStateOf(PlacementFeel.ESTIMATED_FALLBACK_MS)

    /**
     * How far ahead, metres, the object places itself on the estimated floor and comes back to on
     * [recenter]. Size it to the model with [PlacementMath.placementDistanceFor], so a sofa stands
     * whole in view at its real size:
     *
     * ```kotlin
     * state.placementDistance = PlacementMath.placementDistanceFor(largestSideMeters)
     * ```
     */
    var placementDistance: Float by mutableStateOf(PlacementMath.placementDistanceFor(0.5f))

    /** What the placed object stands on, `null` while nothing is placed. */
    var placementSource: PlacementSource? by mutableStateOf(null)
        private set

    /**
     * The placement's anchor is fully tracked: always on a surface, and on the estimated floor
     * once ARCore resolves the instant-placement point. The ring's line closes when it turns true.
     */
    var isSettled: Boolean by mutableStateOf(false)
        private set

    /** Bumped each time the object lands on a new spot: placed, recentred or re-anchored. */
    var placementRevision: Int by mutableStateOf(0)
        private set

    /** The object was re-anchored by a drag or [recenter]. */
    internal fun relocated(source: PlacementSource, settled: Boolean) {
        if (!hasPlacement) return
        placementSource = source
        isSettled = settled
        placementRevision++
    }

    /** A finger touched the screen: whatever coasts stops. */
    internal fun touchDown() {
        touchDownAction?.invoke()
    }

    /**
     * Brings the placed object back in front of the user, [placementDistance] ahead on the floor
     * the camera looks at, facing them; the user's own turn on top is kept. Returns `false` when
     * nothing stands or it cannot move now (tracking lost, recovering).
     */
    fun recenter(): Boolean = hasPlacement &&
        (phase == PlacementPhase.PLACED || phase == PlacementPhase.ADJUSTING) &&
        recenterAction?.invoke() == true

    /**
     * Let the user resize the placed object with a pinch, [scaleTo] or the double-tap.
     *
     * Set `false` for anything that must stay at real size (furniture, a pool, a shed): the
     * pinch, [scaleTo] and the double-tap stop resizing it, move and rotate keep working, and
     * an object already resized animates back to 100 %. Same meaning as Quick Look's
     * `allowsContentScaling = false`, Scene Viewer's `resizable=false` and model-viewer's
     * `ar-scale="fixed"`.
     *
     * ```kotlin
     * val state = remember { AutoPlacementState().apply { allowsScaling = false } }
     * ```
     */
    var allowsScaling: Boolean by mutableStateOf(true)

    /** Gesture-driven haptic events (snap, limit, invalid move); set by [ARHapticFeedback]. */
    internal var gestureHapticSink: ((io.github.sceneview.haptic.ARHapticEvent) -> Unit)? = null
    private val canManipulate: Boolean
        get() = isSelected && (phase == PlacementPhase.PLACED || phase == PlacementPhase.ADJUSTING)

    /** Accessibility alternative: metres along the surface's right and up (wall) / forward axes. */
    fun moveBy(x: Float, y: Float): Boolean = canManipulate && x.isFinite() && y.isFinite() &&
        moveAction?.invoke(x, y) == true

    /** Accessibility alternative: degrees about the surface normal, preserving contact. */
    fun rotateBy(degrees: Float) {
        if (canManipulate && degrees.isFinite()) rotateAction?.invoke(degrees)
    }

    /**
     * How far the pinch, [scaleTo] and the double-tap may resize the placed object, as a
     * multiplier of its base size: 25–400 % by default, Scene Viewer's range.
     *
     * Widen it when the base size is not the only sensible one — a 1:16 miniature that the
     * user may pinch all the way up to real size takes `0.25f..16f`. The 100 % detent stays
     * at the base size. An empty or non-positive range is ignored. Changing it does not resize
     * an object already placed: [scaleFactor] keeps its value, even outside the new range,
     * until the next pinch, [scaleTo] or double-tap brings it back inside.
     *
     * ```kotlin
     * val state = remember { AutoPlacementState().apply { scaleRange = 0.25f..16f } }
     * ```
     */
    var scaleRange: ClosedFloatingPointRange<Float> by mutableStateOf(ScaleSnap.RANGE)

    /** [scaleRange], or the default one when it is unusable. */
    internal val usableScaleRange: ClosedFloatingPointRange<Float>
        get() = scaleRange.takeIf {
            it.start > 0f && it.start.isFinite() && it.endInclusive.isFinite() && it.start <= it.endInclusive
        } ?: ScaleSnap.RANGE

    /**
     * Accessibility alternative: uniform base-size multiplier, clamped to [scaleRange]
     * (25–400 % by default). Ignored while [allowsScaling] is `false`.
     */
    fun scaleTo(factor: Float) {
        if (allowsScaling && canManipulate && factor.isFinite()) {
            val range = usableScaleRange
            scaleAction?.invoke(factor.coerceIn(range.start, range.endInclusive))
        }
    }

    /**
     * The double-tap on the placed object: selects it, then animates it back to exactly
     * 100 % if it was resized — or, already at 100 %, back to the size the user had pinched
     * it to before (a toggle, not a one-way reset). Never resized, it plays the 100 %
     * rebound so the tap is acknowledged. While [allowsScaling] is `false` it only selects.
     * Wire it to `onDoubleTap` when the tapped node is the placed model;
     * [AutoPlacementScene] already does.
     */
    fun toggleBaseScale() {
        if (!hasPlacement || (phase != PlacementPhase.PLACED && phase != PlacementPhase.ADJUSTING)) return
        isSelected = true
        if (allowsScaling) scaleToggleAction?.invoke()
    }

    var phase: PlacementPhase by mutableStateOf(PlacementPhase.INITIALIZING)
        private set

    /** Bumped by [dismiss]; part of every [AssetTicket]. */
    var sessionGeneration: Int = 0
        private set

    /** Bumped by [selectModel]; part of every [AssetTicket]. */
    var selectionGeneration: Int = 0
        private set

    /** A placement request is pending and has not been consumed. */
    var placementRequested: Boolean = false
        private set

    /** An anchor exists for this session (it may be paused — see [PlacementPhase.RECOVERING]). */
    var hasPlacement: Boolean = false
        private set

    /** Total anchors ever created by this controller — the number the tests count. */
    var placementsCreated: Int = 0
        private set

    /** `nowMillis` of the last [FrameEffect.PLACE], `0` when nothing was placed. */
    var placedAtMillis: Long = 0L
        private set

    /**
     * At least one camera frame reached [onFrame] this session, tracking or not. The init
     * scrim keys on this rather than on [PlacementPhase.INITIALIZING], which also covers
     * the first untracked frames of a normal start.
     */
    var hasCameraFrame: Boolean by mutableStateOf(false)
        private set

    /**
     * The floor's height in ARCore world space (metres): the lowest tracked upward-facing
     * plane, `null` before one is tracked; a floor that stops tracking for a while keeps its last
     * height. [AutoPlacementScene] keeps it up to date. An object
     * placed on a table that should stand on the floor instead — a scanned room at its real
     * size — drops by `floorHeight - placement.pose.ty()`.
     *
     * It moves only when the floor does by [FLOOR_STEP_M] or more, so the plane's frame-to-frame
     * jitter does not recompose whoever reads it.
     */
    var floorHeight: Float? by mutableStateOf(null)
        private set

    /** The floor [AutoPlacementScene] tracks this frame ([floorHeight]); `null` while there is none. */
    internal fun onFloor(height: Float?) {
        if (height == null) return
        val current = floorHeight
        if (current == null || abs(height - current) >= FLOOR_STEP_M) floorHeight = height
    }

    /**
     * The camera's height in ARCore world space (metres), `null` before the camera tracks. With
     * [floorHeight] it tells a placement on the floor (the camera a standing person's height
     * above it) from one on a table. [AutoPlacementScene] keeps it up to date, in
     * [CAMERA_STEP_M] steps so walking around does not recompose whoever reads it every frame.
     */
    var cameraHeight: Float? by mutableStateOf(null)
        private set

    /** The tracked camera's height this frame ([cameraHeight]); `null` while it does not track. */
    internal fun onCamera(height: Float?) {
        if (height == null || !height.isFinite()) return
        val current = cameraHeight
        if (current == null || abs(height - current) >= CAMERA_STEP_M) cameraHeight = height
    }

    private var searchStartedAt: Long? = null
    private var recoveringSince: Long? = null
    private var phaseBeforeLoss: PlacementPhase = PlacementPhase.SCANNING
    private var dismissed = false

    /** Gesture permission is revoked immediately on tracking loss. */
    var isSelected: Boolean by mutableStateOf(false)
        private set

    fun selectPlacement() { if (hasPlacement) isSelected = true }
    fun deselectPlacement() { isSelected = false }

    private var adjustmentCount = 0

    var isAdjusting: Boolean = false
        private set

    fun beginAdjustment(): Boolean {
        if (phase != PlacementPhase.PLACED && phase != PlacementPhase.ADJUSTING) return false
        isSelected = true
        adjustmentCount++
        isAdjusting = true
        phase = PlacementPhase.ADJUSTING
        return true
    }

    fun endAdjustment() {
        adjustmentCount = (adjustmentCount - 1).coerceAtLeast(0)
        isAdjusting = adjustmentCount > 0
        if (!isAdjusting && phase == PlacementPhase.ADJUSTING) phase = PlacementPhase.PLACED
    }

    /** Whether the session should bother running the surface search this frame. */
    val wantsSurface: Boolean
        get() = placementRequested && !hasPlacement && !dismissed

    val ticket: AssetTicket
        get() = AssetTicket(sessionGeneration, selectionGeneration)

    /** Ask for one placement. Idempotent while a request is already pending. */
    fun requestPlacement() {
        if (dismissed || hasPlacement) return
        placementRequested = true
    }

    /**
     * The user picked another model — every in-flight asset result is now stale. Also the
     * way back into a [dismiss]ed controller: the host keeps one state across chooser ↔
     * camera round trips, so leaving the camera must not brick the next entry. A ticket
     * minted before the dismissal still carries the old session generation and stays
     * refused.
     */
    fun selectModel(): AssetTicket {
        dismissed = false
        selectionGeneration++
        return ticket
    }

    /**
     * The selection now points at an asset that has not arrived yet (a streamed row still
     * downloading). Nothing may be placed until it lands: the pending request is withdrawn
     * so a surface found in the meantime does not stand the *previous* model in the room
     * under the new row's name. A standing placement is untouched — the swap happens when
     * the asset lands (§2.2).
     */
    fun withdrawRequest() {
        if (hasPlacement) return
        placementRequested = false
        searchStartedAt = null
        if (phase == PlacementPhase.NO_SURFACE) phase = PlacementPhase.SCANNING
    }

    /**
     * Gate for an asynchronous asset result. A result can only land when it was issued for
     * the current session **and** the current selection, and the session is still alive.
     */
    fun acceptsAsset(ticket: AssetTicket): Boolean =
        !dismissed && ticket == this.ticket

    /**
     * Leave the camera: nothing issued before this may ever populate the scene again, and
     * no frame is acted on until the next [selectModel] opens a new session generation.
     */
    fun dismiss() {
        adjustmentCount = 0
        isAdjusting = false
        isSelected = false
        scaleFactor = 1f
        cameraHeight = null
        moveAction = null
        rotateAction = null
        scaleAction = null
        scaleToggleAction = null
        touchDownAction = null
        screenGestures = null
        relocateAction = null
        placementSource = null
        isSettled = false
        dismissed = true
        sessionGeneration++
        placementRequested = false
        hasPlacement = false
        placedAtMillis = 0L
        searchStartedAt = null
        recoveringSince = null
        phaseBeforeLoss = PlacementPhase.SCANNING
        hasCameraFrame = false
        phase = PlacementPhase.INITIALIZING
    }

    /**
     * *Reset placement* / *Scan again*: the anchor goes, the asset stays, the search restarts.
     * The caller detaches the anchor; this only rewrites the decision state.
     */
    fun resetPlacement(nowMillis: Long) {
        if (dismissed) return
        adjustmentCount = 0
        isAdjusting = false
        isSelected = false
        scaleFactor = 1f
        hasPlacement = false
        placementSource = null
        isSettled = false
        placementRequested = true
        recoveringSince = null
        searchStartedAt = nowMillis
        placedAtMillis = 0L
        if (phase != PlacementPhase.INITIALIZING && phase != PlacementPhase.TRACKING_LOST) {
            phase = PlacementPhase.SCANNING
        }
        phaseBeforeLoss = PlacementPhase.SCANNING
    }

    /** *Keep scanning* on the no-surface card: a fresh 10 s interval, detection never stopped. */
    fun keepScanning(nowMillis: Long) {
        if (phase != PlacementPhase.NO_SURFACE) return
        searchStartedAt = nowMillis
        phase = PlacementPhase.SCANNING
    }

    /**
     * The init scrim gave up and no frame ever came. A no-op once any frame arrived: a
     * session that delivers frames but has not started tracking yet is starting, not broken.
     */
    fun cameraFailed() {
        if (phase == PlacementPhase.INITIALIZING && !hasCameraFrame) phase = PlacementPhase.CAMERA_ERROR
    }

    /**
     * A tap that landed on nothing. Deliberately a no-op that returns [FrameEffect.NONE]:
     * the whole point of this flow is that empty space is not a placement affordance.
     */
    fun onBackgroundTap(): FrameEffect = FrameEffect.NONE

    /**
     * The anchor factory must succeed before the request is consumed. Called on the render thread.
     *
     * [commit] is asked for a [PlacementSource.SURFACE] anchor when [FrameInput.surfaceAvailable],
     * else for a [PlacementSource.ESTIMATED] one once [estimatedFallbackMillis] of tracked search
     * ran out and [FrameInput.estimatedAvailable]; a surface always wins when both are there.
     */
    fun onFrame(input: FrameInput, commit: (PlacementSource) -> Boolean = { true }): FrameEffect = when {
        dismissed || phase == PlacementPhase.CAMERA_ERROR -> FrameEffect.NONE
        !input.tracking -> {
            if (!hasCameraFrame) hasCameraFrame = true
            loseTracking()
        }
        else -> {
            if (!hasCameraFrame) hasCameraFrame = true
            if (phase == PlacementPhase.TRACKING_LOST) phase = phaseBeforeLoss
            when {
                hasPlacement -> followAnchor(input)
                !placementRequested -> idle()
                input.surfaceAvailable && commit(PlacementSource.SURFACE) ->
                    place(input.nowMillis, PlacementSource.SURFACE, settled = true)
                input.estimatedAvailable && estimatedDue(input.nowMillis) && commit(PlacementSource.ESTIMATED) ->
                    place(input.nowMillis, PlacementSource.ESTIMATED, settled = input.anchorSettled == true)
                else -> search(input.nowMillis)
            }
        }
    }

    private fun loseTracking(): FrameEffect {
        // Untracked frames before the first tracked one are the normal start-up: nothing
        // was lost, so no TRACKING_LOST phase, no warning haptic, no "paused" copy.
        if (phase == PlacementPhase.TRACKING_LOST || phase == PlacementPhase.INITIALIZING) {
            return FrameEffect.NONE
        }
        phaseBeforeLoss = phase
        // The search clock does not run in the dark.
        searchStartedAt = null
        recoveringSince = null
        adjustmentCount = 0
        isAdjusting = false
        phase = PlacementPhase.TRACKING_LOST
        return FrameEffect.TRACKING_LOST
    }

    /** An object stands: mirror its anchor's tracking, never create a second one. */
    private fun followAnchor(input: FrameInput): FrameEffect {
        if (input.anchorSettled == true && !isSettled) isSettled = true
        when {
            input.anchorTracking != false -> {
                recoveringSince = null
                phase = if (isAdjusting) PlacementPhase.ADJUSTING else PlacementPhase.PLACED
            }
            phase == PlacementPhase.RECOVERY_FAILED -> Unit
            else -> {
                adjustmentCount = 0
                isAdjusting = false
                val since = recoveringSince ?: input.nowMillis.also { recoveringSince = it }
                phase = if (input.nowMillis - since >= RECOVERY_TIMEOUT_MS) {
                    PlacementPhase.RECOVERY_FAILED
                } else {
                    PlacementPhase.RECOVERING
                }
            }
        }
        return FrameEffect.NONE
    }

    private fun idle(): FrameEffect {
        if (phase == PlacementPhase.INITIALIZING) phase = PlacementPhase.SCANNING
        return FrameEffect.NONE
    }

    /** The search ran long enough without a surface to fall back on the estimated floor. */
    private fun estimatedDue(now: Long): Boolean {
        val after = estimatedFallbackMillis ?: return false
        val started = searchStartedAt ?: now.also { searchStartedAt = it }
        return now - started >= after.coerceAtLeast(0L)
    }

    private fun place(now: Long, source: PlacementSource, settled: Boolean): FrameEffect {
        placementRequested = false
        hasPlacement = true
        placementSource = source
        isSettled = settled
        placementRevision++
        isSelected = true
        placementsCreated++
        placedAtMillis = now
        searchStartedAt = null
        phase = PlacementPhase.PLACED
        return FrameEffect.PLACE
    }

    private fun search(now: Long): FrameEffect {
        val started = searchStartedAt ?: now.also { searchStartedAt = it }
        if (phase != PlacementPhase.NO_SURFACE) {
            phase = if (now - started >= NO_SURFACE_TIMEOUT_MS) {
                PlacementPhase.NO_SURFACE
            } else {
                PlacementPhase.SCANNING
            }
        }
        return FrameEffect.NONE
    }

    companion object {
        /** How far the floor moves before [floorHeight] follows it: under its frame-to-frame jitter. */
        internal const val FLOOR_STEP_M = 0.01f

        /** How far the camera moves up or down before [cameraHeight] follows it. */
        internal const val CAMERA_STEP_M = 0.05f

        /** Scanning without a usable surface for this long shows the help card (§2.2). */
        const val NO_SURFACE_TIMEOUT_MS = 10_000L

        /** A paused anchor that has not re-tracked for this long is declared lost (§2.2). */
        const val RECOVERY_TIMEOUT_MS = 10_000L
    }
}

// ── Usable surface (plan §2.3) ───────────────────────────────────────────────────────────

/**
 * What "a usable surface" means, as pure predicates the session unpacks ARCore objects into.
 *
 *  - a **tracked, supported plane** — never a ceiling, never
 *    a feature point or a depth guess (walls excepted, see below);
 *  - the pose lies **inside the plane's polygon**;
 *  - between [MIN_DISTANCE_M] and [MAX_DISTANCE_M] from the camera — closer is a hand or a
 *    table edge, farther is a guess the user cannot see the error of;
 *  - **in front of the camera and inside the viewport** (the fallback path only — a ray hit
 *    is inside by construction).
 *
 * Ordering: the ray through the viewport centre first; failing that, the visible plane
 * centres by proximity ([rankFallback]). No dwell: the first frame that has one places.
 *
 * A **wall** is looser, because ARCore grows wall polygons slowly and finds no features at all
 * on a plain painted wall (#4070) — see [wallHit]: a vertical plane hit up to
 * [WALL_POLYGON_TOLERANCE_M] outside its polygon, or a depth hit whose surface normal is
 * horizontal. That is the Android side of ARKit's `.existingPlaneInfinite` / `.estimatedPlane`.
 */
object UsableSurfacePolicy {
    const val MIN_DISTANCE_M = 0.25f
    const val MAX_DISTANCE_M = 3.0f

    /**
     * A depth hit whose surface normal has a world-Y component within ±this is a vertical
     * surface. 0.25 is about 14° off vertical: a wall, a door or a cupboard front, never a
     * floor or a table top.
     */
    internal const val WALL_DEPTH_NORMAL_MAX_Y = 0.25f

    /**
     * How far outside a tracked wall's polygon a hit on that wall still counts, in metres.
     * The polygon only covers the textured patch ARCore has seen so far; the wall goes on.
     */
    internal const val WALL_POLYGON_TOLERANCE_M = 1.0f

    /**
     * The wall acceptance rule, as the facts the session unpacks one ARCore hit into. Returns
     * how the hit holds a wall, or `null` when it does not:
     *
     *  - [WallHitKind.PLANE]: a tracked vertical plane, pose inside its polygon (the only rule
     *    before #4070);
     *  - [WallHitKind.EXTENDED_PLANE]: the same plane, at most [WALL_POLYGON_TOLERANCE_M]
     *    outside its polygon;
     *  - [WallHitKind.DEPTH_POINT]: an ARCore `DepthPoint` (depth API on) whose normal is
     *    horizontal within [WALL_DEPTH_NORMAL_MAX_Y].
     *
     * Every kind needs a tracking trackable within [minDistance]..[maxDistance] of the camera.
     */
    internal fun wallHit(
        isVerticalPlane: Boolean,
        isDepthPoint: Boolean,
        isTrackableTracking: Boolean,
        isPoseInPolygon: Boolean,
        outsidePolygonMeters: Float,
        normalY: Float,
        distanceMeters: Float,
        minDistance: Float = MIN_DISTANCE_M,
        maxDistance: Float = MAX_DISTANCE_M,
    ): WallHitKind? {
        if (!isTrackableTracking || !distanceMeters.isFinite()) return null
        if (distanceMeters < minDistance || distanceMeters > maxDistance) return null
        return when {
            isVerticalPlane && isPoseInPolygon -> WallHitKind.PLANE
            isVerticalPlane && outsidePolygonMeters.isFinite() && outsidePolygonMeters >= 0f &&
                outsidePolygonMeters <= WALL_POLYGON_TOLERANCE_M -> WallHitKind.EXTENDED_PLANE
            isDepthPoint && normalY.isFinite() &&
                kotlin.math.abs(normalY) < WALL_DEPTH_NORMAL_MAX_Y -> WallHitKind.DEPTH_POINT
            else -> null
        }
    }

    fun accept(
        isUpwardHorizontalPlane: Boolean,
        isTrackableTracking: Boolean,
        isPoseInPolygon: Boolean,
        distanceMeters: Float,
    ): Boolean =
        isUpwardHorizontalPlane &&
            isTrackableTracking &&
            isPoseInPolygon &&
            distanceMeters >= MIN_DISTANCE_M &&
            distanceMeters <= MAX_DISTANCE_M

    /** Additive alignment-aware policy; the original horizontal-only overload is unchanged. */
    fun accept(
        surface: PlacementSurface,
        isUpwardHorizontalPlane: Boolean,
        isVerticalPlane: Boolean,
        isTrackableTracking: Boolean,
        isPoseInPolygon: Boolean,
        distanceMeters: Float,
    ): Boolean = accept(
        isUpwardHorizontalPlane = when (surface) {
            PlacementSurface.SURFACE -> isUpwardHorizontalPlane
            PlacementSurface.WALL -> isVerticalPlane
        },
        isTrackableTracking = isTrackableTracking,
        isPoseInPolygon = isPoseInPolygon,
        distanceMeters = distanceMeters,
    )

    /**
     * Orders the fallback candidates — one per visible tracked plane centre — nearest first,
     * dropping anything behind the camera, outside the viewport or outside the distance band.
     */
    fun <T> rankFallback(candidates: List<FallbackCandidate<T>>): List<T> =
        candidates
            .filter { it.ndc != null && it.ndc.isInsideViewport && it.distanceMeters in MIN_DISTANCE_M..MAX_DISTANCE_M }
            .sortedBy { it.ndc!!.x * it.ndc.x + it.ndc.y * it.ndc.y }
            .map { it.payload }
}

/**
 * How a candidate holds a wall, most trusted first — the order the centre ray's hits are
 * ranked in. [FLOOR_SEAM] is not a ray hit on the wall: the ray met the tracked floor where it
 * ends against the wall, and the wall is inferred from there ([seamWallCandidate]).
 */
internal enum class WallHitKind { PLANE, EXTENDED_PLANE, DEPTH_POINT, FLOOR_SEAM }

/** A plane centre projected for [UsableSurfacePolicy.rankFallback]. `ndc == null` ⇒ behind the camera. */
data class FallbackCandidate<T>(
    val payload: T,
    val distanceMeters: Float,
    val ndc: Ndc?,
)

/** Normalised device coordinates of a projected world point. */
data class Ndc(val x: Float, val y: Float) {
    val isInsideViewport: Boolean get() = x >= -1f && x <= 1f && y >= -1f && y <= 1f
}

/**
 * Column-major 4×4 maths for the fallback projection, written out so the JVM tests can run
 * it without `android.opengl.Matrix` (a stub off-device).
 */
object ViewportProjection {

    /** `a × b`, both column-major 16-element arrays, as `android.opengl.Matrix.multiplyMM`. */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
        return out
    }

    /**
     * Projects a world point through `viewProjection`. `null` when the point is behind the
     * camera (clip `w <= 0`), which is also how "outside the viewport" is refused upstream.
     */
    fun project(viewProjection: FloatArray, x: Float, y: Float, z: Float): Ndc? {
        val m = viewProjection
        val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (cw <= 1e-6f) return null
        return Ndc(cx / cw, cy / cw)
    }

    fun distance(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

