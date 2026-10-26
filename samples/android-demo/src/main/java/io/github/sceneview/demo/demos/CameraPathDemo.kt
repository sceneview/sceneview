@file:OptIn(ExperimentalSceneViewApi::class)

package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.sceneview.Aabb
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.camera.CameraPathPlayer
import io.github.sceneview.camera.CameraPosePath
import io.github.sceneview.camera.applyPose
import io.github.sceneview.camera.rememberCameraPathPlayer
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.StageSkyFog
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.common.rememberStageSkybox
import io.github.sceneview.demo.common.themedStageSky
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.GlassIconButton
import io.github.sceneview.demo.ui.GlassSurface
import io.github.sceneview.material.setColor
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.model.model
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView
import io.github.sceneview.toAabb
import io.github.sceneview.verticalFovDegreesForFocalLength
import java.util.Locale

/**
 * **Camera path** — a keyframed camera move played around an animated subject.
 *
 * The camera is driven by [CameraPosePath.orbit]: one turn round the fox, framed from the
 * subject's measured bounding box and the camera's real field of view, so the whole subject stays
 * in frame at every azimuth. A [CameraPathPlayer] owns the playhead; the scene's `onFrame` advances
 * it by the time between frames and writes the pose to the camera. The timeline bar plays, pauses
 * and scrubs the same playhead, so a scrubbed frame and a played frame at the same time are the
 * same pose.
 *
 * The camera manipulator is off (`cameraManipulator = null`): the path owns the camera, and a
 * manipulator would overwrite the scripted pose on the next frame.
 *
 * In QA mode the move starts paused at its first keyframe and the fox holds its rest pose (no
 * clip plays), so a capture is deterministic.
 */
@Composable
fun CameraPathDemo(onBack: () -> Unit) {
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(
            title = stringResource(R.string.demo_camera_path_title),
            onBack = onBack,
        )
        return
    }

    val engine = rememberEngine()
    val view = rememberView(engine)
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val cameraNode = rememberCameraNode(engine)
    val instance = rememberModelInstance(modelLoader, SUBJECT_ASSET)
    val renderInvalidator = rememberRenderInvalidator()

    val sky = themedStageSky()
    val skybox = rememberStageSkybox(engine, sky, renderInvalidator::requestRender)
    StageSkyFog(view, sky, renderInvalidator::requestRender)
    val firstFrame = rememberFirstFrameState(engine)
    firstFrame.holdUntilModels(modelLoader, instancesLoaded = instance != null)
    val baseEnvironment = rememberModelDemoEnvironment(environmentLoader, firstFrame)
    val environment = remember(baseEnvironment, skybox) { baseEnvironment.copy(skybox = skybox) }

    val floorMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(sky.floor, metallic = 0f, roughness = FLOOR_ROUGHNESS)
    }
    LaunchedEffect(floorMaterial, sky.floor) {
        floorMaterial.setColor(sky.floor)
        renderInvalidator.requestRender()
    }

    // The subject's world bounds: grounded on y = 0, centred on the vertical axis, and scaled so
    // its largest side is SUBJECT_UNITS — the glTF box scaled by that same factor.
    val bounds = remember(instance) {
        val extents = instance?.let { runCatching { it.model.boundingBox.toAabb() }.getOrNull() }
            ?.takeUnless { it.isEmpty }?.extents
        val largest = extents?.let { maxOf(it.x, it.y, it.z) } ?: 0f
        val size = if (extents != null && largest > 0f) {
            extents * (SUBJECT_UNITS / largest)
        } else {
            Position(SUBJECT_UNITS * FALLBACK_DEPTH, SUBJECT_UNITS, SUBJECT_UNITS * FALLBACK_DEPTH)
        }
        Aabb(center = Position(0f, size.y / 2f, 0f), halfExtent = size / 2f)
    }
    // The 3D view's own aspect, not the screen's: the orbit is framed for the viewport the camera
    // renders into. Until the view is laid out, a phone-portrait guess.
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val aspect = remember(viewSize) {
        if (viewSize.width > 0 && viewSize.height > 0) {
            viewSize.width.toDouble() / viewSize.height
        } else {
            FALLBACK_ASPECT
        }
    }
    // Remembered on its inputs: a timeline rebuilt on every composition would redo the spline.
    val path = remember(bounds, aspect) {
        CameraPosePath.orbit(
            bounds = bounds,
            // The default camera's 28 mm lens, the projection rememberCameraNode sets up.
            verticalFovDegrees = verticalFovDegreesForFocalLength(LENS_FOCAL_LENGTH_MM),
            aspect = aspect,
            durationSeconds = ORBIT_SECONDS,
            elevationDegrees = ORBIT_ELEVATION_DEGREES,
            startAzimuthDegrees = ORBIT_START_AZIMUTH_DEGREES,
        )
    }
    val player = rememberCameraPathPlayer(path, autoPlay = !DemoSettings.qaMode)
    // Place the camera before the first frame: `onFrame` only fires once a frame was presented.
    LaunchedEffect(path) { cameraNode.applyPose(player.pose) }

    DemoScaffold(
        title = stringResource(R.string.demo_camera_path_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        sceneReady = firstFrame.sceneReady,
        contentIssue = firstFrame.contentIssue,
        onReset = {
            player.seekTo(0f)
            player.play()
        },
        themedStage = true,
        bottomOverlay = {
            CameraPathTimeline(
                player = player,
                modifier = Modifier
                    // The timeline goes with the dock: one small gutter above it.
                    .offset(y = (dockGap - SceneViewTokens.Space.sm).coerceAtLeast(0.dp))
                    .padding(horizontal = SceneViewTokens.Space.md)
                    .widthIn(max = SceneViewTokens.ArOverlay.maxWidth)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
            )
        },
    ) {
        SceneView(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = it },
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            environment = environment,
            renderInvalidator = renderInvalidator,
            // Every vsync: the path moves the camera on its own, with nothing the library sees.
            frameRatePolicy = FrameRatePolicy.Continuous(),
            // The orbit is computed from the subject's own bounds; re-centring would move it.
            autoCenterContent = false,
            cameraNode = cameraNode,
            // The path owns the camera; a manipulator would overwrite the scripted pose.
            cameraManipulator = null,
            onFrame = { frameTimeNanos ->
                cameraNode.applyPose(player.advanceFrame(frameTimeNanos))
                firstFrame.onFrame(frameTimeNanos)
            },
        ) {
            PlaneNode(
                size = Size(x = FLOOR_SIZE, y = 0f, z = FLOOR_SIZE),
                normal = Direction(y = 1f),
                materialInstance = floorMaterial,
            )
            instance?.let {
                ModelNode(
                    modelInstance = it,
                    scaleToUnits = SUBJECT_UNITS,
                    centerOrigin = Position(y = -1f),
                    autoAnimate = false,
                    // QA mode: no clip, the rest pose, so every capture shows the same fox.
                    animationName = SUBJECT_CLIP.takeUnless { DemoSettings.qaMode },
                )
            }
        }
    }
}

