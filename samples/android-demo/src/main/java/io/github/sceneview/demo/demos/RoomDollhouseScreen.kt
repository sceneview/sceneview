package io.github.sceneview.demo.demos

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded._3dRotation
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARHapticFeedback
import io.github.sceneview.ar.AutoPlacementNode
import io.github.sceneview.ar.AutoPlacementScene
import io.github.sceneview.ar.AutoPlacementState
import io.github.sceneview.ar.PlacementPhase
import io.github.sceneview.ar.rememberArGuidanceState
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.AR_CAMERA_INIT_SCRIM_TIMEOUT_MS
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoTopOverlayScope
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.isDemoCompactHeight
import io.github.sceneview.demo.SETTINGS_FAB_RESERVED_SPACE
import io.github.sceneview.demo.common.ArCameraStagePermissionGate
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.placement.PlacementActionCard
import io.github.sceneview.demo.common.rememberArCameraPermission
import io.github.sceneview.demo.common.placement.PlacementCard
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.DollhouseArControl
import io.github.sceneview.demo.demos.internal.DollhouseCopy
import io.github.sceneview.demo.demos.internal.DollhousePlace
import io.github.sceneview.demo.demos.internal.DollhouseScale
import io.github.sceneview.demo.demos.internal.DollhouseStage
import io.github.sceneview.demo.demos.internal.RerunStoredSession
import io.github.sceneview.demo.demos.internal.RoomDollhouse
import io.github.sceneview.demo.demos.internal.ScanRoomStatus
import io.github.sceneview.demo.demos.internal.dollhousePlace
import io.github.sceneview.demo.demos.internal.dollhouseStage
import io.github.sceneview.demo.demos.internal.scanRoomStatus
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Radius
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.theme.SceneViewTokens.Type
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
 * Which recording stands is always said, by name and date ([session]), and can be changed from
 * [sessions] ([onPickSession]). A recording that kept no surface — only the path walked and its
 * photos — says so and offers to record again, rather than standing an empty plinth.
 *
 * @param media the recording, once read; null while it is read, or when there is none.
 * @param session the kept session [media] is (or is being) read from, null when unknown.
 * @param sessions every kept session, newest first; null until listed.
 * @param sessionsKnown whether the list of kept sessions has been read.
 * @param hasSession whether there is a recording to open.
 * @param openFailed the recording could not be read.
 * @param startIn3d open on the 3D view rather than in AR (QA captures).
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod") // one screen, five stages
internal fun RoomDollhouseScreen(
    onBack: () -> Unit,
    title: String,
    media: RerunReplayMedia?,
    session: RerunStoredSession?,
    sessions: List<LandingSession>?,
    onPickSession: (RerunStoredSession) -> Unit,
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
    // The whole room, cut open: read once per recording, off the main thread — a dense cloud
    // takes its floor, its cut and its surfels rebuilt.
    val built by produceState<DollhouseBuild?>(null, media) {
        value = media?.let { withContext(Dispatchers.Default) { DollhouseBuild.of(it) } }
    }
    val build = built?.takeIf { it.media === media }
    val room = build?.room
    val hasSurfaces = room != null && RoomDollhouse.hasSurfaces(room)
    // The path the phone walked: off, so the room shows first; a switch in the settings.
    var showPath by rememberSaveable { mutableStateOf(false) }
    var availability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    val arAvailable = availability != ARCoreAvailability.Unsupported &&
        availability != ARCoreAvailability.SessionFailed
    var previewChosen by remember { mutableStateOf(startIn3d) }
    val stage = dollhouseStage(
        sessionsKnown = sessionsKnown,
        hasSession = hasSession,
        // Read: a recording with nothing at all in it is one without surfaces, not a failure.
        opened = build != null,
        openFailed = openFailed,
        arAvailable = arAvailable,
        previewChosen = previewChosen,
        hasSurfaces = hasSurfaces,
    )

    // One placement state per generation (DollhouseArControl): never the one a previous AR view
    // dismissed on its way out, which would never place again nor reset.
    var ar by remember { mutableStateOf(DollhouseArControl()) }
    val state = remember(ar.generation) { AutoPlacementState() }
    val armed by produceState(ar.armed(SystemClock.uptimeMillis()), ar) {
        val wait = ar.holdUntil - SystemClock.uptimeMillis()
        if (wait > 0) {
            value = false
            delay(wait)
        }
        value = true
    }
    val realSize = ar.realSize
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }
    val guidance = rememberArGuidanceState(state, trackingFailureReason = trackingFailure)
    var invalidMove by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(false) }
    var hintShown by remember { mutableStateOf(false) }
    val inRoom = stage == DollhouseStage.InRoom
    val origin = remember(session) { session?.let { sessionOrigin(it) } }
    val choices = remember(sessions) {
        sessions.orEmpty().let { kept ->
            val byId = kept.associateBy { it.id }
            RoomDollhouse.choices(kept.map { it.info }).mapNotNull { byId[it.id] }
        }
    }

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
    // Only the AR view needs the camera: the 3D view, the list and the messages never ask.
    val camera = rememberArCameraPermission()
    LaunchedEffect(state.phase, availability, inRoom, camera.granted) {
        // No camera yet is a permission card, not a camera that failed to start.
        val starting = inRoom && camera.granted
        if (starting && state.phase == PlacementPhase.INITIALIZING && availability == null) {
            delay(AR_CAMERA_INIT_SCRIM_TIMEOUT_MS)
            state.cameraFailed()
        }
    }
    fun reset() {
        invalidMove = false
        showHint = false
        // A fresh placement state: the anchor goes with the old one, and nothing is placed
        // until the hold is over.
        ar = ar.reset(SystemClock.uptimeMillis())
    }
    val canAdjust = state.hasPlacement &&
        (state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING)
    fun toggleRealSize() {
        ar = ar.toggleRealSize()
        // Each size starts unpinched: 1:12 is 1:12, real size is real size.
        state.selectPlacement()
        state.scaleTo(1f)
    }
    // The pinch runs continuously from a quarter of the tabletop scale up to real size, in both
    // modes (#4731): it used to stop at 400 % of the miniature, a 1:16 room at 1:4.
    val pinchRange = room?.fit?.let { DollhouseScale.pinchRange(it, realSize) }
    SideEffect { if (pinchRange != null) state.scaleRange = pinchRange }
    // How much the placed room is pinched: 1 outside AR, where nothing is.
    val pinch = if (inRoom && state.hasPlacement) state.scaleFactor else 1f
    // The height the placed room rests at, read off its placement; null until one stands.
    var restY by remember(state) { mutableStateOf<Float?>(null) }
    val place = room?.fit?.let { fit ->
        dollhousePlace(
            placed = inRoom && state.hasPlacement,
            realSize = realSize,
            absoluteScale = DollhouseScale.absolute(fit, realSize, pinch),
            restY = restY,
            floorY = state.floorHeight,
            cameraY = state.cameraHeight,
        )
    }
    val pickSession = { picked: RerunStoredSession ->
        if (picked.id != session?.id) onPickSession(picked)
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
    // The scale pill shows once the room stands: in the 3D view, or placed in AR.
    val showsScale = hasSurfaces && (stage == DollhouseStage.Preview || canAdjust)
    // A phone held sideways: the chip goes to the top end corner, the room to the band beside
    // the chrome (DollhousePreview, #4527).
    val compactHeight = isDemoCompactHeight()
    val card =when (state.phase) {
        PlacementPhase.NO_SURFACE -> PlacementCard.NO_SURFACE
        PlacementPhase.RECOVERY_FAILED -> PlacementCard.RECOVERY_FAILED
        else -> null
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_splat_room_title),
        onBack = onBack,
        firstFrameRendered = ready,
        // The permission card stands where the camera view would be: no scene to call ready.
        hasScene = !(inRoom && !camera.granted),
        loadingLabel = DollhouseCopy.OPENING,
        // The camera feed is media; the 3D view, the empty state and the error follow the theme.
        themedStage = !inRoom,
        // The mode pill belongs to 3D Scan's landing, not to a session opened from it.
        modeSwitch = null,
        // Held sideways, the pill above the dock would stand in the middle of the room's band:
        // the scale moves into the recording's chip, in the top end corner (#4527).
        peekHeader = if (fit != null && showsScale && !compactHeight) {
            DollhouseCopy.peek(title, fit, realSize && inRoom, pinch)
        } else null,
        onReset = if (inRoom) ::reset else null,
        // Which recording stands, by name and date, and the way to stand another.
        topOverlay = if (stage == DollhouseStage.InRoom || stage == DollhouseStage.Preview) {
            {
                DollhouseRecordingChip(
                    // Where the room stands, said only once it does, and only when it is known.
                    // Held sideways the scale line below already says "Real size": once is enough.
                    place = if (inRoom && !(compactHeight && showsScale && place == DollhousePlace.RealSize)) {
                        DollhouseCopy.place(place)
                    } else null,
                    title = session?.title ?: title,
                    origin = origin,
                    scale = if (fit != null && showsScale && compactHeight) {
                        DollhouseCopy.size(fit, realSize && inRoom, pinch)
                    } else null,
                    corner = compactHeight,
                    note = if (compactHeight && stage == DollhouseStage.Preview && !arAvailable) {
                        DollhouseCopy.NO_AR
                    } else null,
                    choices = choices,
                    current = session,
                    onPick = pickSession,
                )
            }
        } else null,
        controls = {
            Text(DollhouseCopy.INTRO, style = MaterialTheme.typography.bodyMedium)
            if (fit != null && hasSurfaces) {
                Text(
                    text = DollhouseCopy.size(fit, realSize && inRoom, pinch),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = Space.sm),
                )
                Box(Modifier.padding(top = Space.sm)) {
                    SwitchRow(DollhouseCopy.SHOW_PATH, showPath) { showPath = it }
                }
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
                DollhouseCopy.scaleToggle(realSize).let { toggle ->
                    DockItem(
                        icon = Icons.Outlined.Straighten,
                        label = toggle.label,
                        onClick = ::toggleRealSize,
                        enabled = canAdjust,
                        selected = toggle.selected,
                    )
                }.takeIf { inRoom },
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
        // Held sideways, nothing stands above the dock outside AR: the room's band runs beside
        // the dock, measured, and the "no AR" note moves into the chip (#4527).
        bottomOverlay = if (!compactHeight || stage == DollhouseStage.InRoom) {
            {
            when (stage) {
                // Without the camera the stage is the permission card, which says it all.
                DollhouseStage.InRoom -> if (camera.granted) {
                    val status = scanRoomStatus(
                        phase = state.phase,
                        scanReady = true,
                        cardShown = card != null,
                        coaching = guidance.isCoaching,
                        invalidMove = invalidMove,
                        showGestureHint = showHint,
                        lowLight = trackingFailure == TrackingFailureReason.INSUFFICIENT_LIGHT,
                    )
                    val text = if (!armed) DollhouseCopy.RESET_HOLD else when (status) {
                        null -> null
                        ScanRoomStatus.OpeningScan -> DollhouseCopy.OPENING
                        ScanRoomStatus.MoveSlowly -> DollhouseCopy.PLACE_HINT
                        ScanRoomStatus.KeepOnSurface -> stringResource(R.string.ar_place_keep_on_surface)
                        ScanRoomStatus.TrackingPaused -> stringResource(R.string.ar_place_tracking_paused)
                        ScanRoomStatus.TrackingPausedLowLight ->
                            stringResource(R.string.ar_place_tracking_paused_low_light)
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
                        { ar = ar.restart() },
                    )
                }
                DollhouseStage.Preview ->
                    if (!arAvailable) DemoStatusBanner(DollhouseCopy.NO_AR, tone = DemoStatusTone.Guidance)
                else -> Unit
            }
            }
        } else null,
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
            DollhouseStage.Failed, DollhouseStage.NoSurfaces -> {
                val failed = stage == DollhouseStage.Failed
                DollhouseMessage(
                    title = if (failed) title else DollhouseCopy.NO_SURFACES_TITLE,
                    body = if (failed) DollhouseCopy.OPEN_FAILED else DollhouseCopy.NO_SURFACES_BODY,
                    onRecord = onRecord,
                    testTag = if (failed) DOLLHOUSE_FAILED_TAG else DOLLHOUSE_NO_SURFACES_TAG,
                    recording = session?.title ?: title,
                    origin = origin,
                    others = choices.filter { it.id != session?.id },
                    onPick = pickSession,
                )
            }
            DollhouseStage.Preview -> if (build != null && room != null) {
                DollhousePreview(
                    build = build,
                    room = room,
                    showPath = showPath,
                    orbit = orbit,
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    modifier = Modifier.fillMaxSize().testTag(DOLLHOUSE_PREVIEW_TAG),
                    onShown = { previewShown = true },
                )
            }
            DollhouseStage.InRoom -> if (build != null && room != null) ArCameraStagePermissionGate(camera) {
                key(ar.sceneKey) {
                // Leaving AR (3D view, another recording) retires this placement state: its
                // AutoPlacementScene dismisses it on the way out, and a dismissed state never
                // places again — coming back to AR opened on a camera that never placed the room.
                DisposableEffect(media) { onDispose { ar = ar.leftAr() } }
                val scale = if (realSize) 1f else room.fit.scale
                val orientation = remember(room) { RoomDollhouse.orientation(room) }
                AutoPlacementScene(
                    assetReady = armed,
                    modifier = Modifier.fillMaxSize(),
                    state = state,
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    coachingContentPadding = PaddingValues(bottom = LocalDemoChromeBottomInset.current),
                    playbackDataset = arPlaybackDataset,
                    onARCoreAvailability = { availability = it },
                    onTrackingFailureChanged = { trackingFailure = it },
                ) { placement ->
                    AutoPlacementNode(placement, state, onInvalidMove = { invalidMove = it }) {
                        // The side the recording started from faces the user; at real size the
                        // room reaches away from where the miniature stood instead of being
                        // centred on it, around the camera (DollhouseOrientation).
                        // At real size the room rests on the floor, not on the table the
                        // miniature stood on (#4720).
                        // `placement.pose` is no state: a finished drag replaces it, then ends the
                        // adjustment. Reading the phase here is what recomposes this block after
                        // each placement and each committed drag, so the pose below is the new one.
                        val phase = state.phase
                        val placedY = placement.pose.ty()
                        val drop = if (realSize) {
                            RoomDollhouse.floorDrop(state.floorHeight, placedY, state.scaleFactor)
                        } else 0f
                        // Where it rests, for the chip: the floor once it dropped to it; kept while
                        // a drag is under way, read again once it is committed.
                        val rests = if (drop != 0f) state.floorHeight else placedY
                        SideEffect { if (phase != PlacementPhase.ADJUSTING) restY = rests }
                        Node(
                            position = Position(y = drop, z = orientation.offsetZ(realSize, scale)),
                            rotation = Rotation(y = orientation.yawDegrees),
                        ) {
                            DollhouseModel(
                                build = build,
                                room = room,
                                engine = engine,
                                materialLoader = materialLoader,
                                // The AR stage is media: the dark palette reads over any camera feed.
                                palette = chrome.debug,
                                base = chrome.card,
                                scale = scale,
                                styleScale = scale,
                                pickable = true,
                                showPath = showPath,
                                // At real size the room's floor goes on the real one, no plinth.
                                plinth = !realSize,
                            )
                        }
                    }
                }
                ARCameraInitScrim(state.phase == PlacementPhase.INITIALIZING && !state.hasCameraFrame, availability)
                }
            }
        }
    }
}

