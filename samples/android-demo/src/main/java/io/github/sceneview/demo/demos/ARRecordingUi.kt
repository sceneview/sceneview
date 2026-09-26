package io.github.sceneview.demo.demos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.LocalDemoChromeTopInset
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.LiveStat
import io.github.sceneview.demo.demos.internal.TakeQuality
import io.github.sceneview.demo.demos.internal.formatClock
import io.github.sceneview.demo.demos.internal.formatFileSize
import io.github.sceneview.demo.demos.internal.formatRecordingTitle
import io.github.sceneview.demo.demos.internal.recordingContentsLine
import io.github.sceneview.demo.demos.internal.recordingTitleOf
import io.github.sceneview.demo.demos.internal.replayProgress
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.haptic.rememberHapticFeedback
import java.io.File
import java.time.LocalDate

/*
 * The AR Recording demo's surfaces (#3831). Everything drawn over the camera is an AR overlay
 * card — theme-independent scrim, white text, `over-media-edge` — per DESIGN.md "AR Overlay
 * Card"; the gallery replaces the camera with the full-screen stage colour, which DESIGN.md
 * gives the same value in both themes, so the glass chrome above it stays legible.
 */

// Component sizes, built from the spacing scale rather than typed in.
private val ShutterSize = Space.x3l + Space.sm // 72 dp — a camera app's shutter
private val ShutterDiscSize = Space.x2l + Space.sm // 56 dp
private val ShutterStopSize = Space.xl - Space.xs // 28 dp
private val ShutterRingWidth = Space.xs - Space.xs / 4 // 3 dp
private val ThumbnailWidth = Space.x4l // 96 dp, 4:3
private val SavedThumbnailWidth = Space.x3l // 64 dp, 4:3
private val RecordDotSize = Space.sm + Space.xs / 2 // 10 dp

private const val THUMBNAIL_ASPECT = 4f / 3f

// ── Cards over the camera ────────────────────────────────────────────────────────────────

/** The scrim card every overlay of this demo sits in — one at a time, centred, max 480 dp. */
@Composable
internal fun OverlayCard(
    testTag: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = modifier
                .padding(horizontal = Space.md)
                .widthIn(max = ArOverlay.maxWidth)
                .fillMaxWidth()
                .shadow(elevation = SceneViewTokens.Elevation.lg, shape = shape, clip = false)
                .background(color = cardScrim(), shape = shape)
                .overMediaEdge(shape)
                .padding(Space.md)
                .testTag(testTag),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
            content = content,
        )
    }
}

/** Same rule as the Cloud Anchor cards: only the scrim's opacity follows the theme. */
@Composable
private fun cardScrim(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) ArOverlay.scrimDark else ArOverlay.scrimLight

internal val OnScrimTitle: TextStyle @Composable get() = SceneViewTokens.Type.card.copy(color = ArOverlay.onScrim)
internal val OnScrimBody: TextStyle @Composable get() = SceneViewTokens.Type.body.copy(color = ArOverlay.onScrimMuted)
internal val OnScrimCaption: TextStyle @Composable get() =
    SceneViewTokens.Type.caption.copy(color = ArOverlay.onScrimMuted)

