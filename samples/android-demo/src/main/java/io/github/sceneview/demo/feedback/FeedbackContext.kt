package io.github.sceneview.demo.feedback

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.github.sceneview.demo.BuildConfig
import java.util.Locale

/**
 * Snapshot of device / app context attached to a feedback submission. This is
 * what the maintainer sees in the GitHub issue's context table — it tells them
 * exactly which app build, OS and device the feedback came from.
 *
 * @param demoId id of the demo the user was on when they sent the feedback, or
 *   `null` if they were on a tab screen. Captured from the `NavController`'s
 *   current destination (#1934) so the maintainer knows exactly which demo a
 *   bug is in. Defaults to [FeedbackRecorder.currentDemoId], which is kept in
 *   sync with live navigation even while the feedback dialog is dismissed for
 *   recording.
 */
fun captureFeedbackContext(
    context: Context,
    demoId: String? = FeedbackRecorder.currentDemoId,
): Map<String, String> {
    val freeRamMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        ?.let { am ->
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem / (1024L * 1024L)
        }
    return buildMap {
        put("appVersion", BuildConfig.VERSION_NAME)
        put("appVersionCode", BuildConfig.VERSION_CODE.toString())
        put("androidVersion", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
        put("sdkInt", Build.VERSION.SDK_INT.toString())
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        put("locale", Locale.getDefault().toLanguageTag())
        if (freeRamMb != null) put("freeRamMb", freeRamMb.toString())
        // The exact demo / screen the feedback is about. `demo` is the demo id
        // (omitted when the user is on a tab screen); `route` is the Compose
        // navigation route, always present, so the maintainer can tell a
        // tab-screen report apart from an in-demo one.
        if (!demoId.isNullOrBlank()) put("demo", demoId)
        put("route", if (!demoId.isNullOrBlank()) "demo/$demoId" else "list")
    }
}
