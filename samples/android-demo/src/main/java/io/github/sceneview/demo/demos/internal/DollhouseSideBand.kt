package io.github.sceneview.demo.demos.internal

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where the dollhouse's 3D view frames the room on a phone held sideways (#4527): the band on
 * the **start** side of the window, beside the chrome instead of under it.
 *
 * Held sideways, the chrome is a column down the middle of a view barely taller than it is —
 * the recording's chip under the identity row, the dock at the bottom — and the band it leaves
 * between them is a strip. The sides are free. So the chip moves to the top **end** corner
 * ([cornerCard] wide at most, like the replay's timeline card), the dock stays centred at the
 * bottom, and the room is framed in what is left at the start: from the window's safe start
 * edge to the dock's start edge, or to the corner card's when that reaches further in, and from
 * under the identity row down to the window's safe bottom — the dock is not in that band.
 *
 * Returned as the padding, from each window edge, that the room must clear — the same contract
 * as `demoContentPadding` upright. Absolute sides: [startIsLeft] says which one the start is.
 *
 * @param width            The view's width (the whole window: the view runs under the chrome).
 * @param safeLeft         The window's safe-drawing inset on the left.
 * @param safeRight        The window's safe-drawing inset on the right.
 * @param safeTop          The window's safe-drawing inset at the top (status bar).
 * @param safeBottom       The window's safe-drawing inset at the bottom (navigation bar).
 * @param identityRow      The identity row and its gutter, below [safeTop].
 * @param bottomChromeWidth The dock's measured width, centred; [Dp.Unspecified] before it is
 *   measured, when the band is taken to run to the window's centre (the opening keeps the view
 *   veiled until the framing it settles on has been drawn).
 * @param cornerCard       The widest the chip in the top end corner may be.
 * @param cornerMargin     The chip's margin from the window's safe end edge.
 */
@Suppress("LongParameterList")
internal fun dollhouseSideBand(
    width: Dp,
    safeLeft: Dp,
    safeRight: Dp,
    safeTop: Dp,
    safeBottom: Dp,
    identityRow: Dp,
    bottomChromeWidth: Dp,
    cornerCard: Dp,
    cornerMargin: Dp,
    startIsLeft: Boolean,
): PaddingValues {
    val safeStart = if (startIsLeft) safeLeft else safeRight
    val safeEnd = if (startIsLeft) safeRight else safeLeft
    val dockHalf = if (bottomChromeWidth == Dp.Unspecified) 0.dp else bottomChromeWidth / 2
    // Distance from the start edge to the first chrome the band meets on its way across.
    val dockStart = width / 2 - dockHalf
    val cardStart = width - safeEnd - cornerMargin - cornerCard
    val bandEnd = minOf(dockStart, cardStart).coerceAtLeast(safeStart)
    val endCover = (width - bandEnd).coerceAtLeast(0.dp)
    return PaddingValues.Absolute(
        left = if (startIsLeft) safeStart else endCover,
        top = safeTop + identityRow,
        right = if (startIsLeft) endCover else safeStart,
        bottom = safeBottom,
    )
}
