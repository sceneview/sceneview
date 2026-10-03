package io.github.sceneview.demo.telemetry

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import io.github.sceneview.demo.R
import java.util.concurrent.Executors

/**
 * The demo app's usage statistics and push, in one place.
 *
 * [analytics] is the only thing screens call. It is a no-op until [ensureInit] ran, and stays one
 * in a build without `google-services.json` (see `PLAY_STORE_SETUP.md` § Firebase), until the
 * user's consent allows collection ([TelemetryConsent]), or when Firebase fails to start: nothing
 * here may crash a demo.
 *
 * Firebase does not start with the process: the manifest removes `FirebaseInitProvider`, and
 * [FirebaseApp.initializeApp] runs only once collection is allowed (consent given in the EEA, the
 * UK and Switzerland; on by default elsewhere) or the user turned push on. Except for push the
 * user already turned on (FCM needs Firebase, and starts it before the consent is answered), no
 * Firebase component runs before that and nothing reaches Google — not even a Firebase
 * Installations call. A start without consent always has collection off ([initializeFirebase]).
 */
object Telemetry {

    private const val TAG = "DemoTelemetry"

    /** FCM topics every opted-in install listens to; the `.qa` variant also gets [TOPIC_QA]. */
    private val TOPICS = listOf("all", "new_samples")
    private const val TOPIC_QA = "qa"

    const val CHANNEL_WHATS_NEW = "whats_new"

    @Volatile
    private var prefs: TelemetryPrefs? = null

    @Volatile
    private var delegate: DemoAnalytics = NoOpDemoAnalytics

    /**
     * Whether this build carries a Firebase config, i.e. analytics and push can work once
     * allowed. Read from the generated resources, without starting Firebase.
     */
    @Volatile
    var firebaseAvailable: Boolean = false
        private set

    /** Whether FirebaseApp runs in this process (collection allowed, or push wanted). */
    @Volatile
    var firebaseStarted: Boolean = false
        private set

    /** Whether this install must be asked first ([ConsentRegion]), or the debug extra said "ask". */
    @Volatile
    private var consentZone: Boolean = true

    /** Whether usage statistics and crash reports may be collected now. */
    @Volatile
    private var collecting: Boolean = false

    /** Set by the debug `telemetry_consent=ask` extra: the sheet shows even without a config. */
    @Volatile
    private var forceAsk: Boolean = false

    /** Every screen's single entry point. */
    val analytics: DemoAnalytics = GatedDemoAnalytics(
        delegate = object : DemoAnalytics {
            override fun log(event: AnalyticsEvent) = delegate.log(event)
            override fun setUserProperty(property: UserProperty, value: String) =
                delegate.setUserProperty(property, value)
            override fun setCollectionEnabled(enabled: Boolean) = delegate.setCollectionEnabled(enabled)
            override fun resetData() = delegate.resetData()
        },
        isEnabled = { collecting },
    )

    /** How the next sample was reached; read (and reset) by the demo route when it opens. */
    var nextOpenSource: OpenSource = OpenSource.Other

    /** Canonical sample id and received id for the next open, before alias resolution. */
    var nextEntryId: Pair<String, String>? = null

    /** Compose-observable mirror of the two About -> Privacy & notifications switches. */
    var analyticsEnabled by mutableStateOf(false)
        private set
    var notificationsEnabled by mutableStateOf(false)
        private set

    /** The consent sheet is owed: [ConsentHost] shows it over Home. */
    var consentPending by mutableStateOf(false)
        private set

    /** The consent was answered in this process: the push pre-prompt waits for a later session. */
    @Volatile
    private var consentAnsweredThisSession: Boolean = false

    /** Push on and off; null until Firebase started. */
    @Volatile
    private var push: PushSwitch? = null

    /** Idempotent. Called by MainActivity and by the messaging service, whichever runs first. */
    @Synchronized
    fun ensureInit(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        val p = TelemetryPrefs(app)
        migrateConsent(app, p)
        consentZone = forceAsk || ConsentRegion.requiresConsent(ConsentSignals.read(app))
        firebaseAvailable = runCatching { FirebaseOptions.fromResource(app) != null }.getOrDefault(false)
        notificationsEnabled = p.notificationsEnabled
        refreshConsent(p)
        prefs = p
        if (!firebaseAvailable) {
            Log.i(TAG, "No Firebase config in this build: analytics and push are off.")
            return
        }
        createChannel(app)
        startFirebaseIfNeeded(app)
        syncPush(app)
        refreshUserProperties(app)
        // ArCoreApk may hit the Play Store service: off the main thread.
        background.execute {
            val supported = runCatching {
                val availability = ArCoreApk.getInstance().checkAvailability(app)
                when {
                    availability.isTransient -> "unknown"
                    availability.isSupported -> "true"
                    else -> "false"
                }
            }.getOrDefault("unknown")
            analytics.setUserProperty(UserProperty.ArSupported, supported)
        }
    }

