package io.github.sceneview.demo.telemetry

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.sceneview.demo.R

/**
 * Builds and posts a "What's new" notification on the `whats_new` channel. The one path for a
 * received push ([DemoMessagingService]) and for the debug `push_qa` extra, so what QA sees on
 * an emulator is what a push draws on a phone.
 */
object DemoPushNotifier {

    private const val TAG = "DemoPush"

    /** What one notification says and opens. */
    data class Content(
        val title: String,
        val body: String,
        /** A demo id: the tap opens that sample ([PushIntent]). */
        val sample: String?,
        val campaign: String?,
        /** Stable id, so a re-delivered message replaces its notification. */
        val key: String,
    )

    /**
     * Posts [content] unless an opt-out or the cadence says no ([PushPostGate]). [broadcast]: a
     * topic message that counts toward [PushCadence]. Returns the verdict, logged either way.
     */
    fun post(context: Context, content: Content, broadcast: Boolean, ignoreSwitch: Boolean = false): PushPostGate.Verdict {
        Telemetry.ensureInit(context)
        ensureChannel(context)
        val cadence = PushCadence(Telemetry.pushCadenceStore(context))
        val verdict = PushPostGate.decide(
            switchOn = ignoreSwitch || Telemetry.notificationsEnabled,
            systemAllows = Telemetry.systemAllowsNotifications(context),
            channelBlocked = channelBlocked(context),
            cadenceAllows = !broadcast || cadence.allows(),
        )
        Log.i(TAG, "whats_new push: $verdict (sample=${content.sample}, campaign=${content.campaign})")
        if (verdict != PushPostGate.Verdict.Post) return verdict
        val tap = PushIntent.forTap(context, sample = content.sample, campaign = content.campaign)
        val pending = PendingIntent.getActivity(
            context,
            content.key.hashCode(),
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, Telemetry.CHANNEL_WHATS_NEW)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.md_theme_primary))
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        // Re-checked just above (systemAllowsNotifications covers the API 33+ grant).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return PushPostGate.Verdict.SystemOff
        }
        NotificationManagerCompat.from(context).notify(content.key.hashCode(), notification)
        if (broadcast) cadence.record()
        return verdict
    }

    /**
     * Debug `--es push_qa <sample-id>`: the notification a `new_samples` push for that sample
     * would draw. Skips the in-app switch only — a build without a Firebase config has no switch
     * to turn on — and keeps every system opt-out. Never counts toward the cadence.
     */
    fun postQa(context: Context, sample: String) {
        post(
            context,
            Content(
                title = context.getString(R.string.push_default_title),
                body = context.getString(R.string.push_default_body),
                sample = sample,
                campaign = "qa",
                key = "push_qa_$sample",
            ),
            broadcast = false,
            ignoreSwitch = true,
        )
    }

    /** True when the user turned the `whats_new` channel off in system settings. */
    private fun channelBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val channel = NotificationManagerCompat.from(context).getNotificationChannel(Telemetry.CHANNEL_WHATS_NEW)
            ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    /** Idempotent: Android keeps the user's settings on a channel that already exists. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                Telemetry.CHANNEL_WHATS_NEW,
                context.getString(R.string.notification_channel_whats_new),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notification_channel_whats_new_description) }
            manager.createNotificationChannel(channel)
        }
    }
}
