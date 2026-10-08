package io.github.sceneview.demo.demos

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import io.github.sceneview.demo.demos.internal.ArDebugFormat
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.DebugGroup
import io.github.sceneview.demo.demos.internal.filmstripFrames
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge

/*
 * The chrome of the Room Scan replay (#4379): the room has the screen. One thin glass bar over
 * the dock carries the timeline — play, a filmstrip of the recorded frames that scrubs the 3D
 * view, the clock — and everything that is read once, the figures and the layer toggles, lives
 * in the settings sheet. The replay is a themed stage (#4080): the bar takes its card, text and
 * edge from [LocalStageChrome] — the dark scrim of the other AR demos in dark theme,
 * `glass-sheet` over the light stage in light theme.
 */

/**
 * The replay's layers and what each one holds, as rows of the settings sheet: the path walked,
 * the planes, the points and the anchors, each with its figure and a switch that shows or hides
 * it in the 3D view, then the room's measured floor plan.
 *
 * These were a card over the stage, on screen all the time, for figures that are read once.
 */
@Composable
internal fun RerunLayersSection(session: ArDebugSession, modifier: Modifier = Modifier) {
    val stats = session.stats
    val debug = LocalStageChrome.current.debug
    Column(modifier = modifier.fillMaxWidth().testTag(RERUN_LAYERS_TAG)) {
        Text(
            text = LAYERS_TITLE,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = Space.xs).semantics { heading() },
        )
        LayerRow("Path", ArDebugFormat.distance(stats.pathMetres), debug.trailNew, session, DebugGroup.Trail)
        LayerRow("Planes", ArDebugFormat.count(stats.planes), debug.floorOutline, session, DebugGroup.Planes)
        LayerRow("Points", ArDebugFormat.compactCount(stats.mapPoints), debug.mapPoint, session, DebugGroup.Points)
        LayerRow("Anchors", ArDebugFormat.count(stats.anchors), debug.anchor, session, DebugGroup.Anchors)
        // The room the planes outline, measured as a floor plan.
        stats.room?.let { room -> SheetFigureRow("Room", room) }
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

/** One layer: its colour in the 3D view, its name, its figure, and the switch — the whole row toggles. */
@Composable
private fun LayerRow(label: String, value: String, dot: Color, session: ArDebugSession, group: DebugGroup) {
    val on = session.isVisible(group)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch) { session.toggle(group) }
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .semantics { contentDescription = "$label $value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(
            Modifier
                .size(Space.sm + Space.xs / 2)
                .background(if (on) dot else MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
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
        // Decorative: the row is the toggle, so the switch takes no second focus stop.
        Switch(checked = on, onCheckedChange = null)
    }
}

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
    var full by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (path != null) decodeFrame(media, path)?.let { full = it.asImageBitmap() }
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
        shown?.let {
            Image(
                it,
                contentDescription = "The camera frame recorded at this moment",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
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
    Row(
        modifier = modifier
            .height(SceneViewTokens.Layout.touchTarget)
            // No drop shadow: the bar is translucent, and on the light stage a shadow shows
            // through as a grey frame (#4306). The edge ring separates it.
            .clip(shape)
            .background(chrome.card, shape)
            .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
            // The bar is chrome: a tap on its glass is not a tap on the stage, which hides it.
            .pointerInput(Unit) {}
            .padding(end = Space.md)
            .testTag(RERUN_FILMSTRIP_TAG),
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
internal const val RERUN_CAMERA_VIEW_TAG = "rerun_camera_view"
internal const val RERUN_FILMSTRIP_TAG = "rerun_filmstrip"
internal const val RERUN_FILMSTRIP_STRIP_TAG = "rerun_filmstrip_strip"
