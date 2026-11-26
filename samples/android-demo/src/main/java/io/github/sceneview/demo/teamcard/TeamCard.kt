package io.github.sceneview.demo.teamcard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import io.github.sceneview.demo.R
import io.github.sceneview.demo.telemetry.AnalyticsEvent
import io.github.sceneview.demo.telemetry.SessionAsks
import io.github.sceneview.demo.telemetry.Telemetry
import io.github.sceneview.demo.telemetry.TeamCardAction
import io.github.sceneview.demo.telemetry.TeamCardSurface

/**
 * "Your team ships SceneView?": an in-app card, never a push, that invites teams whose app uses
 * SceneView to say so. Two places: a permanent row in About, and a Home card shown once
 * ([TeamCardPolicy]). It offers the "Used in" list and a direct line to the maintainer, and
 * nothing else: no price, no paid offer, no link to the support sheet. It never appears in
 * the support sheet.
 *
 * This object persists the policy's inputs and mirrors its verdict for Compose ([homeVisible]).
 */
object TeamCard {

    const val ADDRESS = "hello@sceneview.dev"
    const val SUBJECT = "Our app ships SceneView"

    /** The action, as published in the docs and the README (sceneview#5280). */
    const val MAILTO = "mailto:hello@sceneview.dev?subject=Our%20app%20ships%20SceneView"

    /** Whether the Home card is on screen. */
    var homeVisible: Boolean by mutableStateOf(false)
        private set

    private var policy: TeamCardPolicy? = null
    private var prefs: TeamCardPrefs? = null

    fun init(context: Context) {
        if (policy != null) return
        val store = TeamCardPrefs(context)
        prefs = store
        policy = TeamCardPolicy(store)
    }

    /** One cold start (an Activity created without saved state). */
    fun onSessionStart() {
        policy?.onLaunch()
    }

    /** A sample opened. */
    fun onSampleOpened() {
        policy?.onSampleOpened()
    }

    /** Home is on screen: shows the card if [TeamCardPolicy] says so, and logs it the first time. */
    fun onHome(consentAskedThisSession: Boolean) {
        val p = policy ?: return
        val decision = p.onHome(SessionAsks.otherThanTeamCard(consentAskedThisSession))
        if (decision == TeamCardPolicy.Decision.ShowFirst) {
            SessionAsks.onTeamCardShown()
            Telemetry.analytics.log(AnalyticsEvent.TeamCard(TeamCardAction.Shown, TeamCardSurface.Home))
        }
        homeVisible = decision != TeamCardPolicy.Decision.Hidden
    }

    /** "Dismiss" on the Home card. Definitive. */
    fun dismiss() {
        policy?.retire()
        homeVisible = false
        Telemetry.analytics.log(AnalyticsEvent.TeamCard(TeamCardAction.Dismissed, TeamCardSurface.Home))
    }

    /**
     * The mail action, from the Home card or the About row. From Home it also retires the card:
     * the user answered it, so it has no reason to stay.
     */
    fun mail(context: Context, surface: TeamCardSurface) {
        Telemetry.analytics.log(AnalyticsEvent.TeamCard(TeamCardAction.Mail, surface))
        if (surface == TeamCardSurface.Home) {
            policy?.retire()
            homeVisible = false
        }
        try {
            context.startActivity(mailIntent())
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.team_card_no_mail_app, ADDRESS), Toast.LENGTH_LONG)
                .show()
        }
    }

    /** `ACTION_SENDTO` on [MAILTO]; the extras repeat it for the mail apps that ignore the query. */
    fun mailIntent(): Intent = Intent(Intent.ACTION_SENDTO, Uri.parse(MAILTO)).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(ADDRESS))
        putExtra(Intent.EXTRA_SUBJECT, SUBJECT)
    }

    /** Debug QA (`--es team_card_qa reset`): forget launches, samples and the answer, as on a first install. */
    internal fun resetForQa() {
        prefs?.clear()
        val store = prefs ?: return
        policy = TeamCardPolicy(store)
        homeVisible = false
    }
}

/** [TeamCardStore] on SharedPreferences. */
internal class TeamCardPrefs(context: Context) : TeamCardStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var launches: Int
        get() = prefs.getInt(KEY_LAUNCHES, 0)
        set(value) = prefs.edit { putInt(KEY_LAUNCHES, value) }

    override var samplesOpened: Int
        get() = prefs.getInt(KEY_SAMPLES, 0)
        set(value) = prefs.edit { putInt(KEY_SAMPLES, value) }

    override var shown: Boolean
        get() = prefs.getBoolean(KEY_SHOWN, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOWN, value) }

    override var retired: Boolean
        get() = prefs.getBoolean(KEY_RETIRED, false)
        set(value) = prefs.edit { putBoolean(KEY_RETIRED, value) }

    fun clear() = prefs.edit(commit = true) { clear() }

    private companion object {
        const val FILE = "team_card"
        const val KEY_LAUNCHES = "launches"
        const val KEY_SAMPLES = "samples_opened"
        const val KEY_SHOWN = "home_shown"
        const val KEY_RETIRED = "home_retired"
    }
}
