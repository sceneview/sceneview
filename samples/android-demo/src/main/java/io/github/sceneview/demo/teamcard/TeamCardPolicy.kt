package io.github.sceneview.demo.teamcard

/** What [TeamCardPolicy] remembers between launches. [TeamCardPrefs] backs it on device. */
interface TeamCardStore {
    /** Cold starts of the app, this one included. */
    var launches: Int

    /** Samples opened, ever. */
    var samplesOpened: Int

    /** Set the session the Home card first appeared: it never appears in another one. */
    var shown: Boolean

    /** "Dismiss" or the mail action on the Home card: it never comes back. */
    var retired: Boolean
}

/**
 * When the "Your team ships SceneView?" card shows on Home. Pure logic, no Android.
 *
 * - Once, ever: in the first session that reaches the [MIN_LAUNCHES]th launch **and** has seen
 *   [MIN_SAMPLES_OPENED] samples opened, whichever of the two comes later. It stays on Home for
 *   the rest of that session, and never shows in another one.
 * - "Dismiss" (or the mail action) retires it at once, for good.
 * - Never in a session where something else already asked ([io.github.sceneview.demo.telemetry.SessionAsks]):
 *   the push pre-prompt, the support sheet or the usage-statistics consent. The card then waits
 *   for a later, quiet session.
 *
 * The About row is permanent and does not go through this policy.
 */
class TeamCardPolicy(private val store: TeamCardStore) {

    /** What Home does with the card right now. */
    enum class Decision {
        /** Not on Home. */
        Hidden,

        /** Appears now, for the first time: log `team_card` / `shown` once. */
        ShowFirst,

        /** Already shown earlier in this session: still on Home. */
        Showing,
    }

    /** Set when the card appeared in this process. */
    var shownThisSession: Boolean = false
        private set

    /** One cold start. */
    fun onLaunch() {
        store.launches = store.launches + 1
    }

    /** One sample opened (a rotation of the same sample does not count again). */
    fun onSampleOpened() {
        store.samplesOpened = store.samplesOpened + 1
    }

    /**
     * Home is on screen. [otherAskThisSession]: the pre-prompt, the support sheet or the consent
     * already asked in this process.
     */
    fun onHome(otherAskThisSession: Boolean): Decision {
        if (store.retired) return Decision.Hidden
        if (shownThisSession) return Decision.Showing
        if (store.shown || otherAskThisSession) return Decision.Hidden
        if (store.launches < MIN_LAUNCHES || store.samplesOpened < MIN_SAMPLES_OPENED) return Decision.Hidden
        store.shown = true
        shownThisSession = true
        return Decision.ShowFirst
    }

    /** "Dismiss", or the mail action: the card leaves Home and never comes back. */
    fun retire() {
        store.retired = true
    }

    companion object {
        const val MIN_LAUNCHES = 5
        const val MIN_SAMPLES_OPENED = 3
    }
}
