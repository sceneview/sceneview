package io.github.sceneview.demo.ui

import androidx.compose.ui.unit.dp

/**
 * Breathing room at the bottom of a root tab's scrollable content.
 *
 * Purely cosmetic: it keeps the last card from sitting flush against the
 * navigation bar. It is deliberately *not* a clearance reserved for a floating
 * element — the tabs used to leave `FEEDBACK_FAB_RESERVED_SPACE` (168 dp) for a
 * feedback FAB that floated over them, and that FAB is gone (it masked whatever
 * card happened to rest in its fixed band, which no amount of bottom padding
 * could fix; feedback is now a card in the About tab). If something floating
 * ever comes back over these tabs, the answer is to make it a sibling, not to
 * grow this number.
 */
val LIST_BOTTOM_GUTTER = 24.dp