/** Four figures side by side: a big value over a small label. */
@Composable
internal fun StatsRow(stats: List<LiveStat>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        stats.forEach { stat ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(stat.value, style = OnScrimTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stat.label, style = OnScrimCaption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * What is being captured, live: elapsed time and file size, then frames, distance, surfaces
 * and placements — the panel the QA walkthrough asked for ("show live what can be saved").
 */
@Composable
internal fun LiveCaptureCard(
    elapsedMillis: Long,
    sizeBytes: Long,
    stats: List<LiveStat>,
    guidance: String?,
    mayNotReplay: Boolean,
) {
    OverlayCard(testTag = AR_REC_LIVE_CARD_TAG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordDot()
            Spacer(Modifier.width(Space.sm))
            Text(
                text = stringResource(R.string.ar_rec_recording),
                style = OnScrimTitle,
                modifier = Modifier.weight(1f),
            )
            Text(text = formatClock(elapsedMillis), style = OnScrimTitle)
            if (sizeBytes > 0L) {
                Text(
                    text = "  ·  ${formatFileSize(sizeBytes)}",
                    style = OnScrimCaption,
                )
            }
        }
        StatsRow(stats)
        if (guidance != null) {
            Text(
                text = if (mayNotReplay) "$guidance ${stringResource(R.string.ar_rec_may_not_replay)}" else guidance,
                style = SceneViewTokens.Type.caption.copy(color = ArOverlay.accentGuidance),
            )
        }
    }
}

/**
 * The camera-app "on air" dot — the only red on screen means recording (DESIGN.md). Solid:
 * the ticking clock next to it already says the take is live, and DESIGN.md keeps motion to
 * the chrome's one spring and one fade.
 */
@Composable
private fun RecordDot() {
    Box(
        modifier = Modifier
            .size(RecordDotSize)
            .background(ArOverlay.accentRecord, CircleShape),
    )
}

/** A replay in progress: which take, where in it, and what has been rebuilt so far. */
@Composable
internal fun ReplayCard(
    title: String,
    elapsedMillis: Long,
    durationMillis: Long,
    stats: List<LiveStat>,
    onStop: () -> Unit,
) {
    OverlayCard(testTag = AR_REC_REPLAY_CARD_TAG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.ar_rec_replaying), style = OnScrimCaption)
                Text(title, style = OnScrimTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalButton(onClick = onStop) { Text(stringResource(R.string.ar_rec_stop)) }
        }
        LinearProgressIndicator(
            progress = { replayProgress(elapsedMillis, durationMillis) },
            modifier = Modifier.fillMaxWidth(),
            color = ArOverlay.accentProgress,
            trackColor = ArOverlay.meterTrack,
            drawStopIndicator = {},
        )
        Row {
            Text(formatClock(elapsedMillis), style = OnScrimCaption, modifier = Modifier.weight(1f))
            if (durationMillis > 0L) Text(formatClock(durationMillis), style = OnScrimCaption)
        }
        StatsRow(stats)
    }
}

/** Shown in place of the shutter once a take is saved: the take itself, and what to do next. */
@Composable
internal fun SavedTakeCard(
    thumbnail: ImageBitmap?,
    summary: String,
    quality: TakeQuality,
    qualityLine: String,
    onReplay: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    OverlayCard(testTag = AR_REC_SAVED_CARD_TAG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(thumbnail, width = SavedThumbnailWidth, onMedia = true)
            Spacer(Modifier.width(Space.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.ar_rec_saved_title), style = OnScrimTitle)
                Text(summary, style = OnScrimCaption)
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.ar_rec_close),
                    tint = ArOverlay.onScrimMuted,
                )
            }
        }
        Text(
            text = qualityLine,
            style = if (quality == TakeQuality.Unsteady) {
                SceneViewTokens.Type.body.copy(color = ArOverlay.accentGuidance)
            } else {
                OnScrimBody
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Button(onClick = onReplay) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(Space.xs))
                Text(stringResource(R.string.ar_rec_replay))
            }
            FilledTonalButton(onClick = onShare) { Text(stringResource(R.string.ar_rec_share)) }
        }
    }
}

