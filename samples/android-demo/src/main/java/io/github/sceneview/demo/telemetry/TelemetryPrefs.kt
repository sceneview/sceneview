package io.github.sceneview.demo.telemetry

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The consent behind About -> Privacy & notifications -> "Share usage statistics", the
 * Notifications switch.
 *
 * Usage statistics and crash reports follow the consent ([TelemetryConsent]): asked first in the
 * EEA, the UK and Switzerland, on by default elsewhere, and the switch gives or withdraws it.
 * Notifications default to **off**: push is opt-in, turned on only by the switch. No FCM token exists before that.
 */
class TelemetryPrefs(context: Context) : PushDisableStore, ConsentStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var consentState: ConsentState
        get() = ConsentState.fromKey(prefs.getString(KEY_CONSENT_STATE, null))
        set(value) = prefs.edit { putString(KEY_CONSENT_STATE, value.key) }

    override var consentAt: Long
        get() = prefs.getLong(KEY_CONSENT_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_CONSENT_AT, value) }

    override var consentVersion: Int
        get() = prefs.getInt(KEY_CONSENT_VERSION, 0)
        set(value) = prefs.edit { putInt(KEY_CONSENT_VERSION, value) }

    override var legacyAnalyticsEnabled: Boolean?
        get() = if (prefs.contains(KEY_LEGACY_ANALYTICS)) prefs.getBoolean(KEY_LEGACY_ANALYTICS, true) else null
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LEGACY_ANALYTICS) else putBoolean(KEY_LEGACY_ANALYTICS, value)
        }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATIONS, value) }

    /** A push opt-out whose unsubscribe or token deletion has not succeeded yet (offline). */
    override var pushDisablePending: Boolean
        get() = prefs.getBoolean(KEY_PUSH_DISABLE_PENDING, false)
        set(value) = prefs.edit { putBoolean(KEY_PUSH_DISABLE_PENDING, value) }

    private companion object {
        const val FILE = "sceneview_demo_telemetry"
        const val KEY_CONSENT_STATE = "consent_state"
        const val KEY_CONSENT_AT = "consent_at"
        const val KEY_CONSENT_VERSION = "consent_version"

        /** The pre-consent About switch: read once by [TelemetryConsent.migrate], then removed. */
        const val KEY_LEGACY_ANALYTICS = "analytics_enabled"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_PUSH_DISABLE_PENDING = "push_disable_pending"
    }
}
