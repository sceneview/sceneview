package io.github.sceneview.demo.demos

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded._3dRotation
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARCoreAvailabilityOverlay
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.ar.rememberHostLifecycle
import io.github.sceneview.ar.rerun.RerunBridge
import io.github.sceneview.ar.rerun.rememberRerunBridge
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.LocalDemoFullScreen
import io.github.sceneview.demo.LocalDemoSheetCover
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.QaCameraBackdrop
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.common.qaCameraBackdropEnabled
import io.github.sceneview.demo.common.qaCameraBackdropSurfaceType
import io.github.sceneview.demo.common.ArDemoPermissionGate
import io.github.sceneview.demo.common.qaStateOverridesAllowed
import io.github.sceneview.demo.common.rememberQaCameraBackdropActive
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.demos.internal.ArDebugFormat
import io.github.sceneview.demo.demos.internal.ArDebugLogPlayer
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.OrbitBand
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.RERUN_DEVELOPER_TITLE
import io.github.sceneview.demo.demos.internal.RERUN_INTRO
import io.github.sceneview.demo.demos.internal.RERUN_REPLAY_INTRO
import io.github.sceneview.demo.demos.internal.RERUN_SETUP_STEPS
import io.github.sceneview.demo.demos.internal.RERUN_SETUP_TITLE
import io.github.sceneview.demo.demos.internal.ReplayGeometry
import io.github.sceneview.demo.demos.internal.RerunReplayAssets
import io.github.sceneview.demo.demos.internal.RerunScanModel
import io.github.sceneview.demo.demos.internal.ScanDevice
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunExportFormat
import io.github.sceneview.demo.demos.internal.RerunExportSource
import io.github.sceneview.demo.demos.internal.RerunImportFailure
import io.github.sceneview.demo.demos.internal.RerunSessionSource
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.RerunSetupStep
import io.github.sceneview.demo.demos.internal.RerunStatusUx
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DollhouseCopy
import io.github.sceneview.demo.demos.internal.RoomDollhouse
import io.github.sceneview.demo.demos.internal.ScanBack
import io.github.sceneview.demo.demos.internal.ScanClock
import io.github.sceneview.demo.demos.internal.ScanActivity
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.demos.internal.RescanRefusal
import io.github.sceneview.demo.demos.internal.ScanSaveFailure
import io.github.sceneview.demo.demos.internal.OpenedCapture
import io.github.sceneview.demo.demos.internal.RerunShareCopy
import io.github.sceneview.demo.demos.internal.ScanWallWalk
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.ScanDetail
import io.github.sceneview.demo.demos.internal.turnedBy
import kotlin.math.PI
import kotlin.math.sin
import io.github.sceneview.demo.demos.internal.ScanFollow
import io.github.sceneview.demo.demos.internal.ScanLens
import io.github.sceneview.demo.demos.internal.ScanPhase
import io.github.sceneview.demo.demos.internal.ScanStageSplit
import io.github.sceneview.demo.theme.LocalStageChrome
import androidx.compose.foundation.layout.height
import io.github.sceneview.demo.demos.internal.ScanCutoff
import io.github.sceneview.demo.demos.internal.PlanOffer
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.ScanScopeCopy
import io.github.sceneview.demo.demos.internal.HomeScan
import io.github.sceneview.demo.demos.internal.HomeRoom
import io.github.sceneview.demo.demos.internal.RerunSyntheticBedroom
import io.github.sceneview.demo.theme.SceneViewTokens.Type
import io.github.sceneview.demo.demos.internal.of
import io.github.sceneview.demo.demos.internal.parseArDebugLog
import io.github.sceneview.demo.demos.internal.rerunSaveActionUx
import io.github.sceneview.demo.demos.internal.rerunSaveFailureMessage
import io.github.sceneview.demo.demos.internal.rerunShowsSaveAction
import io.github.sceneview.demo.demos.internal.rerunStatusUx
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 3D Scan (the Rerun demo, id `ar-rerun`): a space scanned with ARCore, rebuilt in 3D.
 *
 * It opens on a **landing** laid out as the iOS demo's (#4068): "Start scanning", "See an
 * example", "Open scan", and the scans kept on this phone (#4627).
 *
 * The **sample** is a real ARCore session bundled with the app — the camera's path, its photos in
 * their frustums, the planes it found textured with the room, the coloured point cloud and the
 * models placed on them — orbitable, with a filmstrip of the recorded frames that scrubs it. It
 * needs no ARCore, so it works on every device and on the emulator (#2754).
 *
 * **Start scanning** captures a space live with ARCore — the path, the coloured points, the
 * planes and a photo every few steps, growing in a 3D card while you walk. Pause freezes it;
 * Finish opens a review that Keep scanning leaves for the same capture; Save scan writes it on
 * the phone, as the same three files the sample ships in the iOS demo's session layout, and
 * opens it in the same replay; it stays under "Your scans" until deleted, and shares as a
 * `.svscan` scan file the iOS demo opens too. Nothing leaves the phone unless you share it.
 *
 * **Place** (#4075), at the end of a replay of your own room or from a session's menu, stands
 * that room on a table as a miniature — a dollhouse — through [RoomDollhouseScreen]. With
 * [startInDollhouse] the demo opens there directly: the `ar-splat-room` card of the home screen.
 *
 * Advanced, from the sheet: the live screen also streams the session to the Rerun viewer on a
 * computer. The bridge auto-connects to the Python recorder at `127.0.0.1:9876` — for USB,
 * `adb reverse tcp:9876 tcp:9876` — and "Save & Share recording" flushes a `.rrd` file.
 */
@Composable
fun ARRerunDemo(onBack: () -> Unit, startInDollhouse: Boolean = false) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // The replay's shaders compile while the landing is read, not behind its loading cover.
    LaunchedEffect(engine, materialLoader) { warmUpReplay(engine, materialLoader) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    ForgetRoomSurveysOnExit()
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576): that harness drives the live path.
    val arPlaybackDataset = rememberArPlaybackDataset()

    val qaState = remember { DemoSettings.qaDemoState?.takeIf { qaStateOverridesAllowed() } }
    val qaReplay = remember { RerunReplayQaState.of(qaState) }
    var screen by remember {
        mutableStateOf(
            when {
                startInDollhouse || qaState in DOLLHOUSE_QA_STATES -> RerunScreen.Dollhouse
                qaState == QA_STATE_CHOOSE -> RerunScreen.Choose
                qaState == QA_STATE_HOME_SUMMARY -> RerunScreen.Home
                qaState in MODEL_QA_STATES -> RerunScreen.Model
                qaReplay != null -> RerunScreen.Replay
                arPlaybackDataset != null || qaState in LIVE_QA_STATES -> RerunScreen.Live
                else -> RerunScreen.Landing
            },
        )
    }
    var mode by remember { mutableStateOf(qaReplay?.mode ?: RerunMode.Scene) }

    // The sample lives here, above every screen: the landing turns it, the replay plays it, and
    // the QA record take is fed from it.
    val sample by produceState<RerunReplayMedia?>(null) {
        value = runCatching { loadRerunReplay(context) }.getOrNull()
    }

    // The sessions kept on this phone, re-read whenever one is saved, opened from a file or deleted.
    val store = remember { rerunSessionStore(context.applicationContext) }
    var sessionsVersion by remember { mutableIntStateOf(0) }
    // A scan saved after its capture screen was left (#4500) lands in the list too.
    val savedAway = RerunScanBuilds.savedAway
    val sessions by produceState<List<LandingSession>?>(null, sessionsVersion, savedAway) {
        value = store.landingSessions()
    }
    // What the landing says about the last file opened: why it did not open, or that it is opening.
    var notice by remember { mutableStateOf<String?>(null) }
    var openingFile by remember { mutableStateOf(false) }

    // What the replay shows: the sample, or one of your scans — the same view either way.
    var showingScan by remember { mutableStateOf(qaState in PLAN_QA_STATES) }
    var scanMedia by remember { mutableStateOf<RerunReplayMedia?>(null) }
    // The replay is titled after the session it shows, as the card that opened it is.
    var scanTitle by remember { mutableStateOf(ScanCopy.REPLAY_TITLE) }
    // The scan's own files, for the export sheet; the sample's are read from the assets there.
    var scanPack by remember { mutableStateOf<RerunCapturePack?>(null) }
    // A part of the open scan scanned again (#4736): for the live screen it was started for only.
    var rescanTarget by remember { mutableStateOf<RescanTarget?>(null) }
    LaunchedEffect(screen) { if (screen != RerunScreen.Live) rescanTarget = null }
    // What the scan shows (#4683), kept with it: an object's has no floor plan and no room figures.
    var scanKind by remember { mutableStateOf(ScanScope.Room) }
    // The floor it covered, the figure its replay ends on, said on its result too (#4734).
    val scanFloorCovered by produceState<Float?>(null, scanMedia, showingScan, scanKind) {
        val scan = scanMedia?.takeIf { showingScan && scanKind.mapsGround }
        value = scan?.let { withContext(Dispatchers.Default) { it.floorCoveredSquareMetres() } }
    }
    // The kept session the replay shows, when it was opened from the list: Place stands that one.
    var scanId by remember { mutableStateOf<String?>(null) }
    var exporting by remember {
        mutableStateOf(qaReplay == RerunReplayQaState.ReplayExport || qaState == QA_STATE_MODEL_EXPORT)
    }
    var opening by remember { mutableStateOf<Job?>(null) }
    // Bumped on every open, so reopening the same replay frames and plays it afresh.
    var openCount by remember { mutableIntStateOf(0) }
    val media = if (showingScan) scanMedia else sample
    // The scan's result (RerunModelUi.kt): your room as one model, kept with the scan. Picked once
    // when a scan opens, so the build is not restarted by a recomposition. The sample has none.
    var modelSource by remember {
        mutableStateOf<RerunModelSource?>(
            when (qaState) {
                QA_STATE_MODEL_SYNTHETIC, QA_STATE_MODEL_EXPORT -> RerunModelSource.Synthetic()
                QA_STATE_MODEL_POINTS -> RerunModelSource.Synthetic(meshFails = true)
                else -> null
            },
        )
    }
    // The model on screen, once built or read back: what the export sheet writes first.
    var resultModel by remember { mutableStateOf<RerunScanModel?>(null) }
    // Whether the scan shown has a result screen: the replay's Back returns to it.
    var hasResult by remember { mutableStateOf(modelSource != null) }

    val replaySession = remember { ArDebugSession().apply { loops = true } }
    // The band of the stage the chrome leaves free, per orientation: the room is fitted into it.
    val stageBand = LocalConfiguration.current.let { OrbitBand.stage(it.screenWidthDp.toFloat() / it.screenHeightDp) }
    // QA captures hold still: no intro fly-in, no idle sway. Each opening is framed afresh.
    val replayOrbit = remember(media, openCount) { ArDebugOrbitCamera(drift = qaState == null, band = stageBand) }
    LaunchedEffect(media, openCount) {
        val replay = media ?: return@LaunchedEffect
        replaySession.trace = replay.trace
        // Held until the stage is on screen: the sample then plays from its first frame; your own
        // scan opens whole, on its last frame, ready to be turned with a finger.
        val at = if (showingScan) 1f else qaReplay?.pauseAt ?: 0f
        replaySession.scrubTo(replay.trace.duration * at)
    }
    // The replay opens when its first frames are on screen, not when its files are read: the
    // cover, the chrome and the playback all wait for the stage, so nothing shows up empty.
    var revealed by remember(media, openCount) { mutableStateOf(false) }
    LaunchedEffect(revealed, media) {
        val held = qaReplay?.pauseAt != null || qaState == QA_STATE_MODEL_SYNTHETIC
        if (revealed && !showingScan && !held) replaySession.playFromStart()
    }
    LaunchedEffect(mode, replayOrbit) { replayOrbit.overhead = mode == RerunMode.Map }

    // The dollhouse (#4075): one of your sessions, stood on a table in AR. [dollhouseRequest] is
    // the session asked for (null: the newest), [dollhouseMedia] the one read, and Back returns to
    // [dollhouseReturn] — the screen it was opened from (#4619: the replay or the model, not the
    // list) — or leaves the demo when it opened straight on the dollhouse.
    var dollhouseReturn by remember { mutableStateOf(if (startInDollhouse) null else RerunScreen.Landing) }
    var dollhouseRequest by remember { mutableStateOf<String?>(null) }
    var dollhouseMedia by remember { mutableStateOf<RerunReplayMedia?>(null) }
    var dollhouseTitle by remember { mutableStateOf(ScanCopy.REPLAY_TITLE) }
    var dollhouseFailed by remember { mutableStateOf(false) }
    // Record, opened from the dollhouse's empty state, comes back to it with the new room.
    var recordingForDollhouse by remember { mutableStateOf(false) }
    // What is scanned (#4647): asked before each scan, the last choice remembered.
    var scanScope by remember {
        mutableStateOf(
            when (qaState) {
                QA_STATE_RECORD_OBJECT -> ScanScope.Object
                QA_STATE_RECORD_HOME, QA_STATE_HOME_SUMMARY -> ScanScope.Home
                QA_STATE_RECORD, QA_STATE_RECORD_NO_DEPTH, QA_STATE_RECORD_WALLS, QA_STATE_RECORD_WALLS_STALLED -> ScanScope.Room
                QA_STATE_RECORD_AREA -> ScanScope.Area
                else -> ScanScopeStore.last(context)
            },
        )
    }
    // A whole home in progress: the rooms saved in this session, each measured on its own. Its id
    // tells it from the next: a room saved, or measured, after its home was left joins only that one.
    var home by remember { mutableStateOf(HomeScan()) }
    // The room of the home whose plan is open full screen.
    var homePlanRoom by remember { mutableStateOf<HomeRoom?>(null) }
    val dollhouseSession = if (qaState == QA_STATE_DOLLHOUSE_EMPTY) {
        null
    } else {
        sessions?.let { kept -> RoomDollhouse.pickSession(kept.map { it.info }, dollhouseRequest) }
    }
    // The kept session on the table, said by name and date. A recording handed over already read
    // (just recorded, or from the replay) without its id is found by its title.
    val dollhouseShown = when {
        dollhouseRequest != null || dollhouseMedia == null -> dollhouseSession
        else -> sessions?.firstOrNull { it.info.title == dollhouseTitle }?.info
    }
    LaunchedEffect(screen, dollhouseSession?.id) {
        if (screen != RerunScreen.Dollhouse || dollhouseMedia != null || dollhouseFailed) return@LaunchedEffect
        val session = dollhouseSession ?: return@LaunchedEffect
        dollhouseTitle = session.title
        val capture = withContext(Dispatchers.IO) { store.capture(session.id) }
        val opened = capture?.let { runCatching { loadRerunSession(it) }.getOrNull() }
        // Another recording picked meanwhile: this one no longer stands.
        if (dollhouseRequest != null && dollhouseRequest != session.id) return@LaunchedEffect
        if (opened == null) dollhouseFailed = true else dollhouseMedia = opened
    }
    val openDollhouse = { id: String?, title: String, media: RerunReplayMedia? ->
        opening?.cancel()
        opening = null
        dollhouseRequest = id
        dollhouseTitle = title
        dollhouseMedia = media
        dollhouseFailed = false
        // Another session picked on the dollhouse, or one just recorded for it, keeps its way back.
        if (screen == RerunScreen.Landing || screen == RerunScreen.Replay || screen == RerunScreen.Model) {
            dollhouseReturn = screen
        }
        screen = RerunScreen.Dollhouse
    }

    val toLanding = {
        opening?.cancel()
        opening = null
        screen = RerunScreen.Landing
    }
    // The floor plan opens from an export sheet (#4540) and Back returns to the screen whose sheet
    // opened it: the replay's or the result's (#4599). QA opens it straight, with no opener.
    var planOpener by remember { mutableStateOf<RerunScreen?>(null) }
    val leavePlan = { screen = planReturnOf(planOpener, hasResult) }
    val leaveDollhouse = { dollhouseReturn?.let { screen = it } ?: onBack() }
    val leaveLive = {
        if (recordingForDollhouse) {
            recordingForDollhouse = false
            screen = RerunScreen.Dollhouse
        } else if (scanScope.roomByRoom && home.rooms.isNotEmpty()) {
            // A home with rooms saved ends on their summary, whichever way the capture is left.
            screen = RerunScreen.Home
        } else {
            toLanding()
        }
    }
    BackHandler(enabled = screen != RerunScreen.Landing) {
        when (screen) {
            RerunScreen.Dollhouse -> leaveDollhouse()
            RerunScreen.Live -> leaveLive()
            RerunScreen.Replay -> if (showingScan && hasResult) screen = RerunScreen.Model else toLanding()
            RerunScreen.Plan -> leavePlan()
            RerunScreen.HomePlan -> screen = RerunScreen.Home
            else -> toLanding()
        }
    }

    val openSample = {
        showingScan = false
        mode = RerunMode.Scene
        openCount++
        screen = RerunScreen.Replay
    }
    // A scan, just recorded or reopened: its result when it has one (a model kept with it, or the
    // depth to build one), else its replay. [kept]: the store already holds its model.
    val openResult = { media: RerunReplayMedia, title: String, pack: RerunCapturePack, id: String?, kept: Boolean,
            kind: ScanScope ->
        scanId = id
        scanKind = kind
        scanMedia = media
        scanTitle = title
        scanPack = pack
        showingScan = true
        mode = RerunMode.Scene
        openCount++
        // A kept model is read back as it is: no source, so nothing is built again.
        modelSource = if (kept) null else RerunModelSource.of(pack, media)
        hasResult = kept || modelSource != null
        resultModel = null
        screen = if (hasResult) RerunScreen.Model else RerunScreen.Replay
    }
    val openStored = { id: String, title: String, kind: ScanScope ->
        scanKind = kind
        scanMedia = null
        scanTitle = title
        scanPack = null
        scanId = id
        showingScan = true
        resultModel = null
        mode = RerunMode.Scene
        opening?.cancel()
        opening = scope.launch {
            // A scan with its model opens on it at once; the recording is read meanwhile. Without
            // one — or with one that no longer reads — the replay's cover says "Opening your scan…"
            // while its files are read, then the model is built again from them.
            val keptModel = withContext(Dispatchers.IO) { store.model(id) }
            val kept = keptModel != null
            resultModel = keptModel
            modelSource = null
            hasResult = kept
            screen = if (kept) RerunScreen.Model else RerunScreen.Replay
            val capture = withContext(Dispatchers.IO) { store.capture(id) }
            val opened = capture?.let { runCatching { loadRerunSession(it) }.getOrNull() }
            when {
                capture != null && opened != null -> {
                    if (screen == RerunScreen.Model && kept) {
                        // Already on the model: the recording only completes the scan behind it.
                        scanMedia = opened
                        scanPack = capture
                        openCount++
                    } else {
                        openResult(opened, title, capture, id, kept, kind)
                    }
                    // A raw surface kept for running out of memory, with attempts left, is finished
                    // again from the recording, behind the surface already shown
                    // ([io.github.sceneview.demo.demos.internal.RerunScanModel.retriesFinish]).
                    if (keptModel?.retriesFinish == true) modelSource = RerunModelSource.of(capture, opened)
                }
                kept -> Unit
                else -> {
                    notice = ScanCopy.OPEN_FAILED
                    screen = RerunScreen.Landing
                }
            }
        }
    }
    // A file opened here ("Open file") or handed over by another app: kept as a session, then
    // replayed — or, when it is not one this app reads, the landing says why.
    val importFile = { uri: Uri, done: () -> Unit ->
        notice = null
        openingFile = true
        scope.launch {
            val result = try { importSession(context, store, uri) } finally { done() }
            openingFile = false
            sessionsVersion++
            result
                .onSuccess { openStored(it.id, it.title, it.scope) }
                .onFailure { failure ->
                    notice = (failure as? RerunImportFailure)?.let { ScanCopy.importFailure(it, it.message.orEmpty()) }
                        ?: ScanCopy.OPEN_FAILED
                }
        }
        Unit
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFile(it) {} }
    }
    // QA's floor plan: the ray-cast room, kept as a scan would be, then opened on its plan.
    if (qaState in PLAN_QA_STATES) {
        LaunchedEffect(Unit) {
            val pack = syntheticPlanCapture(partial = qaState == QA_STATE_PLAN_PARTIAL)
            val kept = withContext(Dispatchers.IO) {
                // It replaces the one session it kept the time before, whose id it wrote beside
                // the sessions — never another session, whatever that one is titled.
                val mine = File(store.root, QA_PLAN_MARKER)
                val last = runCatching { mine.readText().trim() }.getOrNull()
                store.list().firstOrNull { it.id == last && it.title == QA_PLAN_TITLE }?.let { store.delete(it.id) }
                store.save(pack, QA_PLAN_TITLE, RerunSessionSource.Recorded).also { mine.writeText(it.id) }
            }
            sessionsVersion++
            val opened = loadRerunSession(pack) ?: return@LaunchedEffect
            scanId = kept.id
            scanMedia = opened
            scanTitle = kept.title
            scanPack = pack
            scanKind = ScanScope.Room
            showingScan = true
            screen = RerunScreen.Plan
        }
    }
    // QA's whole home (#4647): two ray-cast rooms, measured each on its own.
    if (qaState == QA_STATE_HOME_SUMMARY) {
        LaunchedEffect(Unit) {
            val rooms = listOf<suspend () -> RerunCapturePack>(
                { syntheticPlanCapture(partial = false) },
                {
                    syntheticPlanCapture(partial = false, RerunSyntheticBedroom.scene) {
                        RerunSyntheticBedroom.poses()
                    }
                },
            )
            // Each room shows as soon as it is saved, then its figures once measured, as on a phone.
            rooms.forEach { capture ->
                val opened = loadRerunSession(capture()) ?: return@LaunchedEffect
                val number = home.nextNumber
                home = home.add(null, "${ScanScopeCopy.label(ScanScope.Home, number)} · $QA_PLAN_TITLE")
                // One survey at a time: each holds its own grid (#4675).
                home = home.measured(number, withContext(Dispatchers.Default) { surveyOf(opened) })
            }
        }
    }
    // A room of the home just saved: kept in the summary, then measured off the main thread, by
    // the same measure the export sheet and the plan wait on for this scan.
    val onRoomSaved = { scan: RerunReplayMedia, title: String, id: String? ->
        sessionsVersion++
        val number = home.nextNumber
        home = home.add(id, title)
        val measuring = home.id
        scope.launch {
            val survey = measureRoom(scan)
            // A new home started meanwhile: this survey is the old one's, never the new Room's.
            home = home.measuredIn(measuring, number, survey)
        }
        Unit
    }
    val startScan = { picked: ScanScope ->
        scanScope = picked
        ScanScopeStore.remember(context, picked)
        home = home.next()
        screen = RerunScreen.Live
    }
    // A file handed to this activity only: another MainActivity still showing 3D Scan behind it
    // must not import it too (#4680).
    val inbox = DemoSettings.rerunInbox
    val activity = LocalActivity.current
    LaunchedEffect(inbox) {
        val delivery = inbox ?: return@LaunchedEffect
        if (!RerunInbox.claim(delivery, activity)) return@LaunchedEffect
        opening?.cancel()
        screen = RerunScreen.Landing
        importFile(delivery.uri) { RerunInbox.read(delivery) }
    }

    when (screen) {
        RerunScreen.Landing -> RerunLandingScreen(
            onBack = onBack,
            state = RerunLandingState(
                sessions = sessions,
                notice = notice,
                openingFile = openingFile,
                savingScan = RerunScanBuilds.saving > 0,
            ),
            actions = RerunLandingActions(
                onRecord = {
                    notice = null
                    screen = RerunScreen.Choose
                },
                onWatchSample = {
                    notice = null
                    openSample()
                },
                onOpenFile = { pickFile.launch(arrayOf("*/*")) },
                onOpen = {
                    notice = null
                    openStored(it.id, it.info.title, it.info.scope)
                },
                onViewInAr = { session ->
                    notice = null
                    openDollhouse(session.id, session.info.title, null)
                },
                onDelete = { session ->
                    scope.launch {
                        withContext(Dispatchers.IO) { store.delete(session.id) }
                        sessionsVersion++
                    }
                },
                onDismissNotice = { notice = null },
            ),
        )
        // No Settings on the scope step (#4730): nothing in the sheet changes the scan about to
        // start, and the capture screen it opens keeps the same sheet. The dock would hold only it.
        RerunScreen.Choose -> DemoScaffold(
            title = stringResource(R.string.demo_ar_rerun_title),
            onBack = toLanding,
            dock = emptyList(),
            dockHidden = true,
            themedStage = true,
            modeSwitch = null,
        ) {
            ScanScopePage(selected = scanScope, onSelect = { scanScope = it }, onStart = { startScan(scanScope) })
        }
        RerunScreen.Home -> DemoScaffold(
            title = stringResource(R.string.demo_ar_rerun_title),
            onBack = toLanding,
            controls = { RerunSheet() },
            dock = emptyList(),
            themedStage = true,
        ) {
            HomeSummaryPage(
                home = home,
                onOpenPlan = { room ->
                    homePlanRoom = room
                    screen = RerunScreen.HomePlan
                },
                onScanAnother = { screen = RerunScreen.Live },
            )
        }
        RerunScreen.HomePlan -> DemoScaffold(
            title = stringResource(R.string.demo_ar_rerun_plan_export_title),
            onBack = { screen = RerunScreen.Home },
            controls = { Text(stringResource(R.string.demo_ar_rerun_plan_about), style = Type.body) },
            themedStage = true,
            opaqueSheet = true,
            hasScene = false,
        ) {
            val room = homePlanRoom
            val heading = planHeading(room?.title.orEmpty(), sessions?.firstOrNull { it.id == room?.id }?.info?.createdAt)
            RerunPlanView(survey = room?.survey, title = heading.title, date = heading.date)
        }
        // Capture is the camera, and so is ARCore's MP4 playback as the library runs it:
        // `ARCore.checkPermissionAndInstall` holds every session back without the permission.
        // The landing page, the replays and the shared files around it never ask (#4139).
        // A QA state stages this screen without a session, so it stages it without the gate.
        RerunScreen.Live -> ArDemoPermissionGate(
            title = stringResource(R.string.demo_ar_rerun_title),
            onBack = leaveLive,
            enabled = qaState == null,
        ) {
            RerunLiveScreen(
                onBack = leaveLive,
                onScanned = { scan, title, pack, id ->
                    sessionsVersion++
                    if (recordingForDollhouse) {
                        recordingForDollhouse = false
                        openDollhouse(null, title, scan)
                    } else {
                        // Save scan lands on the model, built here and kept with the scan.
                        openResult(scan, title, pack, id, false, scanScope)
                    }
                },
                store = store,
                sample = sample,
                scanScope = if (recordingForDollhouse) ScanScope.Room else scanScope,
                roomNumber = home.nextNumber,
                onRoomSaved = onRoomSaved,
                homeSession = home.id,
                onSavedAway = { scan, title, id, homeOf ->
                    sessionsVersion++
                    // A room of the home still open joins its summary, measured as any other.
                    if (scan != null && homeOf != null && homeOf == home.id) onRoomSaved(scan, title, id)
                },
                onSaveFailedAway = { notice = ScanCopy.SAVE_FAILED_AWAY },
                onFinishHome = { screen = RerunScreen.Home },
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                arPlaybackDataset = arPlaybackDataset,
                qaState = qaState,
                rescan = rescanTarget,
            )
        }
        RerunScreen.Replay -> RerunReplayScreen(
            onBack = { if (showingScan && hasResult) screen = RerunScreen.Model else toLanding() },
            mode = mode,
            onMode = { mode = it },
            media = media,
            isScan = showingScan,
            measuresRoom = !showingScan || scanKind.hasFloorPlan,
            mapsGround = !showingScan || scanKind.mapsGround,
            scanTitle = scanTitle,
            session = replaySession,
            orbit = replayOrbit,
            stageBand = stageBand,
            revealed = revealed,
            onRevealed = { revealed = true },
            onExport = { exporting = true },
            // Your own room only: the sample is not a room of yours to stand on a table.
            onViewInAr = scanMedia?.takeIf { showingScan }?.let { scan ->
                { openDollhouse(scanId, scanTitle, scan) }
            },
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
        )
        RerunScreen.Dollhouse -> RoomDollhouseScreen(
            onBack = leaveDollhouse,
            title = dollhouseTitle,
            media = dollhouseMedia,
            session = dollhouseShown,
            sessions = sessions.takeUnless { qaState == QA_STATE_DOLLHOUSE_EMPTY },
            onPickSession = { picked -> openDollhouse(picked.id, picked.title, null) },
            sessionsKnown = sessions != null || dollhouseMedia != null,
            hasSession = dollhouseMedia != null || dollhouseSession != null,
            openFailed = dollhouseFailed,
            onRecord = {
                recordingForDollhouse = true
                screen = RerunScreen.Live
            },
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            arPlaybackDataset = arPlaybackDataset,
            startIn3d = qaState == QA_STATE_DOLLHOUSE_3D,
        )
        RerunScreen.Plan -> {
            val heading = planHeading(scanTitle, sessions?.firstOrNull { it.id == scanId }?.info?.createdAt)
            RerunPlanScreen(media = scanMedia, title = heading.title, date = heading.date, onBack = leavePlan)
        }
        RerunScreen.Model -> RerunModelScreen(
            source = modelSource,
            title = if (showingScan) scanTitle else ScanCopy.REPLAY_TITLE,
            store = store.takeIf { scanId != null },
            id = scanId,
            onModel = { resultModel = it },
            onBack = toLanding,
            onRename = scanId?.let { id ->
                { name: String ->
                    scanTitle = name.trim().ifEmpty { scanTitle }
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { store.rename(id, name) } }
                        sessionsVersion++
                    }
                }
            },
            // The recording behind the model, once it is read: a scan reopened shows its model first.
            onReplay = scanMedia?.takeIf { showingScan }?.let { { screen = RerunScreen.Replay } },
            onViewInAr = scanMedia?.takeIf { showingScan }?.let { scan ->
                { openDollhouse(scanId, scanTitle, scan) }
            },
            onExport = { exporting = true },
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            hintStays = qaState != null,
            opened = resultModel,
            floorCovered = scanFloorCovered,
            // One room or area of yours: a part scanned again is kept as a completed copy (#4736).
            onRescan = scanPack?.takeIf { showingScan && !scanKind.roomByRoom }?.let { pack ->
                val completed = stringResource(R.string.scan_rescan_merged_title, scanTitle)
                val part = stringResource(R.string.scan_rescan_part_title, scanTitle)
                val start: (RescanPick) -> Unit = { pick ->
                    rescanTarget = RescanTarget(pick, pack, completed, part)
                    scanScope = scanKind
                    screen = RerunScreen.Live
                }
                start
            },
        )
    }
    val exportable = when (screen) {
        RerunScreen.Replay -> media != null
        RerunScreen.Model -> true
        else -> false
    }
    if (exporting && exportable) {
        val onModel = screen == RerunScreen.Model
        val source = remember(showingScan, scanTitle, scanPack, onModel, resultModel, scanKind) {
            val pack = scanPack
            val model = resultModel
            when {
                showingScan -> RerunExportSource(
                    title = scanTitle,
                    capture = pack?.let { { it } },
                    model = model?.let { { it } },
                    scope = scanKind,
                )
                // QA: the synthetic room's model, and no recording to go with it.
                onModel -> RerunExportSource(title = ScanCopy.REPLAY_TITLE, model = model?.let { { it } })
                else -> {
                    val appContext = context.applicationContext
                    RerunExportSource(ScanCopy.SAMPLE_TITLE, capture = { sampleCapturePack(appContext) })
                }
            }
        }
        // The floor plan is a scan's: it is measured on the depth the scan recorded (#4540).
        // An object has none (#4683), nor an outdoor area (#4720): neither is a room, whatever
        // its points. A room without a plan keeps the row, disabled, with why (#4714).
        val planMedia = scanMedia?.takeIf { showingScan && scanKind.hasFloorPlan }
        val planSurvey = rememberRoomSurvey(planMedia, wanted = planMedia != null)
        RerunExportSheet(
            source = source,
            onDismiss = { exporting = false },
            floorPlan = PlanOffer.of(planSurvey),
            onFloorPlan = planMedia?.let {
                {
                    exporting = false
                    planOpener = screen
                    screen = RerunScreen.Plan
                }
            },
        )
    }
}