    private val background by lazy { Executors.newSingleThreadExecutor() }

    fun promptStore(context: Context): PushPromptStore {
        ensureInit(context)
        return requireNotNull(prefs)
    }

    /**
     * Whether the push pre-prompt may show: the consent is settled, and was not answered in this
     * session — one question per session, never a second sheet right after the first.
     */
    fun consentAllowsPushPrompt(): Boolean {
        val p = prefs ?: return false
        return TelemetryConsent.pushPromptAllowed(p.consentState, consentZone, consentAnsweredThisSession)
    }

    /** The consent sheet: "Share" or "Don't share"; dismissing it counts as "Don't share". */
    fun answerConsent(context: Context, granted: Boolean) {
        consentAnsweredThisSession = true
        setAnalyticsEnabled(context, granted)
    }

    /** About → "Share usage statistics": gives or withdraws the consent. */
    fun setAnalyticsEnabled(context: Context, enabled: Boolean) {
        ensureInit(context)
        val p = prefs ?: return
        val app = context.applicationContext
        val wasCollecting = collecting
        if (!enabled && wasCollecting) {
            // Best effort, and usually lost: logged while collection is still on, but the
            // resetData() below also clears the events still waiting on the device, this one
            // with them. Accepted rather than delaying the reset, which is what the opt-out
            // promises.
            analytics.log(AnalyticsEvent.SettingsChanged("analytics", "false"))
        }
        TelemetryConsent.record(p, granted = enabled, now = System.currentTimeMillis())
        refreshConsent(p)
        if (enabled) startFirebaseIfNeeded(app)
        // Turns collection off too, and deletes the crash reports not sent yet.
        runCatching { delegate.setCollectionEnabled(collecting) }
        // On -> off only: forget the app-instance id and the data waiting on the device, so
        // nothing collected before the opt-out can be joined to anything after an opt-in.
        // Not at every launch while opted out: there is nothing new to forget.
        if (!enabled && wasCollecting) runCatching { delegate.resetData() }
        if (enabled) {
            analytics.log(AnalyticsEvent.SettingsChanged("analytics", "true"))
            refreshUserProperties(app)
        }
    }

    /**
     * Debug builds only: `adb shell am start ... --es telemetry_consent granted|denied|ask`.
     * Automation passes `denied` so no capture or test run ever shows the sheet or sends data;
     * `ask` forgets the answer and shows the sheet wherever the device is. Applied before
     * [ensureInit] on a cold start, so Firebase never starts on a value about to change.
     */
    fun applyDebugConsent(context: Context, value: String?) {
        val debug = DebugConsent.parse(value) ?: return
        Log.i(TAG, "Debug consent override: $debug")
        if (prefs == null) {
            val seed = TelemetryPrefs(context.applicationContext)
            migrateConsent(context.applicationContext, seed)
            when (debug) {
                DebugConsent.Granted -> TelemetryConsent.record(seed, granted = true, now = System.currentTimeMillis())
                DebugConsent.Denied -> TelemetryConsent.record(seed, granted = false, now = System.currentTimeMillis())
                DebugConsent.Ask -> {
                    seed.consentState = ConsentState.Unknown
                    forceAsk = true
                }
            }
            ensureInit(context)
            return
        }
        when (debug) {
            DebugConsent.Granted -> setAnalyticsEnabled(context, true)
            DebugConsent.Denied -> setAnalyticsEnabled(context, false)
            DebugConsent.Ask -> {
                val p = prefs ?: return
                val wasCollecting = collecting
                forceAsk = true
                consentZone = true
                consentAnsweredThisSession = false
                p.consentState = ConsentState.Unknown
                p.consentAt = 0L
                refreshConsent(p)
                runCatching { delegate.setCollectionEnabled(false) }
                if (wasCollecting) runCatching { delegate.resetData() }
            }
        }
    }