/** The end of a replay: what it held, how steady it was, and where it lost its place. */
@Composable
internal fun ReplayReportCard(
    title: String,
    stats: List<LiveStat>,
    quality: TakeQuality,
    qualityLine: String,
    lostReasons: List<String>,
    onReplayAgain: () -> Unit,
    onAllRecordings: () -> Unit,
) {
    OverlayCard(testTag = AR_REC_REPORT_CARD_TAG) {
        Column {
            Text(
                stringResource(R.string.ar_rec_replay_finished),
                style = OnScrimTitle,
                modifier = Modifier.semantics { heading() },
            )
            Text(title, style = OnScrimCaption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatsRow(stats)
        Text(
            text = qualityLine,
            style = if (quality == TakeQuality.Unsteady) {
                SceneViewTokens.Type.body.copy(color = ArOverlay.accentGuidance)
            } else {
                OnScrimBody
            },
        )
        if (lostReasons.isNotEmpty()) {
            Column {
                Text(
                    text = stringResource(R.string.ar_rec_lost_heading),
                    style = OnScrimCaption.copy(fontWeight = FontWeight.SemiBold),
                )
                lostReasons.forEach { Text(it, style = OnScrimCaption) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Button(onClick = onReplayAgain) { Text(stringResource(R.string.ar_rec_replay_again)) }
            FilledTonalButton(onClick = onAllRecordings) { Text(stringResource(R.string.ar_rec_all_recordings)) }
        }
    }
}

/**
 * Camera-style shutter: a ring with a red disc that turns into a rounded square while
 * recording. Greyed out until the camera has its bearings — a take started before that
 * fails, and the failure used to surface as a developer message.
 */
@Composable
internal fun RecordShutter(
    isRecording: Boolean,
    startEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val tappable = isRecording || startEnabled
    val haptic = rememberHapticFeedback()
    val label = stringResource(if (isRecording) R.string.ar_rec_stop_recording else R.string.ar_rec_start_recording)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            onClick = {
                if (!DemoSettings.qaMode) haptic.heavy()
                if (isRecording) onStop() else onStart()
            },
            enabled = tappable,
            shape = CircleShape,
            color = ArOverlay.meterTrack,
            border = BorderStroke(
                width = ShutterRingWidth,
                color = ArOverlay.onScrim.copy(alpha = if (tappable) 1f else DISABLED_ALPHA),
            ),
            modifier = Modifier
                .size(ShutterSize)
                .testTag(AR_REC_SHUTTER_TAG)
                .semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(if (isRecording) ShutterStopSize else ShutterDiscSize)
                        .background(
                            color = ArOverlay.accentRecord.copy(alpha = if (tappable) 1f else DISABLED_ALPHA),
                            shape = if (isRecording) RoundedCornerShape(SceneViewTokens.Radius.xs) else CircleShape,
                        ),
                )
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.4f

// ── Gallery ──────────────────────────────────────────────────────────────────────────────

/**
 * Every recording on the phone, newest first, each with a frame from the take, its length,
 * its size and what it holds. Tapping one replays it in place; Share, Save to Downloads and
 * Delete sit behind the row's overflow menu. Replaces the old list, which had no picture and
 * whose "Open" left the app for a video player chooser.
 */
@Composable
internal fun RecordingsGallery(
    recordings: List<File>,
    library: RecordingLibrary,
    newestName: String?,
    onReplay: (File) -> Unit,
    onShare: (File) -> Unit,
    onSaveToDownloads: (File) -> Unit,
    onDelete: (File) -> Unit,
    onRecord: () -> Unit,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + LocalDemoChromeTopInset.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        LocalDemoChromeBottomInset.current + Space.md
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SceneViewTokens.Stage.background)
            .testTag(AR_REC_GALLERY_TAG),
    ) {
        if (recordings.isEmpty()) {
            GalleryEmptyState(
                onRecord = onRecord,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = top, bottom = bottom)
                    .padding(horizontal = Space.lg),
            )
            return@Box
        }
        val today = remember { LocalDate.now() }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Space.md, end = Space.md, top = top, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .widthIn(max = ArOverlay.maxWidth)
                        .fillMaxWidth()
                        .padding(bottom = Space.sm),
                ) {
                    Text(
                        text = stringResource(R.string.ar_rec_gallery_title),
                        style = SceneViewTokens.Type.title.copy(color = ArOverlay.onScrim),
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.ar_rec_gallery_subtitle),
                        style = SceneViewTokens.Type.body.copy(color = ArOverlay.onScrimMuted),
                    )
                }
            }
            items(recordings, key = { it.absolutePath }) { file ->
                RecordingRow(
                    file = file,
                    library = library,
                    title = formatRecordingTitle(recordingTitleOf(file.name), today),
                    isNew = file.name == newestName,
                    onReplay = { onReplay(file) },
                    onShare = { onShare(file) },
                    onSaveToDownloads = { onSaveToDownloads(file) },
                    onDelete = { onDelete(file) },
                )
            }
        }
    }
}

