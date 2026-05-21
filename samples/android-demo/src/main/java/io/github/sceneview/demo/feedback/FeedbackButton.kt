package io.github.sceneview.demo.feedback

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.sceneview.demo.R

/**
 * Always-visible entry point to the feedback flow. Extended (icon + label) on
 * the tab screens where there is room, collapsed to an icon inside the
 * full-screen demos so it never competes with the 3D scene.
 */
@Composable
fun FeedbackButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cd = stringResource(R.string.feedback_cd_button)
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = expanded,
        icon = { Icon(Icons.Outlined.Feedback, contentDescription = null) },
        text = { Text(stringResource(R.string.feedback_button)) },
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.semantics { contentDescription = cd },
    )
}
