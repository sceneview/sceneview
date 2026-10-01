package io.github.sceneview.demo.telemetry

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The two About -> Privacy & notifications switches (usage statistics, notifications) and the pre-prompt's
 * memory. Usage statistics default to on: they are anonymous and ad-free (consent mode denies
 * every ad signal) and the switch turns them off. Notifications default to **off**: push is
 * opt-in, turned on by the pre-prompt (the system permission on Android 13+, "Notify me"
 * below) or by the switch. No FCM token exists before that.
 */
class TelemetryPrefs(context: Context) : PushPromptStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var analyticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANALYTICS, true)
        set(value) = prefs.edit { putBoolean(KEY_ANALYTICS, value) }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATIONS, value) }

    override var homeReturns: Int
        get() = prefs.getInt(KEY_HOME_RETURNS, 0)
        set(value) = prefs.edit { putInt(KEY_HOME_RETURNS, value) }

    override var timesShown: Int
        get() = prefs.getInt(KEY_TIMES_SHOWN, 0)
        set(value) = prefs.edit { putInt(KEY_TIMES_SHOWN, value) }

    override var snoozedUntil: Long
        get() = prefs.getLong(KEY_SNOOZED_UNTIL, 0L)
        set(value) = prefs.edit { putLong(KEY_SNOOZED_UNTIL, value) }

    override var settled: Boolean
        get() = prefs.getBoolean(KEY_SETTLED, false)
        set(value) = prefs.edit { putBoolean(KEY_SETTLED, value) }

    private companion object {
        const val FILE = "sceneview_demo_telemetry"
        const val KEY_ANALYTICS = "analytics_enabled"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_HOME_RETURNS = "push_prompt_home_returns"
        const val KEY_TIMES_SHOWN = "push_prompt_times_shown"
        const val KEY_SNOOZED_UNTIL = "push_prompt_snoozed_until"
        const val KEY_SETTLED = "push_prompt_settled"
    }
}
