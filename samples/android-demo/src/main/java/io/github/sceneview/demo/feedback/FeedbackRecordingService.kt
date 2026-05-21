package io.github.sceneview.demo.feedback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.sceneview.demo.MainActivity
import io.github.sceneview.demo.R
import java.io.File

/**
 * Foreground service that captures the screen + microphone with MediaProjection
 * while the user demonstrates a bug. Started once the user has granted the
 * screen-capture permission; stopped from its notification action, the in-app
 * Stop pill, or when the system revokes the projection. The result is published
 * through [FeedbackRecorder].
 */
class FeedbackRecordingService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var videoFile: File? = null
    private var startedAt = 0L

    /** Guards [stopRecording] against the notification / pill / projection-callback
     *  all triggering a stop. */
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = intent?.let {
            if (Build.VERSION.SDK_INT >= 33) {
                it.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra(EXTRA_DATA)
            }
        }
        if (resultCode == 0 || data == null) {
            FeedbackRecorder.setFailed("missing screen-capture permission")
            stopSelf()
            return START_NOT_STICKY
        }

        // The service must be foreground (with the mediaProjection type) BEFORE
        // the MediaProjection is acquired — required on Android 14+.
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startRecording(resultCode, data)
        return START_NOT_STICKY
    }

    /** Promote to a foreground service. Returns false (and publishes a failure)
     *  if the system refuses the foreground start. */
    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.feedback_recording_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FeedbackRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.feedback_recording_title))
            .setContentText(getString(R.string.feedback_recording_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.feedback_recording_stop),
                stopIntent,
            )
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (e: Exception) {
            FeedbackRecorder.setFailed("could not start the recording service")
            false
        }
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, data)
                ?: error("could not acquire the screen-capture session")
            projection = mp
            mp.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            val dm = resources.displayMetrics
            // Downscale so the longer edge is <= 1280 px — keeps the clip small
            // (the worker caps uploads at 30 MB) and within H.264 encoder
            // limits. Dimensions are rounded to even numbers.
            val scale = minOf(1f, 1280f / maxOf(dm.widthPixels, dm.heightPixels))
            val width = (dm.widthPixels * scale).toInt() / 2 * 2
            val height = (dm.heightPixels * scale).toInt() / 2 * 2

            val out = File(cacheDir, "feedback-${System.currentTimeMillis()}.mp4")
            videoFile = out

            val rec = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(out.absolutePath)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setVideoSize(width, height)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(3_500_000)
            rec.prepare()

            virtualDisplay = mp.createVirtualDisplay(
                "feedback-capture",
                width,
                height,
                dm.densityDpi,
                0, // a projection-backed display mirrors implicitly — no flags
                rec.surface,
                null,
                null,
            )
            rec.start()
            recorder = rec
            startedAt = System.currentTimeMillis()
            FeedbackRecorder.setRecording()
        } catch (e: Exception) {
            FeedbackRecorder.setFailed(e.message ?: "could not start recording")
            cleanup()
            stopForegroundAndSelf()
        }
    }

    private fun stopRecording() {
        if (stopping) return
        stopping = true

        val out = videoFile
        val duration = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
        val stopped = try {
            recorder?.stop()
            true
        } catch (e: Exception) {
            // MediaRecorder.stop() throws if too few frames were captured.
            false
        }
        cleanup()
        if (stopped && out != null && out.exists() && out.length() > 0L) {
            // Demux the audio track off the main thread, then publish the result.
            val cache = cacheDir
            Thread {
                val audio = File(cache, "feedback-audio-${System.currentTimeMillis()}.m4a")
                FeedbackRecorder.setDone(
                    FeedbackRecording(out, demuxAudioTrack(out, audio), duration),
                )
            }.start()
        } else {
            out?.delete()
            FeedbackRecorder.setFailed("recording incomplete")
        }
        stopForegroundAndSelf()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Projection revoked externally (system / another app) — finalize
            // the recording rather than leaving the service stuck.
            stopRecording()
        }
    }

    private fun cleanup() {
        runCatching { recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    companion object {
        private const val CHANNEL_ID = "feedback_recording"
        private const val NOTIF_ID = 4711
        private const val ACTION_STOP = "io.github.sceneview.demo.feedback.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        /** Start recording with a granted MediaProjection token. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FeedbackRecordingService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }

        /** Stop an in-progress recording. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, FeedbackRecordingService::class.java)
                    .setAction(ACTION_STOP),
            )
        }
    }
}
