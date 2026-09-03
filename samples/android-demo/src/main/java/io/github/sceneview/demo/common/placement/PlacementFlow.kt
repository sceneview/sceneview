package io.github.sceneview.demo.common.placement

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The decision layer of the **one** AR placement flow
 * ([#3405](https://github.com/sceneview/sceneview/issues/3405)).
 *
 * ## The shape of the flow, and why it has two phases
 *
 * Before this file, every placement surface in the app dropped the user straight into a
 * live camera and *then* asked what to place — a bottom sheet floated over a viewfinder
 * that was still converging its first plane. `ar-instant-placement` did not even ask: it
 * cycled a different model on every tap.
 *
 * AR Model Viewer ("Will It Fit") settled the same question the other way and wrote the
 * rule down: **AR is never the entry point; it is always reached with a subject already
 * chosen.** Its four doors — viewer, discover, search, stand-ins — are all pre-AR screens,
 * and its camera has no model picker at all. The reason is not aesthetic. A picker over a
 * live camera competes with plane discovery for the user's attention at exactly the moment
 * both need it, and a "size this thing" keyboard over a black viewfinder was measured
 * there as the worst screen in the product.
 *
 * So the demo flow is two phases, and the phase is the state:
 *
 *  - [PlacementFlowPhase.CHOOSING] — a **themed, still** screen (no camera, no ARCore, no
 *    Filament): pick the model, pick how a tap resolves, read what the demo teaches. This
 *    is also the only half of the flow that renders on the emulator, where ARCore has no
 *    camera HAL ([#2754](https://github.com/sceneview/sceneview/issues/2754)) — which is
 *    why the picker is a screen and not a sheet: a sheet over a black viewport is not a
 *    screenshot of anything.
 *  - [PlacementFlowPhase.PLACING] — the camera, already knowing its subject.
 *
 * Everything in this file is a **pure function or a plain state holder**: no Compose UI, no
 * ARCore types, no Android types. That is deliberate and load-bearing — the emulator cannot
 * run an ARCore session, so the only way these decisions get tested at all is on the JVM.
 * See `PlacementFlowTest`.
 */

/** The two phases of the one AR placement flow. See the file KDoc. */
enum class PlacementFlowPhase {
    /** Pick the model and the options. No camera, no ARCore session. */
    CHOOSING,

    /** The live tap-to-place camera, with a model already armed. */
    PLACING,
}

/**
 * How a tap resolves into an ARCore anchor — the axis that used to be a whole second demo
 * (`ar-instant-placement`, folded into this flow by #3405).
 */
enum class PlacementMode {
    /**
     * Wait for a real plane. `Config.InstantPlacementMode.DISABLED`; a tap is only accepted
     * on a tracked plane inside its polygon ([PlacementHitPolicy]).
     */
    PLANE,

    /**
     * `Config.InstantPlacementMode.LOCAL_Y_UP` — a tap lands immediately, before plane
     * detection has converged, on an `InstantPlacementPoint` estimated at
     * [INSTANT_APPROXIMATE_DISTANCE_M]. The point refines from
     * `SCREENSPACE_WITH_APPROXIMATE_DISTANCE` to `FULL_TRACKING` once ARCore has gathered
     * enough features, which is the transition the badge reports.
     */
    INSTANT,
}

/**
 * The approximate distance, in metres, handed to `Frame.hitTestInstantPlacement` — ARCore's
 * guess for where the surface is before it knows. One metre is ARCore's own documented
 * starting point and the value the retired `ar-instant-placement` demo used, kept so the
 * folded demo behaves identically to the one it replaced.
 */
const val INSTANT_APPROXIMATE_DISTANCE_M = 1.0f

/** What the system back gesture means at a given [PlacementFlowPhase]. */
enum class PlacementBackAction {
    /**
     * Leave the camera, keep the demo. Back out of AR lands on the chooser with the same
     * model still armed — the AR Model Viewer rule ("Back from AR returns to *the same*
     * viewer instance"), and the fix for the device note there: *"j'ai fait Back pour
     * dismiss et ça a quitté l'AR."*
     */
    RETURN_TO_CHOOSER,

    /** Already on the chooser — Back leaves the demo, as it does on every other screen. */
    LEAVE_DEMO,
}

/** Pure back-ladder rung. See [PlacementBackAction]. */
fun placementBackAction(phase: PlacementFlowPhase): PlacementBackAction = when (phase) {
    PlacementFlowPhase.PLACING -> PlacementBackAction.RETURN_TO_CHOOSER
    PlacementFlowPhase.CHOOSING -> PlacementBackAction.LEAVE_DEMO
}

/** Whether ARCore's instant-placement mode should be configured on. */
fun instantPlacementEnabled(mode: PlacementMode): Boolean = mode == PlacementMode.INSTANT

/**
 * Which trackable an accepted tap anchors to.
 *
 * The retired `ar-instant-placement` demo branched *exclusively*: instant ON meant
 * `hitTestInstantPlacement` and nothing else, so with instant on you could never get a real
 * plane anchor even when a plane was right there under the reticle. The folded flow prefers
 * the accurate answer and falls back — which is what the SDK's own
 * [io.github.sceneview.ar.PlacementScene] `instantPlacement` flag already documents ("models
 * can be dropped before ARCore has converged a plane, then snap to the real surface once
 * tracking catches up").
 */
enum class PlacementHitSource {
    /** A tracked plane hit inside its polygon, within [PlacementHitPolicy.MAX_HIT_DISTANCE_M]. */
    PLANE,

    /** No usable plane; an `InstantPlacementPoint` at the approximate distance. */
    INSTANT,

    /** Nothing to anchor to — the tap is dropped. */
    NONE,
}

/** Pure hit-source precedence. See [PlacementHitSource]. */
fun placementHitSource(
    hasPlaneHit: Boolean,
    instantEnabled: Boolean,
    hasInstantHit: Boolean,
): PlacementHitSource = when {
    hasPlaneHit -> PlacementHitSource.PLANE
    instantEnabled && hasInstantHit -> PlacementHitSource.INSTANT
    else -> PlacementHitSource.NONE
}

/**
 * The one thing `ar-instant-placement` taught that nothing else does: an instant-placed
 * model starts as a guess and becomes real.
 *
 * `null` ⇒ say nothing (the placement is plane-anchored, so there is no approximation to
 * report and a permanent "Tracked" badge would just be chrome).
 */
enum class InstantTrackingLabel {
    /** `TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE` — the pose is still a guess. */
    APPROXIMATING,

    /** `TrackingMethod.FULL_TRACKING` — ARCore has resolved the real surface. */
    TRACKED,
}

/**
 * Pure mapping from "is this an instant point, and has it converged" to the badge.
 *
 * @param isInstantPoint the placement anchored to an `InstantPlacementPoint`.
 * @param isFullTracking the point's tracking method reached `FULL_TRACKING`.
 */
fun instantTrackingLabel(
    isInstantPoint: Boolean,
    isFullTracking: Boolean,
): InstantTrackingLabel? = when {
    !isInstantPoint -> null
    isFullTracking -> InstantTrackingLabel.TRACKED
    else -> InstantTrackingLabel.APPROXIMATING
}

/**
 * Instant placement changes what "ready" means, so it has to change the coaching too.
 *
 * In [PlacementMode.PLANE], [TapToPlaceUxState.AIMING] is a real refusal: the reticle is
 * empty and a tap will be dropped, so the screen says "point at a surface". In
 * [PlacementMode.INSTANT] a tap in that same moment *lands* — telling the user to keep
 * aiming would be the screen lying about its own behaviour.
 *
 * [TapToPlaceUxState.SCANNING] is deliberately **not** promoted: the plane-discovery guide
 * is still the right thing to show there (an instant placement made before any plane exists
 * is the least accurate one available), and a user who follows it gets a better anchor.
 */
fun effectivePlacementUxState(
    uxState: TapToPlaceUxState,
    instantEnabled: Boolean,
): TapToPlaceUxState =
    if (instantEnabled && uxState == TapToPlaceUxState.AIMING) {
        TapToPlaceUxState.READY
    } else {
        uxState
    }

/** What the chooser's "Place in AR" call to action can be doing. */
enum class PlacementCtaState {
    /** `ArCoreApk.checkAvailability` has not answered yet — the CTA is disabled and says so. */
    CHECKING,

    /** ARCore is usable and a model is armed. */
    READY,

    /** ARCore will never run here — the CTA is disabled and explains why. */
    AR_UNSUPPORTED,

    /**
     * No catalogue row to arm. Only reachable if a host passes an empty catalogue; the CTA
     * refuses rather than entering AR with nothing to place.
     */
    NO_MODEL,
}

/**
 * Pure CTA gate. ARCore availability outranks the catalogue: on a device that cannot run AR
 * at all, "pick a model first" would be the wrong sentence.
 *
 * @param arSupported `null` while `ArCoreApk.checkAvailability` is still resolving.
 */
fun placementCtaState(
    arSupported: Boolean?,
    hasArmedModel: Boolean,
): PlacementCtaState = when {
    arSupported == false -> PlacementCtaState.AR_UNSUPPORTED
    arSupported == null -> PlacementCtaState.CHECKING
    !hasArmedModel -> PlacementCtaState.NO_MODEL
    else -> PlacementCtaState.READY
}

/**
 * Phase + options for one run of the flow, hoisted so the demo's chooser, its AR chrome and
 * its settings sheet all read the same values.
 *
 * [phase] and [mode] are `rememberSaveable`-backed: a rotation in the middle of a placement
 * session must not dump the user back onto the chooser, and must not silently re-arm
 * plane-only mode behind their back.
 */
@Stable
class PlacementFlowState internal constructor(
    private val phaseState: MutableState<PlacementFlowPhase>,
    private val modeState: MutableState<PlacementMode>,
) {
    var phase: PlacementFlowPhase
        get() = phaseState.value
        set(value) {
            phaseState.value = value
        }

    var mode: PlacementMode
        get() = modeState.value
        set(value) {
            modeState.value = value
        }

    /**
     * #1883 — ON: only detected planes accept a placement. OFF: any tracked hit (feature
     * points, depth) does. Independent of [mode]; under [PlacementMode.INSTANT] it still
     * governs the *plane* half of [placementHitSource].
     */
    var snapToPlane: Boolean by mutableStateOf(true)

    /** #1882 — dev toggle, so a screenshot can be taken without the centre reticle. */
    var showReticle: Boolean by mutableStateOf(true)

    val instantEnabled: Boolean get() = instantPlacementEnabled(mode)

    fun enterAr() {
        phase = PlacementFlowPhase.PLACING
    }

    fun backToChooser() {
        phase = PlacementFlowPhase.CHOOSING
    }
}

@Composable
fun rememberPlacementFlowState(
    initialPhase: PlacementFlowPhase = PlacementFlowPhase.CHOOSING,
    initialMode: PlacementMode = PlacementMode.PLANE,
): PlacementFlowState {
    val phase = rememberSaveable { mutableStateOf(initialPhase) }
    val mode = rememberSaveable { mutableStateOf(initialMode) }
    return remember { PlacementFlowState(phase, mode) }
}