/**
 * The demo's screens: the landing, the live AR session (Record), the replay, the dollhouse — a
 * session stood on a table in AR (#4075) — the scan's result, and its floor plan (#4540). Before a
 * capture, what is scanned (#4647); after a whole home, its summary and each room's plan.
 */
internal enum class RerunScreen { Landing, Choose, Live, Replay, Dollhouse, Model, Plan, Home, HomePlan }

/**
 * Where Back from the floor plan goes: the screen whose export sheet opened it ([opener]), or —
 * opened with none, as QA does — the scan's result when it has one, else its replay.
 */
internal fun planReturnOf(opener: RerunScreen?, hasResult: Boolean): RerunScreen =
    opener?.takeUnless { it == RerunScreen.Plan }
        ?: if (hasResult) RerunScreen.Model else RerunScreen.Replay

/** What follows a save: the scan's result, or for a room of a home the next room or the summary. */
private enum class SaveNext { Open, NextRoom, FinishHome }

/** The replay's three views. */
private enum class RerunMode { Scene, Map, Camera }

/**
 * The landing, laid out as the iOS demo's: "3D Scan", "Start scanning", the example and "Open
 * scan", then "Your scans". A themed stage like the replay it opens (#4080): the
 * light stage in light theme, the dark one in dark theme.
 */
