package io.github.sceneview.demo.feedback

import android.content.Context

/**
 * One-time "feedback onboarding seen" flag, backed by [android.content.SharedPreferences].
 * A single boolean does not warrant a DataStore dependency.
 */
object FeedbackPrefs {
    private const val PREFS = "sceneview_feedback"
    private const val KEY_ONBOARDED = "onboarding_seen"

    fun hasSeenOnboarding(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDED, false)

    fun markOnboardingSeen(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    // applicationContext — prefs access must never pin an Activity context.
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
