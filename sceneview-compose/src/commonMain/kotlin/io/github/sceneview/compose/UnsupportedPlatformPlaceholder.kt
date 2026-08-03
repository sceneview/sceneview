package io.github.sceneview.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Drawn by an `actual` whose renderer is not implemented yet.
 *
 * A viewport that renders nothing is indistinguishable from a model that failed to load,
 * and that ambiguity costs real debugging time. This says which platform is missing and
 * why, on screen, in the viewport's own bounds.
 */
@Composable
internal fun UnsupportedPlatformPlaceholder(
    platform: String,
    reason: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(PlaceholderBackground),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.text.BasicText(
            text = "SceneViewer is not available on $platform yet —\n$reason.",
            modifier = Modifier.padding(24.dp),
            style = TextStyle(
                color = PlaceholderForeground,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

private val PlaceholderBackground = Color(0xFF1B1B1F)
private val PlaceholderForeground = Color(0xFFE3E2E6)