@Composable
private fun RerunLandingScreen(onBack: () -> Unit, state: RerunLandingState, actions: RerunLandingActions) {
    DemoScaffold(
        title = stringResource(R.string.demo_ar_rerun_title),
        onBack = onBack,
        controls = { RerunSheet() },
        dock = emptyList(),
        themedStage = true,
    ) {
        RerunLanding(state, actions)
    }
}

/**
 * The replay: the 3D view (orbit, or the overhead map) or the camera's frames, and one thin
 * timeline bar over the dock (#4379). A themed stage (#4080): it follows the app theme, where the
 * live camera screen keeps the media chrome. A tap on the stage hides the header, the bar and
 * the dock; another brings them back.
 *
 * What is read once lives in the settings sheet: the layers and their figures, and — for a scan
 * that can be meshed — Points | Surface (#4306), the surface being drawn by this same 3D view,
 * under the same camera, instead of a second screen.
 */
@Composable
@Suppress("LongParameterList") // the demo's shared engine and replay state, handed down once
private fun RerunReplayScreen(
    onBack: () -> Unit,
    mode: RerunMode,
    onMode: (RerunMode) -> Unit,
    media: RerunReplayMedia?,
    isScan: Boolean,
    /** Whether the scan is a room's, measured as one: an object's draws no room dimensions (#4683). */
    measuresRoom: Boolean,
    /** Whether the scan maps the ground it covered: a room's and an outdoor area's do, an object's not (#4720). */
    mapsGround: Boolean,
    scanTitle: String,
    revealed: Boolean,
    onRevealed: () -> Unit,
    session: ArDebugSession,
    orbit: ArDebugOrbitCamera,
    stageBand: OrbitBand,
    onExport: () -> Unit,
    onViewInAr: (() -> Unit)?,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
) {
    val thumbnails = remember(media) { media?.thumbnails?.mapValues { it.value.asImageBitmap() }.orEmpty() }
    // The object tapped in the 3D view or the map is still the one picked back from the Camera view.
    val selection = remember(media) { ScanSelection() }
    val title = if (isScan) scanTitle else ScanCopy.SAMPLE_TITLE
    // The stage the chrome really leaves, measured on screen: the room is fitted between the
    // header above and the timeline bar below, whatever the phone or the font scale. A phone on
    // its side has no height to give a row under the room: the bar stands in the top corner,
    // under the header's line, and the room keeps the middle of the window.
    val compact = compactStage()
    var stage by remember { mutableStateOf(Rect.Zero) }
    // Kept while a tap has the chrome hidden: the room does not move when the bar leaves.
    var timeline by remember(compact) { mutableStateOf<Rect?>(null) }
    var headerBottom by remember(compact) { mutableFloatStateOf(Float.NaN) }
    // On its side: where the dock starts, under the room.
    var pillTop by remember(compact) { mutableFloatStateOf(Float.NaN) }
    val statusBottom = WindowInsets.safeDrawing.getTop(LocalDensity.current).toFloat()
    // The room's dimensions are written under its floor, outside the box the band fits: they
    // keep this much air over the dock.
    val pillClearance = with(LocalDensity.current) { Space.lg.toPx() }
    val measured = when {
        compact -> sideBand(stage, timeline, statusBottom, pillTop - pillClearance)
        else -> stackedBand(stage, headerBottom, timeline)
    }
    val band = measured ?: stageBand
    // The settings sheet rises over the stage's lower part (#4564): while it is up, the room is
    // drawn smaller and higher, into the band it leaves between the header and the sheet's top,
    // and comes back down when it closes. The sheet's target detent, so the picture starts moving
    // with the sheet. Read in the scaffold's scene slot, which is where the scaffold provides it.
    var sheetCover by remember { mutableFloatStateOf(0f) }
    val windowHeight = LocalWindowInfo.current.containerSize.height.toFloat()
    val squeeze = if (sheetCover > 0f && measured != null) {
        val sheetTop = windowHeight - sheetCover
        when {
            compact -> sideBand(stage, timeline, statusBottom, minOf(pillTop - pillClearance, sheetTop))
            else -> stackedBand(stage, headerBottom, timeline, coveredFrom = sheetTop)
        }
    } else {
        null
    }
    SideEffect {
        orbit.band = band
        orbit.squeeze = squeeze
    }
    // What the recording covers at the replay's time (#4631): the map card in the top corner, in
    // the 3D and Map views, which feed it.
    val coverage = remember(media) { ScanCoverageState() }
    // The ground's coverage is a room's or an area's figure: an object's scan has none (#4683, #4720).
    val showsCoverage = media != null && mode != RerunMode.Camera && mapsGround
    // An object's scan has no map: one opened while the map was showing opens in 3D.
    LaunchedEffect(mapsGround, mode) { if (!mapsGround && mode == RerunMode.Map) onMode(RerunMode.Scene) }
    // The camera frames are pictures, ready with the files; the 3D view says when it has drawn.
    val ready = media != null && (revealed || mode == RerunMode.Camera)
    LaunchedEffect(ready) { if (ready) onRevealed() }
    val readyState = rememberUpdatedState(ready)
    val filmstripIn = rememberReveal(revealed, delayMillis = REVEAL_STAGGER_MS)
    // The 3D view runs the session's clock while it draws. Over the camera's frames there is
    // none on screen (the corner picture-in-picture that ran it is gone, #4379): the screen does.
    // It counts for the view too: the sheet's layer rows and the timeline read those figures.
    if (media != null && mode == RerunMode.Camera) {
        LaunchedEffect(session, media) {
            // A finished recording stands on the floor of the whole recording, as in the 3D view.
            val whole = if (media.growing) null else session.trace.let { it.frameAt(it.duration) }
            var last = withFrameNanos { it }
            var countedAt = last - STATS_INTERVAL_NS
            while (true) {
                val now = withFrameNanos { it }
                session.tick((now - last) / NANOS_PER_SECOND)
                last = now
                if (now - countedAt >= STATS_INTERVAL_NS) {
                    countedAt = now
                    val frame = session.trace.frameAt(session.time)
                    session.count(frame, media.pointCountAt(frame.time), stageFloorY(whole ?: frame))
                }
            }
        }
    }
    // The session on screen as open files: .rrd, .glb and .ply, written on the phone.
    val export = DockItem(
        icon = Icons.Rounded.IosShare,
        label = RerunExportFormat.DOCK_LABEL,
        caption = RerunExportFormat.DOCK_CAPTION,
        onClick = onExport,
        enabled = media != null,
    )
    DemoScaffold(
        title = stringResource(R.string.demo_ar_rerun_title),
        onBack = onBack,
        controls = {
            // What the 3D view draws, layer by layer, and its figures.
            RerunSheet { RerunLayersSection(session) }
        },
        firstFrameRendered = readyState,
        loadingLabel = if (isScan) ScanCopy.LOADING else RERUN_REPLAY_LOADING,
        themedStage = true,
        // The room has the screen (#4379): a tap on the stage hides the header, the timeline and
        // the dock, and another brings them back.
        chromeToggleOnTap = true,
        overlaysFollowChrome = true,
        // 3D Scan shows no mode switch on any of its screens (#4397).
        modeSwitch = null,
        topOverlay = {
            if (media != null && compact) {
                // On its side: what is scanned in the start corner, the timeline in the end one,
                // the room centred between them (the band keeps clear of the wider of the two).
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    if (showsCoverage) {
                        ScanCoverageMap(
                            state = coverage,
                            modifier = Modifier
                                .padding(start = Space.md)
                                .reveal(filmstripIn, rise = -Space.md),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    RerunTimelineBar(
                        media = media,
                        thumbnails = thumbnails,
                        session = session,
                        modifier = Modifier
                            .padding(end = Space.md)
                            .width(SceneViewTokens.DebugView.compactCardWidth)
                            // Measured outside the reveal: where the bar rests, not where it rises from.
                            .onGloballyPositioned { timeline = it.boundsInRoot() }
                            .reveal(filmstripIn, rise = -Space.md),
                    )
                }
            } else if (showsCoverage) {
                // What is scanned and what is not, in the corner under the header: the room is
                // fitted below the card, as below the header, so the card never stands on it.
                ScanCoverageMap(
                    state = coverage,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(start = Space.md)
                        // Measured outside the reveal: where the card rests, not where it rises from.
                        .onGloballyPositioned { headerBottom = it.boundsInRoot().bottom }
                        .reveal(filmstripIn, rise = -Space.md),
                )
            } else {
                // Nothing to draw: this marks where the header ends over the room — by its
                // position: an empty box has no bounds.
                Spacer(Modifier.fillMaxWidth().onGloballyPositioned { headerBottom = it.positionInRoot().y })
            }
        },
        bottomOverlay = {
            if (media != null && compact) {
                // Nothing to draw: the timeline stands in the corner. This marks where the dock
                // starts under the room.
                Spacer(Modifier.fillMaxWidth().onGloballyPositioned { pillTop = it.positionInRoot().y })
            } else if (media != null) {
                RerunTimelineBar(
                    media = media,
                    thumbnails = thumbnails,
                    session = session,
                    modifier = Modifier
                        // The bar goes with the dock: one small gutter above it, not a card
                        // stacked over it.
                        .offset(y = (dockGap - Space.sm).coerceAtLeast(0.dp))
                        .padding(horizontal = Space.md)
                        .widthIn(max = ArOverlay.maxWidth)
                        .fillMaxWidth()
                        .onGloballyPositioned { timeline = it.boundsInRoot() }
                        .reveal(filmstripIn, rise = Space.lg),
                )
            }
        },
        dock = listOfNotNull(
            DockItem(
                icon = Icons.Rounded._3dRotation,
                label = "3D view",
                caption = "3D",
                onClick = { if (mode == RerunMode.Scene) orbit.recenter() else onMode(RerunMode.Scene) },
                selected = mode == RerunMode.Scene,
            ),
            // The map is the ground seen from above: an object's scan has none, and its top view is
            // what the 3D view's orbit already shows (#4683). An outdoor area keeps it (#4720).
            DockItem(
                icon = Icons.Rounded.Map,
                label = "Map view",
                caption = "Map",
                onClick = { onMode(RerunMode.Map) },
                selected = mode == RerunMode.Map,
            ).takeIf { mapsGround },
            DockItem(
                icon = Icons.Rounded.Movie,
                label = "Camera frames",
                caption = "Camera",
                onClick = { onMode(RerunMode.Camera) },
                selected = mode == RerunMode.Camera,
            ),
            // With Place as the accent, Export stays one tap away in the dock.
            export.takeIf { onViewInAr != null },
        ),
        // Your own room stands on a table in AR (#4075); the sample offers its files instead.
        dockAccent = onViewInAr?.let {
            DockItem(
                icon = Icons.Rounded.ViewInAr,
                label = stringResource(R.string.scan_place),
                onClick = it,
                enabled = media != null,
            )
        } ?: export,
    ) {
        val cover = with(LocalDensity.current) { LocalDemoSheetCover.current.toPx() }
        // Full screen (#4558): the chrome is gone, so the room is drawn into the whole view —
        // larger and centred — and back into its band on the way out, on `motion-focus`.
        val fullScreen = LocalDemoFullScreen.current
        SideEffect {
            sheetCover = cover
            orbit.focusBand = if (fullScreen) OrbitBand.FULL_SCREEN else null
        }
        when {
            media == null -> Unit // the scaffold's cover says it is loading
            mode == RerunMode.Camera -> RerunCameraView(media, thumbnails, session, Modifier.fillMaxSize())
            else -> ArDebugSceneView(
                session = session,
                orbit = orbit,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                modifier = Modifier.fillMaxSize().onGloballyPositioned { stage = it.boundsInRoot() },
                replay = media,
                measuresRoom = measuresRoom,
                onShown = onRevealed,
                selection = selection,
                // The Camera card of the 3D view, with the padding that hangs it in its corner.
                cornerCard = if (mode == RerunMode.Scene) {
                    DpSize(
                        SceneViewTokens.DebugView.pipWidth + Space.md,
                        SceneViewTokens.DebugView.pipHeight + Space.sm * 2,
                    )
                } else {
                    DpSize.Zero
                },
                observe = { frame ->
                    val dense = media.dense?.cloud?.positions
                    coverage.observe(frame, dense, media.pointCountAt(frame.time), saved = media.trace)
                },
            )
        }
    }
}

/**
 * A phone on its side: too short for the stacked cards, and wide enough for the two side cards to
 * leave the room a stage between them — by the rule the band itself is measured with
 * ([OrbitBand.halfWidthBeside]). A window that is short and narrow (split screen) keeps the
 * stacked layout: beside the room, the cards would stand on it.
 */
@Composable
private fun compactStage(): Boolean {
    if (LocalConfiguration.current.screenHeightDp.dp >= SceneViewTokens.DebugView.compactStageHeight) return false
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    // The room stays centred: the side a cutout pushes further in decides for both.
    val inset = maxOf(insets.getLeft(density, direction), insets.getRight(density, direction))
    val card = with(density) { (Space.md + SceneViewTokens.DebugView.compactCardWidth).toPx() }
    val width = LocalWindowInfo.current.containerSize.width.toFloat()
    return OrbitBand.halfWidthBeside(cardEnd = inset + card, viewWidth = width) != null
}

/**
 * The band the replay's chrome leaves the room on [stage] when the phone is upright, measured on
 * screen: from [headerBottom], where the header ends, down to the timeline bar — or to
 * [coveredFrom], the settings sheet's top, when it rises higher. `null` until both are laid out.
 */
private fun stackedBand(
    stage: Rect,
    headerBottom: Float,
    timeline: Rect?,
    coveredFrom: Float = Float.POSITIVE_INFINITY,
): OrbitBand? {
    if (timeline == null) return null
    val bottom = minOf(timeline.top, coveredFrom)
    return OrbitBand.between(headerBottom - stage.top, bottom - stage.top, stage.height)
}

/**
 * The same band for a phone on its side: the timeline bar stands in a top corner, so the room —
 * which stays centred — keeps clear of it on both sides, from [statusBottom] under the status bar
 * down to [pillTop], where the dock starts. `null` until the bar is laid out.
 */
private fun sideBand(stage: Rect, timeline: Rect?, statusBottom: Float, pillTop: Float): OrbitBand? {
    if (timeline == null) return null
    // How far in the bar reaches from the edge it stands against, whichever that is (RTL).
    val reach = minOf(stage.right - timeline.left, timeline.right - stage.left)
    return OrbitBand.betweenSides(
        startCardEnd = reach,
        endCardStart = stage.width - reach,
        top = (statusBottom - stage.top).coerceAtLeast(0f),
        bottom = pillTop - stage.top,
        viewWidth = stage.width,
        viewHeight = stage.height,
    )
}

/**
 * The settings sheet: [head], what the screen it is opened from keeps off its stage (#4379) — the
 * replay's layers and figures, a scan's counts — then what the demo is and, advanced, how to
 * stream a live session to a computer, with [stream], that stream's status, once it is connected.
 */
@Composable
private fun RerunSheet(head: (@Composable () -> Unit)? = null) {
    if (head != null) {
        head()
        Spacer(Modifier.size(Space.md))
    }
    Text(
        text = RERUN_REPLAY_INTRO,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Debug builds only, and folded (#4619): shell commands are for us, not for the sheet's reader.
    if (BuildConfig.DEBUG) RerunDeveloperSection()
}

/** "Developer tools", folded by default: streaming to a computer and the forced tracking failures. */
@Composable
private fun RerunDeveloperSection() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = Space.md)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SceneViewTokens.Layout.touchTarget)
                .clickable(role = Role.Button, onClick = { expanded = !expanded }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = RERUN_DEVELOPER_TITLE,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                RerunSetupSection()
                // Lets QA force-emit each TrackingFailureReason so the actionable-message overlay can
                // be validated without staging a real failure. See ForcedTrackingFailure / #1881.
                ForceTrackingFailureMenu()
            }
        }
    }
}

