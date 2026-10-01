package io.github.sceneview.demo.telemetry

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import io.github.sceneview.demo.BuildConfig

/**
 * [DemoAnalytics] on Firebase Analytics + Crashlytics. Built only once [Telemetry] has checked
 * that a `FirebaseApp` exists; every call is still guarded, because nothing here may crash a demo.
 */
internal class FirebaseDemoAnalytics(context: Context) : DemoAnalytics {

    private val analytics = FirebaseAnalytics.getInstance(context.applicationContext)

    init {
        // Consent mode: analytics only. Also the manifest defaults — re-applied here so a
        // stale value persisted by an older build can never re-enable an ad signal.
        runCatching {
            analytics.setConsent(
                mapOf(
                    FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE to FirebaseAnalytics.ConsentStatus.GRANTED,
                    FirebaseAnalytics.ConsentType.AD_STORAGE to FirebaseAnalytics.ConsentStatus.DENIED,
                    FirebaseAnalytics.ConsentType.AD_USER_DATA to FirebaseAnalytics.ConsentStatus.DENIED,
                    FirebaseAnalytics.ConsentType.AD_PERSONALIZATION to FirebaseAnalytics.ConsentStatus.DENIED,
                ),
            )
        }
    }

    override fun log(event: AnalyticsEvent) {
        val params = event.params()
        if (BuildConfig.DEBUG) Log.d(TAG, "${event.name} $params")
        val bundle = Bundle()
        params.forEach { (key, value) ->
            when (value) {
                is Long -> bundle.putLong(key, value)
                is Int -> bundle.putLong(key, value.toLong())
                is Double -> bundle.putDouble(key, value)
                is Float -> bundle.putDouble(key, value.toDouble())
                else -> bundle.putString(key, value.toString())
            }
        }
        analytics.logEvent(event.name, bundle)
    }

    override fun setUserProperty(property: UserProperty, value: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "user property ${property.key}=$value")
        analytics.setUserProperty(property.key, value)
    }

    override fun setCollectionEnabled(enabled: Boolean) {
        if (BuildConfig.DEBUG) Log.d(TAG, "collection enabled=$enabled")
        analytics.setAnalyticsCollectionEnabled(enabled)
        runCatching { FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled) }
        // Opting out also forgets the app-instance id: what was collected before cannot be
        // joined to anything collected after a later opt-in (the privacy policy says so).
        if (!enabled) analytics.resetAnalyticsData()
    }

    private companion object {
        const val TAG = "DemoAnalytics"
    }
}
