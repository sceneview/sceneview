package io.github.sceneview.sample.common.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive banner that surfaces a Play in-app update.
 *
 * No-op while [InAppUpdateManager.updateState] is `IDLE` / `CHECKING` /
 * `UP_TO_DATE`. Renders an integrated card for every actionable state so the
 * whole flexible-update flow feels native to the demo — no Google modals beyond
 * the single consent dialog (#1941):
 *
 * - `AVAILABLE` → "A new version is available" + an in-app **Update** button
 *   that calls [InAppUpdateManager.startUpdate] (the one Google consent modal).
 * - `DOWNLOADING` → integrated progress bar.
 * - `READY_TO_INSTALL` → "Update ready" + a **Restart** button that calls
 *   [InAppUpdateManager.completeUpdate].
 *
 * The banner is intentionally rounded + edge-aligned (24 dp radius, 16 dp inset)
 * so it overlays cleanly on top of any sample UI — including the full-bleed
 * SceneView surface — without competing with primary CTAs.
 *
 * @param actionFocusRequester optional [FocusRequester] for the banner's action
 * CTA — the **Update** button while `AVAILABLE`, the **Restart** button while
 * `READY_TO_INSTALL`. D-pad hosts (Android TV) should pass one in: when the
 * banner reaches an actionable state the visible button is focused
 * automatically so the Leanback user can act without hunting for it. Phone
 * hosts leave this `null` — touch users tap the button regardless, and an
 * unsolicited focus request would be inert.
 */
@Composable
fun UpdateBanner(
    updateManager: InAppUpdateManager,
    modifier: Modifier = Modifier,
    actionFocusRequester: FocusRequester? = null,
) {
    val state = updateManager.updateState
    val showBanner = state == InAppUpdateManager.UpdateState.AVAILABLE
            || state == InAppUpdateManager.UpdateState.DOWNLOADING
            || state == InAppUpdateManager.UpdateState.READY_TO_INSTALL

    AnimatedVisibility(
        visible = showBanner,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (state) {
                    InAppUpdateManager.UpdateState.READY_TO_INSTALL ->
                        MaterialTheme.colorScheme.primaryContainer
                    else ->
                        MaterialTheme.colorScheme.secondaryContainer
                }
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (state) {
                                InAppUpdateManager.UpdateState.AVAILABLE ->
                                    "A new version is available"
                                InAppUpdateManager.UpdateState.DOWNLOADING ->
                                    "Downloading update…"
                                InAppUpdateManager.UpdateState.READY_TO_INSTALL ->
                                    "Update ready!"
                                else -> ""
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        when (state) {
                            InAppUpdateManager.UpdateState.AVAILABLE -> Text(
                                text = "Tap Update to download it in the background.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            InAppUpdateManager.UpdateState.DOWNLOADING -> Text(
                                text = "${(updateManager.downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            else -> {}
                        }
                    }

                    when (state) {
                        InAppUpdateManager.UpdateState.AVAILABLE -> {
                            // Auto-focus the Update CTA for D-pad hosts. Keyed
                            // on `updateState` so it fires exactly once on the
                            // transition into AVAILABLE, not on every
                            // recomposition. No-op for phone hosts, which pass
                            // `actionFocusRequester == null`. Without this the
                            // Update button is unreachable by D-pad on Android
                            // TV (#1942 review — MAJOR 5).
                            if (actionFocusRequester != null) {
                                LaunchedEffect(state) {
                                    actionFocusRequester.requestFocus()
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                // `startUpdate()` is a no-op once the state
                                // leaves AVAILABLE, so a fast double tap can't
                                // double-prompt — the manager owns the guard.
                                onClick = { updateManager.startUpdate() },
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = if (actionFocusRequester != null) {
                                    Modifier.focusRequester(actionFocusRequester)
                                } else {
                                    Modifier
                                }
                            ) {
                                Text("Update")
                            }
                        }
                        InAppUpdateManager.UpdateState.READY_TO_INSTALL -> {
                            // Auto-focus the Restart CTA for D-pad hosts. Keyed
                            // on `updateState` so it fires exactly once on the
                            // transition into READY_TO_INSTALL, not on every
                            // recomposition. No-op for phone hosts, which pass
                            // `actionFocusRequester == null`.
                            if (actionFocusRequester != null) {
                                LaunchedEffect(state) {
                                    actionFocusRequester.requestFocus()
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                // `completeUpdate()` is a no-op unless the
                                // state is READY_TO_INSTALL — the manager
                                // guards it, so the button can't misfire.
                                onClick = { updateManager.completeUpdate() },
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = if (actionFocusRequester != null) {
                                    Modifier.focusRequester(actionFocusRequester)
                                } else {
                                    Modifier
                                }
                            ) {
                                Text("Restart")
                            }
                        }
                        else -> {}
                    }
                }

                if (state == InAppUpdateManager.UpdateState.DOWNLOADING) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { updateManager.downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f),
                        strokeCap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
