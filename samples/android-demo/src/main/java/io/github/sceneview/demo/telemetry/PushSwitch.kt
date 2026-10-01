package io.github.sceneview.demo.telemetry

/** FCM behind an interface, so [PushSwitch] runs in a JVM test. */
interface PushBackend {
    /** Turns FCM auto-init on and joins [topics]. */
    fun enable(topics: List<String>)

    /**
     * Turns FCM auto-init off, leaves [topics], then deletes the registration token. [onDone]
     * reports whether the topics were left and the token deleted (both false when FCM is absent).
     */
    fun disable(topics: List<String>, onDone: (left: Boolean, deleted: Boolean) -> Unit)
}

/** Where an opt-out that has not gone through yet survives the process. */
interface PushDisableStore {
    var pushDisablePending: Boolean
}

/**
 * Push on and off, as the user and the system allow it. FCM is turned on only once the user
 * opted in AND the system lets the app post, so no registration token exists for someone who
 * never said yes. An opt-out stays pending until the topics are left and the token deleted;
 * offline, the next [sync] retries it.
 *
 * Main thread only (the cold-start path goes through `@Synchronized` [Telemetry.ensureInit]).
 *
 * @param wanted the user's choice (About -> Privacy & notifications, or the pre-prompt).
 * @param systemAllows whether the system lets the app post (POST_NOTIFICATIONS on API 33+).
 */
class PushSwitch(
    private val backend: PushBackend,
    private val store: PushDisableStore,
    private val topics: List<String>,
    private val wanted: () -> Boolean,
    private val systemAllows: () -> Boolean,
) {
    /** Whether this process already turned FCM on (auto-init, topics). */
    @Volatile
    private var activated = false

    /** Set while a disable runs, so a resume during it does not start a second one. */
    @Volatile
    private var disableInFlight = false

    /** The user turned push off again while a disable ran: run one more once it lands. */
    @Volatile
    private var redoDisable = false

    /**
     * Idempotent. Runs at launch, on every resume and when the user turns push on, so a
     * permission granted in system settings makes push possible as soon as the user comes back.
     */
    fun sync() {
        if (!wanted()) {
            if (store.pushDisablePending) disable()
            return
        }
        if (activated || !systemAllows()) return
        activated = true
        store.pushDisablePending = false
        backend.enable(topics)
    }

    /** The user turned push off; [wasOn] is the choice before this one. */
    fun turnOff(wasOn: Boolean) {
        if (!wasOn && !activated) return
        activated = false
        if (disableInFlight) {
            // Off -> on -> off while the first disable runs: the "on" cleared the pending flag
            // and turned auto-init back on, and its subscriptions may land on a new token.
            // The disable in flight cannot undo that; the one after it will.
            redoDisable = true
            store.pushDisablePending = true
            return
        }
        disable()
    }

    private fun disable() {
        if (disableInFlight) return
        disableInFlight = true
        store.pushDisablePending = true
        backend.disable(topics) { left, deleted ->
            disableInFlight = false
            if (wanted()) {
                // Turned back on while this ran: the topics the re-enable joined went with the
                // token just deleted, and `activated` would keep sync() from joining them again.
                redoDisable = false
                store.pushDisablePending = false
                activated = false
                sync()
            } else if (redoDisable) {
                redoDisable = false
                disable()
            } else if (left && deleted) {
                store.pushDisablePending = false
            }
        }
    }
}
