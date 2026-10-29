package io.github.sceneview.demo.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARCoreAvailabilityOverlay
import io.github.sceneview.ar.ARCoreAvailabilityState
import io.github.sceneview.demo.theme.LocalStageChrome

/**
 * The SDK's ARCore availability card ("Couldn't start AR", its reason and "Try again"), drawn
 * on the stage ground instead of the black viewport an AR session that never started leaves
 * behind (#4622). Pass it as
 * `ARSceneView(arCoreAvailabilityOverlay = { ArAvailabilityStage(it) })`.
 *
 * The retry stays in place: it is the SDK's own `ARCoreAvailabilityState.retry`, which creates
 * a fresh session. The scaffold's `arSessionFailed` card has no retry and asks the user to
 * leave the demo, so a demo that wires this keeps `arSessionFailed` for failures the SDK does
 * not explain (see [arSessionFailedUnexplained]).
 */
@Composable
fun BoxScope.ArAvailabilityStage(state: ARCoreAvailabilityState) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(LocalStageChrome.current.ground),
    )
    ARCoreAvailabilityOverlay(state)
}

/**
 * What a demo passes to `DemoScaffold(arSessionFailed = …)` when it also draws
 * [ArAvailabilityStage]: a failure only takes the scaffold's card (no retry) when the SDK has
 * no availability card of its own to show for it — a recorded dataset that will not play,
 * say. A session that could not be created publishes `ARCoreAvailability.SessionFailed`, and
 * that card, with its "Try again", is the one the user sees (#4622).
 *
 * Clear `sessionFailed` when the availability goes back to `null`: that is a retry starting,
 * and a retry that succeeds must not land on the scaffold's card.
 */
fun arSessionFailedUnexplained(
    sessionFailed: Boolean,
    arCoreAvailability: ARCoreAvailability?,
): Boolean = sessionFailed && arCoreAvailability == null
