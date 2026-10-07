package io.github.sceneview.demo.demos

import android.view.MotionEvent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import io.github.sceneview.rememberMediaPlayer
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView
import io.github.sceneview.sample.LifecycleAwareLaunchedEffect
import io.github.sceneview.sample.rememberMaterialInstance
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/**
 * Media: a framed drawable, a muted video and a floating sprite, each named by a TextNode.
 * Orbit to see fixed surfaces go edge-on while captions and the badge face the camera.
 * TextNode and BillboardNode require cameraPositionProvider; their provider is construction-only,
 * so key those nodes on Face camera. VideoNode(player) needs a prepared, owned MediaPlayer:
 * rememberMediaPlayer prepares synchronously and returns null on failure. Never wait on null.
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
    val poster = rememberGallerySprite(layout.SCREEN_SIZE.x / layout.SCREEN_SIZE.y, SceneViewColors.SurfaceLight)
    val captions = listOf(R.string.demo_two_d_in_three_d_image_node,
        R.string.demo_two_d_in_three_d_video_node, R.string.demo_two_d_in_three_d_billboard_node)
    // TextNode's bitmap is 512 x 128 px. Twice the headline size is the largest that keeps the
    // longest name, "BillboardNode", inside it in monospace.
    val fontSize = MaterialTheme.typography.headlineMedium.fontSize.value * 2f
    val player = rememberMediaPlayer(assetFileLocation = "videos/sample.mp4", autoStart = false,
        isLooping = true)
    var videoFailed by remember { mutableStateOf(player == null) }
    var attached by remember { mutableStateOf(false) }
    var seekFinished by remember { mutableStateOf(!qa) }
    var seekFallback by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        player?.setVolume(0f, 0f)
        player?.setOnErrorListener { _, _, _ -> videoFailed = true; playing = false; true }
        onDispose { player?.setOnErrorListener(null) }
    }
    LaunchedEffect(player, attached) {
        val seekable = attached && qa && !videoFailed
        if (player != null && seekable) {
            player.setOnSeekCompleteListener { seekFinished = true; invalidator.requestRender() }
            runCatching { player.seekTo(QA_FRAME_MILLIS) }.onFailure { seekFallback = true }
            delay(SEEK_TIMEOUT_MILLIS)
            if (!seekFinished && !videoFailed) seekFallback = true
            if (seekFallback && !videoFailed) playing = true
            player.setOnSeekCompleteListener(null)
        }
    }
    LifecycleAwareLaunchedEffect(player, attached, playing, videoFailed) {
        if (player == null || !attached || videoFailed) return@LifecycleAwareLaunchedEffect
        try {
            runCatching { if (playing) player.start() else if (player.isPlaying) player.pause() }
                .onFailure { videoFailed = true }
            invalidator.requestRender()
            awaitCancellation()
        } finally {
            runCatching { if (player.isPlaying) player.pause() }
        }
    }
    // Preparation has already completed (or failed) before this composition. QA also waits for
    // its paused seek; errors and a decoder timeout must never leave the loading cover up.
    val rendered = remember(firstFrame, player) { derivedStateOf {
        firstFrame.rendered.value && (videoFailed || !qa || seekFinished || seekFallback)
    } }
    val ready = remember(firstFrame, rendered) { derivedStateOf {
        rendered.value && firstFrame.sceneReady.value
    } }
    // `playing` is the request; a null or failed player leaves it true with nothing on screen.
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
            Text(stringResource(R.string.demo_two_d_in_three_d_media_explainer),
                style = MaterialTheme.typography.bodyMedium)
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
                        } else if (player != null && !videoFailed) {
                            VideoNode(player = player, size = exhibit.size, position = layout.CONTENT_OFFSET,
                                apply = { attached = true })
                        } else {
                            // No decoder, no file: the screen shows a still, never an empty frame.
                            ImageNode(bitmap = poster, size = exhibit.size, position = layout.CONTENT_OFFSET)
                        }
                    }
                } }
                // Only these nodes are recreated: their immutable provider cannot be swapped in place.
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

/**
 * A remembered Canvas sprite made entirely from the existing palette and sizing tokens: the badge,
 * and with a [ground] and a [widthOverHeight] the still a video that cannot play leaves behind.
 */
@Composable
private fun rememberGallerySprite(widthOverHeight: Float = 1f, ground: Color? = null): Bitmap {
    val density = LocalDensity.current
    val size = with(density) { SceneViewTokens.Space.x4l.roundToPx() }
    val radius = with(density) { SceneViewTokens.Space.x3l.toPx() / 2f }
    val inset = with(density) { SceneViewTokens.Space.lg.toPx() }
    return remember(size, radius, inset, widthOverHeight, ground) {
        val width = (size * widthOverHeight).toInt()
        Bitmap.createBitmap(width, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val centerX = width / 2f
            val centerY = size / 2f
            ground?.let { canvas.drawColor(it.toArgb()) }
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