    /**
     * About -> Privacy & notifications -> "Notifications". The system permission is asked by the caller on
     * API 33+. An explicit choice here also retires the pre-prompt for good.
     */
    fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        ensureInit(context)
        prefs?.settled = true
        analytics.log(AnalyticsEvent.SettingsChanged("notifications", enabled.toString()))
        applyNotifications(context, enabled)
    }

    /** The pre-prompt was accepted: the permission granted (API 33+) or "Notify me" (below). */
    fun acceptPush(context: Context) {
        ensureInit(context)
        applyNotifications(context, true)
    }

    private fun applyNotifications(context: Context, enabled: Boolean) {
        val p = prefs ?: return
        val wasEnabled = p.notificationsEnabled
        p.notificationsEnabled = enabled
        notificationsEnabled = enabled
        // Push is its own consent: it starts Firebase, with collection still as the consent says.
        if (enabled) startFirebaseIfNeeded(context.applicationContext)
        if (enabled) syncPush(context) else push?.turnOff(wasOn = wasEnabled)
        refreshUserProperties(context)
    }

    /**
     * Turns FCM on once the user opted in AND the system lets the app post, or retries an
     * opt-out that failed offline; see [PushSwitch]. Idempotent: MainActivity re-runs it on every
     * resume, so a permission granted in system settings makes push possible on return.
     */
    fun syncPush(context: Context) {
        ensureInit(context)
        push?.sync()
    }

    /** True when the system lets this app post (the POST_NOTIFICATIONS grant on API 33+). */
    fun systemAllowsNotifications(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                )

    /** Re-sends the user properties that can change while the app runs. */
    fun refreshUserProperties(context: Context) {
        if (!firebaseStarted || !collecting) return
        val app = context.applicationContext
        val dark = (app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        analytics.setUserProperty(UserProperty.AppTheme, if (dark) "dark" else "light")
        analytics.setUserProperty(
            UserProperty.NotifEnabled,
            (notificationsEnabled && systemAllowsNotifications(app)).toString(),
        )
    }

    /**
     * The consent migration, plus the Firebase settings an older build persisted: they would
     * otherwise override the manifest the first time Firebase starts ([FirebaseLeftovers]).
     */
    private fun migrateConsent(app: Context, store: ConsentStore) {
        if (store.consentVersion < TelemetryConsent.VERSION) FirebaseLeftovers.clear(app)
        TelemetryConsent.migrate(store)
    }

    /** Re-derives what the consent allows and mirrors it for Compose. */
    private fun refreshConsent(store: ConsentStore) {
        val allowed = TelemetryConsent.collectionAllowed(store.consentState, consentZone)
        collecting = allowed
        analyticsEnabled = allowed
        consentPending = TelemetryConsent.mustAsk(store.consentState, consentZone) && (firebaseAvailable || forceAsk)
    }

    /**
     * Starts FirebaseApp the first time something allows it: collection (consent, or outside the
     * zone), push turned on, or a push opt-out still to finish. Never before.
     */
    @Synchronized
    private fun startFirebaseIfNeeded(app: Context) {
        if (firebaseStarted || !firebaseAvailable) return
        val p = prefs ?: return
        if (!collecting && !p.notificationsEnabled && !p.pushDisablePending) return
        val started = runCatching {
            FirebaseApp.getApps(app).firstOrNull()
                ?: initializeFirebase(app, collecting) { FirebaseApp.initializeApp(app) }
        }
            .onFailure { Log.w(TAG, "Firebase failed to start", it) }
            .getOrNull() != null
        if (!started) return
        firebaseStarted = true
        Log.i(TAG, "Firebase started (collection=$collecting, push=${p.notificationsEnabled})")
        delegate = runCatching { FirebaseDemoAnalytics(app) }
            .onFailure { Log.w(TAG, "Firebase Analytics unavailable", it) }
            .getOrDefault(NoOpDemoAnalytics)
        // Re-applied at every start; the reset is not (see setAnalyticsEnabled).
        runCatching { delegate.setCollectionEnabled(collecting) }
        push = PushSwitch(
            backend = FirebasePushBackend(),
            store = p,
            topics = topics(app),
            wanted = { notificationsEnabled },
            systemAllows = { systemAllowsNotifications(app) },
        )
    }

    /**
     * Runs [initialize] (`FirebaseApp.initializeApp`), first clearing the collection flags Firebase
     * saved earlier ([FirebaseLeftovers]) whenever this start must not collect.
     *
     * Not only after a consent-version change: an install that collected outside the zone, never
     * answered, then moved inside it (a US phone on a French network) with push on still holds
     * `measurement_enabled=true` and the Crashlytics flag. Left in place, Firebase would start
     * collecting until [DemoAnalytics.setCollectionEnabled] turns it off — time enough for
     * Crashlytics to send cached reports. Cleared, the manifest's "off" applies from the first
     * instant, and the consent re-applies its own value right after.
     */
    @VisibleForTesting
    internal fun <T> initializeFirebase(app: Context, collecting: Boolean, initialize: () -> T): T {
        if (!collecting) FirebaseLeftovers.clear(app)
        return initialize()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_WHATS_NEW,
                context.getString(R.string.notification_channel_whats_new),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notification_channel_whats_new_description) }
            manager.createNotificationChannel(channel)
        }
    }

    private fun topics(context: Context): List<String> =
        if (context.packageName.endsWith(".qa")) TOPICS + TOPIC_QA else TOPICS
}
