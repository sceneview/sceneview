package io.github.sceneview.demo.demos

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.ArDebugFormat
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.DebugGroup
import io.github.sceneview.demo.demos.internal.DebugPlaneKind
import io.github.sceneview.demo.demos.internal.filmstripFrames
import io.github.sceneview.demo.demos.internal.scanPathLength
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge

/*
 * The chrome of the 3D Scan replay (#4379): the room has the screen. One thin glass bar over
 * the dock carries the timeline — play, a filmstrip of the recorded frames that scrubs the 3D
 * view, the clock — and everything that is read once, the figures and the layer toggles, lives
 * in the settings sheet. The replay is a themed stage (#4080): the bar takes its card, text and
 * edge from [LocalStageChrome] — the dark scrim of the other AR demos in dark theme,
 * `glass-sheet` over the light stage in light theme.
 */

/**
 * The replay's layers and what each one holds, as rows of the settings sheet: the camera's path,
 * its photos, the planes and the points ([RerunReplayControls.layers]), each with a swatch of
 * what it draws, a line that says it, its figure and a switch, then the room's measured floor
 * plan. The swatches and their lines are the 3D view's legend (#4597).
 *
 * These were a card over the stage, on screen all the time, for figures that are read once.
 */
@Composable
internal fun RerunLayersSection(session: ArDebugSession, modifier: Modifier = Modifier) {
    val stats = session.stats
    val locale = LocalLocale.current.platformLocale
    Column(modifier = modifier.fillMaxWidth().padding(top = Space.md).testTag(RERUN_LAYERS_TAG)) {
        Text(
            text = LAYERS_TITLE,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = Space.xs).semantics { heading() },
        )
        RerunReplayControls.layers.forEach { layer ->
            val value = when (layer.group) {
                DebugGroup.Trail -> scanPathLength(stats.pathMetres, locale)
                DebugGroup.Planes -> ArDebugFormat.count(stats.planes, locale)
                DebugGroup.Points -> ArDebugFormat.compactCount(stats.mapPoints, locale)
                else -> null
            }
            LayerRow(layer, value, session)
        }
        // The room its walls outline, measured as a floor plan: named so, not read as the floor covered (#4734).
        stats.room?.let { room -> SheetFigureRow("Room from walls", room) }
    }
}

/** One figure of the settings sheet: what it counts, and the count in tabular digits. */
@Composable
internal fun SheetFigureRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * One layer: a swatch of what it draws in the 3D view, its name over a line that says it, its
 * figure, and the switch — the whole row toggles.
 */
@Composable
private fun LayerRow(layer: RerunLayer, value: String?, session: ArDebugSession) {
    val on = session.isVisible(layer.group)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch) { session.toggle(layer.group) }
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .padding(vertical = Space.xs)
            .semantics { contentDescription = listOfNotNull(layer.label, value, layer.hint).joinToString(", ") }
            .testTag(RERUN_LAYER_ROW_TAG + layer.group.name),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        LayerSwatch(layer.group, on)
        Column(Modifier.weight(1f)) {
            Text(
                text = layer.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = layer.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        value?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // Decorative: the row is the toggle, so the switch takes no second focus stop.
        Switch(checked = on, onCheckedChange = null)
    }
}

/**
 * What a layer looks like in the 3D view, drawn small: the path as its own faint-to-strong
 * stroke, the photos as a frustum's outline, the planes as a floor and a wall chip, the points as
 * a few dots. Greyed out while the layer is hidden.
 */
