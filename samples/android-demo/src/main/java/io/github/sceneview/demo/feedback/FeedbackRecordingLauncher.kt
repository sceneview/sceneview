package io.github.sceneview.demo.feedback

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Controls returned by [rememberFeedbackRecordingLauncher].
 *
 * @property startRecording requests the mic (and, on Android 13+, the
 *   notification) permission, then the screen-capture permission, then hands
 *   the granted MediaProjection token to [FeedbackRecordingService]. Any denial
 *   publishes a [RecordingState.Failed].
 * @property openAppSettings opens this app's system settings page so a user who
 *   permanently denied the microphone permission can grant it by hand. The
 *   per-app-permission picker has no in-app dialog once a runtime permission is
 *   "Don't ask again"-denied, so this is the only recovery path (#1933 review).
 */
class FeedbackRecordingLauncher(
    val startRecording: () -> Unit,
    val openAppSettings: () -> Unit,
)

/** Opens the system app-details settings page for [context]'s package. */
private fun openAppDetailsSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * True when [Manifest.permission.RECORD_AUDIO] is permanently denied — i.e. it
 * is not granted and the system will not show a request dialog again (the user
 * chose "Don't ask again", or it is policy-restricted). The system permission
 * request then returns instantly with no UI, so the flow must route the user to
 * app settings instead of looping a no-op "Retry".
 */
fun isMicPermanentlyDenied(activity: Activity): Boolean {
    val granted = ContextCompat.checkSelfPermission(
        activity,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    if (granted) return false
    // After at least one denial, `shouldShowRequestPermissionRationale` is
    // false only when the user picked "Don't ask again". On a never-asked
    // permission it is also false — but that case is harmless: the first
    // request still shows the dialog. The flow only consults this after a
    // denial has already been observed.
    return !ActivityCompat.shouldShowRequestPermissionRationale(
        activity,
        Manifest.permission.RECORD_AUDIO,
    )
}

@Composable
fun rememberFeedbackRecordingLauncher(): FeedbackRecordingLauncher {
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

    return remember(permissionLauncher, context) {
        FeedbackRecordingLauncher(
            startRecording = {
                // Short-circuit on a build where screen recording is unavailable
                // (manifest omits FOREGROUND_SERVICE_MEDIA_PROJECTION on
                // Android 14+ — see #2120). Don't ask for mic / notification
                // permissions, don't open the screen-capture consent dialog,
                // don't start the foreground service — any of those would
                // strand the user in front of a system prompt that ends in a
                // ForegroundServiceDidNotStartInTimeException crash.
                // Publishing a failure lets the FeedbackFlow surface the
                // "skip recording" path so the user can still submit a
                // text-only note.
                if (!FeedbackRecordingService.isRecordingAvailable(context)) {
                    FeedbackRecorder.setFailed(
                        FeedbackRecorder.FAILURE_RECORDING_UNAVAILABLE,
                    )
                    return@FeedbackRecordingLauncher
                }
                val perms = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                permissionLauncher.launch(perms.toTypedArray())
            },
            openAppSettings = { openAppDetailsSettings(context) },
        )
    }
}