/**
 * The recording, by name and date, on glass, under where it stands ([place], when known): a tap lists every kept recording,
 * newest first, with how many surfaces each kept — picking one stands it instead.
 */
@Composable
private fun DemoTopOverlayScope.DollhouseRecordingChip(
    place: String?,
    title: String,
    origin: String?,
    choices: List<LandingSession>,
    current: RerunStoredSession?,
    onPick: (RerunStoredSession) -> Unit,
    scale: String? = null,
    corner: Boolean = false,
    note: String? = null,
) {
    val chrome = LocalStageChrome.current
    var menu by remember { mutableStateOf(false) }
    val canChange = choices.any { it.id != current?.id }
    // In the top end corner, as wide as the replay's corner card at most (#4527).
    val placement = if (corner) Modifier.align(Alignment.End) else Modifier
    Box(modifier = placement.padding(horizontal = Space.md)) {
        Row(
            modifier = Modifier
                .widthIn(max = if (corner) DOLLHOUSE_CORNER_CARD_WIDTH else MessageMaxWidth)
                .clip(RoundedCornerShape(Radius.md))
                .background(chrome.glass)
                .clickable(enabled = canChange, role = Role.Button, onClickLabel = DollhouseCopy.CHANGE_RECORDING) {
                    menu = true
                }
                .padding(horizontal = Space.md, vertical = Space.sm)
                .testTag(DOLLHOUSE_RECORDING_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                if (place != null) {
                    Text(
                        text = place,
                        style = Type.caption.copy(color = chrome.onGlassMuted),
                        maxLines = 1,
                        modifier = Modifier.testTag(DOLLHOUSE_PLACE_TAG),
                    )
                }
                Text(
                    text = title,
                    style = Type.card.copy(color = chrome.onGlass),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (origin != null) {
                    Text(
                        text = origin,
                        style = Type.caption.copy(color = chrome.onGlassMuted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (scale != null) {
                    Text(
                        text = scale,
                        style = Type.caption.copy(color = chrome.onGlass),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (note != null) {
                    Text(
                        text = note,
                        style = Type.caption.copy(color = chrome.onGlassMuted),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Space.xs),
                    )
                }
            }
            if (canChange) {
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = chrome.onGlass)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            choices.forEach { choice ->
                val info = choice.info
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(info.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = remember(info) { sessionOrigin(info) },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                            Text(
                                text = DollhouseCopy.surfaces(info.planes, info.points),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                    },
                    trailingIcon = if (info.id == current?.id) {
                        { Icon(Icons.Rounded.Check, contentDescription = "Standing now") }
                    } else null,
                    onClick = {
                        menu = false
                        onPick(info)
                    },
                    modifier = Modifier.testTag(DOLLHOUSE_CHOICE_TAG),
                )
            }
        }
    }
}

/**
 * The empty state, the error and the recording without surfaces: what happened, "Record your
 * room" to fix it and, when there are others, the recordings that could stand instead.
 */
@Composable
@Suppress("LongParameterList")
private fun DollhouseMessage(
    title: String,
    body: String,
    onRecord: () -> Unit,
    testTag: String,
    recording: String? = null,
    origin: String? = null,
    others: List<LandingSession> = emptyList(),
    onPick: (RerunStoredSession) -> Unit = {},
) {
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
                .verticalScroll(rememberScrollState())
                .testTag(testTag),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(
                    text = title,
                    style = Type.title.copy(color = chrome.onGlass),
                    modifier = Modifier.semantics { heading() },
                )
                if (recording != null && recording != title) {
                    Text(text = recording, style = Type.card.copy(color = chrome.onGlass))
                }
                if (origin != null) {
                    Text(text = origin, style = Type.caption.copy(color = chrome.onGlassMuted))
                }
                Text(text = body, style = Type.body.copy(color = chrome.onGlassMuted))
            }
            RecordRoomCard(onClick = onRecord)
            if (others.isNotEmpty()) {
                Text(
                    text = DollhouseCopy.OTHER_RECORDINGS,
                    style = Type.caption.copy(color = chrome.onGlassMuted),
                    modifier = Modifier.semantics { heading() },
                )
                others.take(MAX_OTHERS).forEach { other ->
                    val info = other.info
                    val otherOrigin = remember(info) { sessionOrigin(info) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.md))
                            .background(chrome.glass)
                            .clickable(role = Role.Button) { onPick(info) }
                            .padding(horizontal = Space.md, vertical = Space.sm)
                            .testTag(DOLLHOUSE_CHOICE_TAG),
                    ) {
                        Text(
                            text = info.title,
                            style = Type.card.copy(color = chrome.onGlass),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = otherOrigin + " · " + DollhouseCopy.surfaces(info.planes, info.points),
                            style = Type.caption.copy(color = chrome.onGlassMuted),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** The demo header's row — back button and title — which the message stays clear of. */
private val HeaderClearance = SceneViewTokens.Layout.touchTarget + Space.md * 2
private val MessageMaxWidth = 560.dp
private const val GESTURE_HINT_MS = 5_000L
private const val MAX_OTHERS = 4

internal const val DOLLHOUSE_EMPTY_TAG = "ar_rerun_dollhouse_empty"
internal const val DOLLHOUSE_FAILED_TAG = "ar_rerun_dollhouse_failed"
internal const val DOLLHOUSE_NO_SURFACES_TAG = "ar_rerun_dollhouse_no_surfaces"
internal const val DOLLHOUSE_RECORDING_TAG = "ar_rerun_dollhouse_recording"
internal const val DOLLHOUSE_CHOICE_TAG = "ar_rerun_dollhouse_choice"
internal const val DOLLHOUSE_PREVIEW_TAG = "ar_rerun_dollhouse_preview"
internal const val DOLLHOUSE_PLACE_TAG = "ar_rerun_dollhouse_place"
