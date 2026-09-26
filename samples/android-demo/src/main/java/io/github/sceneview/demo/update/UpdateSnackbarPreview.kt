@file:Suppress("MaxLineLength") // @Preview annotation strings and uiMode flags are intentionally long

package io.github.sceneview.demo.update

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.sample.common.R

/**
 * Studio previews of the two Play update snackbars `UpdateSnackbarEffect` shows on the tab
 * host. The on-device flow can be driven on an emulator with the debug-only
 * `--es update_qa available` extra (see `QaAppUpdateManager`).
 */
@Composable
private fun UpdateSnackbarSample(message: Int, action: Int, dismissible: Boolean) {
    Snackbar(
        modifier = Modifier.padding(SceneViewTokens.Space.md),
        action = { TextButton(onClick = {}) { Text(stringResource(action)) } },
        dismissAction = if (dismissible) {
            { IconButton(onClick = {}) { Icon(Icons.Filled.Close, contentDescription = null) } }
        } else {
            null
        },
    ) {
        Text(stringResource(message))
    }
}

@Preview(showBackground = true, name = "Update snackbar - Available")
@Composable
private fun UpdateSnackbarAvailablePreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { UpdateSnackbarSample(R.string.sample_update_available, R.string.sample_update_action, dismissible = true) }
    }
}

@Preview(showBackground = true, name = "Update snackbar - Ready")
@Composable
private fun UpdateSnackbarReadyPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { UpdateSnackbarSample(R.string.sample_update_ready, R.string.sample_update_restart, dismissible = false) }
    }
}

@Preview(showBackground = true, name = "Update snackbar - Dark Available", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UpdateSnackbarDarkAvailablePreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { UpdateSnackbarSample(R.string.sample_update_available, R.string.sample_update_action, dismissible = true) }
    }
}

@Preview(showBackground = true, name = "Update snackbar - Dark Ready", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UpdateSnackbarDarkReadyPreview() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface { UpdateSnackbarSample(R.string.sample_update_ready, R.string.sample_update_restart, dismissible = false) }
    }
}
