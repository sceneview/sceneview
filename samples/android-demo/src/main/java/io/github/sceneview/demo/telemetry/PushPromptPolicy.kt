package io.github.sceneview.demo.telemetry

/** What [PushPromptPolicy] remembers between launches. [TelemetryPrefs] backs it on device. */
interface PushPromptStore {
    /** Returns to Home after a sample, counted since the last time the prompt was shown. */
    var homeReturns: Int

    /** How many times the pre-prompt has been shown, ever. */
    var timesShown: Int

    /** Epoch millis before which the prompt stays hidden ("Not now"), 0 when not snoozed. */
    var snoozedUntil: Long

    /** Set once the user answered for good (granted or refused at the system prompt). */
    var settled: Boolean
}

/**
 * When to show the "Get notified when new samples land" pre-prompt. Pure logic, no Android:
 * the caller supplies whether the prompt could matter at all ([eligible]: API 33+, permission
 * not yet granted, push available, Notifications setting on).
 *
 * - Never on first launch: the prompt waits for the [RETURNS_BEFORE_PROMPT]nd return to Home
 *   after opening a sample.
 * - "Not now" hides it for [SNOOZE_MILLIS] (7 days).
 * - Shown at most [MAX_SHOWS] times, then never again.
 * - An answer at the system dialog (granted or denied) settles it for good: Android would not
 *   show the system dialog again after two refusals anyway, and asking a third time is nagging.
 *   The Notifications switch in About stays the way back.
 */
class PushPromptPolicy(
    private val store: PushPromptStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Home came back after a sample: counts the return, then says whether the sheet shows now.
     * The only way in — the sheet never shows at launch, over the Home hero, even when an
     * earlier session left enough returns behind and the snooze has run out.
     */
    fun onReturnedHome(eligible: Boolean): Boolean {
        store.homeReturns = store.homeReturns + 1
        return shouldShow(eligible)
    }

    private fun shouldShow(eligible: Boolean): Boolean = eligible &&
        !store.settled &&
        store.timesShown < MAX_SHOWS &&
        now() >= store.snoozedUntil &&
        store.homeReturns >= RETURNS_BEFORE_PROMPT

    /** Call once when the sheet appears. */
    fun onShown() {
        store.timesShown = store.timesShown + 1
        store.homeReturns = 0
    }

    /**
     * "Not now", or the sheet dismissed without an answer. The returns start over too: once
     * the snooze runs out, the sheet waits for two fresh returns to Home.
     */
    fun onLater() {
        store.snoozedUntil = now() + SNOOZE_MILLIS
        store.homeReturns = 0
    }

    /** The system dialog answered, either way. */
    fun onAnswered() {
        store.settled = true
    }

    companion object {
        const val RETURNS_BEFORE_PROMPT = 2
        const val MAX_SHOWS = 3
        const val SNOOZE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
