package io.github.sceneview.demo.feedback

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.sceneview.demo.R

/**
 * Floating "Stop recording" control shown while a feedback screen recording is
 * in progress. The feedback dialog is dismissed during capture so the user can
 * demonstrate the bug; this pill stays on top as the in-app stop affordance
 * (the foreground-service notification carries the same action as a backup).
 */
@Composable
fun RecordingStopPill(onStop: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = onStop,
        icon = { Icon(Icons.Filled.Stop, contentDescription = null) },
        text = { Text(stringResource(R.string.feedback_recording_stop_action)) },
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        modifier = modifier,
    )
}
