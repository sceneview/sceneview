@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.telemetry

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * Hosts the notification pre-prompt over the tab host. [returnedHomeFromSample] is bumped by
 * the NavController observer each time Home comes back after a sample; every bump counts
 * toward the prompt ([PushPromptPolicy]), and the sheet shows once the policy says so — on
 * Home only ([onHome]), never over a sample.
 */
@Composable
fun PushPromptHost(returnedHomeFromSample: Int, onHome: Boolean) {
    val context = LocalContext.current
    val policy = remember { PushPromptPolicy(Telemetry.promptStore(context)) }
    var showing by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        policy.onAnswered()
        Telemetry.analytics.log(
            AnalyticsEvent.PushPromptResult(if (granted) PromptResult.Granted else PromptResult.Denied),
        )
        if (granted) Telemetry.acceptPush(context) else Telemetry.refreshUserProperties(context)
    }

    LaunchedEffect(returnedHomeFromSample) {
        // 0 is the launch: never a reason to show the sheet (it would open over the Home hero).
        if (returnedHomeFromSample <= 0) return@LaunchedEffect
        // Push is opt-in: offered while it is off. Below Android 13 there is no permission to
        // ask, so the sheet's "Notify me" is the opt-in, pointless if the system blocks posting.
        val eligible = Telemetry.firebaseAvailable &&
            !Telemetry.notificationsEnabled &&
            (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
                    Telemetry.systemAllowsNotifications(context)
                )
        // Never while the usage-statistics consent is owed, nor in the session that answered it.
        if (!showing && policy.onReturnedHome(eligible, Telemetry.consentAllowsPushPrompt())) {
            policy.onShown()
            Telemetry.analytics.log(AnalyticsEvent.PushPromptShown)
            showing = true
        }
    }

    if (showing && onHome) {
        PushPromptSheet(
            onNotifyMe = {
                showing = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    policy.onAnswered()
                    Telemetry.analytics.log(AnalyticsEvent.PushPromptResult(PromptResult.Granted))
                    Telemetry.acceptPush(context)
                }
            },
            onNotNow = {
                showing = false
                policy.onLater()
                Telemetry.analytics.log(AnalyticsEvent.PushPromptResult(PromptResult.NotNow))
            },
        )
    }
}

/** "Get notified when new samples land" — Notify me / Not now. */
@Composable
fun PushPromptSheet(onNotifyMe: () -> Unit, onNotNow: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    DemoModalBottomSheet(onDismissRequest = onNotNow, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = SceneViewTokens.Space.lg)
                .padding(bottom = SceneViewTokens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(SceneViewTokens.Space.xl),
            )
            Text(
                text = stringResource(R.string.push_prompt_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.push_prompt_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onNotifyMe,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = SceneViewTokens.Space.md),
            ) {
                Text(stringResource(R.string.push_prompt_notify_me), fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.push_prompt_not_now), textAlign = TextAlign.Center)
            }
        }
    }
}
