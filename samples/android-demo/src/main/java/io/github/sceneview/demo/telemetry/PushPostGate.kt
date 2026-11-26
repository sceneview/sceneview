package io.github.sceneview.demo.telemetry

/**
 * Whether a received "What's new" push may be posted. Pure logic, no Android: the caller reads
 * each input from the system ([DemoPushNotifier]).
 *
 * Every opt-out wins, in the order the user reaches them: the in-app Notifications switch
 * (About -> Privacy & notifications), the system's app-level switch and the POST_NOTIFICATIONS
 * grant, then the `whats_new` channel turned off in system settings. A blocked channel would
 * drop the notification anyway; checking it here means nothing is built, no tap intent is
 * created and the post does not count toward [PushCadence].
 */
object PushPostGate {

    enum class Verdict {
        Post,
        SwitchOff,
        SystemOff,
        ChannelOff,
        OverCadence,
    }

    fun decide(
        switchOn: Boolean,
        systemAllows: Boolean,
        channelBlocked: Boolean,
        cadenceAllows: Boolean,
    ): Verdict = when {
        !switchOn -> Verdict.SwitchOff
        !systemAllows -> Verdict.SystemOff
        channelBlocked -> Verdict.ChannelOff
        !cadenceAllows -> Verdict.OverCadence
        else -> Verdict.Post
    }
}

/** When [PushCadence] posted, newest last. [TelemetryPrefs] backs it on device. */
interface PushCadenceStore {
    var pushPostedAt: List<Long>
}

/**
 * At most [MAX_POSTS] broadcast "What's new" notifications per [WINDOW_MILLIS] (2 a month), on the
 * device itself: the pre-prompt promises "a few times a month at most", and a send by mistake
 * must not break that promise. Broadcast means a topic message (`all`, `new_samples`); a push sent
 * to the `qa` topic or to one device's token is a test and does not count ([counts]).
 *
 * There is no inactivity reminder: a SDK demo has no reason to call people back.
 */
class PushCadence(
    private val store: PushCadenceStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Whether one more broadcast push may be posted now. */
    fun allows(): Boolean = recent().size < MAX_POSTS

    /** A broadcast push was posted. Keeps only the posts still inside the window. */
    fun record() {
        store.pushPostedAt = recent() + now()
    }

    private fun recent(): List<Long> {
        val since = now() - WINDOW_MILLIS
        return store.pushPostedAt.filter { it > since }
    }

    companion object {
        const val MAX_POSTS = 2
        const val WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000

        /**
         * Whether a message from [from] (`RemoteMessage.from`: `/topics/<name>` for a topic, the
         * sender id for a message to one token) counts toward the cadence.
         */
        fun counts(from: String?): Boolean =
            from != null && from.startsWith(TOPIC_PREFIX) && from != "${TOPIC_PREFIX}qa"

        private const val TOPIC_PREFIX = "/topics/"
    }
}
