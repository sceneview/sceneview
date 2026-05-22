package io.github.sceneview.demo.feedback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import java.util.concurrent.Executors

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

    /** Encoder surface dimensions, fixed at [startRecording]. The recorder's
     *  video size cannot change mid-clip, so on a rotation the VirtualDisplay is
     *  resized to the rotated screen aspect *fitted into* this surface — the
     *  clip stays correctly proportioned (deliberate letterboxing) instead of
     *  stretched (#2030). */
    private var encoderWidth = 0
    private var encoderHeight = 0

    /** True once the recorder auto-stopped at the size/duration cap. */
    private var cappedHit = false

    /** Session id of this recording — captured before the post-stop demux so a
     *  stale callback from a superseded recording is dropped (#1933 review). */
    private var session = 0L

    /** Single-thread executor for the post-stop audio demux. Cancelled in
     *  [onDestroy] so a demux can never outlive the service (#1933 review). */
    private val demuxExecutor = Executors.newSingleThreadExecutor()

    /** Guards [stopRecording] against the notification / pill / projection-callback
     *  all triggering a stop. */
    private var stopping = false

    /** True once the system / another app revoked the MediaProjection
     *  ([MediaProjection.Callback.onStop]). Lets [stopRecording] tell a
     *  too-short-capture-after-revocation apart from a genuine encoder failure
     *  so the user gets an accurate message (#2030). */
    private var projectionRevoked = false

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
                // DEFAULT (not LOW) so the stop control is more prominent — the
                // notification is a documented stop affordance and on a
                // full-screen demo it may be the only one if the user denied
                // POST_NOTIFICATIONS-adjacent visibility (#1933 review).
                NotificationManager.IMPORTANCE_DEFAULT,
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
            rec.setVideoEncodingBitRate(VIDEO_BITRATE)
            // Hard caps so the clip can never exceed the worker's 30 MB upload
            // limit (a silent 413). The size cap leaves headroom for the
            // multipart envelope and the separately-uploaded demuxed audio; the
            // duration cap is a secondary guard. Both must be set after
            // setOutputFile and before prepare(). On the cap being approached /
            // reached the recorder auto-stops gracefully (#1933 review).
            rec.setMaxFileSize(MAX_FILE_SIZE_BYTES)
            rec.setMaxDuration(MAX_DURATION_MS)
            rec.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING,
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED,
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> {
                        cappedHit = true
                        // Finish the clip on the main thread — stopRecording
                        // touches the MediaRecorder which is not thread-safe.
                        Handler(Looper.getMainLooper()).post { stopRecording() }
                    }
                }
            }
            rec.prepare()
            rec.start()

            // Create the VirtualDisplay only AFTER start() succeeds — an
            // encoder-busy start() failure must not leave a display feeding a
            // dead recorder (#1933 review).
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
            recorder = rec
            encoderWidth = width
            encoderHeight = height
            startedAt = System.currentTimeMillis()
            session = FeedbackRecorder.beginSession()
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
        val capped = cappedHit
        val recordingSession = session
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
            // Demux the audio track off the main thread, then publish the
            // result. The work runs on a managed executor (cancelled in
            // onDestroy) and is wrapped so an exception is reported as a
            // recording failure rather than crashing the process (#1933 review).
            val cache = cacheDir
            demuxExecutor.execute {
                runCatching {
                    val audio = File(cache, "feedback-audio-${System.currentTimeMillis()}.m4a")
                    FeedbackRecorder.setDone(
                        FeedbackRecording(out, demuxAudioTrack(out, audio), duration, capped),
                        recordingSession,
                    )
                }.onFailure {
                    // Demux failed — the video alone is still useful; publish it
                    // without an audio track rather than losing the recording.
                    FeedbackRecorder.setDone(
                        FeedbackRecording(out, null, duration, capped),
                        recordingSession,
                    )
                }
            }
        } else {
            out?.delete()
            // A revoked projection that yields a too-short clip is not a
            // genuine failure — the user (or the system) stopped the capture
            // before enough frames were recorded. Tell them what happened so
            // the generic "recording didn't work" copy isn't misleading (#2030).
            val reason = if (projectionRevoked) {
                FeedbackRecorder.FAILURE_REVOKED
            } else {
                "recording incomplete"
            }
            FeedbackRecorder.setFailed(reason, recordingSession)
        }
        stopForegroundAndSelf()
    }

    /**
     * The screen was rotated (or otherwise reconfigured) mid-recording. The
     * MediaRecorder's encoder surface dimensions are fixed at [startRecording]
     * and cannot change, but the VirtualDisplay is a logical mirror that *can*
     * be resized live. Without this, the rotated screen keeps being mirrored at
     * the original portrait/landscape dimensions, so the post-rotation portion
     * of the clip is stretched.
     *
     * Re-fit the VirtualDisplay to the current screen aspect, scaled to fit
     * inside the fixed encoder surface — the rotated content stays correctly
     * proportioned with deliberate, centred letterboxing instead of distortion
     * (#2030). Runs on the main thread (service callback); [VirtualDisplay.resize]
     * is a cheap, thread-confined call.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val display = virtualDisplay ?: return
        if (encoderWidth <= 0 || encoderHeight <= 0) return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        if (screenW <= 0 || screenH <= 0) return
        // Fit the current screen aspect inside the fixed encoder surface.
        val fit = minOf(
            encoderWidth.toFloat() / screenW,
            encoderHeight.toFloat() / screenH,
        )
        val fittedW = (screenW * fit).toInt().coerceIn(2, encoderWidth)
        val fittedH = (screenH * fit).toInt().coerceIn(2, encoderHeight)
        runCatching { display.resize(fittedW, fittedH, dm.densityDpi) }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Projection revoked externally (system / another app) — note it so
            // a too-short clip is reported as a revocation rather than a
            // generic failure, then finalize rather than leaving the service
            // stuck (#2030).
            projectionRevoked = true
            stopRecording()
        }
    }

    private fun cleanup() {
        runCatching { recorder?.setOnInfoListener(null) }
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
        // Cancel any in-flight demux so it cannot outlive the service.
        demuxExecutor.shutdownNow()
    }

    companion object {
        private const val CHANNEL_ID = "feedback_recording"
        private const val NOTIF_ID = 4711
        private const val ACTION_STOP = "io.github.sceneview.demo.feedback.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        private const val VIDEO_BITRATE = 3_500_000

        /**
         * Hard cap on the recorded mp4 (25 MB). The worker rejects uploads over
         * 30 MB with a 413; this leaves ~5 MB of headroom for the multipart
         * envelope + the separately-uploaded demuxed AAC audio track + metadata
         * (#2032 — the previous 28 MB cap was too close to the 30 MB ceiling).
         */
        private const val MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024

        /** Secondary cap on the clip length (~120 s). */
        private const val MAX_DURATION_MS = 120_000

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
