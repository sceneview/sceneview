package io.github.sceneview.demo.telemetry

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.MainActivity
import io.github.sceneview.demo.R

/**
 * Receives "What's new" pushes. Runs for every data message, and for every message while the
 * app is in the foreground — Android only draws a `notification` payload itself when the app
 * is in the background — so the notification is built here and a push is never silently
 * swallowed while the user is looking at the app.
 *
 * Payload contract (shared with iOS and `tools/send-demo-push.py`): `title`, `body` (or a
 * `notification` block), `sample` = a demo id to open, `campaign` = a label logged in
 * `push_opened`. Send data-only to Android, so this runs, the opt-outs and the 2-a-month
 * cadence ([PushCadence]) apply, and the tap opens `sample` even when the app was in the
 * background.
 */
class DemoMessagingService : FirebaseMessagingService() {

    /**
     * The manifest removes `FirebaseInitProvider`, so a process started by a push has no
     * FirebaseApp yet: [Telemetry.ensureInit] starts it (push on, or an opt-out to finish).
     */
    override fun onCreate() {
        Telemetry.ensureInit(applicationContext)
        super.onCreate()
    }

    override fun onNewToken(token: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "FCM token: $token")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        runCatching { show(this, message) }.onFailure { Log.w(TAG, "push not shown", it) }
    }

    private fun show(context: Context, message: RemoteMessage) {
        val data = message.data
        val title = message.notification?.title ?: data["title"] ?: context.getString(R.string.push_default_title)
        val body = message.notification?.body ?: data["body"] ?: context.getString(R.string.push_default_body)
        // Every opt-out (switch, permission, `whats_new` channel off) and the 2-a-month cadence
        // are checked in DemoPushNotifier: a refused push builds nothing.
        DemoPushNotifier.post(
            context,
            DemoPushNotifier.Content(
                title = title,
                body = body,
                sample = data[PushIntent.KEY_SAMPLE],
                campaign = data[PushIntent.KEY_CAMPAIGN],
                key = message.messageId ?: title,
            ),
            broadcast = PushCadence.counts(message.from),
        )
    }

    private companion object {
        const val TAG = "DemoPush"
    }
}

/**
 * The push → activity hand-off. A tap on a notification this app built carries [KEY_FROM_PUSH];
 * a tap on one Android drew itself (app in background) carries FCM's `google.message_id` and the
 * data keys as extras. Both are read by [from].
 */
object PushIntent {
    const val KEY_SAMPLE = "sample"
    const val KEY_CAMPAIGN = "campaign"
    private const val KEY_FROM_PUSH = "sceneview_push"
    private const val KEY_FCM_MESSAGE_ID = "google.message_id"

    data class Tap(val sample: String?, val campaign: String?)

    fun forTap(context: Context, sample: String?, campaign: String?): Intent =
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(KEY_FROM_PUSH, true)
            sample?.let { putExtra(KEY_SAMPLE, it) }
            campaign?.let { putExtra(KEY_CAMPAIGN, it) }
        }

    /** The push this intent was opened from, or null. Clears the markers so it is read once. */
    fun from(intent: Intent?): Tap? {
        val extras = intent?.extras ?: return null
        val fromPush = extras.getBoolean(KEY_FROM_PUSH, false) || extras.containsKey(KEY_FCM_MESSAGE_ID)
        if (!fromPush) return null
        val tap = Tap(
            sample = extras.getString(KEY_SAMPLE)?.takeIf { it.isNotBlank() },
            campaign = extras.getString(KEY_CAMPAIGN)?.takeIf { it.isNotBlank() },
        )
        // A recreated activity re-reads its intent: without this, rotating after a push
        // would log a second push_opened.
        intent.removeExtra(KEY_FROM_PUSH)
        intent.removeExtra(KEY_FCM_MESSAGE_ID)
        return tap
    }
}