/**
 * Live AR — the capture: the camera, with taps placing the shiba on planes, recorded into the
 * in-app 3D view (#3950). The scan starts by itself once ARCore has found the space; Pause,
 * Finish and its review, Keep scanning and Save scan follow [ScanPhase] (#4627), and a saved
 * scan is handed to [onScanned], opened, with its id in the store, for its result (#4597).
 * Back during a capture asks first.
 *
 * #3831: the screen used to open on a banner about a "recording service" and "Settings" for
 * every Play Store user without a computer attached. Streaming to a computer is now an advanced
 * option: its steps live in the settings sheet, and its status card shows only once connected.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // the live AR screen, moved as-is behind the replay
private fun RerunLiveScreen(
    onBack: () -> Unit,
    onScanned: (RerunReplayMedia, String, RerunCapturePack, String?) -> Unit,
    store: RerunSessionStore,
    sample: RerunReplayMedia?,
    scanScope: ScanScope,
    roomNumber: Int,
    onRoomSaved: (RerunReplayMedia, String, String?) -> Unit,
    homeSession: Int,
    /** A save that finished after the screen was left (#4500): the scan, and its home if a room of one. */
    onSavedAway: (RerunReplayMedia?, String, String?, Int?) -> Unit,
    onSaveFailedAway: () -> Unit,
    onFinishHome: () -> Unit,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    arPlaybackDataset: File?,
    qaState: String?,
    /** A part of a scan scanned again (#4736): guided live, merged into a copy of the scan on save. */
    rescan: RescanTarget? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // #4500: a scan being saved outlives this screen. The capture's own work runs in a scope kept
    // until its save is done, and the save itself in the process's (RerunScanBuilds).
    val captureScope = remember { RerunScanBuilds.captureScope() }
    val attached = remember { AtomicBoolean(true) }
    val saveJob = remember { AtomicReference<Job?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            attached.set(false)
            val saving = saveJob.get()?.takeIf { it.isActive }
            if (saving == null) captureScope.cancel() else saving.invokeOnCompletion { captureScope.cancel() }
        }
    }

    // QA only (`--es qa_state connected|saved`): draw the connected status, or the saved
    // dialog, on the emulator, which can reach neither ARCore nor a computer (#2754).
    val qaConnected = qaState == QA_STATE_CONNECTED
    // QA only (`--es qa_state pip|pip-stream|3d|3d-scrub|3d-stream`, #3950): feed the in-app 3D
    // view from a recorded session, since the emulator cannot track. Never shown to a user.
    val qaDebug = remember { ArDebugQaState.of(qaState) }

    // The in-app 3D debug view (#3950): what ARCore understood of the room, drawn by a second
    // SceneView from a free camera. The picture-in-picture over the camera opens it full-screen.
    val debugSession = remember { ArDebugSession() }
    val debugRecorder = remember { ArDebugRecorder() }
    val debugOrbit = remember { ArDebugOrbitCamera(drift = qaState == null) }
    val pipOrbit = remember { ArDebugOrbitCamera(drift = qaState == null) }
    var debugFullScreen by remember { mutableStateOf(qaDebug?.fullScreen == true) }

    LaunchedEffect(qaDebug) {
        val fixture = qaDebug ?: return@LaunchedEffect
        val events = withContext(Dispatchers.IO) {
            context.assets.open(AR_DEBUG_FIXTURE).bufferedReader().useLines { parseArDebugLog(it) }
        }
        if (fixture.streams) {
            // The trail grows as the recorded walk plays, the way a live session fills in.
            val player = ArDebugLogPlayer(events)
            debugSession.trace = player.trace
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 0f else (now - last) / 1e9f
                    last = now
                    if (player.advance(dt)) debugSession.trace = player.trace
                }
            }
        } else {
            debugSession.trace = ArDebugTrace.of(events)
            if (fixture.scrubbed) debugSession.scrubTo(debugSession.trace.duration * QA_SCRUB_FRACTION)
        }
    }

    // Capture mode: the scan in progress, and its 3D card's camera. A scan records into the
    // debug session's trace, so the 3D view and the scan are the same data.
    var scan by remember { mutableStateOf<ScanCapture?>(null) }
    // #4627: where the capture stands. Pause and Finish freeze it; Finish opens a review that
    // Keep scanning leaves for the same capture. Only Save scan builds it — `finish()` is final.
    var phase by remember { mutableStateOf(ScanPhase.Scanning) }
    // The built scan, held across a failed write so Try again writes the same one.
    var builtPack by remember { mutableStateOf<RerunCapturePack?>(null) }
    var builtTitle by remember { mutableStateOf("") }
    // Why the last save failed, said on the "Couldn't save" card (#4815), and the scan it could
    // not keep — the merged copy, for a part scanned again — which Save as file still writes out.
    var saveFailure by remember { mutableStateOf<ScanSaveFailure?>(null) }
    var unsaved by remember { mutableStateOf<Pair<RerunCapturePack, String>?>(null) }
    // The built scan [unsaved] was made from: Try again writes that same copy, never merging the
    // re-scan a second time beside it — two copies of a long scan where one ran out of memory.
    var unsavedFrom by remember { mutableStateOf<RerunCapturePack?>(null) }
    // Save as file: writing the copy for the share sheet, and whether that failed too.
    var fileSaving by remember { mutableStateOf(false) }
    var fileSaveFailed by remember { mutableStateOf(false) }
    // A part scanned again that did not line up on its scan (#4736): why, said on the card, and
    // whether it is now saved as a separate scan of its own — lining it up again would only
    // refuse it again, so the save never waits on it a second time (#4815).
    var rescanRefusal by remember { mutableStateOf<RescanRefusal?>(null) }
    var rescanSeparate by remember { mutableStateOf(false) }
    var leaveAsked by remember { mutableStateOf(false) }
    // Discard on the "Couldn't save" card: the scan was never written, so it asks first.
    var discardAsked by remember { mutableStateOf(false) }
    // Rerun v2: whether this phone runs ARCore's raw depth, set when the session is configured.
    // With it a scan also fuses a dense surfel map (tier "depth"); without it, the sparse v1 scan.
    var rawDepth by remember { mutableStateOf(false) }
    // QA only (`--es qa_state record`): the capture screen mid-scan, fed by the sample room's
    // log and photos, since the emulator cannot track. Its Save scan saves that take like a real
    // scan — the same builder, bake and file — and opens it. `record-tracking-lost` holds it on
    // a lost track; `record-save-failed` makes its write fail.
    var qaScan by remember { mutableStateOf<RerunReplayMedia?>(null) }
    val qaRecord = qaState in RECORD_QA_STATES
    val qaTrackingLost = qaState == QA_STATE_RECORD_TRACKING_LOST
    val qaSaveFails = qaState == QA_STATE_RECORD_SAVE_FAILED
    // #4629: `record-long` feeds the dense map a whole flat; `-fixed` runs it with the old cap.
    var qaLong by remember { mutableStateOf<QaLongScan?>(null) }
    // Each room of a home is a take of its own: the QA take restarts for the next room.
    var take by remember { mutableIntStateOf(0) }
    // Between two rooms of a home: the new capture waits, paused, until Start.
    var betweenRooms by remember { mutableStateOf(false) }
    LaunchedEffect(qaRecord, sample, take) {
        val source = sample?.takeIf { qaRecord } ?: return@LaunchedEffect
        val events = withContext(Dispatchers.IO) {
            // #4540: a turn round a synthetic room, whose walls the sample's close take lacks.
            if (qaState == QA_STATE_RECORD_WALLS || qaState == QA_STATE_RECORD_WALLS_STALLED) {
                ScanWallWalk.events()
            } else {
                context.assets.open(RerunReplayAssets.LOG).bufferedReader().useLines { parseArDebugLog(it) }
            }
        }
        val player = ArDebugLogPlayer(events, loopPauseSeconds = QA_RECORD_NEVER_LOOP_S)
        player.trace.keyframeSpacing = ReplayGeometry.KEYFRAME_SPACING_M
        player.trace.journal = ArrayList()
        // The take is cut as a real scan of this scope is (#4683): an object keeps arm's length.
        player.trace.cutoff = ScanCutoff.of(scanScope.preset)
        debugSession.trace = player.trace
        val long = qaState.takeIf { it in LONG_QA_STATES }?.let {
            QaLongScan.over(
                source.trace,
                fixed = it == QA_STATE_RECORD_LONG_FIXED,
                plain = it == QA_STATE_RECORD_LOW_TEXTURE,
            )
        }
        qaLong = long
        qaScan = RerunReplayMedia(
            player.trace, source.manifest, emptyMap(), source.thumbnails, ByteArray(0), growing = true,
            liveDense = long?.let { { it.dense.live } },
        )
        long?.let { launch { it.run { phase.integrates } } }
        var last = 0L
        var played = 0f
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1e9f
                last = now
                // The tracking-loss state scans a few seconds first, so it loses a real capture.
                val tracking = !qaTrackingLost || played < QA_TRACKING_LOST_AFTER_S
                if (phase.integrates && tracking) played += dt.also { player.advance(it) }
            }
        }
    }
    val scanMedia = scan?.live ?: qaScan
    val recording = scanMedia != null
    // #4628: the live view follows the phone heading-up, like a navigation app. A drag or a
    // pinch takes it over and shows Recenter, which hands it back to the phone.
    var scanFollowing by remember(scanMedia) { mutableStateOf(true) }
    val scanFollow = remember(scanMedia) { ScanFollow() }
    // #4716: following, the live view draws from the phone's own eye and lens, so its points sit
    // on the camera image they were taken from, instead of 2.6 m behind and above the phone.
    val scanLens = remember(scanMedia) { ScanLens() }
    // What the capture has covered (#4631): the live map and the coach read it, frame by frame.
    val scanCoverage = remember(scanMedia) { ScanCoverageState() }
    // A room's walls, recognised as it is scanned (#4540): Room and Home only.
    val scanWalls = remember(scanMedia, scanScope) { if (scanScope.hasFloorPlan) ScanWallsState() else null }
    // The turn to its next gap, held once: the arrow and the coaching line change together.
    val heldTurn = rememberHeld(scanWalls?.turn)
    // Tapped open, the live map is the map to complete (#4718): the fallback card, where the
    // screen is too short to share between the live 3D map and the camera.
    var scanMapExpanded by remember(scanMedia) { mutableStateOf(false) }
    // The live 3D map takes the larger share of the screen; the swap button gives it to the camera.
    var scanMapFirst by remember(scanMedia) { mutableStateOf(true) }
    // The phone now, written each AR frame (#4718): paused, the trace records nothing, and the
    // live view and the map follow this instead, so the cloud stays put in the room.
    val livePose = remember { LivePose() }
    // While it scans, the view follows the trace, which the overlay is drawn from; otherwise the
    // phone now. The emulator's phone never moves: a QA capture paused turns it slowly instead.
    val pausedSince = remember(phase.integrates) { SystemClock.elapsedRealtimeNanos() }
    val scanLivePose: (DebugPose?) -> DebugPose? = { traced ->
        when {
            phase.integrates -> traced
            qaRecord -> traced?.turnedBy(QaPausedTurn.degreesAt(SystemClock.elapsedRealtimeNanos() - pausedSince))
            else -> livePose.pose ?: traced
        }
    }
    val scanOrbit = remember(scanMedia) {
        ArDebugOrbitCamera(
            drift = false,
            band = ScanFollow.BAND,
            approachRate = ScanFollow.APPROACH_RATE,
            maxTurnDegreesPerSecond = ScanFollow.MAX_TURN_DEGREES_PER_SECOND,
            degreesPerViewWidth = ScanFollow.DRAG_DEGREES_PER_VIEW_WIDTH,
            elevationRange = ScanFollow.DRAG_ELEVATION_RANGE,
            maxCoastDegrees = ScanFollow.MAX_COAST_DEGREES,
        )
            .apply {
                onFollowingChange = { scanFollowing = it }
                firstPerson = scanLens
            }
    }

    // #4716 on the emulator, which has no AR: the `record` take's own photos stand in for the
    // camera, each with the pose and the lens it was taken with, drawn full screen under the live
    // view as ARCore draws its image — so a capture shows whether the points sit on the picture.
    var qaPhoto by remember(scanMedia) { mutableStateOf<ImageBitmap?>(null) }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(qaScan, scanLens, stageSize) {
        val media = qaScan?.takeIf { qaRecord && !qaTrackingLost } ?: return@LaunchedEffect
        if (stageSize.width <= 0 || stageSize.height <= 0) return@LaunchedEffect
        val aspect = stageSize.width.toFloat() / stageSize.height
        var shown = -1
        while (true) {
            val index = withFrameNanos { media.trace.imageIndexAt(media.trace.duration) }
            if (!ScanLens.seesThroughPhone(tracking = true, phase)) {
                scanLens.invalidate()
                shown = -1
                continue
            }
            if (index < 0 || index == shown) continue
            val path = media.trace.imagePath(index)
            val photo = media.thumbnails[path] ?: continue
            val pose = media.trace.frameAt(media.trace.imageTime(index)).camera ?: continue
            shown = index
            qaPhoto = photo.asImageBitmap()
            scanLens.update { cameraToWorld, projection ->
                ScanLens.poseMatrix(pose, cameraToWorld)
                ScanLens.cropProjection(media.manifest.lens, aspect, projection)
            }
        }
    }

    var isTracking by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }

    // #3341: non-null once ARCore has ruled this device out. The flag the scanning
    // banner waits on never flips then, so that banner has to read the verdict or
    // it promises a scan under the SDK's "AR unavailable" card, forever.
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var eventsPerSec by remember { mutableStateOf(0f) }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    val anchors = remember { mutableStateListOf<Anchor>() }

    var sharing by remember { mutableStateOf(false) }
    var shareResult by remember {
        mutableStateOf(if (qaState == QA_STATE_SAVED) QA_SHARE_RESULT else null)
    }

    // Bridge auto-connects on first composition, auto-disconnects on
    // dispose — no Connect/Disconnect UI to confuse first-time users who
    // came in from the QR code on /rerun/.
    val bridge = rememberRerunBridge(rateHz = 10, enabled = true)
    // Read the bridge's actually-shipped count, not a local frame counter — a
    // local counter ticks even when the recorder is unreachable, which would
    // mislead the user into thinking events are being sent.
    val isConnected = bridge.isConnected || qaConnected
    val eventCount = if (qaConnected) QA_EVENTS_SENT else bridge.eventsSent

    // Sample events/sec once per second. Reads the bridge inside the loop: the previous
    // version read a value captured at first composition, so the rate never left zero.
    LaunchedEffect(bridge) {
        var lastSampleCount = bridge.eventsSent
        while (true) {
            delay(1000)
            val current = bridge.eventsSent
            eventsPerSec = (current - lastSampleCount).toFloat()
            lastSampleCount = current
        }
    }
    val status = rerunStatusUx(
        isConnected = isConnected,
        eventsSent = eventCount,
        eventsPerSecond = if (qaConnected) QA_EVENTS_PER_SECOND else eventsPerSec,
    )

    // Save & Share is the demo's primary action. Hoisted so the on-screen
    // SceneActionBar can invoke it — primary actions belong on-screen, not in
    // the Settings sheet (#1964).
    val onSaveAndShare = {
        if (!sharing) {
            sharing = true
            bridge.requestSaveAndShare { result ->
                scope.launch {
                    withContext(Dispatchers.Main) {
                        sharing = false
                        shareResult = result
                    }
                }
            }
        }
    }

    // Record starts on the frame on screen, whose camera gives the scan its lens.
    val onStartScan = start@{
        val frame = latestFrame ?: return@start
        val capture = ScanCapture.start(frame, captureScope, rawDepth, scanScope.preset) ?: return@start
        debugFullScreen = false
        debugSession.trace = capture.trace
        debugSession.goLive()
        phase = ScanPhase.Scanning
        scan = capture
    }
    // The capture starts by itself, once, as soon as ARCore has found the space. After a
    // capture that caught nothing, or a discarded one, the shutter starts the next one.
    var autoStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (qaDebug != null || qaRecord) return@LaunchedEffect
        while (!autoStarted) {
            if (isTracking && scan == null) {
                onStartScan()
                autoStarted = scan != null
            }
            delay(AUTO_START_POLL_MS)
        }
    }
    // Pause, Finish and Keep scanning move the phase; the capture's clock follows it (below).
    val moveTo = { next: ScanPhase -> phase = next }
    // Drops the capture, built or not: the camera goes back to waiting for the next one.
    val dropCapture = {
        if (scan != null) {
            debugSession.trace = ArDebugTrace()
            scan = null
        }
        qaScan = null
        builtPack = null
        saveFailure = null
        unsaved = null
        unsavedFrom = null
        fileSaveFailed = false
        rescanRefusal = null
        rescanSeparate = false
        leaveAsked = false
        discardAsked = false
        phase = ScanPhase.Scanning
    }
    // Save scan builds the capture — no more photos, the last ones encoded, its planes painted
    // from its photos — writes it on the phone and opens it, read back from what was saved. A
    // failed write keeps the built scan on screen for Try again; nothing claims it was saved.
    // What follows a save: the result screen, or — for a room of a home — the next room or the summary.
    var saveNext by remember { mutableStateOf(SaveNext.Open) }
    val saveScan: (SaveNext) -> Unit = save@{ next ->
        saveNext = next
        val qaTrace = qaScan?.trace
        val capture = scan
        val nothingToSave = builtPack == null && qaTrace == null && capture == null
        if (phase == ScanPhase.Saving || nothingToSave) return@save
        leaveAsked = false
        moveTo(ScanPhase.Saving)
        // The home this room belongs to, when it is one: read now, for a save that ends away.
        val homeOf = homeSession.takeIf { next != SaveNext.Open }
        RerunScanBuilds.started()
        saveFailure = null
        fileSaveFailed = false
        val appContext = context.applicationContext
        // Every way a save ends short lands here: the card says why, logcat says it with the
        // figures — `adb logcat -s ScanSave` — and the scan stays held for Try again (#4815).
        val failed = { stage: String, error: Throwable?, failure: ScanSaveFailure, pack: RerunCapturePack? ->
            val heap = Runtime.getRuntime()
            Log.w(
                SCAN_SAVE_LOG_TAG,
                ScanSaveFailure.logLine(
                    stage = stage,
                    failure = failure,
                    error = error,
                    logBytes = pack?.log?.size?.toLong() ?: 0L,
                    mediaBytes = pack?.media?.size?.toLong() ?: 0L,
                    heapUsedBytes = heap.totalMemory() - heap.freeMemory(),
                    heapMaxBytes = heap.maxMemory(),
                    freeStorageBytes = appContext.filesDir.usableSpace,
                ),
                error,
            )
            saveFailure = failure
            phase = ScanPhase.SaveFailed
            if (!attached.get()) onSaveFailedAway()
        }
        val job = RerunScanBuilds.scope.launch {
            val now = System.currentTimeMillis()
            // The build can throw too — out of memory on a long scan, painting its planes. That
            // lands on the same "Couldn't save" card as a failed write, and Try again rebuilds.
            val built = runCatching {
                builtPack ?: when {
                    qaTrace != null && sample != null ->
                        qaSessionOf(
                            qaTrace, sample, scanScope,
                            depth = qaState != QA_STATE_RECORD_NO_DEPTH,
                            depthStalled = qaState == QA_STATE_RECORD_WALLS_STALLED,
                        )
                    capture != null -> capture.finish()
                    else -> null
                }
            }.onFailure { if (it is CancellationException) throw it }
            if (built.isFailure) {
                val error = built.exceptionOrNull()
                failed("build", error, error?.let { ScanSaveFailure.of(it) } ?: ScanSaveFailure.Other, null)
                return@launch
            }
            val pack = built.getOrNull()?.also {
                if (builtPack == null) builtTitle = recordingTitle(now, scanScope, roomNumber)
                builtPack = it
            }
            if (pack == null) {
                // Nothing to build after all: the next capture starts from the shutter.
                dropCapture()
                return@launch
            }
            // A part scanned again (#4736) is lined up on its scan and merged into a copy of it,
            // kept as a new scan, the scan itself untouched; one that does not line up is never
            // merged wrong. It used to fail the whole save, and Try again lined it up the same way
            // to the same refusal: the scan was lost (#4815). Now the card says why and saves
            // it as a separate scan — the part on its own, under its own name — and a save that
            // ends away from the screen keeps it that way at once. Try again after a failed
            // write writes the copy already merged (#4815).
            val held = unsaved?.takeIf { unsavedFrom === pack }
            val merging = runCatching {
                rescan?.takeUnless { held != null || rescanSeparate }?.let {
                    withContext(Dispatchers.Default) { it.merge(pack) }
                }
            }.onFailure { if (it is CancellationException) throw it }
            if (merging.isFailure) {
                val error = merging.exceptionOrNull()
                failed("rescan", error, error?.let { ScanSaveFailure.of(it) } ?: ScanSaveFailure.Other, pack)
                return@launch
            }
            val rescanned = merging.getOrNull()
            if (rescanned is RescanSave.Refused) {
                rescanRefusal = rescanned.why
                rescanSeparate = true
                if (attached.get()) {
                    val why = IllegalStateException("re-scan not lined up: ${rescanned.why ?: "no depth points"}")
                    failed("rescan", why, ScanSaveFailure.Refused, pack)
                    return@launch
                }
            }
            val merged = (rescanned as? RescanSave.Merged)?.pack
            // Kept on its own, a part scanned again is named after its scan: "Living room · part".
            val (saved, title) = held ?: when {
                merged != null -> merged to (rescan?.mergedTitle ?: builtTitle)
                else -> pack to (rescan?.takeIf { rescanSeparate }?.partTitle ?: builtTitle)
            }
            unsaved = saved to title
            unsavedFrom = pack
            // Opened once, before it is written: the result screen shows it, and the save counts
            // its figures off it instead of parsing a long scan's log a second time (#4815).
            val openedResult = runCatching { loadRerunSession(saved) }
                .onFailure { if (it is CancellationException) throw it }
            openedResult.exceptionOrNull()?.let {
                Log.w(SCAN_SAVE_LOG_TAG, "3D Scan: opening the built scan failed; saving it anyway", it)
            }
            val opened = openedResult.getOrNull()
            // The saved entry's id opens its result screen, where the name is edited (#4597).
            val savedResult = withContext(Dispatchers.IO) {
                runCatching {
                    check(!qaSaveFails) { "QA: forced save failure" }
                    val read = opened?.let { OpenedCapture(it.trace, it.manifest, saved.media) }
                    store.save(saved, title, RerunSessionSource.Recorded, now, scope = scanScope, opened = read).id
                }
            }
            val savedId = savedResult.getOrNull()
            if (savedId == null) {
                val error = savedResult.exceptionOrNull()
                val failure = when {
                    // The QA state shows the card a long scan's full heap gets, Save as file included.
                    qaSaveFails -> ScanSaveFailure.Memory
                    error != null -> ScanSaveFailure.of(
                        error,
                        scanBytes = saved.manifest.size.toLong() + saved.log.size + saved.media.size,
                        freeStorageBytes = appContext.filesDir.usableSpace,
                    )
                    else -> ScanSaveFailure.Other
                }
                failed("write", error, failure, saved)
                return@launch
            }
            dropCapture()
            if (!attached.get()) {
                // The screen was left while it saved: the scan is kept and listed, nothing opens.
                onSavedAway(opened, title, savedId, homeOf)
                return@launch
            }
            if (opened == null) return@launch
            when (next) {
                SaveNext.Open -> onScanned(opened, title, saved, savedId)
                SaveNext.FinishHome -> {
                    onRoomSaved(opened, title, savedId)
                    onFinishHome()
                }
                SaveNext.NextRoom -> {
                    onRoomSaved(opened, title, savedId)
                    // The next room's capture opens paused, in the same AR session: nothing is
                    // captured on the way there, and Start begins it.
                    betweenRooms = true
                    if (qaRecord) take++ else onStartScan()
                    phase = ScanPhase.Paused
                }
            }
        }
        saveJob.set(job)
        job.invokeOnCompletion { RerunScanBuilds.ended(away = !attached.get()) }
    }
    val onSaveScan = { saveScan(if (scanScope.roomByRoom) SaveNext.FinishHome else SaveNext.Open) }
    val onDiscardScan = {
        dropCapture()
        onBack()
    }
    // Back during a capture asks first: keep scanning, save, or discard. The capture pauses
    // while it asks, so nothing is recorded behind the dialog. While it saves, Back leaves at
    // once and the save goes on behind it (#4500): the scan lands in "Your scans".
    val onBackFromLive: () -> Unit = {
        when (ScanBack.of(recording, betweenRooms, phase)) {
            ScanBack.Leave -> {
                // Between two rooms, nothing captured since the last one: Back goes to the summary.
                if (recording) dropCapture()
                onBack()
            }
            ScanBack.LeaveWhileSaving -> onBack()
            ScanBack.AskFirst -> {
                moveTo(phase.leaveAsked())
                leaveAsked = true
            }
        }
    }
    BackHandler(enabled = recording, onBack = onBackFromLive)

    // What the capture holds so far, counted off the scan itself: dense points on a depth scan,
    // ARCore's feature points otherwise. Zero at the start, never shown against a cap.
    val stats = debugSession.stats
    val capturedPoints = when {
        // The count that only grows (#4717): a coarsened region moves nothing on screen.
        scan?.rawDepth == true -> scan?.denseCaptured ?: 0
        qaLong != null -> qaLong?.shownCount ?: 0
        else -> stats.mapPoints
    }
    // What the review and the save say: the points the saved scan keeps, which "Your scans" lists
    // too. On a depth scan that is fewer than the HUD's count once the map coarsens (#4717) —
    // 404,337 confirmed against 232k kept on a long outdoor scan — so the review never promises
    // more than the list will show (#4815).
    val keptPoints = if (scan?.rawDepth == true) scan?.denseCount ?: 0 else capturedPoints
    // Points still rising are a scan at work, whatever the floor's coverage says: the top line
    // and the coaching line both read it.
    val pointsRising = rememberPointsRising(capturedPoints) {
        scan?.elapsedSeconds() ?: qaLong?.shownSeconds ?: stats.duration
    }
    // #4717: whether the surface in view gives too little to read, and whether the photos ran
    // out. Plain fields of the scan, read on the HUD's tick.
    val lowTexture by produceState(false, scan, qaLong) {
        while (true) {
            value = scan?.lowTexture ?: qaLong?.shownLowTexture ?: false
            delay(HUD_TICK_MS)
        }
    }
    // #4540: no usable depth for two seconds — the real cause of a count frozen at zero.
    val depthStarved by produceState(false, scan) {
        while (true) {
            value = scan?.depthStarved ?: false
            delay(HUD_TICK_MS)
        }
    }
    val photoLimitReached by produceState(false, scan, qaLong) {
        while (true) {
            value = scan?.isPhotoLimitReached ?: qaLong?.shownPhotoLimit ?: false
            delay(HUD_TICK_MS)
        }
    }
    // The photo limit is said once, in the coaching line, and not again.
    var photoNoticeOver by remember(scanMedia) { mutableStateOf(false) }
    LaunchedEffect(photoLimitReached) {
        if (photoLimitReached && !photoNoticeOver) {
            delay(PHOTO_NOTICE_MS)
            photoNoticeOver = true
        }
    }
    val capturedPhotos = debugSession.trace.imageCount
    val captureEmpty = capturedPoints == 0 && capturedPhotos == 0 && stats.planes == 0
    val capturedClock = ArDebugFormat.clock(scan?.elapsedSeconds() ?: stats.duration)
    val trackingLost = recording && (
        ForcedTrackingFailure.override != null || if (qaRecord) qaTrackingLost else !isTracking
        )
    // The capture's clock runs only while it scans with tracking held: Pause, review, the Back
    // dialog and a lost track all stop it, and it runs on from there.
    val clockRuns = phase.clockRuns(trackingLost)
    LaunchedEffect(scan, clockRuns) { scan?.followClock(clockRuns) }
    // A capture holds the phone upright (#4807), as Polycam, Scaniverse and the RoomPlan apps do:
    // a room is swept with the phone vertical, and turned sideways mid-sweep the whole screen —
    // status card, map, record control — fell into a strip a third of its height. The activity
    // handles the rotation itself (configChanges), so nothing restarts; the lock goes with the capture.
    val captureActivity = LocalActivity.current
    DisposableEffect(recording, captureActivity) {
        val activity = captureActivity ?: return@DisposableEffect onDispose {}
        if (!recording) return@DisposableEffect onDispose {}
        val before = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity.requestedOrientation = before }
    }

    // A room's or an area's capture shares the screen between its live 3D map and the camera
    // (Thomas's QA of 2026-10-22): the map above, the camera below, the swap button trading their
    // shares. Measured: where the status card ends and where the controls start, in root pixels.
    // Held for the capture's length: the coaching line going from one line to two moved both
    // edges, and the camera band jumped with them.
    var hudBottom by remember(scanMedia) { mutableFloatStateOf(Float.NaN) }
    var controlsTop by remember(scanMedia) { mutableFloatStateOf(Float.NaN) }
    var stageTop by remember { mutableFloatStateOf(0f) }
    var stageHeight by remember { mutableFloatStateOf(0f) }
    val splitStage = recording && scanMedia != null && phase in SCAN_MAP_PHASES && scanScope.mapsGround &&
        !debugFullScreen
    val split = if (splitStage) {
        ScanStageSplit.of(stageHeight, hudBottom - stageTop, controlsTop - stageTop, scanMapFirst)
    } else {
        null
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_rerun_title),
        onBack = onBackFromLive,
        // A capture owns the screen (#4807): no dock, and Send feedback / QA mode in the ⋮ menu
        // at the top, where the Settings button took the bottom of the screen for two rows.
        actionsMenu = recording,
        // The sheet holds what the screen must not: the connection steps a developer types
        // once. The screen itself only says what the demo does and whether it is live. A
        // capture in progress owns the screen: no Settings over it.
        controls = if (recording) null else { { RerunSheet() } },
        topOverlay = {
            if (recording && scanMedia != null) {
                // The HUD runs on its own clock: the trace records nothing while tracking is
                // lost ("Not enough detail"), and a HUD read off it froze there for seconds.
                val now by produceState(SystemClock.elapsedRealtimeNanos(), scan) {
                    while (true) {
                        delay(HUD_TICK_MS)
                        value = SystemClock.elapsedRealtimeNanos()
                    }
                }
                // Tracking loss freezes the capture, so the HUD says so: the coaching line
                // reads "Scan paused", and a red "Scanning" dot above it would contradict it.
                // Where the status card ends over the live 3D map: its lowest edge in this capture.
                Box(
                    Modifier.onGloballyPositioned {
                        if (phase in SCAN_MAP_PHASES) {
                            hudBottom = ScanStageSplit.holdLowest(hudBottom, it.positionInRoot().y + it.size.height)
                        }
                    },
                ) {
                    ScanHud(
                        phase = if (trackingLost && phase.integrates) ScanPhase.Paused else phase,
                        seconds = scan?.elapsedSeconds(now) ?: qaLong?.shownSeconds ?: stats.duration,
                        points = capturedPoints,
                        label = ScanScopeCopy.label(scanScope, roomNumber),
                        activity = ScanActivity.of(
                            phase = phase,
                            trackingLost = trackingLost,
                            coach = scanCoverage.coachLine,
                            lowTexture = lowTexture,
                            depthStarved = depthStarved,
                            done = scanWalls?.done == true,
                            pointsRising = pointsRising,
                        ),
                        room = scanWalls,
                    )
                }
            } else if (debugFullScreen) {
                ArDebugLegend(debugSession)
            } else {
                // While the SDK's "Couldn't start AR" card explains why there is no session,
                // the preview has nothing to mirror and sat on top of that card's title,
                // under the "No computer connected" card (#3989). One card at a time
                // (DESIGN.md "AR Overlay Card"): both come back as soon as a session
                // starts. A QA fixture hides the SDK card and owns the 3D view, so it
                // keeps them. Streaming to a computer is advanced: its card shows only
                // once one is connected.
                val availabilityCardShown = arCoreAvailability != null && qaState == null
                if (!availabilityCardShown) {
                    if (isConnected) RerunStatusCard(status)
                    ArDebugPip(
                        session = debugSession,
                        orbit = pipOrbit,
                        engine = engine,
                        modelLoader = modelLoader,
                        materialLoader = materialLoader,
                        onExpand = { debugFullScreen = true },
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(end = Space.md),
                    )
                }
            }
        },
        // Status banner + primary action are both bottom-anchored, so both live in the
        // scaffold slot: a bottom-aligned Column that stacks them instead of letting
        // them share the band with each other and with the Settings FAB (#2779).
        bottomOverlay = {
            if (debugFullScreen) {
                ArDebugTimelineCard(debugSession)
            } else {
                // During a capture its one coaching line speaks instead: one message at a time.
                RerunCameraBottomOverlay(
                    visible = !recording && (
                        (!isTracking && arCoreAvailability == null && qaDebug == null) ||
                            ForcedTrackingFailure.override != null
                        ),
                    trackingFailureReason = trackingFailureReason,
                    // Before a scan, the wait is for Record; during one, ARCore's own guidance.
                    // The scan starts by itself as soon as AR is ready: said while it gets ready (#4540).
                    searching = when {
                        recording -> null
                        !autoStarted && qaDebug == null && !qaRecord -> ScanCopy.STARTING
                        else -> ScanCopy.WAITING
                    },
                    isConnected = isConnected,
                    sharing = sharing,
                    onSaveAndShare = onSaveAndShare,
                )
                // The floor map is room guidance, and an area's: an object has no ground to cover (#4647).
                val mapsFloor = phase in SCAN_MAP_PHASES && scanScope.mapsGround
                // The 2D card only when the screen has no room for the live 3D map.
                if (recording && scanMedia != null && mapsFloor && split == null) {
                    ScanCoverageMap(
                        state = scanCoverage,
                        modifier = Modifier
                            .align(if (scanMapExpanded) Alignment.CenterHorizontally else Alignment.Start)
                            .padding(horizontal = Space.md),
                        // A room's area waits on two walls (#4540): one wall and the floor are not a room.
                        showFloor = scanWalls == null || scanWalls.wallCount >= ArDebugSession.MIN_ROOM_WALLS,
                        expanded = scanMapExpanded,
                        onToggle = { scanMapExpanded = !scanMapExpanded },
                    )
                }
                // Only when it does something: the view was dragged off the phone, on a capture
                // still open (#4718).
                if (recording && !scanFollowing && phase in SCAN_MAP_PHASES) {
                    ScanRecenterPill(onRecenter = { scanOrbit.recenter() })
                }
                if (arCoreAvailability == null || qaState != null) {
                    // Where the controls start, their highest edge in this capture: the live 3D
                    // map and the camera share the band above.
                    Box(
                        Modifier.onGloballyPositioned {
                            // The review's and the shutter's cards are taller: only the capture's controls count.
                            if (recording && phase in SCAN_MAP_PHASES) {
                                controlsTop = ScanStageSplit.holdHighest(controlsTop, it.positionInRoot().y)
                            }
                        },
                    ) {
                        when {
                            !recording -> ScanShutter(startEnabled = isTracking, onStart = onStartScan)
                            phase == ScanPhase.Reviewing && scanScope.roomByRoom -> HomeReviewCard(
                                roomNumber = roomNumber,
                                empty = captureEmpty,
                                figures = ScanCopy.reviewFigures(keptPoints, capturedPhotos, capturedClock),
                                onKeepScanning = { moveTo(ScanPhase.Scanning) },
                                onNextRoom = { saveScan(SaveNext.NextRoom) },
                                onSaveAndFinish = { saveScan(SaveNext.FinishHome) },
                                onFinish = if (roomNumber > 1) {
                                    {
                                        dropCapture()
                                        onFinishHome()
                                    }
                                } else {
                                    null
                                },
                            )
                            phase == ScanPhase.Reviewing -> ScanReviewCard(
                                empty = captureEmpty,
                                figures = ScanCopy.reviewFigures(keptPoints, capturedPhotos, capturedClock),
                                onKeepScanning = { moveTo(ScanPhase.Scanning) },
                                onSave = onSaveScan,
                            )
                            phase == ScanPhase.Saving -> ScanSavingCard(points = keptPoints)
                            phase == ScanPhase.SaveFailed -> ScanSaveFailedCard(
                                // The scan was never written: discarding it asks first.
                                onDiscard = { discardAsked = true },
                                // Try again, or — for a part that did not line up — Save as a separate scan.
                                onRetry = { saveScan(saveNext) },
                                failure = saveFailure,
                                refusal = rescanRefusal,
                                scanBytes = unsaved?.first?.let { it.manifest.size.toLong() + it.log.size + it.media.size } ?: 0L,
                                fileSaving = fileSaving,
                                fileSaveFailed = fileSaveFailed,
                                // Save as file: the scan written as a .svscan for the share sheet — Files,
                                // Drive — when saving on the phone keeps failing the same way (#4815).
                                onSaveAsFile = unsaved?.takeIf { saveFailure?.offersFile == true }?.let { (pack, title) ->
                                    {
                                        if (!fileSaving) {
                                            fileSaving = true
                                            fileSaveFailed = false
                                            val appContext = context.applicationContext
                                            RerunScanBuilds.scope.launch {
                                                val file = withContext(Dispatchers.IO) {
                                                    runCatching {
                                                        RerunShareCopy.write(
                                                            pack, title, rerunShareDirectory(appContext, RERUN_SHARE_UNSAVED),
                                                            includePhotos = true, scope = scanScope,
                                                        )
                                                    }
                                                }
                                                fileSaving = false
                                                file.onSuccess { sharePreparedScan(appContext, it, title) }
                                                    .onFailure {
                                                        Log.w(SCAN_SAVE_LOG_TAG, "3D Scan: Save as file failed", it)
                                                        fileSaveFailed = true
                                                    }
                                            }
                                        }
                                    }
                                },
                            )
                            else -> ScanControls(
                                phase = phase,
                                coaching = ScanScopeCopy.coaching(
                                    scope = scanScope,
                                    line = ScanCopy.coaching(
                                        phase = phase,
                                        trackingLost = trackingLost,
                                        photoLimitReached = photoLimitReached && !photoNoticeOver,
                                        detail = scan?.denseDetail ?: qaLong?.shownDetail ?: ScanDetail.Fine,
                                        coach = scanCoverage.coachLine,
                                        lowTexture = lowTexture,
                                        depthStarved = depthStarved,
                                        pointsRising = pointsRising,
                                        hasPoints = capturedPoints > 0,
                                    ),
                                    nextRoom = roomNumber.takeIf { betweenRooms },
                                    walls = scanWalls?.wallCount,
                                    turn = heldTurn,
                                    done = scanWalls?.done == true,
                                    facingWall = scanCoverage.facingWall,
                                    wallToFinish = scanWalls?.facingUnfinished == true,
                                ),
                                onTogglePause = {
                                    betweenRooms = false
                                    moveTo(phase.togglePause())
                                },
                                onFinish = {
                                    betweenRooms = false
                                    moveTo(phase.finish())
                                },
                                resumeLabel = if (betweenRooms) ScanScopeCopy.START_ROOM else ScanCopy.RESUME,
                            )
                        }
                    }
                }
            }
        },
        // A scan in progress owns the screen: nothing in the dock may leave it half-taken.
        dock = if (recording) emptyList() else listOf(
            DockItem(
                icon = Icons.Rounded.Videocam,
                label = "Camera view",
                caption = "Camera",
                onClick = { debugFullScreen = false },
                selected = !debugFullScreen,
            ),
            DockItem(
                icon = Icons.Rounded.ViewInAr,
                label = "3D debug view",
                caption = "3D view",
                onClick = { debugFullScreen = true },
                selected = debugFullScreen,
            ),
            DockItem(
                icon = Icons.Outlined.RestartAlt,
                label = "Recenter the 3D view",
                caption = "Recenter",
                onClick = { debugOrbit.recenter() },
                enabled = debugFullScreen,
            ),
        ),
    ) {
        val cameraStream = rememberARCameraStream(materialLoader)
        // QA camera backdrop (#3308): the emulator delivers no camera frame.
        val qaBackdrop = rememberQaCameraBackdropActive(cameraReady)
        val density = LocalDensity.current
        val stageGround = LocalStageChrome.current.ground
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    stageTop = it.positionInRoot().y
                    stageHeight = it.size.height.toFloat()
                }
                .then(if (split != null) Modifier.background(stageGround) else Modifier),
        ) {
            // A room's scan shares the screen (remark #2): the live 3D map on top, the camera under
            // it down to the controls. Same composables either way, so the AR session lives on.
            Box(modifier = Modifier.scanCameraPane(split).onSizeChanged { stageSize = it }) {
                val photo = qaPhoto
                if (qaBackdrop && photo != null) {
                    Image(
                        photo,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (qaBackdrop) {
                    QaCameraBackdrop(seed = QA_SEED)
                }
                ARSceneView(
                    lifecycle = rememberHostLifecycle(),
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    isOpaque = !qaCameraBackdropEnabled(),
                    surfaceType = qaCameraBackdropSurfaceType(),
                    cameraStream = if (qaBackdrop) null else cameraStream,
                    playbackDataset = arPlaybackDataset,
                    // A capture draws the walls it recognises itself (#4540): ARCore's own planes, with
                    // their upright dashes on walls and edges cut where ARCore stopped, came on top of
                    // them and read as noise (QA 2026-10-22, remarks 4 and 8).
                    planeRenderer = !recording,
                    sessionCameraConfig = { RerunScanCamera.select(it, playback = arPlaybackDataset != null) },
                    sessionConfiguration = { session: Session, config: Config ->
                        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                        // Rerun v2 tier B: ARCore's raw depth where the phone has it, never where it
                        // does not — that phone keeps the sparse v1 scan, and says so.
                        val depth = runCatching {
                            session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
                        }.getOrDefault(false)
                        if (depth) config.depthMode = Config.DepthMode.RAW_DEPTH_ONLY
                        rawDepth = depth
                    },
                    onSessionUpdated = { session: Session, frame: Frame ->
                        if (frame.timestamp > 0L) cameraReady = true
                        latestFrame = frame
                        isTracking = frame.camera.trackingState == TrackingState.TRACKING
                        // The live view's eye (#4716): this very frame's pose and projection, for the
                        // AR view's display geometry, which the live view shares, full screen. Paused,
                        // the frames keep coming, so the eye stays on the phone and the cloud in the room;
                        // saving, the saving orbit owns the view ([ScanLens.seesThroughPhone]).
                        if (ScanLens.seesThroughPhone(isTracking, phase)) {
                            scanLens.update { cameraToWorld, projection ->
                                frame.camera.displayOrientedPose.toMatrix(cameraToWorld, 0)
                                frame.camera.getProjectionMatrix(projection, 0, ScanLens.NEAR_M, ScanLens.FAR_M)
                            }
                        } else {
                            scanLens.invalidate()
                        }
                        if (isTracking) livePose.pose = frame.camera.displayOrientedPose.let {
                            DebugPose(it.tx(), it.ty(), it.tz(), it.qx(), it.qy(), it.qz(), it.qw())
                        }
                        // Bridge gates on its own enabled + connection state, so this
                        // is safe whether or not the recorder is reachable.
                        bridge.logFrame(session, frame)
                        // A QA fixture owns the 3D view; otherwise it mirrors this session.
                        // A scan rides on the same pass while it is scanning.
                        // Paused, in review or saving, a capture takes nothing: no path, no points.
                        val capturing = scan == null || phase.integrates
                        if (qaDebug == null && !qaRecord && capturing) {
                            debugRecorder.record(debugSession.trace, session, frame, anchors, scan)
                        }
                    },
                    // A forced QA state hides the SDK's "Couldn't start AR" card so the screen
                    // can be captured on the emulator, which never starts AR (#2754).
                    arCoreAvailabilityOverlay = if (qaState == null) {
                        { ARCoreAvailabilityOverlay(it) }
                    } else {
                        null
                    },
                    onARCoreAvailability = { arCoreAvailability = it },
                    onTrackingFailureChanged = { reason ->
                        trackingFailureReason = reason
                    },
                    onGestureListener = rememberOnGestureListener(
                        onSingleTapConfirmed = { event: MotionEvent, _ ->
                            val frame = latestFrame ?: return@rememberOnGestureListener
                            if (frame.camera.trackingState != TrackingState.TRACKING) {
                                return@rememberOnGestureListener
                            }
                            val hit = frame.hitTest(event).firstOrNull { result ->
                                val trackable = result.trackable
                                trackable is Plane &&
                                    trackable.isPoseInPolygon(result.hitPose) &&
                                    result.distance <= MAX_PLACEMENT_DISTANCE_METERS
                            }
                            if (hit != null) {
                                anchors.add(hit.createAnchor())
                            }
                        }
                    )
                ) {
                    anchors.forEach { anchor ->
                        // One model instance per placement: a Filament instance can only hang
                        // off one node, so a shared one showed a single dog however many taps.
                        key(anchor) {
                            val dog = rememberModelInstance(modelLoader, "models/shiba.glb")
                            AnchorNode(anchor = anchor) {
                                dog?.let { ModelNode(modelInstance = it, scaleToUnits = 0.3f) }
                            }
                        }
                    }
                }

                // A capture owns the screen (#4628): its cloud full screen over the dimmed camera,
                // following the phone. The camera keeps tracking, and recording, under it.
                if (recording && scanMedia != null) {
                    ScanLiveView(
                        session = debugSession,
                        orbit = scanOrbit,
                        follow = scanFollow,
                        coverage = scanCoverage,
                        media = scanMedia,
                        engine = engine,
                        modelLoader = modelLoader,
                        materialLoader = materialLoader,
                        livePose = scanLivePose,
                        saving = phase == ScanPhase.Saving,
                        walls = scanWalls,
                        turn = heldTurn,
                        reviewing = phase == ScanPhase.Reviewing,
                    )
                }
                // A part scanned again (#4736): its frame over the camera, and how far along it is —
                // gone once it did not line up: the card says so, once, and saves it on its own (#4815).
                // In the camera's pane, whose size the frame is projected on: never over the map.
                if (recording && rescan != null && phase != ScanPhase.SaveFailed && !rescanSeparate) {
                    RescanGuide(
                        rescan,
                        live = { scanMedia?.liveDense?.invoke()?.cloud },
                        lens = scanLens,
                        drawn = scanFollowing,
                    )
                }
            }

            if (split != null && scanMedia != null) {
                ScanLiveMap(
                    session = debugSession,
                    media = scanMedia,
                    coverage = scanCoverage,
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    livePose = scanLivePose,
                    clearTop = split.clearTop,
                    // A room's area waits on two walls (#4540): one wall and the floor are not a room.
                    showArea = scanWalls == null || scanWalls.wallCount >= ArDebugSession.MIN_ROOM_WALLS,
                    mapFirst = scanMapFirst,
                    onSwap = { scanMapFirst = !scanMapFirst },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { split.mapBottom.toDp() })
                        .background(stageGround),
                )
            }

            // Full-screen 3D view over the camera, which keeps tracking (and recording) under it.
            if (debugFullScreen) {
                ArDebugSceneView(
                    session = debugSession,
                    orbit = debugOrbit,
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (leaveAsked) {
                ScanLeaveDialog(
                    saveFailed = phase == ScanPhase.SaveFailed,
                    saveFailure = saveFailure,
                    // A capture that caught nothing has nothing to save: Keep scanning or Discard.
                    empty = captureEmpty && builtPack == null,
                    onKeepScanning = {
                        leaveAsked = false
                        // Back paused the capture, or it was paused or in review already: Keep
                        // scanning means scanning again, from any of them.
                        moveTo(phase.keepScanning())
                    },
                    onSave = onSaveScan,
                    onDiscard = onDiscardScan,
                )
            }
            if (discardAsked) {
                ScanDiscardDialog(
                    onCancel = { discardAsked = false },
                    onDiscard = {
                        discardAsked = false
                        dropCapture()
                    },
                )
            }

            // Share result dialog
            shareResult?.let { result ->
                ShareResultDialog(
                    result = result,
                    onDismiss = { shareResult = null },
                    onCopyPath = { path ->
                        copyToClipboard(context, "Path", path)
                        Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
                    },
                    onCopyUrl = { url ->
                        copyToClipboard(context, "Viewer URL", url)
                        Toast.makeText(context, "Viewer URL copied", Toast.LENGTH_SHORT).show()
                    },
                    onShare = onShare@{ url ->
                        if (url.isNullOrBlank()) return@onShare
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                            putExtra(Intent.EXTRA_SUBJECT, "AR session — SceneView")
                        }
                        context.startActivity(
                            Intent.createChooser(intent, "Share AR session")
                        )
                    },
                )
            }
        }
    }
}

