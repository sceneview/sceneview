package io.github.sceneview.demo.feedback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * The bottom offset (above the navigation-bars inset) at which the floating
 * feedback FAB sits. Kept in sync with the FAB's `Modifier.padding(bottom = …)`
 * in `MainActivity.kt`.
 */
val FEEDBACK_FAB_BOTTOM_OFFSET = 96.dp

/**
 * The total vertical area a tab's scrollable / column content must leave
 * empty at its bottom so the floating feedback FAB does not mask the last
 * item (#2194).
 *
 * Computed from the FAB's bottom offset above the navigation bars
 * ([FEEDBACK_FAB_BOTTOM_OFFSET]) + the M3 `FloatingActionButton` body height
 * (≈ 56 dp) + a small visual gap. Use as the `bottom` of a tab's `Column`
 * `padding(...)` or a `LazyVerticalGrid` / `LazyColumn` `contentPadding(...)`
 * so the chip floats over a gutter, not over content.
 *
 * The chip itself is anchored on the LEFT (`Alignment.BottomStart`), so a
 * grid whose right column reaches the bottom of the screen is still
 * legible — but the gutter applies to the whole row to keep the layout
 * symmetric and predictable across short / long lists.
 */
val FEEDBACK_FAB_RESERVED_SPACE = 168.dp

/**
 * Process-wide chrome state for the floating feedback FAB.
 *
 * The FAB is rendered once in `MainActivity` as an overlay above the tab
 * host, but a tab can ask to hide it while its own UI fully occupies the
 * bottom of the screen (live AR session, modal bottom sheet, etc. — #2194).
 *
 * Flip [chipVisible] to `false` while the tab's full-screen interaction is
 * active and back to `true` on exit. The default is `true` so the chip
 * shows on every tab that does not opt out.
 *
 * Held as a top-level `mutableStateOf` rather than a `CompositionLocal`
 * because the tab content that toggles it and the `MainActivity` overlay
 * that reads it live in **sibling** composition trees — a `CompositionLocal`
 * provided in one would not be observed by the other.
 */
object FeedbackChrome {
    var chipVisible: Boolean by mutableStateOf(true)
}
