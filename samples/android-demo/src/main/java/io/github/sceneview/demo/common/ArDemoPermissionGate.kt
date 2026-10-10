package io.github.sceneview.demo.common

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.overMediaEdge

/**
 * Holds a screen that needs the camera back until the camera is granted.
 *
 * Wrap the part of a demo that opens the camera, not the whole demo: a chooser, a landing
 * page or a recorded replay must stay reachable for someone who refused. Most AR demos are
 * camera from the first frame and get the gate from `DemoRouter`
 * (see `DemoEntry.opensCameraOnEntry`).
 *
 * [content] is only composed while the camera is granted, so a grant read on the way back
 * from system settings mounts the demo — and a fresh AR session — on its own.
 *
 * @param enabled `false` lets [content] through untouched — for a debug QA state that
 *   stages the screen without opening the camera.
 */
@Composable
internal fun ArDemoPermissionGate(
    title: String,
    onBack: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    ArCameraPermissionGate(
        permission = rememberArCameraPermission(),
        blocked = { detail, action, onAction ->
            ArPermissionScreen(title, onBack, detail, action, onAction)
        },
        content = content,
    )
}

/**
 * [ArDemoPermissionGate] for a camera view that is one stage of a screen which already has
 * its own scaffold: the card takes the stage, the screen keeps its chrome — and with it the
 * ways to the stages that need no camera.
 *
 * [permission] is hoisted so the screen can hold back what only makes sense with a camera
 * (a "camera did not start" timeout, say).
 */
@Composable
internal fun ArCameraStagePermissionGate(
    permission: ArCameraPermission,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ArCameraPermissionGate(
        permission = permission,
        blocked = { detail, action, onAction ->
            ArPermissionStage(detail, action, onAction, modifier)
        },
        content = content,
    )
}

@Composable
private fun ArCameraPermissionGate(
    permission: ArCameraPermission,
    blocked: @Composable (detail: String, action: String, onAction: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    var promptLaunched by rememberSaveable { mutableStateOf(false) }

    when (arDemoPermissionUiState(permission.granted, permission.blocked)) {
        ArDemoPermissionUiState.ShowDemo -> content()
        ArDemoPermissionUiState.AskPermission -> {
            // The same card stays up behind the system dialog and after it: a dialog that
            // never comes back (process death, a dismissal) still leaves a button.
            blocked(
                when (arCameraAskReason(permission.shouldShowRationale)) {
                    ArCameraAskReason.NotAnswered ->
                        stringResource(R.string.ar_permission_allow_subtitle)
                    ArCameraAskReason.Refused ->
                        stringResource(R.string.ar_permission_refused_subtitle)
                },
                stringResource(R.string.ar_permission_allow),
                permission.request,
            )
            val autoPrompt = shouldAutoPromptForCamera(
                granted = permission.granted,
                blocked = permission.blocked,
                shouldShowRationale = permission.shouldShowRationale,
                promptLaunched = promptLaunched,
            )
            LaunchedEffect(autoPrompt) {
                if (autoPrompt) {
                    promptLaunched = true
                    permission.request()
                }
            }
        }
        ArDemoPermissionUiState.OpenSettings -> blocked(
            stringResource(R.string.ar_permission_blocked_subtitle),
            stringResource(R.string.ar_permission_open_settings),
            permission.openSettings,
        )
    }
}

@Composable
private fun ArPermissionScreen(
    title: String,
    onBack: () -> Unit,
    detail: String,
    action: String,
    onAction: () -> Unit,
) {
    // No dock: its "Settings" pill opens the demo's own sheet, which has nothing in it here
    // and reads as the way to the *system* settings the card is talking about.
    // No scene either: the viewport must not announce "Scene ready" over a permission card.
    DemoScaffold(title = title, onBack = onBack, dockHidden = true, hasScene = false) {
        ArPermissionStage(detail, action, onAction)
    }
}

/** The permission card, centred on the dark AR stage it stands in for. */
@Composable
private fun ArPermissionStage(
    detail: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
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
        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
    ) {
        Text(
            text = title,
            style = SceneViewTokens.Type.card,
            color = SceneViewTokens.ArOverlay.onScrim,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = SceneViewTokens.Type.body,
                color = SceneViewTokens.ArOverlay.onScrimMuted,
            )
        }
        if (action != null) {
            Button(
                onClick = onAction,
                modifier = Modifier
                    .fillMaxWidth()
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
