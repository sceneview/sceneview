package io.github.sceneview.demo.common

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The camera permission as the AR surfaces of the demo app read it: one holder behind
 * [ArDemoPermissionGate] and the AR tab's launcher, so both agree on when to ask and when
 * only system settings can help.
 */
@Immutable
internal class ArCameraPermission(
    /** The camera is granted right now — re-read every time the app resumes. */
    val granted: Boolean,
    /** Refused once: Android will show its dialog again, and wants an explanation first. */
    val shouldShowRationale: Boolean,
    /** Android no longer shows the dialog — see [cameraPromptBlocked]. */
    val blocked: Boolean,
    /** Opens the system dialog (or learns that it no longer opens). */
    val request: () -> Unit,
    /** Opens this app's page in system settings. */
    val openSettings: () -> Unit,
)

@Composable
internal fun rememberArCameraPermission(): ArCameraPermission {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    fun grantedNow() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    fun rationaleNow() = activity != null &&
        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)

    var granted by remember { mutableStateOf(grantedNow()) }
    var rationale by remember { mutableStateOf(!granted && rationaleNow()) }
    var blocked by rememberSaveable { mutableStateOf(false) }
    // What the request in flight started from. Saved: the dialog outlives a rotation, and
    // its answer is delivered to the recreated activity.
    var promptAt by rememberSaveable { mutableLongStateOf(0L) }
    var promptRationaleBefore by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        val rationaleAfter = !result && rationaleNow()
        blocked = cameraPromptBlocked(
            granted = result,
            rationaleBefore = promptRationaleBefore,
            rationaleAfter = rationaleAfter,
            elapsedMs = SystemClock.elapsedRealtime() - promptAt,
        )
        granted = result
        rationale = rationaleAfter
    }

    // Back from the app's settings page: pick up what the user switched there.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = grantedNow()
                rationale = !granted && rationaleNow()
                if (granted || rationale) blocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return ArCameraPermission(
        granted = granted,
        shouldShowRationale = rationale,
        blocked = blocked,
        request = {
            promptAt = SystemClock.elapsedRealtime()
            promptRationaleBefore = rationaleNow()
            launcher.launch(Manifest.permission.CAMERA)
        },
        openSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
