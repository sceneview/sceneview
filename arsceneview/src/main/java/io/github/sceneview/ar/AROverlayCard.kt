package io.github.sceneview.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one card behind [ARCameraPermissionOverlay] and [ARCoreAvailabilityOverlay]: a title, a
 * body and at most one action, centred in the scene on a dark ground — the ground is a camera
 * frame, so the card does not follow the app theme.
 *
 * Both overlays are public and may be drawn in any parent, so the layout holds in two kinds:
 *
 * - **A parent shorter than the card** — a phone held sideways, a large font. The action is
 *   measured first and the text takes what it leaves, scrolling when that is less than it
 *   needs; a parent shorter still takes the card's gap and padding before it takes anything
 *   from the action ([fitAROverlayCard]). A plain `Column` handed every pixel to the text and
 *   measured the action at zero (#4509 for the availability card, #4533 for the permission
 *   card).
 * - **A parent with no height limit** — a scrolling column, a `LazyColumn` item. The text is
 *   given the height it asks for. Not a `weight` on the text: a weighted child of an unlimited
 *   height is given zero.
 *
 * [cardTag] is on the card's **ground**: its bounds are the dark rectangle's, not the text
 * inside the padding. A test that asks whether the card overlaps something has to ask about
 * what is drawn (#4533).
 *
 * @param action the action's label, or `null` for a card that only explains.
 */
@Composable
internal fun BoxScope.AROverlayCard(
    title: String,
    body: String,
    action: String?,
    onAction: () -> Unit,
    cardTag: String,
    actionTag: String,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    text = title,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = body,
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            if (action != null) {
                Box(
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(22.dp))
                        .clickable(role = Role.Button, onClick = onAction)
                        .padding(horizontal = AROverlayCardActionPadding, vertical = 12.dp)
                        .testTag(actionTag),
                ) {
                    BasicText(
                        text = action,
                        style = TextStyle(
                            color = Color(0xFF1A1A2E),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            // A label that wraps is centred like the text above it.
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        },
        modifier = modifier
            .align(Alignment.Center)
            .padding(AROverlayCardMargin)
            .widthIn(max = 320.dp)
            .testTag(cardTag)
            .background(OverlayCardBackground, RoundedCornerShape(16.dp))
            .padding(horizontal = AROverlayCardPadding),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val actionPlaceable = measurables.getOrNull(1)?.measure(loose)
        val actionHeight = actionPlaceable?.height ?: 0
        val padding = AROverlayCardPadding.roundToPx() * 2
        val gap = if (actionPlaceable != null) AROverlayCardActionGap.roundToPx() else 0
        val textMaxHeight = if (constraints.hasBoundedHeight) {
            (constraints.maxHeight - padding - gap - actionHeight).coerceAtLeast(0)
        } else {
            // A scrolling container refuses an unlimited height: hand it the height it asks for.
            measurables[0].maxIntrinsicHeight(constraints.maxWidth)
        }
        val text = measurables[0].measure(loose.copy(maxHeight = textMaxHeight))
        val fit = fitAROverlayCard(
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE,
            textHeight = text.height,
            actionHeight = actionHeight,
            padding = padding,
            gap = gap,
        )
        val width = constraints.constrainWidth(maxOf(text.width, actionPlaceable?.width ?: 0))
        val height = constraints.constrainHeight(fit.height)
        layout(width, height) {
            text.placeRelative((width - text.width) / 2, fit.textTop)
            actionPlaceable?.placeRelative((width - actionPlaceable.width) / 2, fit.actionTop)
        }
    }
}

/** Where [AROverlayCard] puts its two blocks, and how tall that makes it. Pixels. */
internal data class AROverlayCardFit(val height: Int, val textTop: Int, val actionTop: Int)

/**
 * What the card gives up, in order, as its parent gets shorter — so that the action, the only
 * way out of the state the card explains, is the last thing to be cut (#4533):
 *
 * 1. **its text**, which scrolls in whatever the action leaves ([textHeight] is already the
 *    height the text was given);
 * 2. **the gap above the action**, once no text is left for it to separate;
 * 3. **its own padding**, top and bottom alike, down to nothing.
 *
 * Only a parent shorter than the action itself cuts the action.
 *
 * @param maxHeight the height the parent allows, `Int.MAX_VALUE` when it sets no limit.
 * @param padding the card's top and bottom padding together.
 * @param gap the gap between the text and the action, `0` for a card with no action.
 */
internal fun fitAROverlayCard(
    maxHeight: Int,
    textHeight: Int,
    actionHeight: Int,
    padding: Int,
    gap: Int,
): AROverlayCardFit {
    val keptGap = if (textHeight > 0) gap else 0
    val content = textHeight + keptGap + actionHeight
    val keptPadding = padding.coerceAtMost((maxHeight - content).coerceAtLeast(0))
    val top = keptPadding / 2
    return AROverlayCardFit(
        height = content + keptPadding,
        textTop = top,
        actionTop = top + textHeight + keptGap,
    )
}

/**
 * The margin the card keeps around itself, on every side. The demo app's
 * `SceneViewTokens.ArOverlay.sdkCardMargin` mirrors it; `ArOverlaySdkCardTokensTest` there
 * measures this card and fails when the two differ.
 */
internal val AROverlayCardMargin = 24.dp

/** The card's padding, between its ground and its text. */
internal val AROverlayCardPadding = 20.dp

/** The gap between the card's text and its action. */
internal val AROverlayCardActionGap = 16.dp

/** The action's padding, on either side of its label. */
internal val AROverlayCardActionPadding = 20.dp

private val OverlayCardBackground = Color.Black.copy(alpha = 0.85f)
