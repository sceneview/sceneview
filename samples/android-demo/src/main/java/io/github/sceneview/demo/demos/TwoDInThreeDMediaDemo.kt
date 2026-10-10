package io.github.sceneview.demo.demos

import android.view.MotionEvent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import io.github.sceneview.SceneView
import io.github.sceneview.createDefaultCameraManipulator
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.StageSkyFog
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.common.rememberStageSkybox
import io.github.sceneview.demo.common.themedStageSky
import io.github.sceneview.demo.demos.internal.CalloutLayout
import io.github.sceneview.demo.demos.internal.StreamPhase
import io.github.sceneview.demo.demos.internal.fitInside
import io.github.sceneview.demo.demos.internal.videoTransport
import io.github.sceneview.demo.isDemoCompactHeight
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.demoSceneFrame
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.themedStageChrome
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView
import io.github.sceneview.sample.LifecycleAwareLaunchedEffect
import io.github.sceneview.sample.rememberMaterialInstance
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/**
 * Media: a framed drawable, a muted streamed video and a floating sprite, each named by a TextNode.
 * Orbit to see fixed surfaces go edge-on while captions and the badge face the camera.
 * TextNode and BillboardNode face the camera only with a cameraPositionProvider. Clearing it leaves
 * a node as it was last turned, so those nodes are keyed on Face camera: rebuilt, they hang at
 * their resting yaw again. VideoNode(player) needs a prepared MediaPlayer:
 * `rememberMediaPlayer` prepares the URL off the main thread ([rememberStreamedVideo]), and until
 * it has, or when it cannot, the screen says so on a still. It never plays a stand-in.
 * QA seeks the attached video to one second while paused; a decoder that cannot finish that seek
 * falls back to muted playback after a bounded wait, so the rest of the gallery remains usable.
 */
