package io.github.sceneview.demo.demos

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import io.github.sceneview.demo.demos.internal.ArDebugFormat
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.DebugGroup
import io.github.sceneview.demo.demos.internal.filmstripFrames
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.DebugView
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge

/*
 * The chrome of the Rerun demo's bundled replay: a glass HUD with what ARCore knew at this
 * instant, the camera's own picture in a corner card, and a filmstrip of the recorded frames that
 * scrubs the 3D view. Every overlay sits on the dark scrim of the other AR demos: the ground is
 * the 3D stage or a camera photo, never a themed surface.
 */

/**
 * The HUD: tracking state, the replay clock and the view's frame rate on one line, then the four
 * figures of the session so far. Each figure is also its layer's toggle.
 */
@Composable
internal fun RerunReplayHud(session: ArDebugSession, modifier: Modifier = Modifier) {
    val stats = session.stats
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)
    Column(
        modifier = modifier
            .shadow(elevation = SceneViewTokens.Elevation.lg, shape = shape, clip = false)
            .clip(shape)
            .background(ArOverlay.scrimDark, shape)
            .overMediaEdge(shape)
            .padding(horizontal = Space.md, vertical = Space.sm + Space.xs)
            .testTag(RERUN_REPLAY_HUD_TAG),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dot = if (stats.tracking) ArOverlay.accentSuccess else ArOverlay.onScrimMuted
            Box(Modifier.size(Space.sm).background(dot, CircleShape))
            Spacer(Modifier.width(Space.sm))
            Text(
                text = if (stats.tracking) "Tracking" else "Initializing",
                style = SceneViewTokens.Type.caption.copy(color = ArOverlay.onScrim, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${ArDebugFormat.clock(stats.time)} · ${session.fps} fps",
                style = HudCaption,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            val figure = Modifier.weight(1f)
            val path = ArDebugFormat.distance(stats.pathMetres)
            val planes = ArDebugFormat.count(stats.planes)
            val points = ArDebugFormat.compactCount(stats.mapPoints)
            val anchors = ArDebugFormat.count(stats.anchors)
            HudFigure("Path", path, DebugView.trailNew, session, DebugGroup.Trail, figure)
            HudFigure("Planes", planes, DebugView.floorOutline, session, DebugGroup.Planes, figure)
            HudFigure("Points", points, DebugView.mapPoint, session, DebugGroup.Points, figure)
            HudFigure("Anchors", anchors, DebugView.anchor, session, DebugGroup.Anchors, figure)
        }
    }
}

@Composable
private fun HudFigure(
    label: String,
    value: String,
    dot: Color,
    session: ArDebugSession,
    group: DebugGroup,
    modifier: Modifier = Modifier,
) {
    val on = session.isVisible(group)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(SceneViewTokens.Radius.xs))
            .clickable(role = Role.Switch) { session.toggle(group) }
            .semantics {
                contentDescription = "$label $value"
                stateDescription = if (on) "Shown" else "Hidden"
            }
            .alpha(if (on) 1f else HIDDEN_ALPHA)
            .padding(vertical = Space.xs),
    ) {
        Text(
            text = value,
            style = SceneViewTokens.Type.card.copy(color = ArOverlay.onScrim, fontFeatureSettings = "tnum"),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(Space.xs + Space.xs / 2).background(if (on) dot else ArOverlay.meterTrack, CircleShape))
            Spacer(Modifier.width(Space.xs))
            Text(label, style = HudCaption, maxLines = 1)
        }
    }
}

/**
 * The camera's own picture at this instant, in a portrait card at the top end: the proof the 3D
 * view is a real room. A tap opens the camera view.
 */
@Composable
internal fun RerunCameraCard(
    media: RerunReplayMedia,
    thumbnails: Map<String, ImageBitmap>,
    session: ArDebugSession,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val index by remember(media) { derivedStateOf { media.trace.imageIndexAt(session.time) } }
    val image = if (index < 0) null else thumbnails[media.trace.imagePath(index)]
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)
    Box(
        modifier = modifier
            .size(DebugView.pipWidth, DebugView.pipHeight)
            .shadow(elevation = SceneViewTokens.Elevation.lg, shape = shape, clip = false)
            .clip(shape)
            .background(SceneViewTokens.Stage.background)
            .testTag(RERUN_CAMERA_CARD_TAG),
    ) {
        image?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .overMediaEdge(shape)
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = "Open the camera view" },
        )
        CardLabel("Camera", Modifier.align(Alignment.BottomStart))
    }
}

