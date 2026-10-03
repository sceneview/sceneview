package io.github.sceneview.demo.demos

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SETTINGS_FAB_RESERVED_SPACE
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Glass
import io.github.sceneview.demo.theme.SceneViewTokens.Radius
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.theme.SceneViewTokens.Type
import io.github.sceneview.demo.theme.StageChrome

/*
 * The Rerun demo's landing, laid out as the iOS demo's (#4068) and the capture apps it answers to
 * (Polycam, Scaniverse, Reality Composer): what the demo does in one line, one primary action —
 * record your own room — the sample and "Open file" one tap away, and the sessions kept on this
 * phone as cards, each opening its replay, with Place, Share and Delete behind its menu.
 */

/** What the landing shows around the sessions list: an error to read, or a file being opened. */
internal class RerunLandingState(
    /** `null` while the list is read. */
    val sessions: List<LandingSession>?,
    val notice: String?,
    val openingFile: Boolean,
)

/** What the landing's controls do. */
internal class RerunLandingActions(
    val onRecord: () -> Unit,
    val onWatchSample: () -> Unit,
    val onOpenFile: () -> Unit,
    val onOpen: (LandingSession) -> Unit,
    /** Stands the session on a table in AR, as a dollhouse (#4075). */
    val onViewInAr: (LandingSession) -> Unit,
    val onDelete: (LandingSession) -> Unit,
    val onDismissNotice: () -> Unit,
)

/**
 * The landing's scrolling page, clear of the demo's header above and its settings button below.
 * It is a themed stage (#4080): ground, cards and text come from [LocalStageChrome], so the page,
 * its menu and its dialog all follow the app theme.
 */
@Composable
internal fun RerunLanding(state: RerunLandingState, actions: RerunLandingActions) {
    LandingPage(state, actions)
}

/** The themed stage the landing is drawn on. */
private val stage: StageChrome @Composable get() = LocalStageChrome.current

@Composable
private fun LandingPage(state: RerunLandingState, actions: RerunLandingActions) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(stage.ground),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = HeaderClearance, bottom = SETTINGS_FAB_RESERVED_SPACE)
                .padding(horizontal = Space.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = PageMaxWidth)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(
                        text = ScanCopy.LANDING_TITLE,
                        style = Type.display.copy(color = stage.onGlass),
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(text = ScanCopy.LANDING_BODY, style = Type.body.copy(color = stage.onGlassMuted))
                }
                RecordRoomCard(onClick = actions.onRecord)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    GlassAction(
                        icon = Icons.Rounded.PlayArrow,
                        label = ScanCopy.WATCH_SAMPLE,
                        onClick = actions.onWatchSample,
                        modifier = Modifier.weight(1f).testTag(WATCH_SAMPLE_TAG),
                    )
                    GlassAction(
                        icon = Icons.Outlined.FileOpen,
                        label = ScanCopy.OPEN_FILE,
                        onClick = actions.onOpenFile,
                        modifier = Modifier.testTag(OPEN_FILE_TAG),
                    )
                }
                // What happened to the file just opened: under the button that opened it, not
                // inside the list of sessions.
                state.notice?.let { NoticeCard(it, onDismiss = actions.onDismissNotice) }
                if (state.openingFile) OpeningCard()
                SessionsSection(state, actions)
            }
        }
    }
}

/**
 * The landing's primary action: the camera in its well, "Record your room" and the privacy
 * promise under it, on the accent fill.
 */
@Composable
internal fun RecordRoomCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RecordCardHeight)
            .clip(shape)
            .background(stage.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.md)
            .testTag(RECORD_ROOM_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Box(
            modifier = Modifier
                .size(RecordWellSize)
                .background(stage.onAccent.copy(alpha = WELL_ALPHA), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Videocam, contentDescription = null, tint = stage.onAccent)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = ScanCopy.RECORD, style = Type.title.copy(color = stage.onAccent))
            Text(
                text = ScanCopy.PRIVACY,
                style = Type.caption.copy(color = stage.onAccent.copy(alpha = MUTED_ALPHA)),
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = stage.onAccent,
        )
    }
}

/** A secondary action on glass: an icon and a label. */
@Composable
private fun GlassAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radius.md)
    Row(
        modifier = modifier
            .heightIn(min = GlassActionHeight)
            .clip(shape)
            .background(stage.glass)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterHorizontally),
    ) {
        Icon(icon, contentDescription = null, tint = stage.onGlass)
        Text(text = label, style = Type.body.copy(color = stage.onGlass), maxLines = 1)
    }
}

