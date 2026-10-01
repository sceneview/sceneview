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
import com.google.ar.core.ArCoreApk
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.R
import java.util.concurrent.Executors

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
        delegate.setCollectionEnabled(p.analyticsEnabled)
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
        if (!enabled) {
            // Logged while still on, so the opt-out itself is the last thing sent.
            analytics.log(AnalyticsEvent.SettingsChanged("analytics", "false"))
        }
        p.analyticsEnabled = enabled
        analyticsEnabled = enabled
        runCatching { delegate.setCollectionEnabled(enabled) }
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
     * `firebase_messaging_auto_init_enabled` to false). Idempotent; re-run on resume, when a
     * permission granted in system settings makes push possible.
     */
    fun syncPush(context: Context) {
        if (!firebaseAvailable || pushActivated) return
        if (!notificationsEnabled || !systemAllowsNotifications(context)) return
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

    /** Leaves every topic, then deletes the registration token: nothing can reach this install. */
    private fun disablePush(context: Context) {
        runCatching {
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = false
            val leaving = topics(context).map { messaging.unsubscribeFromTopic(it) }
            Tasks.whenAllComplete(leaving).addOnCompleteListener {
                messaging.deleteToken().addOnCompleteListener {
                    if (BuildConfig.DEBUG) Log.d(TAG, "topics left, FCM token deleted ok=${it.isSuccessful}")
                }
            }
        }.onFailure { Log.w(TAG, "FCM unavailable (no Play services?)", it) }
    }
}