/**
 * The stream's status, a line of the settings sheet's "Connect your computer" section: a dot that
 * turns green while events reach the computer, a title, and how much has been sent. It was a
 * card over the camera (#4379); Save & Share on the screen already says a computer is there.
 */
@Composable
private fun RerunStatusCard(status: RerunStatusUx) {
    OverlayCard(testTag = RERUN_STATUS_CARD_TAG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(StatusDotSize)
                    .background(
                        color = if (status.live) ArOverlay.accentSuccess else ArOverlay.onScrimMuted,
                        shape = CircleShape,
                    ),
            )
            Spacer(Modifier.width(Space.sm))
            Text(text = status.title, style = OnScrimTitle)
        }
        Text(text = status.detail, style = OnScrimBody)
    }
}

/**
 * The camera view's bottom stack: the tracking banner, then Save & Share once it can work.
 * [visible] gates the banner only; [searching] replaces its generic "scanning" line.
 */
@Composable
private fun DemoBottomOverlayScope.RerunCameraBottomOverlay(
    visible: Boolean,
    trackingFailureReason: TrackingFailureReason?,
    searching: String?,
    isConnected: Boolean,
    sharing: Boolean,
    onSaveAndShare: () -> Unit,
) {
    // ForcedTrackingFailure.override shadows the real ARCore-reported reason
    // when a developer has picked one in the debug menu (#1881). Read it here
    // so flipping the override re-renders the overlay immediately.
    val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
    AnimatedVisibility(
        // #3341: on a device ARCore has ruled out, the flag this banner waits on never
        // flips, so the banner would promise a scan under the SDK's "AR unavailable" card.
        // The caller drops it then, and lets the card carry reason and retry.
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val trackingHint = trackingFailureMessage(effectiveReason)
        DemoStatusBanner(
            text = trackingHint ?: searching ?: stringResource(R.string.ar_status_scanning),
            // Tone comes from the same reason that picks the sentence: a bad
            // session state or a camera taken by another app needs the user to
            // act outside this demo, the light / motion / texture reasons ask
            // for a physical move, and no reason at all is plain scanning.
            tone = when {
                trackingHint == null -> DemoStatusTone.Progress
                effectiveReason == TrackingFailureReason.BAD_STATE ||
                    effectiveReason == TrackingFailureReason.CAMERA_UNAVAILABLE ->
                    DemoStatusTone.Blocked
                else -> DemoStatusTone.Guidance
            },
        )
    }

    // Primary action on-screen (#1964), offered only when it can work (#2658,
    // #3831): with no computer attached a save can only fail, and the status card
    // already says so without an error-toned banner.
    if (BuildConfig.DEBUG && rerunShowsSaveAction(isConnected = isConnected, sharing = sharing)) {
        val saveUx = rerunSaveActionUx(sharing = sharing, isConnected = isConnected)
        SceneActionBar(
            SceneAction(
                label = saveUx.label,
                onClick = onSaveAndShare,
                enabled = saveUx.enabled,
            ),
        )
    }
}

