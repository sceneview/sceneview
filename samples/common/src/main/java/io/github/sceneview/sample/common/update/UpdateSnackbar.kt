package io.github.sceneview.sample.common.update

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import io.github.sceneview.sample.common.R

/**
 * Shows the Play in-app update as a Material 3 snackbar on [hostState]:
 *
 * - "Update available" · **Update** — [SnackbarDuration.Long], with a close icon. Tapping
 *   **Update** starts the flexible download ([InAppUpdateManager.startUpdate], Google's one
 *   consent modal); letting it time out or closing it snoozes it ([UpdatePromptController]).
 * - "Update ready" · **Restart** — [SnackbarDuration.Indefinite]: it stays until the user
 *   restarts into the new version ([InAppUpdateManager.completeUpdate]).
 *
 * The host belongs to the screen, not to this effect: put it in the screen's `Scaffold`
 * `snackbarHost` slot so it lands above the bottom navigation, and pass [enabled] = `false`
 * wherever the bottom of the screen holds live controls (an AR session) — the snackbar is
 * withdrawn there and comes back when the controls go.
 */
@Composable
fun UpdateSnackbarEffect(
    controller: UpdatePromptController,
    hostState: SnackbarHostState,
    enabled: Boolean = true,
) {
    val prompt = if (enabled) controller.prompt else null
    val availableMessage = stringResource(R.string.sample_update_available)
    val availableAction = stringResource(R.string.sample_update_action)
    val readyMessage = stringResource(R.string.sample_update_ready)
    val readyAction = stringResource(R.string.sample_update_restart)

    // Keyed on the prompt: a change of state (or `enabled` flipping off) cancels the
    // snackbar on screen, which removes it — a cancelled show is not a dismissal, so the
    // same prompt comes back when the screen is able to show it again.
    LaunchedEffect(prompt) {
        val shown = prompt ?: return@LaunchedEffect
        val result = when (shown) {
            UpdatePrompt.AVAILABLE -> hostState.showSnackbar(
                message = availableMessage,
                actionLabel = availableAction,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            UpdatePrompt.READY_TO_INSTALL -> hostState.showSnackbar(
                message = readyMessage,
                actionLabel = readyAction,
                withDismissAction = false,
                duration = SnackbarDuration.Indefinite,
            )
        }
        when (result) {
            SnackbarResult.ActionPerformed -> controller.onAction(shown)
            SnackbarResult.Dismissed -> controller.onDismissed(shown)
        }
    }
}
