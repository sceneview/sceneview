package io.github.sceneview.demo.demos

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.shareFile
import io.github.sceneview.demo.theme.SceneViewTokens
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberSurfaceMirrorer
import io.github.sceneview.utils.SurfaceMirrorer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Video Recording demo — records the rendered scene to an MP4 **in-app**, with no
 * MediaProjection (#2626).
 *
 * The scene side is two lines: `rememberSurfaceMirrorer()` +
 * `SceneView(surfaceMirrorer = ...)`. The Record button then points a [MediaRecorder]'s
 * input surface at the scene with
 * [SurfaceMirrorer.startMirroring][io.github.sceneview.utils.SurfaceMirrorer.startMirroring];
 * Stop tears it down with `stopMirroring` + `recorder.stop()`. What lands in the MP4 is
 * exactly what Filament rendered — the orbiting helmet — and none of the Compose UI
 * (no FAB, no scaffold), because only the scene's frames are mirrored. No system consent
 * dialog, no `mediaProjection` foreground service.
 *
 * Recordings are saved to the app's external files dir (`Android/data/<pkg>/files/recordings/`)
 * so no storage permission is needed; the saved path + size surface in the controls sheet
 * for verification.
 */
@Composable
fun VideoRecordingDemo(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // The whole recording feature on the scene side: one remembered mirrorer, one parameter.
    val surfaceMirrorer = rememberSurfaceMirrorer()

    // Active recording session (null = idle) + last completed file for the status line.
    var recording by remember { mutableStateOf<RecordingSession?>(null) }
    var lastSaved by remember { mutableStateOf<File?>(null) }

    var failed by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.demo_video_recording_share)
    val savedUri = remember(lastSaved) {
        lastSaved?.let { file ->
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull()
        }
    }

    // Never leak a recorder: leaving the demo mid-recording finalizes the file.
    DisposableEffect(surfaceMirrorer) {
        onDispose {
            recording?.let { stopRecording(surfaceMirrorer, it) }
            recording = null
        }
    }

    // Slow hero orbit so the recording provably captures motion, not a still.
    val cameraManipulator = rememberHeroOrbitCameraManipulator(
        trigger = modelInstance != null,
        radius = 2.2f,
        yHeight = 0f,
        durationMillis = 20_000,
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_video_recording_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            Text(
                text = stringResource(R.string.demo_video_recording_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lastSaved?.let { file ->
                Text(
                    text = stringResource(
                        R.string.demo_video_recording_saved,
                        file.name,
                        file.length() / 1024,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        bottomOverlay = {
            DemoStatusBanner(
                text = stringResource(when {
                    failed || (lastSaved != null && savedUri == null) -> R.string.demo_video_recording_failed
                    recording != null -> R.string.demo_video_recording_recording
                    lastSaved != null -> R.string.demo_video_recording_ready
                    else -> R.string.demo_video_recording_idle
                }),
                tone = when {
                    failed || (lastSaved != null && savedUri == null) -> DemoStatusTone.Blocked
                    recording != null -> DemoStatusTone.Progress
                    else -> DemoStatusTone.Guidance
                },
            )
            if (recording == null && lastSaved != null) {
                Row(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                ) {
                    FilledTonalButton(enabled = savedUri != null, onClick = {
                        savedUri?.let { uri ->
                            failed = runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            }.isFailure
                        }
                    }) { Text(stringResource(R.string.demo_video_recording_play)) }
                    FilledTonalButton(enabled = savedUri != null, onClick = {
                        savedUri?.let { uri ->
                            failed = !shareFile(context, uri, "video/mp4", shareTitle)
                        }
                    }) { Text(shareTitle) }
                }
            }
            // Record / Stop toggle — the demo's primary action, so it lives in the
            // scaffold's bottom slot (#2779). It keeps the START edge because the
            // Settings FAB owns the bottom-END corner; the slot, not a constant here,
            // supplies the system-bar inset.
            ExtendedFloatingActionButton(
                onClick = {
                    recording?.let { active ->
                        failed = !stopRecording(surfaceMirrorer, active)
                        lastSaved = active.outputFile.takeIf { !failed }
                        recording = null
                    } ?: run {
                        recording = startRecording(context, surfaceMirrorer)
                        failed = recording == null
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (recording != null) {
                            Icons.Filled.Stop
                        } else {
                            Icons.Filled.Videocam
                        },
                        contentDescription = null,
                    )
                },
                text = {
                    Text(
                        text = if (recording != null) {
                            stringResource(R.string.demo_video_recording_stop)
                        } else {
                            stringResource(R.string.demo_video_recording_record)
                        },
                    )
                },
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(SceneViewTokens.Space.md),
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                environment = rememberModelDemoEnvironment(environmentLoader),
                cameraManipulator = cameraManipulator,
                surfaceMirrorer = surfaceMirrorer,
            ) {
                modelInstance?.let { instance ->
                    ModelNode(modelInstance = instance, scaleToUnits = 1f)
                }
            }

            LoadingScrim(
                loading = modelInstance == null,
                label = stringResource(R.string.demo_video_recording_loading),
            )
        }
    }
}

/**
 * An in-flight recording — the [MediaRecorder], its captured input [android.view.Surface],
 * and the MP4 it writes.
 *
 * The surface is captured **once**: `MediaRecorder.getSurface()` may return a new Java
 * object per call (wrapping the same native surface), and `SurfaceMirrorer` identifies
 * mirrors by [android.view.Surface] instance — `stopMirroring` must receive the same
 * instance `startMirroring` did.
 */
private data class RecordingSession(
    val recorder: MediaRecorder,
    val surface: android.view.Surface,
    val outputFile: File,
)

/**
 * Configures a video-only [MediaRecorder] (720p H.264 MP4, no audio → no permission),
 * mirrors the scene onto its input surface, and starts it.
 *
 * Returns `null` when the recorder fails to prepare/start (e.g. emulator without a
 * hardware encoder) — the demo stays usable, the button simply doesn't latch.
 */
private fun startRecording(
    context: Context,
    surfaceMirrorer: SurfaceMirrorer,
): RecordingSession? {
    val externalDir = context.getExternalFilesDir(null) ?: return null
    val outputDir = File(externalDir, "recordings")
    if (!outputDir.isDirectory && !outputDir.mkdirs()) return null
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val outputFile = File(outputDir, "scene_$timestamp.mp4")

    @Suppress("DEPRECATION")
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        MediaRecorder()
    }
    // Captured before the try so the failure path stops exactly the surface THIS call
    // started — never sibling mirrors another recording may own (SurfaceMirrorer is
    // multi-surface, and stopping all of them here would tear down unrelated captures).
    var startedSurface: android.view.Surface? = null
    return runCatching {
        recorder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
            setVideoEncodingBitRate(8_000_000)
            setVideoFrameRate(30)
            setOutputFile(outputFile.absolutePath)
            prepare()
        }
        // recorder.surface is only valid after prepare(). Capture it ONCE — see
        // [RecordingSession] — and letterbox the scene into the 720p frame
        // (width/height = the MediaRecorder video size).
        val recorderSurface = recorder.surface
        startedSurface = recorderSurface
        surfaceMirrorer.startMirroring(recorderSurface, width = VIDEO_WIDTH, height = VIDEO_HEIGHT)
        recorder.start()
        RecordingSession(recorder, recorderSurface, outputFile)
    }.getOrElse { e ->
        Log.e(TAG, "Failed to start recording", e)
        // Stop only the surface we started in this call — not every mirrored surface.
        startedSurface?.let { surfaceMirrorer.stopMirroring(it) }
        runCatching { recorder.release() }
        null
    }
}

/** Stops mirroring first (idempotent), then finalizes the MP4. */
private fun stopRecording(surfaceMirrorer: SurfaceMirrorer, session: RecordingSession): Boolean {
    runCatching { surfaceMirrorer.stopMirroring(session.surface) }
    val stopped = runCatching { session.recorder.stop() }
        .onFailure { e -> Log.e(TAG, "Failed to stop recorder", e) }
    runCatching { session.recorder.release() }
    return stopped.isSuccess && session.outputFile.length() > 0L
}

private const val TAG = "VideoRecordingDemo"
private const val VIDEO_WIDTH = 1280
private const val VIDEO_HEIGHT = 720
