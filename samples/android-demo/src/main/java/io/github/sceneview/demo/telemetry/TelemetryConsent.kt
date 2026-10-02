package io.github.sceneview.demo.telemetry

/** The answer to "Share usage statistics and crash reports?". */
enum class ConsentState(val key: String) {
    /** Never answered, or asked again after [TelemetryConsent.VERSION] changed. */
    Unknown("unknown"),
    Granted("granted"),
    Denied("denied"),
    ;

    companion object {
        fun fromKey(key: String?): ConsentState = entries.firstOrNull { it.key == key } ?: Unknown
    }
}

/** What the consent remembers between launches. [TelemetryPrefs] backs it on device. */
interface ConsentStore {
    var consentState: ConsentState

    /** Epoch millis of the last answer (sheet or About switch), 0 when never answered. */
    var consentAt: Long

    /** The [TelemetryConsent.VERSION] the stored answer was given under; 0 before consent existed. */
    var consentVersion: Int

    /**
     * The About switch of builds that predate the consent (`analytics_enabled`), null when it was
     * never touched. Only read by the migration, then cleared.
     */
    var legacyAnalyticsEnabled: Boolean?
}

/**
 * The consent rules, pure and unit-tested. One consent covers usage statistics and crash
 * reports: one purpose, "help improve the app".
 *
 * - Inside the consent zone ([ConsentRegion]) nothing is collected until the answer is
 *   [ConsentState.Granted]; outside it, collection is on until the user turns it off.
 * - Dismissing the sheet is a refusal, never asked again until [VERSION] changes.
 * - Installs from before the consent: an explicit `analytics_enabled=false` becomes
 *   [ConsentState.Denied], every other install is asked once.
 */
object TelemetryConsent {

    /** Bump to ask everybody again (a new purpose, a new processor). */
    const val VERSION = 1

    /** Brings a store written by an older build up to [VERSION]. Idempotent. */
    fun migrate(store: ConsentStore) {
        if (store.consentVersion >= VERSION) return
        val optedOutBefore = store.consentVersion == 0 && store.legacyAnalyticsEnabled == false
        store.consentState = if (optedOutBefore) ConsentState.Denied else ConsentState.Unknown
        store.consentAt = 0L
        store.consentVersion = VERSION
        store.legacyAnalyticsEnabled = null
    }

    /** Records an answer from the sheet or the About switch, with its time. */
    fun record(store: ConsentStore, granted: Boolean, now: Long) {
        store.consentState = if (granted) ConsentState.Granted else ConsentState.Denied
        store.consentAt = now
        store.consentVersion = VERSION
    }

    /** Whether usage statistics and crash reports may be collected now. */
    fun collectionAllowed(state: ConsentState, inZone: Boolean): Boolean = when (state) {
        ConsentState.Granted -> true
        ConsentState.Denied -> false
        ConsentState.Unknown -> !inZone
    }

    /** Whether the consent sheet is owed: unanswered, and in the zone. */
    fun mustAsk(state: ConsentState, inZone: Boolean): Boolean = state == ConsentState.Unknown && inZone

    /**
     * Whether the push pre-prompt may show: never while the consent sheet is owed, and never in
     * the session that answered it (one question per session).
     */
    fun pushPromptAllowed(state: ConsentState, inZone: Boolean, answeredThisSession: Boolean): Boolean =
        !mustAsk(state, inZone) && !answeredThisSession
}

/** The debug-only `--es telemetry_consent granted|denied|ask` launch extra. */
enum class DebugConsent {
    Granted,
    Denied,

    /** Forgets the answer and treats the device as in the zone, so the sheet shows anywhere. */
    Ask,
    ;

    companion object {
        fun parse(value: String?): DebugConsent? = when (value?.trim()?.lowercase()) {
            "granted" -> Granted
            "denied" -> Denied
            "ask" -> Ask
            else -> null
        }
    }
}
