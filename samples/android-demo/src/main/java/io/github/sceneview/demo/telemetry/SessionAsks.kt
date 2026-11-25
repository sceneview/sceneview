package io.github.sceneview.demo.telemetry

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One ask per session. The demo has four things that ask something of the user on their own:
 * the usage-statistics consent, the notification pre-prompt, the support sheet and the
 * "Your team ships SceneView?" card ([io.github.sceneview.demo.teamcard.TeamCard]). Whichever
 * appears first in a process keeps the others away until the next cold start.
 *
 * In memory on purpose: a session is a process. Compose-observable, so a host that waits for a
 * settle delay (the support prompt) is cancelled when another ask lands during the wait.
 */
object SessionAsks {

    /** The notification pre-prompt showed in this process. */
    var pushPromptShown: Boolean by mutableStateOf(false)
        private set

    /** The support sheet showed in this process, on its own or opened by the user. */
    var supportShown: Boolean by mutableStateOf(false)
        private set

    /** The team card showed on Home in this process. */
    var teamCardShown: Boolean by mutableStateOf(false)
        private set

    fun onPushPromptShown() {
        pushPromptShown = true
    }

    fun onSupportShown() {
        supportShown = true
    }

    fun onTeamCardShown() {
        teamCardShown = true
    }

    /**
     * Whether something other than the team card already asked in this session: the push
     * pre-prompt, the support sheet, or the usage-statistics consent (owed, or answered now).
     */
    fun otherThanTeamCard(consentAskedThisSession: Boolean): Boolean =
        pushPromptShown || supportShown || consentAskedThisSession
}
