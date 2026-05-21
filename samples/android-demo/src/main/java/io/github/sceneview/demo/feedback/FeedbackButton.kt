package io.github.sceneview.demo.feedback

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import io.github.sceneview.demo.R

/**
 * Entry point to the feedback flow — shown on the demo app's tab screens.
 *
 * On a comfortably-sized screen at a normal font scale this is an extended FAB
 * (icon + "Feedback" label). On a very narrow screen or at a large font scale
 * the extended label would overflow or crowd the [androidx.compose.material3.NavigationBar],
 * so the button collapses to an icon-only FAB — keeping its accessible name via
 * a content description (#2030).
 */
@Composable
fun FeedbackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    // Collapse to an icon-only FAB on a narrow screen or at a large font scale:
    // the extended label otherwise overflows or crowds the navigation bar.
    val collapsed =
        configuration.screenWidthDp < COMPACT_WIDTH_DP || fontScale > LARGE_FONT_SCALE
    val label = stringResource(R.string.feedback_button)

    if (collapsed) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = modifier,
        ) {
            // The visible label is gone — give the icon-only FAB the same
            // accessible name so screen-reader users still hear "Feedback".
            Icon(Icons.Outlined.Feedback, contentDescription = label)
        }
    } else {
        ExtendedFloatingActionButton(
            onClick = onClick,
            // The visible label is its own accessible name — no content
            // description needed on the icon.
            icon = { Icon(Icons.Outlined.Feedback, contentDescription = null) },
            text = { Text(label) },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = modifier,
        )
    }
}

/** Below this screen width the extended FAB label crowds the navigation bar. */
private const val COMPACT_WIDTH_DP = 360

/** Above this font scale the extended FAB label overflows its container. */
private const val LARGE_FONT_SCALE = 1.3f
