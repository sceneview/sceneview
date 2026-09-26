package io.github.sceneview.demo.common.placement

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.ar.core.Anchor
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARCoreAvailability

/**
 * The asset the session places. Resolved by the host from the armed picker row, offered to
 * the session with an [AssetTicket] so a stale resolution can never populate the scene
 * ([#2476](https://github.com/sceneview/sceneview/issues/2476) made permanent).
 */
@Immutable
data class PlacementSpec(
    /**
     * `file://…` URI for a streamed asset OR `assets/`-relative path for a bundled
     * GLB. Loaded via `rememberModelInstance(modelLoader, fileLocation = …)` — the
     * named-param overload that scheme-detects both forms
     * ([#1422](https://github.com/sceneview/sceneview/issues/1422) /
     * [#2302](https://github.com/sceneview/sceneview/issues/2302) overload trap).
     */
    val assetLocation: String,
    val displayName: String,
    /**
     * The object's real-world size in metres, passed to `ModelNode(scaleToUnits = …)`.
     * This is what 100 % means on the pinch readout — see
     * [PlacementModel.realWorldSizeMeters] (#3326).
     */
    val realWorldSizeMeters: Float = 0.3f,
    /**
     * Whether [realWorldSizeMeters] was measured rather than assumed — decides between
     * "Actual size" and "Preview size" on the read-out ([scaleLabelMode]).
     */
    val sizeIsMeasured: Boolean = false,
)

/** The one committed placement: an ARCore anchor, and the asset standing on it. */
internal data class PlacedModel(
    val id: Int,
    val placement: io.github.sceneview.ar.AutoPlacementResult,
    val spec: PlacementSpec,
) {
    val anchor: Anchor get() = placement.anchor
}

/** Which edit gesture is live on the placed model. */
enum class PlacementGesture { MOVING, ROTATING, SCALING }

/**
 * Hoisted, Compose-observable mirror of the session — created by the host via
 * [rememberTapToPlaceState], written by the session, read by the overlays and the dock.
 *
 * The *decisions* live in [AutoPlacementController] (pure, JVM-tested); this class only
 * exposes what the UI needs to draw, plus the ARCore anchor the controller cannot hold.
 */
@Stable
class TapToPlaceState internal constructor() {
    internal val controller = AutoPlacementController()

    /** True once the first `onSessionUpdated` frame arrived (drives the init scrim). */
    var cameraReady: Boolean by mutableStateOf(false)
        internal set

    /** Non-null once ARCore has ruled the session out on this device (#3341). */
    var arCoreAvailability: ARCoreAvailability? by mutableStateOf(null)
        internal set

    var isTracking: Boolean by mutableStateOf(false)
        internal set

    var trackingFailureReason: TrackingFailureReason? by mutableStateOf(null)
        internal set

    /** The controller's phase, mirrored change-only from the frame loop. */
    var phase: PlacementPhase by mutableStateOf(PlacementPhase.INITIALIZING)
        internal set

    var activeGesture: PlacementGesture? by mutableStateOf(null)
        internal set

    /** Live pinch percentage of real-world size, `null` when no pinch is in flight (#3326). */
    var scalePercent: Int? by mutableStateOf(null)
        internal set

    /** `true` while the live pinch sits exactly at real-world size. */
    var isRealWorldSize: Boolean by mutableStateOf(false)
        internal set

    /** Whether the placed asset's size is measured — "Actual size" — or a preview. */
    var scaleLabel: ScaleLabelMode by mutableStateOf(ScaleLabelMode.PREVIEW)
        internal set

    /** A one-finger drag is off every usable surface: "Keep the object on a surface." */
    var dragOffSurface: Boolean by mutableStateOf(false)
        internal set

    /**
     * `SystemClock.uptimeMillis` of the placement, `0` when nothing is placed. Opens the
     * one-shot "Drag to move. Pinch or twist to adjust." window.
     */
    var lastPlacedAtMillis: Long by mutableStateOf(0L)
        internal set

    /** The asset currently offered to the session, or `null` while it is still resolving. */
    internal var spec: PlacementSpec? by mutableStateOf(null)

    internal var placed: PlacedModel? by mutableStateOf(null)
    internal var nextId: Int = 0
    internal var modelInstance: io.github.sceneview.model.ModelInstance? by mutableStateOf(null)
    internal var assetRetry: Int by mutableStateOf(0)
    var modelLoading: Boolean by mutableStateOf(false)
        internal set
    var modelError: Boolean by mutableStateOf(false)
        internal set

    /** `0` or `1` — one object per session. Kept as a count for the dock's enabled state. */
    val placedCount: Int get() = if (placed != null) 1 else 0

    /**
     * Offer a resolved asset. Refused when [ticket] is stale — a dismissed session or a
     * superseded selection — which is the only way an async result reaches the scene.
     */
    internal fun offerAsset(ticket: AssetTicket, spec: PlacementSpec): Boolean {
        if (!controller.acceptsAsset(ticket)) return false
        this.spec = spec
        scaleLabel = scaleLabelMode(spec.sizeIsMeasured)
        // A placed object swaps its asset in place; an unplaced session asks for one.
        if (placed != null) {
            placed = placed?.copy(spec = spec)
        } else {
            controller.requestPlacement()
        }
        return true
    }

    /**
     * The armed row is still downloading: mint the new selection (so the previous asset's
     * result is stale) and, while nothing stands, withdraw the previous offer so a surface
     * found before the download lands does not place the model the picker no longer shows.
     */
    internal fun holdForPendingAsset() {
        controller.selectModel()
        if (!controller.hasPlacement) {
            spec = null
            controller.withdrawRequest()
            phase = controller.phase
        }
    }

    /** Remove the anchor, keep the asset, scan again (§2.2 *Restarting placement*). */
    fun resetPlacement(nowMillis: Long = android.os.SystemClock.uptimeMillis()) {
        detachPlacement()
        controller.resetPlacement(nowMillis)
        phase = controller.phase
    }

    /** *Keep scanning* on the no-surface card. */
    fun keepScanning(nowMillis: Long = android.os.SystemClock.uptimeMillis()) {
        controller.keepScanning(nowMillis)
        phase = controller.phase
    }

    /**
     * Leave the camera: detach the anchor and close the session generation, so nothing
     * issued before this can populate the scene. Safe to call more than once.
     */
    fun clearAll() {
        detachPlacement()
        spec = null
        controller.dismiss()
        phase = controller.phase
        cameraReady = false
    }

    private fun detachPlacement() {
        placed?.let { runCatching { it.anchor.detach() } }
        placed = null
        activeGesture = null
        scalePercent = null
        isRealWorldSize = false
        dragOffSurface = false
        lastPlacedAtMillis = 0L
    }
}

@Composable
fun rememberTapToPlaceState(): TapToPlaceState = remember { TapToPlaceState() }