/** Play / pause, a scrubber and a clock, on one glass bar. */
@Composable
private fun CameraPathTimeline(player: CameraPathPlayer, modifier: Modifier = Modifier) {
    val chrome = LocalStageChrome.current
    // Dragging pauses the move so the thumb and the camera stay under the finger; releasing
    // resumes it only when it was playing before the drag.
    var resumeAfterScrub by remember { mutableStateOf(false) }
    val duration = player.durationSeconds
    val playLabel = stringResource(
        if (player.isPlaying) R.string.camera_path_pause else R.string.camera_path_play,
    )
    val timelineLabel = stringResource(R.string.camera_path_timeline)
    GlassSurface(modifier = modifier) {
        Row(
            modifier = Modifier.padding(
                horizontal = SceneViewTokens.Space.xs,
                vertical = SceneViewTokens.Space.xs,
            ),
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                icon = if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = playLabel,
                onClick = { player.toggle() },
            )
            Slider(
                value = player.progress,
                onValueChange = { fraction ->
                    if (player.isPlaying) {
                        resumeAfterScrub = true
                        player.pause()
                    }
                    player.seekTo(fraction * duration)
                },
                onValueChangeFinished = {
                    if (resumeAfterScrub) player.play()
                    resumeAfterScrub = false
                },
                enabled = duration > 0f,
                colors = SliderDefaults.colors(
                    thumbColor = chrome.onGlass,
                    activeTrackColor = chrome.onGlass,
                    inactiveTrackColor = chrome.track,
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = timelineLabel },
            )
            Text(
                text = "${formatClock(player.seconds)} / ${formatClock(duration)}",
                style = SceneViewTokens.Type.caption.copy(fontFeatureSettings = TABULAR_FIGURES),
                color = chrome.onGlass,
                modifier = Modifier.padding(end = SceneViewTokens.Space.sm),
            )
        }
    }
}

/** `m:ss`, rounded down to the second. */
internal fun formatClock(seconds: Float): String {
    val whole = if (seconds.isFinite() && seconds > 0f) seconds.toInt() else 0
    return String.format(Locale.US, "%d:%02d", whole / SECONDS_PER_MINUTE, whole % SECONDS_PER_MINUTE)
}

private const val SUBJECT_ASSET = "models/khronos_fox.glb"
private const val SUBJECT_CLIP = "Survey"
private const val SUBJECT_UNITS = 1f
private const val FALLBACK_DEPTH = 0.6f
private const val FALLBACK_ASPECT = 0.47
private const val LENS_FOCAL_LENGTH_MM = 28.0
private const val ORBIT_SECONDS = 16f
private const val ORBIT_ELEVATION_DEGREES = 18f
private const val ORBIT_START_AZIMUTH_DEGREES = 30f
private const val FLOOR_SIZE = 40f
private const val FLOOR_ROUGHNESS = 0.62f
private const val SECONDS_PER_MINUTE = 60
private const val TABULAR_FIGURES = "tnum"
