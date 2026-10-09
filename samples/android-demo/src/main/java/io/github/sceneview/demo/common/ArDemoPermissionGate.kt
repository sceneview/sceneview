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
import androidx.compose.runtime.key
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

/** Camera permission as observed by the demo app. */
internal enum class ArCameraPermissionState {
    Granted,
    Denied,
}

/** Whether the current AR demo has mounted a session or was stopped by camera permission. */
internal enum class ArDemoSessionState {
    NotStarted,
    Running,
    BlockedByPermission,
}

/** The one screen-level outcome of the camera-permission and AR-session state. */
internal enum class ArDemoPermissionUiState {
    ShowDemo,
    RequestPermission,
    RetryPermission,
    OpenSettings,
    RetrySession,
}

/**
 * Chooses the only UI the AR demo route may show for its current permission/session state.
 */
internal fun arDemoPermissionUiState(
    permission: ArCameraPermissionState,
    shouldShowRationale: Boolean,
    session: ArDemoSessionState,
): ArDemoPermissionUiState = when {
    permission == ArCameraPermissionState.Granted &&
        session == ArDemoSessionState.BlockedByPermission -> ArDemoPermissionUiState.RetrySession
    permission == ArCameraPermissionState.Granted -> ArDemoPermissionUiState.ShowDemo
    session == ArDemoSessionState.NotStarted -> ArDemoPermissionUiState.RequestPermission
    shouldShowRationale -> ArDemoPermissionUiState.RetryPermission
    else -> ArDemoPermissionUiState.OpenSettings
}

/**
 * Owns camera permission for every registered AR demo before the demo can mount ARCore.
 *
 * A grant read after returning from system settings remounts [content] under a fresh key, so
 * the user never has to leave and reopen the demo to create a new AR session.
 */
@Composable
internal fun ArDemoPermissionGate(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    fun cameraPermission() = if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        ArCameraPermissionState.Granted
    } else {
        ArCameraPermissionState.Denied
    }

    val initialPermission = remember { cameraPermission() }
    var permission by remember { mutableStateOf(initialPermission) }
    var session by rememberSaveable {
        mutableStateOf(
            if (initialPermission == ArCameraPermissionState.Granted) {
                ArDemoSessionState.Running
            } else {
                ArDemoSessionState.NotStarted
            },
        )
    }
    var permissionEpoch by remember { mutableIntStateOf(0) }
    var sessionGeneration by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permission = if (granted) {
            ArCameraPermissionState.Granted
        } else {
            ArCameraPermissionState.Denied
        }
        session = when {
            granted && session == ArDemoSessionState.NotStarted -> ArDemoSessionState.Running
            granted -> session
            else -> ArDemoSessionState.BlockedByPermission
        }
        permissionEpoch++
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val resumedPermission = cameraPermission()
                if (resumedPermission == ArCameraPermissionState.Denied &&
                    permission == ArCameraPermissionState.Granted
                ) {
                    session = ArDemoSessionState.BlockedByPermission
                }
                permission = resumedPermission
                permissionEpoch++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shouldShowRationale = remember(permissionEpoch, permission, activity) {
        permission == ArCameraPermissionState.Denied && activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.CAMERA,
            )
    }
    val uiState = arDemoPermissionUiState(permission, shouldShowRationale, session)

    when (uiState) {
        ArDemoPermissionUiState.ShowDemo -> key(sessionGeneration) { content() }
        ArDemoPermissionUiState.RetrySession -> ArPermissionScreen(
            title = title,
            onBack = onBack,
            cardTitle = stringResource(R.string.ar_permission_granted_title),
            detail = stringResource(R.string.ar_permission_granted_subtitle),
            action = stringResource(R.string.ar_permission_try_again),
            onAction = {
                sessionGeneration++
                session = ArDemoSessionState.Running
            },
        )
        ArDemoPermissionUiState.RequestPermission -> {
            ArPermissionScreen(title = title, onBack = onBack)
            LaunchedEffect(Unit) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        ArDemoPermissionUiState.RetryPermission -> ArPermissionScreen(
            title = title,
            onBack = onBack,
            action = stringResource(R.string.ar_permission_try_again),
            onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        )
        ArDemoPermissionUiState.OpenSettings -> ArPermissionScreen(
            title = title,
            onBack = onBack,
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
    cardTitle: String = stringResource(R.string.ar_permission_required_title),
    detail: String = stringResource(R.string.ar_permission_required_subtitle),
    action: String? = null,
    onAction: () -> Unit = {},
) {
    DemoScaffold(title = title, onBack = onBack) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SceneViewTokens.Stage.background)
                .padding(SceneViewTokens.Space.lg),
            contentAlignment = Alignment.Center,
        ) {
            ArPermissionCard(
                title = cardTitle,
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = SceneViewTokens.ArOverlay.accentProgress,
                    contentColor = SceneViewTokens.ArOverlay.onAccentProgress,
                ),
            ) {
                Text(action)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
