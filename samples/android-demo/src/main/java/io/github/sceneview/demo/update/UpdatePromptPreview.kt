@file:Suppress("MaxLineLength") // @Preview annotation strings and uiMode flags are intentionally long

package io.github.sceneview.demo.update

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.sample.common.update.UpdatePrompt

/**
 * Studio previews of every state of the Play update snackbar ([UpdatePromptHost]). The
 * on-device flow can be driven on an emulator with the debug-only
 * `--es update_qa available|cancel|fail` extra (see `QaAppUpdateManager`).
 */
@Composable
private fun UpdatePromptStates() {
    SceneViewDemoTheme(dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(SceneViewTokens.Space.md),
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
            ) {
                listOf(
                    UpdatePrompt.Available,
                    UpdatePrompt.Waiting,
                    UpdatePrompt.Downloading(progress = null),
                    UpdatePrompt.Downloading(progress = 0.42f),
                    UpdatePrompt.ReadyToInstall,
                    UpdatePrompt.Failed,
                ).forEach { prompt ->
                    UpdatePromptSnackbar(prompt = prompt, onAction = {}, onDismiss = {})
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Update snackbar - all states")
@Composable
private fun UpdatePromptStatesPreview() = UpdatePromptStates()

@Preview(showBackground = true, name = "Update snackbar - all states, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UpdatePromptStatesDarkPreview() = UpdatePromptStates()
