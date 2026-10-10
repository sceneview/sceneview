package io.github.sceneview.demo

import android.app.Application
import io.github.sceneview.demo.telemetry.Telemetry

/**
 * Starts [Telemetry] with the process, where `FirebaseInitProvider` used to start Firebase.
 *
 * The init is the consent-gated one: in the consent zone nothing Firebase runs until the user
 * says yes, exactly as before. What this buys is coverage once collection is allowed: Crashlytics
 * installs its handler before any Activity, so a crash in `MainActivity.onCreate`, or in a process
 * started without UI (a WorkManager job downloading the HD pack, the messaging service), is still
 * reported.
 *
 * Release builds only. Debug builds keep the Activity as the first caller so the
 * `telemetry_consent` launch extra ([Telemetry.applyDebugConsent]) is applied before Firebase can
 * start: automation passes `denied` and must never send anything, not even on a first launch.
 */
class DemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.DEBUG) Telemetry.ensureInit(this)
    }

    /** In the background and asked for memory: the decoded preview pictures go first. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) DemoPreviews.trimMemory()
    }
}
