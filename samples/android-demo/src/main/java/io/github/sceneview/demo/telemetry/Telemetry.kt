package io.github.sceneview.demo.telemetry

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.R

/**
 * The demo app's usage statistics and push, in one place.
 *
 * [analytics] is the only thing screens call. It is a no-op until [ensureInit] ran, and stays one
 * in a build without `google-services.json` (FirebaseApp never initialises — see
 * `PLAY_STORE_SETUP.md` § Firebase) or when Firebase fails to start: nothing here may crash a demo.
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

    /** Whether Firebase started, i.e. this build carries a config and push can work. */
    @Volatile
    var firebaseAvailable: Boolean = false
        private set

    /** Every screen's single entry point. */
    val analytics: DemoAnalytics = GatedDemoAnalytics(
        delegate = object : DemoAnalytics {
            override fun log(event: AnalyticsEvent) = delegate.log(event)
            override fun setUserProperty(property: UserProperty, value: String) =
                delegate.setUserProperty(property, value)
            override fun setCollectionEnabled(enabled: Boolean) = delegate.setCollectionEnabled(enabled)
            override fun resetData() = delegate.resetData()
        },
        isEnabled = { prefs?.analyticsEnabled == true },
    )

    /** How the next sample was reached; read (and reset) by the demo route when it opens. */
    var nextOpenSource: OpenSource = OpenSource.Other

    /** Compose-observable mirror of the two About -> Privacy & notifications switches. */
    var analyticsEnabled by mutableStateOf(true)
        private set
    var notificationsEnabled by mutableStateOf(false)
        private set

    /** Whether this process already turned FCM on (auto-init, topics); see [syncPush]. */
    @Volatile
    private var pushActivated = false

    /** Idempotent. Called by MainActivity and by the messaging service, whichever runs first. */
    @Synchronized
    fun ensureInit(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        val p = TelemetryPrefs(app)
        prefs = p
        analyticsEnabled = p.analyticsEnabled
        notificationsEnabled = p.notificationsEnabled
        firebaseAvailable = runCatching { FirebaseApp.getApps(app).isNotEmpty() }.getOrDefault(false)
        if (!firebaseAvailable) {
            Log.i(TAG, "No Firebase config in this build: analytics and push are off.")
            return
        }
        delegate = runCatching { FirebaseDemoAnalytics(app) }
            .onFailure { Log.w(TAG, "Firebase Analytics unavailable", it) }
            .getOrDefault(NoOpDemoAnalytics)
        // Re-applied at every launch; the reset is not (see setAnalyticsEnabled).
        runCatching { delegate.setCollectionEnabled(p.analyticsEnabled) }
        createChannel(app)
        syncPush(app)
        refreshUserProperties(app)
    }

    fun promptStore(context: Context): PushPromptStore {
        ensureInit(context)
        return requireNotNull(prefs)
    }

    /** About → "Share usage statistics". */
    fun setAnalyticsEnabled(context: Context, enabled: Boolean) {
        ensureInit(context)
        val p = prefs ?: return
        val wasEnabled = p.analyticsEnabled
        if (!enabled && wasEnabled) {
            // Logged while still on, so the opt-out itself is the last thing sent.
            analytics.log(AnalyticsEvent.SettingsChanged("analytics", "false"))
        }
        p.analyticsEnabled = enabled
        analyticsEnabled = enabled
        runCatching { delegate.setCollectionEnabled(enabled) }
        // On -> off only: forget the app-instance id and the data waiting on the device, so
        // nothing collected before the opt-out can be joined to anything after an opt-in.
        // Not at every launch while opted out: there is nothing new to forget.
        if (!enabled && wasEnabled) runCatching { delegate.resetData() }
        if (enabled) {
            analytics.log(AnalyticsEvent.SettingsChanged("analytics", "true"))
            refreshUserProperties(context)
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
        if (enabled) {
            syncPush(context)
        } else if (wasEnabled || pushActivated) {
            pushActivated = false
            disablePush(context)
        }
        refreshUserProperties(context)
    }

    /**
     * Turns FCM on once the user opted in AND the system lets the app post, never before, so
     * no registration token exists for someone who never said yes (the manifest sets
     * `firebase_messaging_auto_init_enabled` to false). While opted out, retries a disable
     * that failed (offline) until the topics are left and the token deleted. Idempotent;
     * MainActivity re-runs it on every resume, so a permission granted in system settings
     * makes push possible as soon as the user comes back.
     */
    fun syncPush(context: Context) {
        if (!firebaseAvailable) return
        val p = prefs ?: return
        if (!notificationsEnabled) {
            if (p.pushDisablePending) disablePush(context.applicationContext)
            return
        }
        if (pushActivated || !systemAllowsNotifications(context)) return
        pushActivated = true
        enablePush(context.applicationContext)
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
        if (!firebaseAvailable) return
        val app = context.applicationContext
        val dark = (app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        analytics.setUserProperty(UserProperty.AppTheme, if (dark) "dark" else "light")
        analytics.setUserProperty(
            UserProperty.NotifEnabled,
            (notificationsEnabled && systemAllowsNotifications(app)).toString(),
        )
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

    private fun enablePush(context: Context) {
        prefs?.pushDisablePending = false
        runCatching {
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = true
            topics(context).forEach { topic ->
                val task = messaging.subscribeToTopic(topic)
                if (BuildConfig.DEBUG) {
                    task.addOnCompleteListener { Log.d(TAG, "topic $topic subscribed ok=${it.isSuccessful}") }
                }
            }
            if (BuildConfig.DEBUG) {
                messaging.token.addOnSuccessListener { Log.d(TAG, "FCM token: $it") }
            }
        }.onFailure { Log.w(TAG, "FCM unavailable (no Play services?)", it) }
    }

    /** Set while a disable runs, so a resume during it does not start a second one. */
    @Volatile
    private var disableInFlight = false

    /**
     * Leaves every topic, then deletes the registration token: nothing can reach this install.
     * Persisted as pending until both succeed; offline, [syncPush] retries it on a later resume.
     */
    private fun disablePush(context: Context) {
        if (disableInFlight) return
        disableInFlight = true
        prefs?.pushDisablePending = true
        runCatching {
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = false
            val leaving = topics(context).map { messaging.unsubscribeFromTopic(it) }
            Tasks.whenAllComplete(leaving).addOnCompleteListener {
                val left = leaving.all { it.isSuccessful }
                messaging.deleteToken().addOnCompleteListener { deleted ->
                    val done = left && deleted.isSuccessful
                    // Re-enabled meanwhile: enablePush already cleared the flag, keep it so.
                    if (done || notificationsEnabled) prefs?.pushDisablePending = false
                    disableInFlight = false
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            TAG,
                            "topics left ok=$left, FCM token deleted ok=${deleted.isSuccessful}, pending=${!done}",
                        )
                    }
                }
            }
        }.onFailure {
            disableInFlight = false
            Log.w(TAG, "FCM unavailable (no Play services?)", it)
        }
    }
}
