package io.github.sceneview.demo.common.placement

import kotlin.math.sqrt

/**
 * The decision core of **automatic placement**: one object, placed on the first usable
 * surface, with no cursor and no tap. Pure Kotlin — no Compose, no ARCore, no Android —
 * so every rule below runs on the JVM (`AutoPlacementControllerTest`, `UsableSurfaceTest`).
 *
 * ## Why automatic
 *
 * The owner decision of 2026-09-22: placement that needs both a cursor and a tap "makes no
 * sense, it must lose half the users". The screen therefore behaves like Quick Look: open
 * the camera, move slowly, the object is standing there. The whole tap-to-place vocabulary
 * — reticle, plane grid, "Tap to place", instant-mode chooser, snap-to-plane toggle — is
 * gone from this flow, and no on-screen instruction compensates for it.
 *
 * ## The state machine (plan §2.2)
 *
 * ```
 * INITIALIZING ─first frame─▶ SCANNING ─10 s─▶ NO_SURFACE
 *      │                          │ surface        │ Keep scanning ⟲ / surface
 *      │                          ▼                ▼
 *      │                        PLACED ◀───────────┘
 *      │                          │ anchor paused        Scan again
 *      │                          ▼                          │
 *      │                      RECOVERING ─10 s─▶ RECOVERY_FAILED
 *   any phase ─camera not tracking─▶ TRACKING_LOST ─tracking─▶ (phase before)
 * ```
 *
 * Rules pinned by tests:
 *  - **One placement request is consumed by exactly one placement.** A hundred frames with
 *    a usable surface under each produce one anchor; a background tap produces none.
 *  - **A tracking interruption never creates another anchor**, and never resets a
 *    placed session to scanning — the placement is *recovered*, not redone.
 *  - **Async asset results carry the session and selection generations they were issued
 *    under** ([AssetTicket]); a result for a dismissed session or a superseded selection is
 *    refused at the door.
 *  - **The search interval is 10 s**, restarted by *Keep scanning*, and paused by a
 *    tracking loss (a dark second is not a second the surface search was running).
 */
enum class PlacementPhase {
    /** No camera frame yet — the init scrim covers this. */
    INITIALIZING,

    /** Camera tracking, request pending, searching for a usable surface. */
    SCANNING,

    /** [AutoPlacementController.NO_SURFACE_TIMEOUT_MS] of scanning without a surface. */
    NO_SURFACE,

    /** The object is anchored and its anchor is tracking. */
    PLACED,

    /** The camera is not tracking (or a QA failure is forced). */
    TRACKING_LOST,

    /** Camera back, but the placement's anchor has not re-tracked yet. */
    RECOVERING,

    /** [AutoPlacementController.RECOVERY_TIMEOUT_MS] of recovering — offer *Scan again*. */
    RECOVERY_FAILED,

    /** The session never delivered a frame past the init scrim's timeout. */
    CAMERA_ERROR,
}

/**
 * The generations an asynchronous asset result was issued under. A result is only allowed
 * to populate the scene when both still match the controller's current ones — see
 * [AutoPlacementController.acceptsAsset].
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
 *   session when [AutoPlacementController.wantsSurface] is true, so the hit test is not run
 *   at 60 Hz for nothing).
 * @param anchorTracking the placement's anchor tracking state, `null` when nothing is placed.
 */
data class FrameInput(
    val nowMillis: Long,
    val tracking: Boolean,
    val surfaceAvailable: Boolean,
    val anchorTracking: Boolean? = null,
)

class AutoPlacementController {

    var phase: PlacementPhase = PlacementPhase.INITIALIZING
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

