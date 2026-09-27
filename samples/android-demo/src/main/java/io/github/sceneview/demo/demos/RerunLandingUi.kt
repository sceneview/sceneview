package io.github.sceneview.demo.demos

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge

/*
 * The Rerun demo's landing: the sample room turning in 3D behind, one primary action — record
 * your own room — the sample one tap away, and the scans saved on this phone. Laid out like the
 * capture apps it answers to (Polycam, Scaniverse, Reality Composer): the library over the
 * capture button, the capture button in the thumb's reach.
 */

/** "Watch a sample session": a glass pill at the top, over the sample it opens. */
@Composable
internal fun WatchSamplePill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = CircleShape
    Row(
        modifier = modifier
            .padding(horizontal = Space.md)
            .overMediaEdge(shape)
            .clip(shape)
            .background(SceneViewTokens.Glass.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = PillHeight)
            .padding(start = Space.sm, end = Space.md)
            .testTag(WATCH_SAMPLE_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = ArOverlay.onScrim)
        Text(text = ScanCopy.WATCH_SAMPLE, style = SceneViewTokens.Type.body.copy(color = ArOverlay.onScrim))
    }
}

/**
 * The scans saved on this phone, newest first, each opening its replay and deletable after a
 * confirmation. [sessions] is `null` while the list is read.
 */
@Composable
internal fun RerunSessionsCard(
    sessions: List<StoredSession>?,
    onOpen: (StoredSession) -> Unit,
    onDelete: (StoredSession) -> Unit,
) {
    var confirming by remember { mutableStateOf<StoredSession?>(null) }
    OverlayCard(testTag = SESSIONS_CARD_TAG) {
        Text(
            text = ScanCopy.SESSIONS_TITLE,
            style = OnScrimTitle,
            modifier = Modifier.semantics { heading() },
        )
        when {
            sessions == null -> Unit
            sessions.isEmpty() -> Text(text = ScanCopy.SESSIONS_EMPTY, style = OnScrimBody)
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = SessionListMaxHeight),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(session, onOpen = { onOpen(session) }, onDelete = { confirming = session })
                }
            }
        }
    }
    confirming?.let { session ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(ScanCopy.DELETE_TITLE) },
            text = { Text(ScanCopy.DELETE_DETAIL) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = null
                        onDelete(session)
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SessionRow(session: StoredSession, onOpen: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val date = remember(session.meta.createdAtMillis) {
        DateUtils.formatDateTime(
            context,
            session.meta.createdAtMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }
    val summary = ScanCopy.summary(session.meta.durationSeconds, session.meta.points, session.meta.photos)
    val cover = remember(session) { session.cover?.asImageBitmap() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SceneViewTokens.Radius.sm))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "Open the scan of $date, $summary" }
            .testTag(SESSION_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        val coverShape = RoundedCornerShape(SceneViewTokens.Radius.xs)
        Box(
            modifier = Modifier
                .size(CoverSize)
                .clip(coverShape)
                .background(SceneViewTokens.Stage.background),
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(CoverSize),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = date, style = OnScrimTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = summary, style = OnScrimBody, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete the scan of $date", tint = ArOverlay.onScrimMuted)
        }
    }
}

/**
 * The landing's primary action, full width at the thumb: the record dot and "Record your room",
 * with the privacy promise under it.
 */
@Composable
internal fun RecordRoomButton(onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        val shape = CircleShape
        Row(
            modifier = Modifier
                .padding(horizontal = Space.md)
                .widthIn(max = ArOverlay.maxWidth)
                .fillMaxWidth()
                .heightIn(min = RecordButtonHeight)
                .overMediaEdge(shape)
                .clip(shape)
                .background(ArOverlay.accentProgress)
                .clickable(role = Role.Button, onClick = onClick)
                .testTag(RECORD_ROOM_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(RecordDotSize)
                    .background(ArOverlay.accentRecord, CircleShape),
            )
            Text(
                text = ScanCopy.RECORD,
                style = SceneViewTokens.Type.card.copy(color = ArOverlay.onAccentProgress),
                modifier = Modifier.padding(start = Space.sm),
            )
        }
        Text(
            text = ScanCopy.PRIVACY,
            style = SceneViewTokens.Type.caption.copy(color = ArOverlay.onScrim),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(ArOverlay.scrimDark, CircleShape)
                .padding(horizontal = Space.sm, vertical = Space.xs / 2),
        )
    }
}

private val PillHeight = 44.dp
private val RecordButtonHeight = 60.dp
private val RecordDotSize = Space.md - Space.xs / 2
private val CoverSize = 56.dp

/** Two rows and a half: the list reads as one that scrolls, and the room stays in view. */
private val SessionListMaxHeight = 160.dp

internal const val WATCH_SAMPLE_TAG = "ar_rerun_watch_sample"
internal const val SESSIONS_CARD_TAG = "ar_rerun_sessions"
internal const val SESSION_ROW_TAG = "ar_rerun_session_row"
internal const val RECORD_ROOM_TAG = "ar_rerun_record_room"