/** The small dark pill in a card's corner: what the card shows. */
@Composable
internal fun CardLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SceneViewTokens.Type.caption.copy(color = ArOverlay.onScrim),
        modifier = modifier
            .padding(Space.sm)
            .background(ArOverlay.scrimDark, CircleShape)
            .padding(horizontal = Space.sm, vertical = Space.xs / 2),
    )
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
    Box(modifier.background(SceneViewTokens.Stage.background).testTag(RERUN_CAMERA_VIEW_TAG)) {
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
 * The replay's timeline: play/pause and the clock over a filmstrip of the recorded frames. The
 * strip is the scrubber — tap or drag anywhere on it — with the part still to come dimmed and a
 * playhead on the current instant. Dragging pauses; letting go plays on if it was playing.
 */
@Composable
internal fun RerunFilmstripCard(
    media: RerunReplayMedia,
    thumbnails: Map<String, ImageBitmap>,
    session: ArDebugSession,
    caption: String,
) {
    val duration = media.trace.duration
    OverlayCard(testTag = RERUN_FILMSTRIP_TAG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val playing = session.playing && !session.live
            IconButton(
                onClick = session::togglePlay,
                modifier = Modifier.size(SceneViewTokens.Layout.touchTarget),
            ) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = ArOverlay.onScrim,
                )
            }
            Spacer(Modifier.width(Space.xs))
            Column(Modifier.weight(1f)) {
                Text("Recorded AR session", style = OnScrimTitle, maxLines = 1)
                Text(caption, style = OnScrimCaption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(Space.sm))
            Text(
                "${ArDebugFormat.clock(session.stats.time)} / ${ArDebugFormat.clock(duration)}",
                style = HudCaption,
            )
        }
        Filmstrip(media, thumbnails, session, duration)
    }
}

@Composable
private fun Filmstrip(
    media: RerunReplayMedia,
    thumbnails: Map<String, ImageBitmap>,
    session: ArDebugSession,
    duration: Float,
) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.xs)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(FilmstripHeight)
            .clip(shape)
            .background(ArOverlay.meterTrack, shape)
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
                drawRect(FilmstripDim, topLeft = Offset(x, 0f), size = Size(size.width - x, size.height))
                val head = PlayheadWidth.toPx()
                drawRoundRect(
                    color = ArOverlay.onScrim,
                    topLeft = Offset((x - head / 2).coerceIn(0f, size.width - head), 0f),
                    size = Size(head, size.height),
                    cornerRadius = CornerRadius(head / 2),
                )
            }
            .testTag(RERUN_FILMSTRIP_STRIP_TAG),
    ) {
        val slotWidth = FilmstripHeight * FRAME_ASPECT
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

/** Shown while the bundled session decodes: the stage, and one quiet line. */
@Composable
internal fun RerunReplayLoading(modifier: Modifier = Modifier) {
    Box(modifier.background(SceneViewTokens.Stage.background), contentAlignment = Alignment.Center) {
        Text("Loading the recorded session…", style = OnScrimBody)
    }
}

private val HudCaption @Composable get() =
    SceneViewTokens.Type.caption.copy(color = ArOverlay.onScrimMuted, fontFeatureSettings = "tnum")

/** Filmstrip height: a touch target and a half-step, so the frames read as pictures. */
private val FilmstripHeight: Dp = SceneViewTokens.Layout.touchTarget + Space.sm
private const val FRAME_ASPECT = 0.75f // the recorded frames are portrait 3:4
private val PlayheadWidth: Dp = Space.xs - Space.xs / 4
/** What is still to come on the strip: the scrim, lighter, so the frames stay legible. */
private val FilmstripDim = ArOverlay.scrimDark.copy(alpha = 0.55f)
private val BACKDROP_BLUR: Dp = Space.lg + Space.sm
private const val BACKDROP_ALPHA = 0.55f
private const val HIDDEN_ALPHA = 0.45f

internal const val RERUN_REPLAY_HUD_TAG = "rerun_replay_hud"
internal const val RERUN_CAMERA_CARD_TAG = "rerun_camera_card"
internal const val RERUN_CAMERA_VIEW_TAG = "rerun_camera_view"
internal const val RERUN_FILMSTRIP_TAG = "rerun_filmstrip"
internal const val RERUN_FILMSTRIP_STRIP_TAG = "rerun_filmstrip_strip"
