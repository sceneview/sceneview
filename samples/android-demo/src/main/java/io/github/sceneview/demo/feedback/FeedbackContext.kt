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
 *   recording. Emitted under the `demoId` key — the one the feedback worker
 *   recognises and renders as the named "Demo" row.
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
        // The exact demo the feedback is about, under the `demoId` key the
        // worker recognises (rendered as the named "Demo" row in the issue's
        // Context table — see feedback-worker/src/issue.ts CONTEXT_LABELS).
        // Omitted when the user is on a tab screen, so the worker simply does
        // not render the row. A `route` key was dropped: the worker has no
        // label for it, so it would surface as a raw, unlabelled table row.
        if (!demoId.isNullOrBlank()) put("demoId", demoId)
    }
}
