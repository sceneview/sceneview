package io.github.sceneview.demo.common.placement

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified

/**
 * Where the placement screen's bottom stack — the read-out, the decision card, the coaching
 * line — goes relative to the scaffold's bottom chrome (the dock and the pills above it).
 *
 * @property besideChrome `true` when the stack sits in the corner next to the chrome instead of
 *   above it.
 * @property width  The stack's width beside the chrome; [Dp.Unspecified] above it, where the
 *   stack takes the band's width and each child caps itself.
 * @property bottom The stack's bottom padding, measured up from the safe area.
 */
internal data class CoachStackSlot(
    val besideChrome: Boolean,
    val width: Dp,
    val bottom: Dp,
)

/**
 * Decides [CoachStackSlot]. The rule is that the stack never shares pixels with the chrome:
 *
 * - **Above it**, one gutter over its top edge — every portrait window. The scaffold draws its
 *   chrome after the scene, so a stack parked lower is drawn *behind* the mode pill, its
 *   buttons out of reach (#4509, #4503).
 * - **Beside it**, one gutter off the safe area, when a decision card is up on a phone held
 *   sideways. The band left above the chrome is then thinner than the card's row of buttons,
 *   while the corner next to the centred chrome is free and as tall as the window allows.
 *   Only when that corner is at least [minWidth] wide: a card narrower than that wraps into
 *   a column of words, and the stack then stays above the chrome, where the card scrolls.
 *
 * @param compactHeight  A phone held sideways (`isDemoCompactHeight`).
 * @param cardShown      A decision card is on screen. A one-line coaching pill fits above the
 *   chrome in every window, so it never moves to the corner.
 * @param bandWidth      Width of the safe area the stack and the chrome are both laid out in.
 * @param chromeWidth    Measured width of the centred chrome, or [Dp.Unspecified] when it may
 *   span the band (`LocalDemoChromeBottomWidth`).
 * @param chromeBottom   Top edge of the chrome, measured up from the safe area
 *   (`LocalDemoChromeBottomInset`).
 * @param gutter         The gap the stack keeps from the chrome and from the safe area.
 * @param minWidth       The narrowest corner the card is allowed into.
 * @param maxWidth       The card's own maximum width.
 */
@Suppress("LongParameterList") // Eight measurements of one layout; a holder would only rename them.
internal fun coachStackSlot(
    compactHeight: Boolean,
    cardShown: Boolean,
    bandWidth: Dp,
    chromeWidth: Dp,
    chromeBottom: Dp,
    gutter: Dp,
    minWidth: Dp,
    maxWidth: Dp,
): CoachStackSlot {
    val above = CoachStackSlot(besideChrome = false, width = Dp.Unspecified, bottom = chromeBottom + gutter)
    val cornerWanted = compactHeight && cardShown
    val cornerMeasurable = chromeWidth.isSpecified && bandWidth.isSpecified
    if (!cornerWanted || !cornerMeasurable) return above
    // The chrome is centred: what is left on one side, less a gutter on each side of the stack.
    val corner = (bandWidth - chromeWidth) / 2 - gutter * 2
    return if (corner >= minWidth) {
        CoachStackSlot(besideChrome = true, width = minOf(corner, maxWidth), bottom = gutter)
    } else {
        above
    }
}

/**
 * Where the stack stands inside the safe area for [slot] and the card it shows.
 *
 * Above the chrome it is centred on the chrome's top edge. Beside it, it sits in the bottom
 * corner, level with the dock — a card's buttons belong within a thumb's reach. The one card
 * with no button, [PlacementCard.MOTION_SENSOR_SILENT], is only read: it stands at the top of
 * that corner instead, clear of the scrim the scaffold draws over the dock band, which would
 * otherwise dim its last line (#4522).
 */
internal fun coachStackAlignment(slot: CoachStackSlot, card: PlacementCard?): Alignment = when {
    !slot.besideChrome -> Alignment.BottomCenter
    card == PlacementCard.MOTION_SENSOR_SILENT -> Alignment.TopEnd
    else -> Alignment.BottomEnd
}