/** "Your sessions": the notice, the file being opened, then the cards — or the empty state. */
@Composable
private fun SessionsSection(state: RerunLandingState, actions: RerunLandingActions) {
    var confirming by remember { mutableStateOf<LandingSession?>(null) }
    var sharing by remember { mutableStateOf<LandingSession?>(null) }
    val sessions = state.sessions
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row {
            Text(
                text = ScanCopy.SESSIONS_TITLE,
                style = Type.card.copy(color = stage.onGlass),
                modifier = Modifier.weight(1f).alignByBaseline().semantics { heading() },
            )
            if (!sessions.isNullOrEmpty()) {
                Text(
                    text = ScanCopy.ON_THIS_PHONE,
                    style = Type.caption.copy(color = stage.onGlassMuted),
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        when {
            sessions == null -> Unit
            sessions.isEmpty() -> EmptySessions()
            else -> sessions.forEach { session ->
                SessionCard(
                    session = session,
                    onOpen = { actions.onOpen(session) },
                    onShare = { sharing = session },
                    onViewInAr = { actions.onViewInAr(session) },
                    onDelete = { confirming = session },
                )
            }
        }
    }
    sharing?.let { session ->
        RerunShareSheet(session.info, onDismiss = { sharing = null })
    }
    confirming?.let { session ->
        val destructive = MaterialTheme.colorScheme.error
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(ScanCopy.deleteTitle(session.info.title)) },
            text = { Text(ScanCopy.DELETE_DETAIL) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = null
                        actions.onDelete(session)
                    },
                ) { Text(ScanCopy.DELETE, color = destructive) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NoticeCard(text: String, onDismiss: () -> Unit) {
    LandingCard(background = ArOverlay.accentRecord.copy(alpha = NOTICE_ALPHA), testTag = NOTICE_TAG) {
        Text(text = text, style = Type.body.copy(color = stage.onGlass), modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.room_scan_close),
                tint = stage.onGlassMuted,
            )
        }
    }
}

@Composable
private fun OpeningCard() {
    LandingCard(background = stage.glass, testTag = OPENING_TAG) {
        CircularProgressIndicator(
            modifier = Modifier.size(ProgressSize),
            color = stage.accent,
            strokeWidth = ProgressStroke,
        )
        Text(text = ScanCopy.OPENING_FILE, style = Type.body.copy(color = stage.onGlass))
    }
}

/** A row of the sessions section: glass, rounded, its content spaced like a card. */
@Composable
private fun LandingCard(
    background: Color,
    testTag: String,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GlassActionHeight)
            .clip(RoundedCornerShape(Radius.md))
            .background(background)
            .padding(horizontal = Space.md, vertical = Space.xs)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        content = content,
    )
}

/** No session yet: a dashed outline where the cards will be, and what goes there. */
@Composable
private fun EmptySessions() {
    val dash = stage.onGlassMuted.copy(alpha = DASH_ALPHA)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = Glass.borderWidth.toPx()
                drawRoundRect(
                    color = dash,
                    cornerRadius = CornerRadius(Radius.md.toPx()),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH.toPx())),
                    ),
                )
            }
            .padding(Space.md)
            .testTag(SESSIONS_EMPTY_TAG),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(text = ScanCopy.SESSIONS_EMPTY_TITLE, style = Type.card.copy(color = stage.onGlass))
        Text(text = ScanCopy.SESSIONS_EMPTY, style = Type.body.copy(color = stage.onGlassMuted))
    }
}

/**
 * A kept session: its first photo, its title, when and where it came from, and its figures —
 * opening its replay on a tap, with Place (#4075), Share and Delete behind the menu.
 */
@Composable
private fun SessionCard(
    session: LandingSession,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onViewInAr: () -> Unit,
    onDelete: () -> Unit,
) {
    val info = session.info
    val origin = remember(info) { sessionOrigin(info) }
    val figures = remember(info) { ScanCopy.figures(info.pathMetres, info.points, info.photos, info.duration) }
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(stage.glass)
            .clickable(role = Role.Button, onClickLabel = "Open", onClick = onOpen)
            .padding(start = Space.sm, top = Space.sm, bottom = Space.sm)
            .testTag(SESSION_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Box(
            modifier = Modifier
                .size(ThumbnailSize)
                .clip(RoundedCornerShape(Radius.sm))
                .background(stage.ground),
        ) {
            session.thumbnail?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(ThumbnailSize),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs / 2)) {
            Text(
                text = info.title,
                style = Type.card.copy(color = stage.onGlass),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = origin,
                style = Type.caption.copy(color = stage.onGlassMuted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = figures,
                style = Type.caption.copy(color = stage.onGlassMuted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag(SESSION_MENU_TAG)) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${info.title}", tint = stage.onGlass)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.room_scan_place)) },
                    leadingIcon = { Icon(Icons.Outlined.ViewInAr, contentDescription = null) },
                    onClick = {
                        menu = false
                        onViewInAr()
                    },
                    modifier = Modifier.testTag(SESSION_VIEW_IN_AR_TAG),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.room_scan_share)) },
                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                    onClick = {
                        menu = false
                        onShare()
                    },
                )
                val destructive = MaterialTheme.colorScheme.error
                DropdownMenuItem(
                    text = { Text(ScanCopy.DELETE, color = destructive) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = destructive) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** The demo header's row — back button and title — which the page scrolls under. */
private val HeaderClearance = SceneViewTokens.Layout.touchTarget + Space.md * 2
private val PageMaxWidth = 560.dp
private val RecordCardHeight = Space.x4l
private val RecordWellSize = 56.dp
private val GlassActionHeight = 56.dp
private val ThumbnailSize = Space.x3l
private val ProgressSize = 20.dp
private val ProgressStroke = 2.dp
private val DASH = 6.dp
private const val WELL_ALPHA = 0.12f
private const val MUTED_ALPHA = 0.72f
private const val NOTICE_ALPHA = 0.24f
private const val DASH_ALPHA = 0.6f

internal const val WATCH_SAMPLE_TAG = "ar_rerun_watch_sample"
internal const val OPEN_FILE_TAG = "ar_rerun_open_file"
internal const val SESSIONS_EMPTY_TAG = "ar_rerun_sessions_empty"
internal const val SESSION_ROW_TAG = "ar_rerun_session_row"
internal const val SESSION_MENU_TAG = "ar_rerun_session_menu"
internal const val SESSION_VIEW_IN_AR_TAG = "ar_rerun_session_view_in_ar"
internal const val RECORD_ROOM_TAG = "ar_rerun_record_room"
internal const val NOTICE_TAG = "ar_rerun_notice"
internal const val OPENING_TAG = "ar_rerun_opening"
