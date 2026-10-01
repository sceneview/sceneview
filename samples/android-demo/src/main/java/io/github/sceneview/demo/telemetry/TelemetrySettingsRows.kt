package io.github.sceneview.demo.telemetry

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * About → "Share usage statistics". Off stops Analytics and Crashlytics collection
 * and resets the analytics id ([DemoAnalytics.setCollectionEnabled]).
 */
@Composable
fun UsageStatisticsSettingsRow() {
    val context = LocalContext.current
    SettingsSwitchRow(
        icon = Icons.Outlined.Insights,
        title = stringResource(R.string.settings_usage_statistics_title),
        supporting = stringResource(R.string.settings_usage_statistics_supporting),
        checked = Telemetry.analyticsEnabled,
        onCheckedChange = { Telemetry.setAnalyticsEnabled(context, it) },
    )
}

/**
 * About → "Notifications". Shows ON only when the setting is on AND Android lets the app post,
 * so the switch never claims notifications that cannot arrive. Turning it on asks for the
 * Android 13+ permission and turns the setting on only if it is granted; once Android stops
 * showing its dialog (refused twice), the app's notification settings open instead — the only
 * place left where the user can say yes — and the user taps the switch again on the way back.
 * The FCM resync on return lives in MainActivity.onResume.
 */
@Composable
fun NotificationsSettingsRow() {
    val context = LocalContext.current
    var systemAllows by remember { mutableStateOf(Telemetry.systemAllowsNotifications(context)) }
    LifecycleResumeEffect(Unit) {
        systemAllows = Telemetry.systemAllowsNotifications(context)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        systemAllows = Telemetry.systemAllowsNotifications(context)
        if (granted) {
            Telemetry.setNotificationsEnabled(context, true)
        } else {
            val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().firstOrNull()
            val canAskAgain = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
            if (!canAskAgain) openAppNotificationSettings(context)
        }
    }
    SettingsSwitchRow(
        icon = Icons.Outlined.Notifications,
        title = stringResource(R.string.settings_notifications_title),
        supporting = stringResource(R.string.settings_notifications_supporting),
        checked = Telemetry.notificationsEnabled && systemAllows,
        onCheckedChange = { on ->
            when {
                !on -> Telemetry.setNotificationsEnabled(context, false)
                systemAllows -> Telemetry.setNotificationsEnabled(context, true)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                // Below 13 there is no runtime permission: notifications were blocked in system
                // settings, and only system settings can unblock them. The setting stays off.
                else -> openAppNotificationSettings(context)
            }
        },
    )
}

private fun openAppNotificationSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** An About row with a trailing switch; the whole row toggles (one touch target, one label). */
@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .padding(
                start = SceneViewTokens.Space.md,
                end = SceneViewTokens.Space.md,
                top = SceneViewTokens.Space.sm,
                bottom = SceneViewTokens.Space.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SceneViewTokens.About.rowIcon),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The row owns the click: a second, separately focusable switch would announce twice.
        // Off, M3 draws the track border and thumb in `outline`, which is 1.4:1 on the light
        // page (WCAG 1.4.11 wants 3:1 for a control's boundary): `onSurfaceVariant` keeps an
        // off switch readable as a switch in both themes.
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}