@Composable
private fun LayerSwatch(group: DebugGroup, on: Boolean) {
    val debug = LocalStageChrome.current.debug
    val off = MaterialTheme.colorScheme.outlineVariant
    val ground = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(
        Modifier
            .size(SceneViewTokens.Layout.touchTarget - Space.md)
            .clip(RoundedCornerShape(SceneViewTokens.Radius.xs))
            .background(ground),
    ) {
        val w = size.width
        val h = size.height
        val stroke = Space.xs.toPx() * SWATCH_STROKE
        when (group) {
            DebugGroup.Trail -> {
                val steps = SWATCH_STEPS
                for (i in 0 until steps) {
                    val from = Offset(w * (0.15f + 0.7f * i / steps), h * (0.75f - 0.5f * i / steps))
                    val to = Offset(w * (0.15f + 0.7f * (i + 1) / steps), h * (0.75f - 0.5f * (i + 1) / steps))
                    val color = if (on) trailColorAt(debug, (i + 0.5f) / steps) else off
                    drawLine(color, from, to, strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }
            DebugGroup.Cameras -> {
                val color = if (on) debug.frustum else off
                val apex = Offset(w * 0.2f, h * 0.5f)
                val top = Offset(w * 0.8f, h * 0.25f)
                val bottom = Offset(w * 0.8f, h * 0.75f)
                drawLine(color, apex, top, strokeWidth = stroke / 2f)
                drawLine(color, apex, bottom, strokeWidth = stroke / 2f)
                drawLine(color, top, bottom, strokeWidth = stroke, cap = StrokeCap.Round)
            }
            DebugGroup.Planes -> {
                val floor = if (on) debug.floorOutline else off
                val wall = if (on) debug.wallOutline else off
                drawRect(floor, Offset(w * 0.15f, h * 0.55f), Size(w * 0.7f, h * 0.2f))
                drawRect(wall, Offset(w * 0.15f, h * 0.2f), Size(w * 0.2f, h * 0.3f))
            }
            else -> {
                val color = if (on) debug.mapPoint else off
                listOf(0.3f to 0.35f, 0.55f to 0.3f, 0.7f to 0.55f, 0.4f to 0.65f, 0.6f to 0.75f).forEach { (x, y) ->
                    drawCircle(color, radius = stroke, center = Offset(w * x, h * y))
                }
            }
        }
    }
}

/** The path swatch is drawn in this many pieces, so its ramp reads. */
private const val SWATCH_STEPS = 6

/** The swatch's stroke, as a share of `space-xs`. */
private const val SWATCH_STROKE = 0.75f

/** The camera overlay's points let the photo show through them. */
private const val OVERLAY_POINT_ALPHA = 0.7f

/**
 * The camera view: the recorded frame at the replay's instant, full size, over a blurred copy of
 * itself so the letterbox bands carry the room's colours instead of black. The full-size frame
 * is decoded off the main thread; until it lands, the last one stays up, so playback never
 * flickers through a blurry thumbnail.
 */
@Composable
internal fun RerunCameraView(
    media: RerunReplayMedia,
    thumbnails: Map<String, ImageBitmap>,
    session: ArDebugSession,
    modifier: Modifier = Modifier,
) {
    val index by remember(media) { derivedStateOf { media.trace.imageIndexAt(session.time) } }
    val path = if (index < 0) null else media.trace.imagePath(index)
    var full by remember(media) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(media, path) {
        if (path == null || path !in media.manifest.media) full = null
        else decodeFrame(media, path)?.let { full = it.asImageBitmap() }
    }
    val shown = full ?: path?.let { thumbnails[it] }
    Box(modifier.background(LocalStageChrome.current.ground).testTag(RERUN_CAMERA_VIEW_TAG)) {
        val backdrop = path?.let { thumbnails[it] }
        if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Image(
                backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(BACKDROP_BLUR).alpha(BACKDROP_ALPHA),
            )
        }
        if (media.trace.imageCount == 0) {
            // A scan shared without its photos: the view says so instead of staying blank.
            Text(
                text = stringResource(R.string.scan_no_photos),
                style = SceneViewTokens.Type.body.copy(color = LocalStageChrome.current.onGlass),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(Space.lg).testTag(RERUN_CAMERA_EMPTY_TAG),
            )
        }
        shown?.let {
            Image(
                it,
                contentDescription = "The camera frame recorded at this moment",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            CameraFrameOverlay(media, index, it.width.toFloat() / it.height, session, Modifier.fillMaxSize())
        }
    }
}

/**
 * The reconstruction over the recorded frame (#4597): the room's points and planes, seen from
 * the pose the frame was taken from, through the recording's lens — so the frame shows what the
 * scan made of it, the way Rerun draws its 3D over a camera's image. It follows the sheet's
 * Points and Planes switches.
 */
@Composable
private fun CameraFrameOverlay(
    media: RerunReplayMedia,
    index: Int,
    aspect: Float,
    session: ArDebugSession,
    modifier: Modifier = Modifier,
) {
    if (index < 0) return
    val trace = media.trace
    // The whole reconstruction, seen from the camera the frame was taken with.
    val whole = remember(media, trace.version) { trace.frameAt(trace.duration) }
    val pose = remember(media, index) { trace.frameAt(trace.imageTime(index)).camera } ?: return
    val lens = media.manifest.lens
    val points = remember(whole, pose) {
        val xyz = whole.mapPoints
        val out = ArrayList<Pair<Float, Float>>(xyz.size / 3)
        for (i in 0 until xyz.size / 3) {
            RerunReplayControls.project(pose, lens, xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])
                ?.let { out += it[0] to it[1] }
        }
        out
    }
    val outlines = remember(whole, pose) {
        whole.planes.map { plane ->
            plane.kind to (0 until plane.vertexCount).map { v ->
                val p = plane.polygon
                RerunReplayControls.project(pose, lens, p[v * 3], p[v * 3 + 1], p[v * 3 + 2])
            }
        }
    }
    val debug = LocalStageChrome.current.debug
    val showPoints = session.isVisible(DebugGroup.Points)
    val showPlanes = session.isVisible(DebugGroup.Planes)
    Canvas(modifier.testTag(RERUN_CAMERA_OVERLAY_TAG)) {
        // Where ContentScale.Fit put the frame.
        val frameWidth = minOf(size.width, size.height * aspect)
        val frameHeight = frameWidth / aspect
        val left = (size.width - frameWidth) / 2f
        val top = (size.height - frameHeight) / 2f
        fun at(u: Float, v: Float) = Offset(left + u * frameWidth, top + v * frameHeight)
        val stroke = Space.xs.toPx() * SWATCH_STROKE
        if (showPlanes) {
            outlines.forEach { (kind, corners) ->
                val color = if (kind == DebugPlaneKind.Wall) debug.wallOutline else debug.floorOutline
                corners.indices.forEach { i ->
                    val a = corners[i]
                    val b = corners[(i + 1) % corners.size]
                    if (a != null && b != null) drawLine(color, at(a[0], a[1]), at(b[0], b[1]), strokeWidth = stroke)
                }
            }
        }
        if (showPoints) {
            drawPoints(
                points.map { (u, v) -> at(u, v) },
                PointMode.Points,
                // Fine and a little see-through: the photo stays the subject, the points mark it.
                debug.frustum.copy(alpha = OVERLAY_POINT_ALPHA),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * The replay's timeline, on one row: play/pause, a filmstrip of the recorded frames, the clock.
 * The strip is the scrubber — tap or drag anywhere on it — with the part still to come dimmed and
 * a playhead on the current instant. Dragging pauses; letting go plays on if it was playing.
 *
 * One touch target tall (#4379): it used to be a card with a title, a caption and a taller strip,
 * and with the figures above it left the room under half of the screen.
 */
@Composable
internal fun RerunTimelineBar(
    media: RerunReplayMedia,
    thumbnails: Map<String, ImageBitmap>,
    session: ArDebugSession,
    modifier: Modifier = Modifier,
) {
    val duration = media.trace.duration
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)
    val chrome = LocalStageChrome.current
    // The session's own clock, not the 3D view's figures: it runs over the camera's frames too.
    val clock by remember(session, duration) {
        derivedStateOf { "${ArDebugFormat.clock(session.time)} / ${ArDebugFormat.clock(duration)}" }
    }
    Column(
        modifier = modifier
            // No drop shadow: the bar is translucent, and on the light stage a shadow shows
            // through as a grey frame (#4306). The edge ring separates it.
            .clip(shape)
            .background(chrome.card, shape)
            .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
            // The bar is chrome: a tap on its glass is not a tap on the stage, which hides it.
            .pointerInput(Unit) {}
            .testTag(RERUN_FILMSTRIP_TAG),
    ) {
        Row(
            modifier = Modifier.height(SceneViewTokens.Layout.touchTarget).padding(end = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val playing = session.playing && !session.live
            IconButton(
                onClick = session::togglePlay,
                modifier = Modifier.size(SceneViewTokens.Layout.touchTarget),
            ) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = chrome.onCard,
                )
            }
            Filmstrip(media, thumbnails, session, duration, FilmstripHeight, Modifier.weight(1f))
            Spacer(Modifier.width(Space.sm))
            Text(clock, style = HudCaption, maxLines = 1)
        }
    }
}

@Composable
private fun Filmstrip(
    media: RerunReplayMedia,
    thumbnails: Map<String, ImageBitmap>,
    session: ArDebugSession,
    duration: Float,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.xs)
    val chrome = LocalStageChrome.current
    // What is still to come on the strip: washed towards the card, so the frames stay legible.
    val dim = chrome.card.copy(alpha = FILMSTRIP_DIM_ALPHA)
    BoxWithConstraints(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(chrome.track, shape)
            .semantics {
                contentDescription = "Session timeline"
                stateDescription = ArDebugFormat.clock(session.time)
                setProgress { fraction ->
                    session.scrubTo(fraction.coerceIn(0f, 1f) * duration)
                    true
                }
            }
            .pointerInput(duration) {
                detectTapGestures { session.scrubTo(it.x / size.width * duration) }
            }
            .pointerInput(duration) {
                var resume = false
                detectHorizontalDragGestures(
                    onDragStart = {
                        resume = session.playing
                        session.scrubTo(it.x / size.width * duration)
                    },
                    onDragEnd = { if (resume) session.togglePlay() },
                    onDragCancel = { if (resume) session.togglePlay() },
                ) { change, _ ->
                    change.consume()
                    session.scrubTo(change.position.x / size.width * duration)
                }
            }
            .drawWithContent {
                drawContent()
                // Read in the draw phase: the playhead moves every frame without recomposing.
                val x = size.width * if (duration > 0f) (session.time / duration).coerceIn(0f, 1f) else 0f
                drawRect(dim, topLeft = Offset(x, 0f), size = Size(size.width - x, size.height))
                val head = PlayheadWidth.toPx()
                drawRoundRect(
                    color = chrome.onCard,
                    topLeft = Offset((x - head / 2).coerceIn(0f, size.width - head), 0f),
                    size = Size(head, size.height),
                    cornerRadius = CornerRadius(head / 2),
                )
            }
            .testTag(RERUN_FILMSTRIP_STRIP_TAG),
    ) {
        val slotWidth = height * FRAME_ASPECT
        val slots = (maxWidth / slotWidth).toInt().coerceAtLeast(1)
        val frames = remember(media, slots) { filmstripFrames(media.trace.imageCount, slots) }
        Row(Modifier.fillMaxSize()) {
            frames.forEach { i ->
                val image = thumbnails[media.trace.imagePath(i)]
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    image?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/** What the stage's cover says while the bundled session decodes and the 3D view warms up. */
internal const val RERUN_REPLAY_LOADING = "Loading the recorded session…"

// The timeline follows the stage in, one beat late.
internal const val REVEAL_STAGGER_MS = 100

/**
 * How far one piece of the replay's chrome has arrived: 0 until the stage is [revealed], then 1
 * over the medium duration, [delayMillis] late — the timeline lands while the camera cranes in,
 * instead of sitting over an empty stage.
 */
@Composable
internal fun rememberReveal(revealed: Boolean, delayMillis: Int): State<Float> = animateFloatAsState(
    targetValue = if (revealed) 1f else 0f,
    animationSpec = tween(
        durationMillis = SceneViewTokens.Duration.mediumMillis,
        delayMillis = delayMillis,
        easing = SceneViewTokens.Ease.expressive,
    ),
    label = "rerun-reveal",
)

/** Fades in with [progress] and rises the last [rise] into place (negative: drops into place). */
internal fun Modifier.reveal(progress: State<Float>, rise: Dp): Modifier = graphicsLayer {
    val p = progress.value
    alpha = p
    translationY = (1f - p) * rise.toPx()
}

private val HudCaption @Composable get() =
    SceneViewTokens.Type.caption.copy(color = LocalStageChrome.current.onCardMuted, fontFeatureSettings = "tnum")

/** Filmstrip height: the bar's touch target less a half-step of air above and below. */
private val FilmstripHeight: Dp = SceneViewTokens.Layout.touchTarget - Space.sm
private const val FRAME_ASPECT = 0.75f // the recorded frames are portrait 3:4
private val PlayheadWidth: Dp = Space.xs - Space.xs / 4
private const val FILMSTRIP_DIM_ALPHA = 0.55f
private val BACKDROP_BLUR: Dp = Space.lg + Space.sm
private const val BACKDROP_ALPHA = 0.55f
private const val LAYERS_TITLE = "Layers"

/** The layers section of the replay's settings sheet. */
internal const val RERUN_LAYERS_TAG = "rerun_replay_layers"
internal const val RERUN_LAYER_ROW_TAG = "rerun_replay_layer_"
internal const val RERUN_CAMERA_OVERLAY_TAG = "rerun_replay_camera_overlay"
internal const val RERUN_CAMERA_VIEW_TAG = "rerun_camera_view"
internal const val RERUN_CAMERA_EMPTY_TAG = "rerun_camera_empty"
internal const val RERUN_FILMSTRIP_TAG = "rerun_filmstrip"
internal const val RERUN_FILMSTRIP_STRIP_TAG = "rerun_filmstrip_strip"
