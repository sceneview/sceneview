package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.constrainHeight
import io.github.sceneview.ar.ARCoreAvailabilityOverlay
import io.github.sceneview.ar.ARCoreAvailabilityState
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.LocalDemoChromeBottomWidth
import io.github.sceneview.demo.LocalDemoChromeTopInset
import io.github.sceneview.demo.isDemoCompactHeight
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * The SDK's "Couldn't start AR" / "Install Google Play Services for AR" card, placed for a
 * screen that keeps chrome on while it is up.
 *
 * The SDK centres that card in the scene. Under a [io.github.sceneview.demo.DemoScaffold] with
 * a mode pill the scene runs behind the pill and the dock, so on a phone held sideways the
 * card's "Try again" came up under "Place / Free pose / One call" (#4509). Here the card is
 * centred in what the chrome leaves: between the identity row and the bottom chrome, or — in a
 * short window, where that band is thinner than the card — in the corner beside the centred
 * chrome, exactly as the placement cards do ([coachStackSlot]).
 */
@Composable
internal fun BoxScope.PlacementAvailabilityOverlay(state: ARCoreAvailabilityState) {
    val chromeBottom = LocalDemoChromeBottomInset.current
    val chromeWidth = LocalDemoChromeBottomWidth.current
    val compactHeight = isDemoCompactHeight()
    val chromeTop = LocalDemoChromeTopInset.current
    val gutter = SceneViewTokens.Space.md
    BoxWithConstraints(
        modifier = Modifier
            .matchParentSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val slot = coachStackSlot(
            compactHeight = compactHeight,
            cardShown = true,
            bandWidth = maxWidth,
            chromeWidth = chromeWidth,
            chromeBottom = chromeBottom,
            gutter = gutter,
            // The corner has the window's whole height, so the card's text can scroll there;
            // the band above the pill has room for little more than the button once the text
            // is large. The corner is taken as soon as it leaves the button's label its
            // minimum width — which grows with the font — around the SDK card's own margins.
            minWidth = SceneViewTokens.ArOverlay.sdkCardActionInsets +
                SceneViewTokens.ArOverlay.actionLabelMinWidth * LocalDensity.current.fontScale -
                gutter * 2,
            maxWidth = SceneViewTokens.ArOverlay.maxWidth,
        )
        Box(
            modifier = if (slot.besideChrome) {
                // The SDK card brings its own margin; the corner is the stack's width plus the
                // two gutters `coachStackSlot` set aside around it. Under the identity row:
                // its overflow button sits in this same corner.
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(slot.width + gutter * 2)
                    .fillMaxHeight()
                    .padding(top = chromeTop)
            } else {
                Modifier
                    .fillMaxSize()
                    .padding(top = chromeTop, bottom = chromeBottom)
                    .then(if (compactHeight) Modifier.lendSdkCardMargin() else Modifier)
            },
        ) {
            ARCoreAvailabilityOverlay(state)
        }
    }
}

/**
 * In a short window that is too narrow for the corner, the band between the identity row and
 * the mode pill is all there is, and at the largest text it is shorter than the card's button
 * and the SDK's margin together. So the card may grow into its own margin there — only when
 * it needs to: a card that fits is measured, and sits, exactly as before.
 *
 * A card that takes all of it ends **on** the band's edges, not a gutter inside them. At the
 * top that edge is already a gutter under the identity row (`LocalDemoChromeTopInset`); at
 * the bottom it is the mode pill's own top edge, so the card's ground then touches the pill
 * — 640 × 360 dp at font scale 2.0 — without ever going under it. With system bars on top
 * of that the band is shorter than the card's padding and button together, and the SDK card
 * gives up its padding before its button (`fitAROverlayCard`, #4533).
 */
private fun Modifier.lendSdkCardMargin(): Modifier = layout { measurable, constraints ->
    val lent = if (constraints.hasBoundedHeight) SceneViewTokens.ArOverlay.sdkCardMargin.roundToPx() * 2 else 0
    val placeable = measurable.measure(constraints.copy(maxHeight = constraints.maxHeight + lent))
    val height = constraints.constrainHeight(placeable.height)
    layout(placeable.width, height) {
        placeable.place(0, (height - placeable.height) / 2)
    }
}