/** "Connect your computer", numbered, commands in mono blocks. Theme colours: it is the sheet. */
@Composable
private fun RerunSetupSection() {
    // Space.md above: it opens under the "Developer tools" row that folds it.
    Column(
        modifier = Modifier.padding(top = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = RERUN_SETUP_TITLE,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        // What streaming does, under its own heading: it is the advanced path, not the demo.
        Text(
            text = RERUN_INTRO,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RERUN_SETUP_STEPS.forEachIndexed { index, step -> RerunSetupStepRow(index + 1, step) }
    }
}

@Composable
private fun RerunSetupStepRow(number: Int, step: RerunSetupStep) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(Space.md + Space.xs),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text(text = step.text, style = MaterialTheme.typography.bodyMedium)
            step.command?.let { command ->
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(SceneViewTokens.Radius.xs),
                        )
                        .padding(horizontal = Space.sm, vertical = Space.xs + Space.xs / 2),
                )
            }
        }
    }
}

@Composable
private fun ShareResultDialog(
    result: RerunBridge.ShareResult,
    onDismiss: () -> Unit,
    onCopyPath: (String) -> Unit,
    onCopyUrl: (String) -> Unit,
    onShare: (String?) -> Unit,
) {
    val viewerUrl = result.viewerUrl
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (result.success) "Recording saved" else "Couldn't save")
        },
        text = {
            if (result.success) {
                ShareResultBody(result, onCopyPath, onCopyUrl)
            } else {
                // Never surface the bridge's raw internal reason (e.g. "call
                // connect() first") — map it to actionable setup copy (#2658).
                Text(
                    text = rerunSaveFailureMessage(result.reason),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            if (result.success && viewerUrl != null) {
                TextButton(onClick = { onShare(viewerUrl) }) { Text("Share link") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = if (result.success) {
            { TextButton(onClick = onDismiss) { Text("Done") } }
        } else null,
    )
}

@Composable
private fun ShareResultBody(
    result: RerunBridge.ShareResult,
    onCopyPath: (String) -> Unit,
    onCopyUrl: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            "${result.events} events recorded.",
            style = MaterialTheme.typography.bodyMedium,
        )
        result.path?.let { path ->
            Text("Saved on your computer:", style = MaterialTheme.typography.labelMedium)
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { onCopyPath(path) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy path") }
        }
        result.viewerUrl?.let { url ->
            Text(
                "Drop the file onto sceneview.github.io/rerun to scrub through it. To share " +
                    "it, upload the file somewhere public and send this link:",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(
                onClick = { onCopyUrl(url) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Copy viewer URL") }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private val StatusDotSize = Space.sm + Space.xs / 2 // 10 dp, same as the record dot

private const val MAX_PLACEMENT_DISTANCE_METERS = 5f
private const val QA_SEED = "ar-rerun"

private const val QA_STATE_CONNECTED = "connected"
private const val QA_STATE_SAVED = "saved"

/** QA only: the Record screen mid-scan, fed by the sample room (#2754: no AR on the emulator). */
private const val QA_STATE_RECORD = "record"

/** QA only (#4627): the capture screen held on a lost track, which the emulator never has. */
private const val QA_STATE_RECORD_TRACKING_LOST = "record-tracking-lost"

/** QA only (#4627): the capture screen whose write fails, to show the failure that stays. */
private const val QA_STATE_RECORD_SAVE_FAILED = "record-save-failed"

/** QA only (#4629): the capture screen fed a whole flat's depth, and the same with the old fixed cap. */
private const val QA_STATE_RECORD_LONG = "record-long"
private const val QA_STATE_RECORD_LONG_FIXED = "record-long-fixed"
// #4717: the long walk started on a plain surface, for "Move closer" and the relaxed threshold.
private const val QA_STATE_RECORD_LOW_TEXTURE = "record-low-texture"
private val LONG_QA_STATES = setOf(QA_STATE_RECORD_LONG, QA_STATE_RECORD_LONG_FIXED, QA_STATE_RECORD_LOW_TEXTURE)

/** QA only (#4647): the capture screen as an object's scan, and as a room of a whole home. */
private const val QA_STATE_RECORD_OBJECT = "record-object"
private const val QA_STATE_RECORD_HOME = "record-home"

/** QA only (#4714, #4720): a room taken without depth — no floor plan — and an outdoor area's take. */
private const val QA_STATE_RECORD_NO_DEPTH = "record-no-depth"
private const val QA_STATE_RECORD_AREA = "record-area"

/** QA only (#4647): what 3D Scan asks first, and a whole home of two ray-cast rooms, summed up. */
private const val QA_STATE_CHOOSE = "choose"
private const val QA_STATE_HOME_SUMMARY = "home-summary"

/** QA only (#4540): a room's scan turning round a synthetic room, its walls rising as they are found. */
private const val QA_STATE_RECORD_WALLS = "record-walls"

/**
 * QA only (QA 2026-10-22): the same turn whose depth stalled - its fused cloud a floor and no
 * wall, the walls on ARCore's planes only - for the plan the export offers from the counted walls.
 */
private const val QA_STATE_RECORD_WALLS_STALLED = "record-walls-stalled"

private val RECORD_QA_STATES =
    setOf(
        QA_STATE_RECORD,
        QA_STATE_RECORD_WALLS,
        QA_STATE_RECORD_WALLS_STALLED,
        QA_STATE_RECORD_TRACKING_LOST,
        QA_STATE_RECORD_SAVE_FAILED,
        QA_STATE_RECORD_OBJECT,
        QA_STATE_RECORD_HOME,
        QA_STATE_RECORD_NO_DEPTH,
        QA_STATE_RECORD_AREA,
    ) + LONG_QA_STATES

/** The QA scan plays the sample once and holds on its end, as a scan in progress would. */
private const val QA_RECORD_NEVER_LOOP_S = 3_600f

/** Seconds the tracking-loss QA scan captures before it loses tracking. */
private const val QA_TRACKING_LOST_AFTER_S = 3f

/** `adb logcat -s ScanSave`: why a 3D Scan was not saved, with its size and the phone's heap (#4815). */
private const val SCAN_SAVE_LOG_TAG = "ScanSave"

/** How often the live screen checks whether ARCore has found the room, to start recording. */
private const val AUTO_START_POLL_MS = 100L

/** The scan HUD's clock ticks ten times a second, tracking or not. */
private const val HUD_TICK_MS = 100L
private const val NANOS_PER_SECOND = 1e9f

/**
 * QA only: the take the Record screen played from the sample, built into a session like a real
 * scan — its journal, and the sample's photos taken from where the trace's camera stood.
 */
private suspend fun qaSessionOf(
    trace: ArDebugTrace,
    sample: RerunReplayMedia,
    scope: ScanScope,
    depth: Boolean = true,
    depthStalled: Boolean = false,
): RerunCapturePack? {
    val events = trace.journal?.toList().orEmpty()
    trace.journal = null
    if (events.isEmpty()) return null
    val photos = withContext(Dispatchers.Default) {
        (0 until trace.imageCount).mapNotNull { index ->
            val path = trace.imagePath(index)
            val jpeg = sample.bytesOf(path) ?: return@mapNotNull null
            val pose = trace.frameAt(trace.imageTime(index)).camera ?: return@mapNotNull null
            ScanPhoto(path, jpeg, pose)
        }
    }
    // An object's take is a phone without raw depth (#4683): its sparse points and planes only,
    // cut to arm's length as they were recorded. So is a room taken without depth (#4714).
    if (scope == ScanScope.Object || !depth) {
        val sparse = ScanDevice("android", Build.MODEL, ScanDevice.TIER_SPARSE, ScanDevice.SOURCE_FEATURE_POINTS)
        return RerunCaptureBuilder.build(events, sample.manifest.lens, photos, device = sparse)
    }
    // The emulator has no depth: the ray-cast room stands in for a scan's fused points, so the
    // result the QA take opens on is built, kept and reopened as a real scan's is.
    val dense = withContext(Dispatchers.Default) { syntheticCloud().let { if (depthStalled) it.floorOnly() else it } }
    val device = ScanDevice("android", Build.MODEL, ScanDevice.TIER_DEPTH, ScanDevice.SOURCE_RAW_DEPTH)
    return RerunCaptureBuilder.build(events, sample.manifest.lens, photos, device = device, dense = dense)
}
private const val QA_SCRUB_FRACTION = 0.45f
private const val AR_DEBUG_FIXTURE = "rerun/sample-session.jsonl"

/** The QA states that feed the 3D view from [AR_DEBUG_FIXTURE] (#3950). */
private enum class ArDebugQaState(
    val key: String,
    val fullScreen: Boolean,
    val streams: Boolean,
    val scrubbed: Boolean = false,
) {
    Pip("pip", fullScreen = false, streams = false),
    PipStream("pip-stream", fullScreen = false, streams = true),
    Full("3d", fullScreen = true, streams = false),
    Scrub("3d-scrub", fullScreen = true, streams = false, scrubbed = true),
    FullStream("3d-stream", fullScreen = true, streams = true),
    ;

    companion object {
        fun of(state: String?): ArDebugQaState? = entries.firstOrNull { it.key == state }
    }
}

/** The QA states that open straight on the dollhouse (#4075). */
private val DOLLHOUSE_QA_STATES = setOf(QA_STATE_DOLLHOUSE_EMPTY, QA_STATE_DOLLHOUSE_3D)

/** The dollhouse with no session kept, whatever the phone holds (#4075). */
private const val QA_STATE_DOLLHOUSE_EMPTY = "dollhouse-empty"

/** The dollhouse of the newest session, in the 3D view: the emulator cannot run AR (#2754). */
private const val QA_STATE_DOLLHOUSE_3D = "dollhouse-3d"

/** The result built from the ray-cast room: the emulator's view of the result screen, which it cannot scan. */
private const val QA_STATE_MODEL_SYNTHETIC = "model-synthetic"

/** The same room when meshing finds no surface: the coloured-points fallback. */
private const val QA_STATE_MODEL_POINTS = "model-synthetic-points"

/** The same result with its export sheet open. */
private const val QA_STATE_MODEL_EXPORT = "model-synthetic-export"

/** A kept scan of the ray-cast room, opened on its floor plan (#4540): four walls and the ceiling seen. */
private const val QA_STATE_PLAN_SYNTHETIC = "plan-synthetic"

/** The same room with one wall and the ceiling never scanned: the plan says what is missing. */
private const val QA_STATE_PLAN_PARTIAL = "plan-partial"

private val PLAN_QA_STATES = setOf(QA_STATE_PLAN_SYNTHETIC, QA_STATE_PLAN_PARTIAL)

/** Beside the sessions, the id of the one session QA's plan states kept: the only one they replace. */
private const val QA_PLAN_MARKER = "qa-plan-session.txt"

/** The QA states that open straight on the result screen. */
private val MODEL_QA_STATES = setOf(QA_STATE_MODEL_SYNTHETIC, QA_STATE_MODEL_POINTS, QA_STATE_MODEL_EXPORT)

/** The QA states that open straight on the live AR screen. */
private val LIVE_QA_STATES =
    ArDebugQaState.entries.map { it.key } + QA_STATE_CONNECTED + QA_STATE_SAVED + RECORD_QA_STATES

/**
 * The QA states of the bundled replay: a view, and where the replay stands — paused at a fixed
 * fraction for a stable capture, or playing (`null`). With any QA state the camera neither flies
 * in nor drifts.
 */
private enum class RerunReplayQaState(val key: String, val mode: RerunMode, val pauseAt: Float?) {
    Replay("replay", RerunMode.Scene, QA_REPLAY_FRACTION),
    ReplayPlay("replay-play", RerunMode.Scene, null),
    ReplayMap("replay-map", RerunMode.Map, QA_REPLAY_FRACTION),
    ReplayCamera("replay-camera", RerunMode.Camera, QA_REPLAY_FRACTION),
    /** The sample's replay with its export sheet open. */
    ReplayExport("replay-export", RerunMode.Scene, QA_REPLAY_FRACTION),
    ;

    companion object {
        fun of(state: String?): RerunReplayQaState? = entries.firstOrNull { it.key == state }
    }
}

/** 62 % in: both models placed, all three planes found, the camera mid-turn. */
private const val QA_REPLAY_FRACTION = 0.62f

private const val QA_EVENTS_SENT = 1_204L
private const val QA_EVENTS_PER_SECOND = 10f
private val QA_SHARE_RESULT = RerunBridge.ShareResult(
    success = true,
    path = "~/sceneview/recording.rrd",
    viewerUrl = "https://sceneview.github.io/rerun/?url=https://example.com/recording.rrd",
    events = 1_204,
    reason = null,
)

internal const val RERUN_STATUS_CARD_TAG = "ar_rerun_status_card"

/** The capture phases that show its coverage map (#4631): while it is open, not under a card. */
private val SCAN_MAP_PHASES = setOf(ScanPhase.Scanning, ScanPhase.Paused)

/** The phone now, off the AR frame: a plain field, read in the 3D view's frame loop (#4718). */
private class LivePose {
    @Volatile var pose: DebugPose? = null
}

/** QA only (#4718): a paused emulator capture swings its still phone 40° either way, every 8 s. */
internal object QaPausedTurn {
    fun degreesAt(nanos: Long): Float = AMPLITUDE_DEGREES * sin(2f * PI.toFloat() * nanos / PERIOD_NS)

    private const val AMPLITUDE_DEGREES = 40f
    private const val PERIOD_NS = 8e9f
}

/** How long the photo limit's notice holds the coaching line before it becomes the HUD's line. */
// Brief (#4807): news, not a warning, so it leaves the line to the coaching quickly.
private const val PHOTO_NOTICE_MS = 2_500L

/** QA only: the cloud's floor alone, what a stalled depth leaves (QA 2026-10-22). */
private fun DenseCloud.floorOnly(): DenseCloud {
    val n = normals ?: return this
    val minY = (0 until count).minOfOrNull { positions[it * 3 + 1] } ?: return this
    val keep = (0 until count).filter { n[it * 3 + 1] > QA_FLOOR_NORMAL_Y && positions[it * 3 + 1] < minY + QA_FLOOR_BAND_M }
    fun FloatArray.pick() = FloatArray(keep.size * 3) { this[keep[it / 3] * 3 + it % 3] }
    return DenseCloud(
        positions.pick(),
        IntArray(keep.size) { colors[keep[it]] },
        n.pick(),
        confidences?.let { c -> ByteArray(keep.size) { c[keep[it]] } },
        levels?.let { l -> ByteArray(keep.size) { l[keep[it]] } },
    )
}

private const val QA_FLOOR_NORMAL_Y = 0.9f
private const val QA_FLOOR_BAND_M = 0.15f
