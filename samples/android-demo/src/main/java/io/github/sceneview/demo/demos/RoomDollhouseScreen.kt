package io.github.sceneview.demo.demos

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.rounded._3dRotation
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARHapticFeedback
import io.github.sceneview.ar.AutoPlacementNode
import io.github.sceneview.ar.AutoPlacementScene
import io.github.sceneview.ar.PlacementPhase
import io.github.sceneview.ar.rememberArGuidanceState
import io.github.sceneview.ar.rememberAutoPlacementState
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.AR_CAMERA_INIT_SCRIM_TIMEOUT_MS
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SETTINGS_FAB_RESERVED_SPACE
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.placement.PlacementActionCard
import io.github.sceneview.demo.common.placement.PlacementCard
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.DollhouseCopy
import io.github.sceneview.demo.demos.internal.DollhouseStage
import io.github.sceneview.demo.demos.internal.RoomDollhouse
import io.github.sceneview.demo.demos.internal.ScanRoomStatus
import io.github.sceneview.demo.demos.internal.dollhouseStage
import io.github.sceneview.demo.demos.internal.scanRoomStatus
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.theme.SceneViewTokens.Type
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import kotlinx.coroutines.delay
import java.io.File

/**
 * "Your room, as a dollhouse" (#4075): a room recorded with the Rerun demo, cut open and stood on
 * a table in AR as a miniature — about 1:12 for a bedroom — that a drag moves, a twist turns and a
 * pinch resizes, with *Real size* to stand it at its own size.
 *
 * What it shows is always the user's own recording, never a stock asset: with none kept yet, the
 * screen offers to record one ([onRecord]). Without AR (the emulator, #2754), or on request, the
 * same miniature opens in a plain 3D view.
 *
 * @param media the recording, once read; null while it is read, or when there is none.
 * @param sessionsKnown whether the list of kept sessions has been read.
 * @param hasSession whether there is a recording to open.
 * @param openFailed the recording could not be read.
 * @param startIn3d open on the 3D view rather than in AR (QA captures).
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod") // one screen, four stages
internal fun RoomDollhouseScreen(
    onBack: () -> Unit,
    title: String,
    media: RerunReplayMedia?,
    sessionsKnown: Boolean,
    hasSession: Boolean,
    openFailed: Boolean,
    onRecord: () -> Unit,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    arPlaybackDataset: File?,
    startIn3d: Boolean = false,
) {
    // The whole room, cut open: read once per recording.
    val room = remember(media) { media?.let { RoomDollhouse.room(it.trace.frameAt(it.trace.duration)) } }
    var availability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    val arAvailable = availability != ARCoreAvailability.Unsupported &&
        availability != ARCoreAvailability.SessionFailed
    var previewChosen by remember { mutableStateOf(startIn3d) }
    val stage = dollhouseStage(
        sessionsKnown = sessionsKnown,
        hasSession = hasSession,
        opened = room != null,
        // A recording with nothing in it has no room to stand.
        openFailed = openFailed || (media != null && room == null),
        arAvailable = arAvailable,
        previewChosen = previewChosen,
    )

    var sessionKey by remember { mutableIntStateOf(0) }
    val state = key(sessionKey) { rememberAutoPlacementState() }
    val guidance = rememberArGuidanceState(state)
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var invalidMove by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(false) }
    var hintShown by remember { mutableStateOf(false) }
    var realSize by remember { mutableStateOf(false) }
    val inRoom = stage == DollhouseStage.InRoom

    ARHapticFeedback(state)
    LaunchedEffect(state.hasPlacement) {
        if (state.hasPlacement && !hintShown) {
            hintShown = true
            showHint = true
        }
    }
    LaunchedEffect(showHint) {
        if (showHint) {
            delay(GESTURE_HINT_MS)
            showHint = false
        }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == PlacementPhase.ADJUSTING) showHint = false
        if (state.phase != PlacementPhase.PLACED && state.phase != PlacementPhase.ADJUSTING) invalidMove = false
    }
    LaunchedEffect(state.phase, availability, inRoom) {
        if (inRoom && state.phase == PlacementPhase.INITIALIZING && availability == null) {
            delay(AR_CAMERA_INIT_SCRIM_TIMEOUT_MS)
            state.cameraFailed()
        }
    }
    fun reset() {
        invalidMove = false
        state.resetPlacement(SystemClock.uptimeMillis())
    }
    val canAdjust = state.hasPlacement &&
        (state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING)
    fun toggleRealSize() {
        realSize = !realSize
        // Each size starts unpinched: 1:12 is 1:12, real size is real size.
        state.selectPlacement()
        state.scaleTo(1f)
    }

    // The 3D view frames the room afresh each time it opens.
    val orbit = remember(room, stage == DollhouseStage.Preview) { ArDebugOrbitCamera(drift = false) }
    var previewShown by remember(room, stage == DollhouseStage.Preview) { mutableStateOf(false) }
    val ready = rememberUpdatedState(
        when (stage) {
            DollhouseStage.Loading -> false
            DollhouseStage.Preview -> previewShown
            else -> true
        },
    )
    val fit = room?.fit
    val card = when (state.phase) {
        PlacementPhase.NO_SURFACE -> PlacementCard.NO_SURFACE
        PlacementPhase.RECOVERY_FAILED -> PlacementCard.RECOVERY_FAILED
        else -> null
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_splat_room_title),
        onBack = onBack,
        firstFrameRendered = ready,
        loadingLabel = DollhouseCopy.OPENING,
        // The camera feed is media; the 3D view, the empty state and the error follow the theme.
        themedStage = !inRoom,
        peekHeader = if (fit != null && (stage == DollhouseStage.Preview || canAdjust)) {
            DollhouseCopy.peek(title, fit, realSize && inRoom)
        } else null,
        onReset = if (inRoom) ::reset else null,
        controls = {
            Text(DollhouseCopy.INTRO, style = MaterialTheme.typography.bodyMedium)
            if (fit != null) {
                Text(
                    text = DollhouseCopy.size(fit, realSize && inRoom),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = Space.sm),
                )
            }
        },
        dock = when (stage) {
            DollhouseStage.InRoom, DollhouseStage.Preview -> listOfNotNull(
                DockItem(
                    icon = Icons.Rounded.ViewInAr,
                    label = DollhouseCopy.VIEW_AR_LABEL,
                    caption = DollhouseCopy.VIEW_AR,
                    onClick = { previewChosen = false },
                    enabled = arAvailable,
                    selected = inRoom,
                ),
                DockItem(
                    icon = Icons.Rounded._3dRotation,
                    label = DollhouseCopy.VIEW_3D_LABEL,
                    caption = DollhouseCopy.VIEW_3D,
                    onClick = { if (inRoom) previewChosen = true else orbit.recenter() },
                    selected = !inRoom,
                ),
                DockItem(
                    icon = Icons.Outlined.Straighten,
                    label = if (realSize) DollhouseCopy.MINIATURE else DollhouseCopy.REAL_SIZE,
                    caption = if (realSize) DollhouseCopy.MINIATURE else DollhouseCopy.REAL_SIZE,
                    onClick = ::toggleRealSize,
                    enabled = canAdjust,
                    selected = realSize,
                ).takeIf { inRoom },
                DockItem(
                    icon = Icons.Outlined.RestartAlt,
                    label = stringResource(R.string.ar_dock_reset_label),
                    caption = DollhouseCopy.RESET,
                    onClick = ::reset,
                    enabled = state.hasPlacement,
                ).takeIf { inRoom },
            )
            else -> emptyList()
        },
        bottomOverlay = {
            when (stage) {
                DollhouseStage.InRoom -> {
                    val status = scanRoomStatus(
                        phase = state.phase,
                        scanReady = true,
                        cardShown = card != null,
                        coaching = guidance.isCoaching,
                        invalidMove = invalidMove,
                        showGestureHint = showHint,
                        lowLight = trackingFailure == TrackingFailureReason.INSUFFICIENT_LIGHT,
                    )
                    val text = when (status) {
                        null -> null
                        ScanRoomStatus.OpeningScan -> DollhouseCopy.OPENING
                        ScanRoomStatus.MoveSlowly -> DollhouseCopy.PLACE_HINT
                        ScanRoomStatus.KeepOnSurface -> stringResource(R.string.ar_place_keep_on_surface)
                        ScanRoomStatus.TrackingPaused -> stringResource(R.string.ar_place_tracking_paused)
                        ScanRoomStatus.TrackingPausedLowLight -> stringResource(R.string.ar_place_tracking_paused) +
                            " " + stringResource(R.string.ar_place_try_brighter_area)
                        ScanRoomStatus.FindingPlacement -> stringResource(R.string.ar_place_finding_placement)
                        ScanRoomStatus.GestureHint -> DollhouseCopy.GESTURE_HINT
                        ScanRoomStatus.Scale -> fit?.let { DollhouseCopy.pinched(it, state.scaleFactor, realSize) }
                    }
                    DemoStatusBanner(text, tone = DemoStatusTone.Guidance)
                    PlacementActionCard(
                        card,
                        { previewChosen = true },
                        { state.keepScanning(SystemClock.uptimeMillis()) },
                        ::reset,
                        { sessionKey++ },
                    )
                }
                DollhouseStage.Preview ->
                    if (!arAvailable) DemoStatusBanner(DollhouseCopy.NO_AR, tone = DemoStatusTone.Guidance)
                else -> Unit
            }
        },
    ) {
        val chrome = LocalStageChrome.current
        when (stage) {
            DollhouseStage.Loading -> Unit // the scaffold's cover says it is opening
            DollhouseStage.Empty -> DollhouseMessage(
                title = DollhouseCopy.EMPTY_TITLE,
                body = DollhouseCopy.EMPTY_BODY,
                onRecord = onRecord,
                testTag = DOLLHOUSE_EMPTY_TAG,
            )
            DollhouseStage.Failed -> DollhouseMessage(
                title = title,
                body = DollhouseCopy.OPEN_FAILED,
                onRecord = onRecord,
                testTag = DOLLHOUSE_FAILED_TAG,
            )
            DollhouseStage.Preview -> if (media != null && room != null) {
                DollhousePreview(
                    media = media,
                    room = room,
                    orbit = orbit,
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    modifier = Modifier.fillMaxSize().testTag(DOLLHOUSE_PREVIEW_TAG),
                    onShown = { previewShown = true },
                )
            }
            DollhouseStage.InRoom -> if (media != null && room != null) key(sessionKey) {
                val scale = if (realSize) 1f else room.fit.scale
                AutoPlacementScene(
                    assetReady = true,
                    modifier = Modifier.fillMaxSize(),
                    state = state,
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    playbackDataset = arPlaybackDataset,
                    onARCoreAvailability = { availability = it },
                    onTrackingFailureChanged = { trackingFailure = it },
                ) { placement ->
                    AutoPlacementNode(placement, state, onInvalidMove = { invalidMove = it }) {
                        DollhouseModel(
                            media = media,
                            room = room,
                            engine = engine,
                            materialLoader = materialLoader,
                            // The AR stage is media: the dark palette reads over any camera feed.
                            palette = chrome.debug,
                            base = chrome.card,
                            scale = scale,
                            styleScale = scale,
                            pickable = true,
                        )
                    }
                }
                ARCameraInitScrim(state.phase == PlacementPhase.INITIALIZING && !state.hasCameraFrame, availability)
            }
        }
    }
}

/** The empty state and the error: what happened, and "Record your room" to fix it. */
@Composable
private fun DollhouseMessage(title: String, body: String, onRecord: () -> Unit, testTag: String) {
    val chrome = LocalStageChrome.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(chrome.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = HeaderClearance, bottom = SETTINGS_FAB_RESERVED_SPACE)
            .padding(horizontal = Space.md),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = MessageMaxWidth)
                .fillMaxWidth()
                .testTag(testTag),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(
                    text = title,
                    style = Type.title.copy(color = chrome.onGlass),
                    modifier = Modifier.semantics { heading() },
                )
                Text(text = body, style = Type.body.copy(color = chrome.onGlassMuted))
            }
            RecordRoomCard(onClick = onRecord)
        }
    }
}

/** The demo header's row — back button and title — which the message stays clear of. */
private val HeaderClearance = SceneViewTokens.Layout.touchTarget + Space.md * 2
private val MessageMaxWidth = 560.dp
private const val GESTURE_HINT_MS = 5_000L

internal const val DOLLHOUSE_EMPTY_TAG = "ar_rerun_dollhouse_empty"
internal const val DOLLHOUSE_FAILED_TAG = "ar_rerun_dollhouse_failed"
internal const val DOLLHOUSE_PREVIEW_TAG = "ar_rerun_dollhouse_preview"
