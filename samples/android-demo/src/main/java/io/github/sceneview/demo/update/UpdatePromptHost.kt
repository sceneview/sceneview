@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.sceneview.demo.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.motionFade
import io.github.sceneview.demo.theme.motionTween
import io.github.sceneview.demo.ui.NARRATION_DOT_FLOOR
import io.github.sceneview.demo.ui.NarrationProgressRing
import io.github.sceneview.demo.ui.NarrationText
import io.github.sceneview.sample.common.R
import io.github.sceneview.sample.common.update.UpdatePrompt
import io.github.sceneview.sample.common.update.UpdatePromptController
import kotlinx.coroutines.delay
import java.text.NumberFormat
import kotlin.math.floor

/**
 * The Play in-app update, as one Material 3 snackbar that changes in place:
 *
 * - "Update available" · **Update** · close — times out like a long snackbar (10 s, longer
 *   when accessibility asks for it); timing out or closing snoozes it ([UpdatePromptController]).
 * - **Update** → "Waiting for Google Play…" in the same frame, while Google's consent modal
 *   is up and Play queues the download (indeterminate `LoadingIndicator`).
 * - "Downloading update…" — indeterminate until Play reports a size, then the wavy progress
 *   ring and the percentage (`motion-narration` in `DESIGN.md`).
 * - "Update ready" · **Restart**, until answered.
 * - "Update failed" · **Retry** · close.
 *
 * The surface is the same snackbar throughout — only its content is swapped — so a tap is
 * never followed by an empty screen. Nothing is blocked while it shows: it is a snackbar,
 * not a dialog.
 *
 * Put it in the screen's `Scaffold` `snackbarHost` slot so it lands above the bottom
 * navigation, and pass [enabled] = `false` wherever the bottom of the screen holds live
 * controls (an AR session): it is withdrawn there — not dismissed — and comes back when the
 * controls go.
 */
@Composable
fun UpdatePromptHost(
    controller: UpdatePromptController,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val prompt = if (enabled) controller.prompt else null

    // The unsolicited offer times out like `SnackbarDuration.Long`. Keyed on the offer being
    // on screen, so withdrawing it (an AR session) cancels the timer instead of counting as a
    // dismissal, and an offer that comes back (a cancelled consent modal) gets a fresh one.
    val offerShown = prompt == UpdatePrompt.Available
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(offerShown) {
        if (!offerShown) return@LaunchedEffect
        val timeout = accessibilityManager?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = OFFER_TIMEOUT_MILLIS,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: OFFER_TIMEOUT_MILLIS
        delay(timeout)
        controller.onDismissed(UpdatePrompt.Available)
    }

    // While the snackbar leaves, `prompt` is already null: it leaves with its last content.
    val lastShown = remember { arrayOfNulls<UpdatePrompt>(1) }
    if (prompt != null) lastShown[0] = prompt
    AnimatedVisibility(
        visible = prompt != null,
        modifier = modifier,
        // Rise on entry, fall on exit, like the other bottom-band messages (DemoStatusBanner).
        enter = fadeIn(motionTween(SceneViewTokens.Duration.mediumMillis)) +
            slideInVertically(motionTween(SceneViewTokens.Duration.mediumMillis)) { it / 3 },
        exit = fadeOut(motionTween(SceneViewTokens.Duration.shortMillis)) +
            slideOutVertically(motionTween(SceneViewTokens.Duration.shortMillis)) { it / 3 },
        label = "update-prompt",
    ) {
        val shown = prompt ?: lastShown[0] ?: return@AnimatedVisibility
        UpdatePromptSnackbar(
            prompt = shown,
            onAction = { controller.onAction(shown) },
            onDismiss = { controller.onDismissed(shown) },
        )
    }
}

/**
 * One state of the update snackbar — stateless, drawn by [UpdatePromptHost] and by the
 * previews. [onAction] answers the action button (Update, Restart, Retry), [onDismiss] the
 * close button of the dismissible states.
 */
@Composable
fun UpdatePromptSnackbar(
    prompt: UpdatePrompt,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onActionState = rememberUpdatedState(onAction)
    val onDismissState = rememberUpdatedState(onDismiss)
    val actionLabel = when (prompt) {
        UpdatePrompt.Available -> stringResource(R.string.sample_update_action)
        UpdatePrompt.ReadyToInstall -> stringResource(R.string.sample_update_restart)
        UpdatePrompt.Failed -> stringResource(R.string.sample_update_retry)
        UpdatePrompt.Waiting, is UpdatePrompt.Downloading -> null
    }
    val progress = (prompt as? UpdatePrompt.Downloading)?.progress
    val dismissible = prompt == UpdatePrompt.Available || prompt == UpdatePrompt.Failed

    Snackbar(
        modifier = modifier.animateContentSize(animationSpec = motionFade()),
        action = when {
            actionLabel != null -> {
                {
                    TextButton(
                        onClick = { onActionState.value() },
                        colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                    ) { Text(actionLabel) }
                }
            }
            // The percentage sits where the action would, in the action's colour.
            progress != null -> {
                { DownloadPercent(progress) }
            }
            else -> null
        },
        dismissAction = if (dismissible) {
            {
                IconButton(onClick = { onDismissState.value() }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.sample_update_dismiss))
                }
            }
        } else {
            null
        },
    ) {
        UpdatePromptMessage(prompt, progress)
    }
}

@Composable
private fun UpdatePromptMessage(prompt: UpdatePrompt, progress: Float?) {
    val working = prompt == UpdatePrompt.Waiting || prompt is UpdatePrompt.Downloading
    val message = when (prompt) {
        UpdatePrompt.Available -> stringResource(R.string.sample_update_available)
        UpdatePrompt.Waiting -> stringResource(R.string.sample_update_waiting)
        is UpdatePrompt.Downloading -> stringResource(R.string.sample_update_downloading)
        UpdatePrompt.ReadyToInstall -> stringResource(R.string.sample_update_ready)
        UpdatePrompt.Failed -> stringResource(R.string.sample_update_failed)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm + SceneViewTokens.Space.xs),
    ) {
        if (working) {
            // One loader: the Expressive LoadingIndicator until the size is known, then the
            // wavy ring — `motion-narration` in DESIGN.md.
            Box(Modifier.size(SceneViewTokens.Layout.dockIconSize), contentAlignment = Alignment.Center) {
                if (progress != null) {
                    NarrationProgressRing(
                        progress = progress,
                        color = SnackbarDefaults.actionColor,
                        modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
                        // The default track is a pale container colour, which outshines the
                        // arc on the snackbar's inverse surface: dim the arc's colour instead.
                        trackColor = SnackbarDefaults.actionColor.copy(alpha = NARRATION_DOT_FLOOR),
                    )
                } else {
                    LoadingIndicator(
                        modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
                        color = SnackbarDefaults.actionColor,
                    )
                }
            }
        }
        // The message is the live region, not the percentage: TalkBack says "Downloading
        // update" once, not every percent (the ring carries the progress semantics).
        NarrationText(
            text = message,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            maxLines = 1,
        )
    }
}

@Composable
private fun DownloadPercent(progress: Float) {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getPercentInstance(locale) }
    // Floored: 100 % only once Play says the download is complete.
    val shown = floor(progress * PERCENT) / PERCENT
    Text(
        text = format.format(shown),
        modifier = Modifier.padding(horizontal = SceneViewTokens.Space.md),
        color = SnackbarDefaults.actionColor,
        // Tabular figures: the label does not jitter as the digits change.
        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

/** `SnackbarDuration.Long`. */
private const val OFFER_TIMEOUT_MILLIS = 10_000L

private const val PERCENT = 100f
