// Compose entry point for surface mirroring (#2626). Lives in its own file — not SceneView.kt,
// which is at detekt's TooManyFunctions file cap — same package, so imports are unaffected.
package io.github.sceneview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.sceneview.utils.SurfaceMirrorer

/**
 * Creates and remembers a [SurfaceMirrorer] for in-app video recording of the scene.
 *
 * Pass it to `SceneView(surfaceMirrorer = ...)` or `ARSceneView(surfaceMirrorer = ...)`, then
 * mirror the rendered frames onto a recording surface — no MediaProjection consent dialog, no
 * foreground service, and no overlay UI in the frame:
 *
 * ```kotlin
 * val surfaceMirrorer = rememberSurfaceMirrorer()
 * SceneView(surfaceMirrorer = surfaceMirrorer, ...)
 *
 * // Start recording:
 * val recorder = MediaRecorder(context).apply {
 *     setVideoSource(MediaRecorder.VideoSource.SURFACE)
 *     setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
 *     setVideoEncoder(MediaRecorder.VideoEncoder.H264)
 *     setVideoSize(1280, 720)
 *     setOutputFile(outputFile.absolutePath)
 *     prepare()
 * }
 * surfaceMirrorer.startMirroring(recorder.surface, width = 1280, height = 720)
 * recorder.start()
 *
 * // Stop recording:
 * surfaceMirrorer.stopMirroring(recorder.surface)
 * recorder.stop()
 * recorder.release()
 * ```
 *
 * The mirrorer survives recompositions; the scene's disposal releases any active mirrors.
 *
 * @see SurfaceMirrorer
 */
@Composable
fun rememberSurfaceMirrorer(
    creator: () -> SurfaceMirrorer = { SurfaceMirrorer() }
) = remember(creator)
