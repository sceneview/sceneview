package io.github.sceneview.demo.common.placement

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable

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
 *    Filament): pick the model, read what the demo teaches. This
 *    is also the only half of the flow that renders on the emulator, where ARCore has no
 *    camera HAL ([#2754](https://github.com/sceneview/sceneview/issues/2754)) — which is
 *    why the picker is a screen and not a sheet: a sheet over a black viewport is not a
 *    screenshot of anything.
 *  - [PlacementFlowPhase.PLACING] — the camera, already knowing its subject, placing it on
 *    the first usable surface by itself ([AutoPlacementController]).
 *
 * Everything in this file is a **pure function or a plain state holder**: no Compose UI, no
 * ARCore types, no Android types. That is deliberate and load-bearing — the emulator cannot
 * run an ARCore session, so the only way these decisions get tested at all is on the JVM.
 * See `PlacementFlowTest`.
 */

/** The two phases of the one AR placement flow. See the file KDoc. */
enum class PlacementFlowPhase {
    /** Pick the model. No camera, no ARCore session. */
    CHOOSING,

    /** The live camera, with a model already armed and placed automatically. */
    PLACING,
}

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
 * Phase for one run of the flow, hoisted so the demo's chooser and its AR chrome read the
 * same value. `rememberSaveable`-backed: a rotation in the middle of a placement session
 * must not dump the user back onto the chooser.
 *
 * There are no options any more. Snap-to-plane, the placement cursor and the instant mode
 * were the knobs of a tap-to-place flow; placement is automatic now
 * ([AutoPlacementController]) and the chooser only chooses.
 */
@Stable
class PlacementFlowState internal constructor(
    private val phaseState: MutableState<PlacementFlowPhase>,
) {
    var phase: PlacementFlowPhase
        get() = phaseState.value
        set(value) {
            phaseState.value = value
        }

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
): PlacementFlowState {
    val phase = rememberSaveable { mutableStateOf(initialPhase) }
    return remember { PlacementFlowState(phase) }
}