@Composable
private fun RecordingRow(
    file: File,
    library: RecordingLibrary,
    title: String,
    isNew: Boolean,
    onReplay: () -> Unit,
    onShare: () -> Unit,
    onSaveToDownloads: () -> Unit,
    onDelete: () -> Unit,
) {
    val details by produceState(library.cached(file), file.absolutePath, file.lastModified()) {
        value = library.details(file)
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val deletable = !file.name.startsWith(BUNDLED_PREFIX)
    val shape = RoundedCornerShape(SceneViewTokens.Radius.md)
    Row(
        modifier = Modifier
            .widthIn(max = ArOverlay.maxWidth)
            .fillMaxWidth()
            .clip(shape)
            .background(SceneViewTokens.Glass.surface, shape)
            .clickable(role = Role.Button, onClick = onReplay)
            .padding(Space.sm)
            .testTag(AR_REC_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Thumbnail(details?.thumbnail, width = ThumbnailWidth, onMedia = true)
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = ArOverlay.onScrim,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(ArOverlay.scrimDark.copy(alpha = PLAY_BADGE_ALPHA), CircleShape)
                    .padding(Space.xs),
            )
        }
        Spacer(Modifier.width(Space.md))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs / 2)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = SceneViewTokens.Type.card.copy(color = ArOverlay.onScrim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isNew) {
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        text = stringResource(R.string.ar_rec_new_badge),
                        style = SceneViewTokens.Type.caption.copy(
                            color = ArOverlay.accentProgress,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
            val d = details
            val lengthAndSize = buildString {
                if (d != null && d.durationMillis > 0L) append(formatClock(d.durationMillis)).append(" · ")
                append(formatFileSize(d?.sizeBytes ?: file.length()))
            }
            Text(lengthAndSize, style = SceneViewTokens.Type.caption.copy(color = ArOverlay.onScrimMuted))
            if (d != null) {
                Text(
                    text = recordingContentsLine(d.contents, d.placementCount),
                    style = SceneViewTokens.Type.caption.copy(color = ArOverlay.onScrimMuted),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.ar_rec_more_actions),
                    tint = ArOverlay.onScrimMuted,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ar_rec_share)) },
                    onClick = { menuOpen = false; onShare() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ar_rec_save_to_downloads)) },
                    onClick = { menuOpen = false; onSaveToDownloads() },
                )
                if (deletable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ar_rec_delete)) },
                        onClick = { menuOpen = false; confirmDelete = true },
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.ar_rec_delete_title)) },
            text = { Text(stringResource(R.string.ar_rec_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.ar_rec_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.ar_rec_cancel)) }
            },
        )
    }
}

private const val PLAY_BADGE_ALPHA = 0.6f

/** A 4:3 frame of the take, or its placeholder while it decodes. */
@Composable
private fun Thumbnail(image: ImageBitmap?, width: androidx.compose.ui.unit.Dp, onMedia: Boolean) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.sm)
    val placeholder = if (onMedia) SceneViewTokens.Glass.surface else MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(THUMBNAIL_ASPECT)
            .clip(shape)
            .background(placeholder, shape),
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun GalleryEmptyState(onRecord: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.widthIn(max = ArOverlay.maxWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(
            Icons.Rounded.VideoLibrary,
            contentDescription = null,
            tint = ArOverlay.onScrimMuted,
            modifier = Modifier.size(Space.x2l),
        )
        Text(
            text = stringResource(R.string.ar_rec_empty_title),
            style = SceneViewTokens.Type.title.copy(color = ArOverlay.onScrim),
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.ar_rec_empty_body),
            style = SceneViewTokens.Type.body.copy(color = ArOverlay.onScrimMuted),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.size(Space.sm))
        Button(onClick = onRecord) { Text(stringResource(R.string.ar_rec_step_record)) }
    }
}

// ── Settings sheet ───────────────────────────────────────────────────────────────────────

/**
 * "What a recording keeps" — the sheet's first section, replacing the old "How this helps"
 * wall of text. The sheet follows the app theme, so this uses theme colours.
 */
@Composable
internal fun RecordingKeepsSection() {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            text = stringResource(R.string.ar_rec_keeps_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        KeepsItem(R.string.ar_rec_keeps_video_title, R.string.ar_rec_keeps_video_body)
        KeepsItem(R.string.ar_rec_keeps_motion_title, R.string.ar_rec_keeps_motion_body)
        KeepsItem(R.string.ar_rec_keeps_placements_title, R.string.ar_rec_keeps_placements_body)
        Text(
            text = stringResource(R.string.ar_rec_keeps_rebuilt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KeepsItem(title: Int, body: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = Space.sm - Space.xs / 2)
                .size(Space.sm - Space.xs / 2)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(Space.sm + Space.xs))
        Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal const val BUNDLED_PREFIX = "bundled-"

internal const val AR_REC_LIVE_CARD_TAG = "ar_rec_live_card"
internal const val AR_REC_REPLAY_CARD_TAG = "ar_rec_replay_card"
internal const val AR_REC_SAVED_CARD_TAG = "ar_rec_saved_card"
internal const val AR_REC_REPORT_CARD_TAG = "ar_rec_report_card"
internal const val AR_REC_SHUTTER_TAG = "ar_rec_shutter"
internal const val AR_REC_GALLERY_TAG = "ar_rec_gallery"
internal const val AR_REC_ROW_TAG = "ar_rec_row"
