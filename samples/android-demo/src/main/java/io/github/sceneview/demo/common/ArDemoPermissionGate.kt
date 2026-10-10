package io.github.sceneview.demo.common

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.overMediaEdge

/**
 * Owns camera permission for every registered AR demo before the demo can mount ARCore.
 *
 * [content] is only composed while the camera is granted, so a grant read on the way back
 * from system settings mounts the demo — and a fresh AR session — on its own.
 */
@Composable
internal fun ArDemoPermissionGate(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    fun cameraGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(cameraGranted()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    // Bumped on every dialog answer and every resume: a second "Don't allow" changes no
    // grant, so without it the Try again / Open settings choice would never be re-read.
    var permissionEpoch by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        requested = true
        permissionEpoch++
    }

    // Back from the app's settings page: pick up what the user switched on there.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = cameraGranted()
                permissionEpoch++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shouldShowRationale = remember(permissionEpoch, granted, activity) {
        !granted && activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.CAMERA,
            )
    }

    when (arDemoPermissionUiState(granted, requested, shouldShowRationale)) {
        ArDemoPermissionUiState.ShowDemo -> content()
        ArDemoPermissionUiState.RequestPermission -> {
            ArPermissionScreen(
                title = title,
                onBack = onBack,
                detail = stringResource(R.string.ar_permission_allow_subtitle),
            )
            LaunchedEffect(Unit) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        ArDemoPermissionUiState.RetryPermission -> ArPermissionScreen(
            title = title,
            onBack = onBack,
            detail = stringResource(R.string.ar_permission_allow_subtitle),
            action = stringResource(R.string.ar_permission_try_again),
            onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        )
        ArDemoPermissionUiState.OpenSettings -> ArPermissionScreen(
            title = title,
            onBack = onBack,
            detail = stringResource(R.string.ar_permission_blocked_subtitle),
            action = stringResource(R.string.ar_permission_open_settings),
            onAction = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
    }
}

@Composable
private fun ArPermissionScreen(
    title: String,
    onBack: () -> Unit,
    detail: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    // No dock: its "Settings" pill opens the demo's own sheet, which has nothing in it here
    // and reads as the way to the *system* settings the card is talking about.
    DemoScaffold(title = title, onBack = onBack, dockHidden = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SceneViewTokens.Stage.background)
                .padding(SceneViewTokens.Space.lg),
            contentAlignment = Alignment.Center,
        ) {
            ArPermissionCard(
                title = stringResource(R.string.ar_permission_required_title),
                detail = detail,
                action = action,
                onAction = onAction,
            )
        }
    }
}

/** Shared AR-overlay card for a permission that blocks an AR demo. */
@Composable
internal fun ArPermissionCard(
    title: String,
    detail: String?,
    action: String?,
    onAction: () -> Unit,
) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Column(
        modifier = Modifier
            .widthIn(max = SceneViewTokens.ArOverlay.maxWidth)
            .fillMaxWidth()
            .background(
                color = if (dark) {
                    SceneViewTokens.ArOverlay.scrimDark
                } else {
                    SceneViewTokens.ArOverlay.scrimLight
                },
                shape = shape,
            )
            .overMediaEdge(shape)
            .padding(SceneViewTokens.Space.md),
        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = SceneViewTokens.ArOverlay.onScrim,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = SceneViewTokens.ArOverlay.onScrimMuted,
            )
        }
        if (action != null) {
            Button(
                onClick = onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = SceneViewTokens.Space.sm)
                    .heightIn(min = SceneViewTokens.Layout.touchTarget),
                shape = RoundedCornerShape(SceneViewTokens.Radius.md),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SceneViewTokens.ArOverlay.accentProgress,
                    contentColor = SceneViewTokens.ArOverlay.onAccentProgress,
                ),
            ) {
                Text(action, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
