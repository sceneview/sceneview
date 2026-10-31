package io.github.sceneview.demo.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.motionFade

/** Test tags of [ScrollAwareTopBar]. */
object ScrollAwareTopBarTestTags {
    const val BAR = "scroll_aware_top_bar"
    const val EDGE = "scroll_aware_top_bar_edge"
}

/**
 * The top app bar of a scrolling page (`top-bar-scroll` in `DESIGN.md`): **transparent at
 * rest**, so the page runs up under the status bar with nothing drawn over it, and raised
 * to `surface-container` with an `outline-variant` hairline the moment content scrolls
 * beneath it.
 *
 * Driven by Material's own scroll behaviour, not by a scroll offset read by hand: pass the
 * same [scrollBehavior] to the page as `Modifier.nestedScroll(behavior.nestedScrollConnection)`.
 * Pinned rather than enter-always — a compact bar has nothing to collapse, and a bar that
 * scrolls away takes the page's title with it. The tone follows the behaviour's
 * `overlappedFraction` (Material animates it); the hairline fades on `motion-fade`, because
 * tone alone between `surface` and `surface-container` is a step the eye barely names.
 *
 * [windowInsets] is the band the bar draws under — the status bar on a screen that is not
 * already padded for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrollAwareTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val under = scrollBehavior.state.overlappedFraction > SCROLLED_THRESHOLD
    val edge by animateColorAsState(
        targetValue = if (under) colors.outlineVariant else Color.Transparent,
        animationSpec = motionFade(),
        label = "top-bar-edge",
    )
    Column(modifier = modifier.fillMaxWidth().testTag(ScrollAwareTopBarTestTags.BAR)) {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            },
            navigationIcon = navigationIcon,
            actions = actions,
            windowInsets = windowInsets,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = colors.surfaceContainer,
                titleContentColor = colors.onSurface,
                navigationIconContentColor = colors.onSurface,
                actionIconContentColor = colors.onSurfaceVariant,
            ),
            scrollBehavior = scrollBehavior,
        )
        HorizontalDivider(
            thickness = SceneViewTokens.Layout.hairlineWidth,
            color = edge,
            modifier = Modifier.testTag(ScrollAwareTopBarTestTags.EDGE),
        )
    }
}

/** Overlap past which content counts as "under" the bar: the first scrolled pixel, rounded. */
private const val SCROLLED_THRESHOLD = 0.01f
