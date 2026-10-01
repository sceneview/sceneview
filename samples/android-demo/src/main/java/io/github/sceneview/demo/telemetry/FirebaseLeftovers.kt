package io.github.sceneview.demo.telemetry

import android.content.Context
import android.util.Log

/**
 * What builds from before the consent left in Firebase's own storage, and why it must go before
 * FirebaseApp starts.
 *
 * Those builds called `setAnalyticsCollectionEnabled(true)`, `setCrashlyticsCollectionEnabled(true)`
 * and `setConsent(analytics_storage = GRANTED)`. The SDKs persist those calls, and a value set
 * through the API beats the manifest default. Left in place, the first start after the upgrade
 * (the push exception starts Firebase at launch) would collect and send before the consent sheet
 * is answered: Analytics would queue `app_update` and `session_start`, Crashlytics would upload
 * the crash reports cached by the old build.
 *
 * Clearing them hands the decision back to the manifest (collection off, analytics_storage
 * denied) until [Telemetry] applies the consent. Runs on every consent-version migration, before
 * any [com.google.firebase.FirebaseApp.initializeApp]: a new consent version also takes back the
 * old answer. Key names checked against Analytics 23.2 and Crashlytics 20.1.
 */
internal object FirebaseLeftovers {

    private const val TAG = "DemoTelemetry"

    private const val MEASUREMENT_PREFS = "com.google.android.gms.measurement.prefs"
    private val MEASUREMENT_KEYS = listOf(
        "measurement_enabled",
        "measurement_enabled_from_api",
        "consent_settings",
        "consent_source",
        "dma_consent_settings",
    )

    private const val CRASHLYTICS_PREFS = "com.google.firebase.crashlytics"
    private const val CRASHLYTICS_KEY = "firebase_crashlytics_collection_enabled"

    fun clear(context: Context) {
        runCatching {
            val app = context.applicationContext
            val measurement = app.getSharedPreferences(MEASUREMENT_PREFS, Context.MODE_PRIVATE)
            val crashlytics = app.getSharedPreferences(CRASHLYTICS_PREFS, Context.MODE_PRIVATE)
            val crashlyticsFound = if (crashlytics.contains(CRASHLYTICS_KEY)) 1 else 0
            val found = MEASUREMENT_KEYS.count(measurement::contains) + crashlyticsFound
            if (found == 0) return
            // commit(), not apply(): FirebaseApp may start right after this returns.
            measurement.edit().apply { MEASUREMENT_KEYS.forEach(::remove) }.commit()
            crashlytics.edit().remove(CRASHLYTICS_KEY).commit()
            Log.i(TAG, "Cleared $found collection setting(s) persisted by an older build")
        }.onFailure { Log.w(TAG, "Could not clear Firebase settings from an older build", it) }
    }
}
