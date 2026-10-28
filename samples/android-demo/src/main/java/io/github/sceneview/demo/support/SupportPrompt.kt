package io.github.sceneview.demo.support

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * When the "Support SceneView" sheet may ask on its own — the moderated interstitial.
 *
 * The app is free and nothing in it is gated, so the ask has to earn its place:
 *
 * - **never on the first launches** — only from the [MIN_SESSIONS]th cold start;
 * - **only right after a moment of value** in the current session: a sample used for
 *   [VALUE_MOMENT_MIN_SECONDS] or more, or a first AR placement — and only once the user is
 *   back on the tab host, never on top of a sample or a live AR session (the caller passes
 *   `canShow = false` there);
 * - **at most once every [MIN_INTERVAL_MILLIS]** ("Not now" waits for the next window);
 * - **never again** after "Don't ask again";
 * - **quiet for [QUIET_AFTER_SUPPORT_MILLIS]** after a contribution.
 *
 * The rule itself is the pure [shouldOffer]; this object only persists its inputs.
 */
object SupportPrompt {

    /** `true` while the interstitial is on screen. */
    var isShown: Boolean by mutableStateOf(false)
        private set

    /**
     * A moment of value happened in this process — a sample used for a while, a first AR
     * placement. In memory on purpose: the ask must follow the moment, not a moment from a
     * previous session.
     */
    var valueMoment: Boolean by mutableStateOf(false)
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /** One cold start of the app (an Activity created without saved state). */
    fun onSessionStart() {
        val p = prefs ?: return
        p.edit { putInt(KEY_SESSIONS, p.getInt(KEY_SESSIONS, 0) + 1) }
    }

    /** A sample closed after [seconds] of use. */
    fun onSampleUsed(seconds: Long) {
        if (seconds >= VALUE_MOMENT_MIN_SECONDS) valueMoment = true
    }

    /** The user placed something in AR for the first time in a session. */
    fun onArPlacement() {
        valueMoment = true
    }

    /**
     * Shows the interstitial if every condition holds; [canShow] is the caller's half (tab host
     * on screen, no AR session, no other sheet up). Returns whether it did.
     */
    fun maybeShow(canShow: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs ?: return false
        if (isShown) return true
        val offer = canShow && shouldOffer(
            sessions = p.getInt(KEY_SESSIONS, 0),
            valueMoment = valueMoment,
            optedOut = p.getBoolean(KEY_OPTED_OUT, false),
            lastShownAt = p.getLong(KEY_LAST_SHOWN, 0L),
            lastSupportedAt = p.getLong(KEY_LAST_SUPPORTED, 0L),
            now = now,
        )
        if (!offer) return false
        // Counted when shown, not when answered: a sheet swiped away is a "Not now" too.
        p.edit { putLong(KEY_LAST_SHOWN, now) }
        valueMoment = false
        isShown = true
        return true
    }

    /** "Not now", a swipe, the scrim, Back: the next window is [MIN_INTERVAL_MILLIS] away. */
    fun dismiss() {
        isShown = false
    }

    /** "Don't ask again": the interstitial never comes back. About keeps the entry. */
    fun optOut() {
        prefs?.edit { putBoolean(KEY_OPTED_OUT, true) }
        isShown = false
    }

    /** A contribution went through: no ask for [QUIET_AFTER_SUPPORT_MILLIS]. */
    fun onSupported(now: Long = System.currentTimeMillis()) {
        prefs?.edit { putLong(KEY_LAST_SUPPORTED, now) }
    }

    /** Debug QA (`--es support_qa prompt`): show it now, whatever the rule says. */
    internal fun forceShow() {
        isShown = true
    }

    internal fun shouldOffer(
        sessions: Int,
        valueMoment: Boolean,
        optedOut: Boolean,
        lastShownAt: Long,
        lastSupportedAt: Long,
        now: Long,
    ): Boolean {
        if (optedOut || !valueMoment || sessions < MIN_SESSIONS) return false
        if (lastShownAt > 0L && now - lastShownAt < MIN_INTERVAL_MILLIS) return false
        if (lastSupportedAt > 0L && now - lastSupportedAt < QUIET_AFTER_SUPPORT_MILLIS) return false
        return true
    }

    const val MIN_SESSIONS = 3
    const val VALUE_MOMENT_MIN_SECONDS = 30L
    const val MIN_INTERVAL_MILLIS = 14L * 24 * 60 * 60 * 1000
    const val QUIET_AFTER_SUPPORT_MILLIS = 180L * 24 * 60 * 60 * 1000

    private const val PREFS = "support_prompt"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_OPTED_OUT = "opted_out"
    private const val KEY_LAST_SHOWN = "last_shown_at"
    private const val KEY_LAST_SUPPORTED = "last_supported_at"
}
