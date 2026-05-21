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
 */
fun captureFeedbackContext(context: Context): Map<String, String> {
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
    }
}
