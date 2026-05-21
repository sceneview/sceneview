package io.github.sceneview.demo.feedback

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Provides a `startRecording` lambda that requests the microphone (and, on
 * Android 13+, the notification) permission, then the screen-capture
 * permission, and finally hands the granted MediaProjection token to
 * [FeedbackRecordingService]. Any denial publishes a [RecordingState.Failed].
 */
@Composable
fun rememberFeedbackRecordingLauncher(): () -> Unit {
    val context = LocalContext.current

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            FeedbackRecordingService.start(context, result.resultCode, data)
        } else {
            FeedbackRecorder.setFailed("screen-capture permission was not granted")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            val mpm = context.getSystemService(MediaProjectionManager::class.java)
            if (mpm != null) {
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            } else {
                FeedbackRecorder.setFailed("screen capture is unavailable on this device")
            }
        } else {
            FeedbackRecorder.setFailed("microphone permission was not granted")
        }
    }

    return remember(permissionLauncher) {
        {
            val perms = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            permissionLauncher.launch(perms.toTypedArray())
        }
    }
}