    private var searchStartedAt: Long? = null
    private var recoveringSince: Long? = null
    private var phaseBeforeLoss: PlacementPhase = PlacementPhase.SCANNING
    private var dismissed = false

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
        dismissed = true
        sessionGeneration++
        placementRequested = false
        hasPlacement = false
        placedAtMillis = 0L
        searchStartedAt = null
        recoveringSince = null
        phaseBeforeLoss = PlacementPhase.SCANNING
        phase = PlacementPhase.INITIALIZING
    }

    /**
     * *Reset placement* / *Scan again*: the anchor goes, the asset stays, the search restarts.
     * The caller detaches the anchor; this only rewrites the decision state.
     */
    fun resetPlacement(nowMillis: Long) {
        if (dismissed) return
        hasPlacement = false
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

    /** The init scrim gave up and no frame ever came. */
    fun cameraFailed() {
        if (phase == PlacementPhase.INITIALIZING) phase = PlacementPhase.CAMERA_ERROR
    }

    /**
     * A tap that landed on nothing. Deliberately a no-op that returns [FrameEffect.NONE]:
     * the whole point of this flow is that empty space is not a placement affordance.
     */
    fun onBackgroundTap(): FrameEffect = FrameEffect.NONE

    fun onFrame(input: FrameInput): FrameEffect = when {
        dismissed || phase == PlacementPhase.CAMERA_ERROR -> FrameEffect.NONE
        !input.tracking -> loseTracking()
        else -> {
            if (phase == PlacementPhase.TRACKING_LOST) phase = phaseBeforeLoss
            when {
                hasPlacement -> followAnchor(input)
                !placementRequested -> idle()
                input.surfaceAvailable -> place(input.nowMillis)
                else -> search(input.nowMillis)
            }
        }
    }

    private fun loseTracking(): FrameEffect {
        if (phase == PlacementPhase.TRACKING_LOST) return FrameEffect.NONE
        // Remember where to come back to; INITIALIZING has nothing to come back to.
        phaseBeforeLoss = if (phase == PlacementPhase.INITIALIZING) {
            PlacementPhase.SCANNING
        } else {
            phase
        }
        // The search clock does not run in the dark.
        searchStartedAt = null
        recoveringSince = null
        phase = PlacementPhase.TRACKING_LOST
        return FrameEffect.TRACKING_LOST
    }

    /** An object stands: mirror its anchor's tracking, never create a second one. */
    private fun followAnchor(input: FrameInput): FrameEffect {
        when {
            input.anchorTracking != false -> {
                recoveringSince = null
                phase = PlacementPhase.PLACED
            }
            phase == PlacementPhase.RECOVERY_FAILED -> Unit
            else -> {
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

    private fun place(now: Long): FrameEffect {
        placementRequested = false
        hasPlacement = true
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
 *  - a **tracked, horizontal, upward-facing plane** — never a wall, never a ceiling, never
 *    a feature point or a depth guess;
 *  - the pose lies **inside the plane's polygon**;
 *  - between [MIN_DISTANCE_M] and [MAX_DISTANCE_M] from the camera — closer is a hand or a
 *    table edge, farther is a guess the user cannot see the error of;
 *  - **in front of the camera and inside the viewport** (the fallback path only — a ray hit
 *    is inside by construction).
 *
 * Ordering: the ray through the viewport centre first; failing that, the visible plane
 * centres by proximity ([rankFallback]). No dwell: the first frame that has one places.
 */
object UsableSurfacePolicy {
    const val MIN_DISTANCE_M = 0.25f
    const val MAX_DISTANCE_M = 3.0f

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

    /**
     * Orders the fallback candidates — one per visible tracked plane centre — nearest first,
     * dropping anything behind the camera, outside the viewport or outside the distance band.
     */
    fun <T> rankFallback(candidates: List<FallbackCandidate<T>>): List<T> =
        candidates
            .filter { it.ndc != null && it.ndc.isInsideViewport && it.distanceMeters in MIN_DISTANCE_M..MAX_DISTANCE_M }
            .sortedBy { it.distanceMeters }
            .map { it.payload }
}

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

// ── Scale label (plan §2.3) ──────────────────────────────────────────────────────────────

/** Which words the pinch read-out uses. */
enum class ScaleLabelMode {
    /** The object's size is a measured real-world size: "Actual size". */
    ACTUAL,

    /** A showcase size nothing vouches for: "Preview size". Never claim what is not known. */
    PREVIEW,
}

/**
 * A real-world size is only trustworthy when someone measured it — a bundled row whose
 * metres are the asset's own, or an opened file whose bounds were read. Everything else
 * (the 0.3 m default, a streamed row's fitted guess) is a preview.
 */
fun scaleLabelMode(sizeIsMeasured: Boolean): ScaleLabelMode =
    if (sizeIsMeasured) ScaleLabelMode.ACTUAL else ScaleLabelMode.PREVIEW
