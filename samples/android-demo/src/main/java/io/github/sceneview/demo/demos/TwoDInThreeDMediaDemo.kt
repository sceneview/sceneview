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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import io.github.sceneview.SceneView
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
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.demoSceneFrame
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.themedStageChrome
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraManipulator
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
    val badge = rememberGalleryBadge()
    val captions = listOf(R.string.demo_two_d_in_three_d_image_node,
        R.string.demo_two_d_in_three_d_video_node, R.string.demo_two_d_in_three_d_billboard_node)
    // TextNode's bitmap is 128 px high; derive its type size from the theme at that raster scale.
    val fontSize = MaterialTheme.typography.titleLarge.fontSize.value *
        (128f / SceneViewTokens.Space.x3l.value)
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
        if (player != null && attached && qa && !videoFailed) {
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

    DemoScaffold(
        title = title, onBack = onBack, themedStage = true,
        firstFrameRendered = rendered, sceneReady = ready,
        peekHeader = if (cameraMoved) null else stringResource(R.string.demo_two_d_in_three_d_media_hint),
        dock = listOf(
            DockItem(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(if (playing) R.string.demo_two_d_in_three_d_pause
                    else R.string.demo_two_d_in_three_d_play),
                { playing = !playing }, enabled = !videoFailed && (!qa || seekFallback), selected = playing),
            DockItem(Icons.Filled.Portrait, stringResource(R.string.demo_two_d_in_three_d_face_camera),
                { faceCamera = !faceCamera }, selected = faceCamera),
        ),
        controls = {
            Text(stringResource(R.string.demo_two_d_in_three_d_media_explainer),
                style = MaterialTheme.typography.bodyMedium)
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sceneFrame = demoSceneFrame()
            val home = remember(sceneFrame.restingAspect, DemoSettings.cameraDistance) {
                val distance = DemoSettings.cameraDistance ?: layout.cameraDistance(
                    layout.MEDIA_EXTENT, sceneFrame.restingAspect,
                )
                layout.cameraHome(distance) + layout.MEDIA_TARGET
            }
            SceneView(
                contentPadding = sceneFrame.contentPadding,
                modifier = Modifier.fillMaxSize(), engine = engine, view = view,
                materialLoader = materials, environmentLoader = environments, environment = environment,
                cameraNode = camera, renderInvalidator = invalidator, autoCenterContent = false,
                cameraManipulator = rememberCameraManipulator(home, layout.MEDIA_TARGET),
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
                layout.GALLERY.take(2).forEachIndexed { index, exhibit -> key(index) {
                    Node(position = exhibit.position, rotation = Rotation(y = exhibit.yaw)) {
                        CubeNode(size = layout.frameSize(exhibit.size), materialInstance = frame)
                        if (index == 0) {
                            ImageNode(imageResId = R.drawable.preview_geometry_light,
                                size = exhibit.size, position = layout.CONTENT_OFFSET)
                        } else if (player != null && !videoFailed) {
                            VideoNode(player = player, size = exhibit.size, position = layout.CONTENT_OFFSET,
                                apply = { attached = true })
                        }
                    }
                } }
                // Only these nodes are recreated: their immutable provider cannot be swapped in place.
                key(faceCamera) {
                    val provider: (() -> Position)? = if (faceCamera) ({ eye }) else null
                    val exhibit = layout.GALLERY[2]
                    BillboardNode(bitmap = badge, widthMeters = exhibit.size.x, heightMeters = exhibit.size.y,
                        position = exhibit.position, cameraPositionProvider = provider)
                    layout.GALLERY.forEachIndexed { index, item -> key(index) {
                        TextNode(text = stringResource(captions[index]), fontSize = fontSize,
                            textColor = chrome.onCard.toArgb(), backgroundColor = chrome.card.toArgb(),
                            typeface = Typeface.MONOSPACE,
                            widthMeters = layout.CAPTION_SIZE.x, heightMeters = layout.CAPTION_SIZE.y,
                            position = layout.captionPosition(item), cameraPositionProvider = provider,
                            apply = { rotation = Rotation(y = item.yaw) })
                    } }
                }
            }
        }
    }
}

/** A remembered Canvas sprite made entirely from the existing palette and sizing tokens. */
@Composable
private fun rememberGalleryBadge(): Bitmap {
    val density = LocalDensity.current
    val size = with(density) { SceneViewTokens.Space.x4l.roundToPx() }
    val radius = with(density) { SceneViewTokens.Space.x3l.toPx() / 2f }
    val inset = with(density) { SceneViewTokens.Space.lg.toPx() }
    return remember(size, radius, inset) {
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val center = size / 2f
            paint.color = SceneViewColors.Primary.toArgb()
            canvas.drawCircle(center, center, radius, paint)
            canvas.rotate(45f, center, center)
            paint.color = SceneViewColors.Highlight.toArgb()
            canvas.drawRect(center - inset / 2, center - inset / 2,
                center + inset / 2, center + inset / 2, paint)
        }
    }
}

private const val QA_FRAME_MILLIS = 1_000
private const val SEEK_TIMEOUT_MILLIS = 3_000L
