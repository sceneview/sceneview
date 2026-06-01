package io.github.sceneview.demo.feedback

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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

    /**
     * Whether the *currently visible* list tab has been scrolled away from its
     * resting (top) position.
     *
     * The chip sits at a **fixed y-band** on the bottom-left
     * ([FEEDBACK_FAB_BOTTOM_OFFSET] above the navigation bars), so a full-width
     * card that naturally lands in that band at rest — the first "Trending
     * models" card on Explore, the "Sponsor" card on About — is masked by the
     * chip even though [FEEDBACK_FAB_RESERVED_SPACE] already clears the *last*
     * item (#2358). Reserving bottom padding cannot help a card resting
     * mid-list.
     *
     * The fix is the standard M3 scroll-aware-FAB behaviour: the chip is
     * **hidden at rest** (`false`, where the overlap occurs) and **revealed the
     * moment the user scrolls** (`true`). Once scrolling, the overlapped card
     * has moved out of the chip's fixed band, so the chip can show without
     * masking anything. Discoverability is preserved — both tabs are taller
     * than the viewport, so a browsing user reveals the chip immediately.
     *
     * Each scrollable list tab drives this from its own scroll state; it is
     * reset to `false` whenever the chip is hidden ([chipVisible] = false) or a
     * tab without a scroll signal is shown, so a stale `true` from a previous
     * tab can never leave the chip floating over a fresh tab's resting content.
     */
    var listScrolled: Boolean by mutableStateOf(false)
}

/**
 * Past this many pixels of scroll the list counts as "scrolled away" and the
 * feedback chip is revealed. A few pixels of headroom keeps a 1-px scroll
 * jitter (or an over-scroll spring settling back to 0) from flickering the
 * chip in and out at rest.
 */
private const val FEEDBACK_CHIP_REVEAL_THRESHOLD_PX = 8

/**
 * Drives [FeedbackChrome.listScrolled] from a vertical-scroll tab's
 * [ScrollState] so the floating feedback chip stays hidden at rest and reveals
 * on scroll (#2358). Call once near the top of a scrollable list tab
 * (Explore / About / AR View), passing the same [ScrollState] used by the
 * tab's `verticalScroll(...)`.
 *
 * On leaving the composition the flag is reset to `false`, so switching to a
 * tab that does not drive it (or back to the top of this one) never leaves the
 * chip floating over fresh resting content.
 */
@Composable
fun DriveFeedbackChipReveal(scrollState: ScrollState) {
    val scrolled by remember(scrollState) {
        derivedStateOf { scrollState.value > FEEDBACK_CHIP_REVEAL_THRESHOLD_PX }
    }
    LaunchedEffect(scrolled) { FeedbackChrome.listScrolled = scrolled }
    DisposableEffect(Unit) { onDispose { FeedbackChrome.listScrolled = false } }
}

/**
 * [DriveFeedbackChipReveal] overload for a `LazyVerticalGrid`-backed tab
 * (the Samples list), keyed off the grid's first-visible-item offset / index
 * so the chip reveals as soon as the grid scrolls off its top row.
 */
@Composable
fun DriveFeedbackChipReveal(gridState: LazyGridState) {
    val scrolled by remember(gridState) {
        derivedStateOf {
            gridState.firstVisibleItemIndex > 0 ||
                gridState.firstVisibleItemScrollOffset > FEEDBACK_CHIP_REVEAL_THRESHOLD_PX
        }
    }
    // snapshotFlow keeps the LaunchedEffect from re-keying on every offset
    // delta — it only emits on the boolean's distinct transitions.
    LaunchedEffect(gridState) {
        snapshotFlow { scrolled }.collect { FeedbackChrome.listScrolled = it }
    }
    DisposableEffect(Unit) { onDispose { FeedbackChrome.listScrolled = false } }
}