@Composable
fun TwoDInThreeDMediaDemo(onBack: () -> Unit) {
    val title = stringResource(R.string.demo_two_d_in_three_d_title)
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = title, onBack = onBack)
        return
    }
    val layout = CalloutLayout
    val qa = DemoSettings.qaMode
    // A phone held sideways: the strip above the controls is too thin to give a line to a hint,
    // and the gallery hangs on one line there.
    val strip = isDemoCompactHeight()
    val gallery = layout.gallery(strip)
    val target = layout.mediaTarget(strip)
    var playing by remember { mutableStateOf(!qa) }
    var faceCamera by remember { mutableStateOf(true) }
    var cameraMoved by remember { mutableStateOf(false) }
    val touchedCamera = remember { booleanArrayOf(false) }
    val engine = rememberEngine()
    val view = rememberView(engine)
    val materials = rememberMaterialLoader(engine)
    val environments = rememberEnvironmentLoader(engine)
    val camera = rememberCameraNode(engine)
    val invalidator = rememberRenderInvalidator()
    val firstFrame = rememberFirstFrameState(engine)
    val sky = themedStageSky()
    val chrome = themedStageChrome()
    val skybox = rememberStageSkybox(engine, sky, invalidator::requestRender)
    StageSkyFog(view, sky, invalidator::requestRender)
    LaunchedEffect(view) {
        view.bloomOptions = view.bloomOptions.apply { enabled = false }
        invalidator.requestRender()
    }
    val studio = rememberModelDemoEnvironment(environments, firstFrame)
    val environment = remember(studio, skybox) { studio.copy(skybox = skybox) }
    val floor = rememberMaterialInstance(materials, sky.floor, 0f, 0.62f)
    val frame = rememberMaterialInstance(materials, SceneViewColors.SurfaceDim, 0.2f, 0.45f)
    var eye by remember { mutableStateOf(Position()) }
    val badge = rememberGallerySprite()
    val screenAspect = layout.SCREEN_SIZE.x / layout.SCREEN_SIZE.y
    val loadingStill = rememberVideoStatusStill(
        stringResource(R.string.demo_two_d_in_three_d_video_loading), null, screenAspect)
    val unavailableStill = rememberVideoStatusStill(
        stringResource(R.string.demo_two_d_in_three_d_video_unavailable),
        stringResource(R.string.demo_two_d_in_three_d_video_unavailable_detail), screenAspect)
    val captions = listOf(R.string.demo_two_d_in_three_d_image_node,
        R.string.demo_two_d_in_three_d_video_node, R.string.demo_two_d_in_three_d_billboard_node)
    // TextNode's bitmap is 512 x 128 px. Twice the headline size is the largest that keeps the
    // longest name, "BillboardNode", inside it in monospace.
    val fontSize = MaterialTheme.typography.headlineMedium.fontSize.value * 2f
    val video = rememberStreamedVideo(MEDIA_VIDEO_URL)
    val player = video.player
    val videoFailed = video.phase == StreamPhase.Failed
    var attached by remember { mutableStateOf(false) }
    var seekFinished by remember { mutableStateOf(!qa) }
    var seekFallback by remember { mutableStateOf(false) }
    // A video paused before it ever played has no picture yet: one seek gives it its first frame.
    val pictured = remember { booleanArrayOf(false) }
    LaunchedEffect(player, attached) {
        val seekable = attached && qa && video.phase == StreamPhase.Ready
        if (player != null && seekable) {
            player.setOnSeekCompleteListener { seekFinished = true; invalidator.requestRender() }
            // A seek that throws has nothing to wait for: the fallback plays at once, so the
            // scene is never announced ready over a screen that has no picture coming.
            val refused = runCatching { player.seekTo(QA_FRAME_MILLIS) }.isFailure
            if (!refused) delay(SEEK_TIMEOUT_MILLIS)
            val failed = video.phase == StreamPhase.Failed
            if ((refused || !seekFinished) && !failed) seekFallback = true
            if (seekFallback && !failed) playing = true
            player.setOnSeekCompleteListener(null)
        }
    }
    LifecycleAwareLaunchedEffect(player, attached, playing, videoFailed) {
        if (player == null || !attached || videoFailed) return@LifecycleAwareLaunchedEffect
        try {
            runCatching {
                if (playing) player.start()
                else if (player.isPlaying) player.pause()
                else if (!qa && !pictured[0]) player.seekTo(0)
                pictured[0] = true
            }.onFailure { video.fail() }
            invalidator.requestRender()
            awaitCancellation()
        } finally {
            runCatching { if (player.isPlaying) player.pause() }
        }
    }
    // The scene never waits for the network: the screen shows where the stream stands. QA does
    // wait, for its paused seek; a stream that fails, a preparation or a decoder that times out
    // must never leave the loading cover up.
    val rendered = remember(firstFrame, video) { derivedStateOf {
        firstFrame.rendered.value &&
            (!qa || video.phase == StreamPhase.Failed || seekFinished || seekFallback)
    } }
    val ready = remember(firstFrame, rendered) { derivedStateOf {
        rendered.value && firstFrame.sceneReady.value
    } }
    // `playing` is the request; a failed stream leaves it true with nothing playing on screen.
    val transport = videoTransport(playing, videoFailed, qa, seekFallback)

    DemoScaffold(
        title = title, onBack = onBack, themedStage = true,
        firstFrameRendered = rendered, sceneReady = ready,
        peekHeader = if (cameraMoved || strip) null
            else stringResource(R.string.demo_two_d_in_three_d_media_hint),
        dock = listOf(
            DockItem(if (transport.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(if (transport.playing) R.string.demo_two_d_in_three_d_pause
                    else R.string.demo_two_d_in_three_d_play),
                { playing = !playing }, enabled = transport.enabled, selected = transport.playing),
            DockItem(Icons.Filled.Portrait, stringResource(R.string.demo_two_d_in_three_d_face_camera),
                { faceCamera = !faceCamera }, selected = faceCamera,
                caption = stringResource(R.string.demo_two_d_in_three_d_face_camera_caption)),
        ),
        controls = {
            Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                Text(stringResource(R.string.demo_two_d_in_three_d_media_explainer),
                    style = MaterialTheme.typography.bodyMedium)
                // CC BY asks for the title, the author and the licence wherever the work is shown.
                Text(stringResource(R.string.demo_two_d_in_three_d_video_credit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The hint leaves while the user drags. Keep the frame made with it: a new home rebuilds
            // the manipulator and cuts the camera back in the middle of that drag, a new padding
            // slides the gallery under the finger.
            val liveFrame = demoSceneFrame()
            val heldFrame = remember(maxWidth, maxHeight) { arrayOf(liveFrame) }
            if (!cameraMoved) heldFrame[0] = liveFrame
            val sceneFrame = heldFrame[0]
            val aspect = sceneFrame.restingAspect
            val home = remember(aspect, strip, DemoSettings.cameraDistance) {
                val fit = layout.cameraDistance(layout.mediaExtent(strip), aspect)
                layout.cameraHome(DemoSettings.cameraDistance ?: fit) + target
            }
            // Keyed on the home: the eye-position `rememberCameraManipulator` builds once and keeps
            // the frame of the first composition, made before the sheet has reported its height.
            val manipulator = remember(home) {
                createDefaultCameraManipulator(eyePosition = home, targetPosition = target)
            }
            SceneView(
                contentPadding = sceneFrame.contentPadding,
                modifier = Modifier.fillMaxSize(), engine = engine, view = view,
                materialLoader = materials, environmentLoader = environments, environment = environment,
                cameraNode = camera, renderInvalidator = invalidator, autoCenterContent = false,
                cameraManipulator = manipulator,
                onTouchEvent = { event, _ ->
                    if (event.actionMasked == MotionEvent.ACTION_MOVE) touchedCamera[0] = true
                    false
                },
                onFrame = { nanos ->
                    firstFrame.onFrame(nanos)
                    val current = camera.worldPosition
                    if (layout.movedPerceptibly(eye, current)) {
                        eye = current
                        if (!cameraMoved && touchedCamera[0]) cameraMoved = true
                    }
                },
            ) {
                PlaneNode(size = Size(layout.FLOOR_SIZE, layout.FLOOR_SIZE, 0f),
                    rotation = Rotation(x = -90f), materialInstance = floor,
                    apply = { isHittable = false })
                gallery.take(2).forEachIndexed { index, exhibit -> key(index) {
                    Node(position = exhibit.position, rotation = Rotation(y = exhibit.yaw)) {
                        CubeNode(size = layout.frameSize(exhibit.size), materialInstance = frame)
                        if (index == 0) {
                            ImageNode(imageResId = R.drawable.preview_geometry_light,
                                size = exhibit.size, position = layout.CONTENT_OFFSET)
                        } else if (player != null && video.phase == StreamPhase.Ready) {
                            // Construction-only size: the picture fits the screen, never stretched.
                            VideoNode(player = player, position = layout.CONTENT_OFFSET,
                                size = fitInside(exhibit.size, player.videoWidth, player.videoHeight),
                                apply = { attached = true })
                        } else {
                            // Not there yet, or not coming: the screen says which.
                            ImageNode(bitmap = if (videoFailed) unavailableStill else loadingStill,
                                size = exhibit.size, position = layout.CONTENT_OFFSET)
                        }
                    }
                } }
                // Only these nodes are recreated: a cleared provider would leave them as last turned.
                key(faceCamera) {
                    val provider: (() -> Position)? = if (faceCamera) ({ eye }) else null
                    val exhibit = gallery[2]
                    BillboardNode(bitmap = badge, widthMeters = exhibit.size.x, heightMeters = exhibit.size.y,
                        position = exhibit.position, cameraPositionProvider = provider)
                    gallery.forEachIndexed { index, item -> key(index) {
                        TextNode(text = stringResource(captions[index]), fontSize = fontSize,
                            textColor = chrome.onCard.toArgb(), backgroundColor = chrome.card.toArgb(),
                            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
                            widthMeters = layout.CAPTION_SIZE.x, heightMeters = layout.CAPTION_SIZE.y,
                            position = item.caption, cameraPositionProvider = provider,
                            apply = { rotation = Rotation(y = item.yaw) })
                    } }
                }
            }
        }
    }
}

/** A remembered Canvas sprite made entirely from the existing palette and sizing tokens. */
@Composable
private fun rememberGallerySprite(): Bitmap {
    val density = LocalDensity.current
    val size = with(density) { SceneViewTokens.Space.x4l.roundToPx() }
    val radius = with(density) { SceneViewTokens.Space.x3l.toPx() / 2f }
    val inset = with(density) { SceneViewTokens.Space.lg.toPx() }
    return remember(size, radius, inset) {
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val centerX = size / 2f
            val centerY = size / 2f
            paint.color = SceneViewColors.Primary.toArgb()
            canvas.drawCircle(centerX, centerY, radius, paint)
            canvas.rotate(45f, centerX, centerY)
            paint.color = SceneViewColors.Highlight.toArgb()
            canvas.drawRect(centerX - inset / 2, centerY - inset / 2,
                centerX + inset / 2, centerY + inset / 2, paint)
        }
    }
}

private const val QA_FRAME_MILLIS = 1_000
private const val SEEK_TIMEOUT_MILLIS = 3_000L
