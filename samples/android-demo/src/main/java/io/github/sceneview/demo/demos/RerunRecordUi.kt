package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.sceneview.demo.demos.internal.CaptureChrome
import io.github.sceneview.demo.demos.internal.CaptureCue
import io.github.sceneview.demo.demos.internal.ScanActivity
import io.github.sceneview.demo.demos.internal.RescanRefusal
import io.github.sceneview.demo.demos.internal.ScanSaveFailure
import io.github.sceneview.demo.demos.internal.ScanTurn
import io.github.sceneview.math.Position
import io.github.sceneview.demo.demos.internal.OrbitPose
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.Icon
import androidx.compose.ui.res.stringResource
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.DebugGroup
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.ScanFollow
import io.github.sceneview.demo.demos.internal.ScanObserveGate
import io.github.sceneview.demo.demos.internal.ScanSavingOrbit
import io.github.sceneview.demo.demos.internal.ScanOpenTop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.sceneview.demo.ui.GlassActionPill
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.google.android.filament.Engine
import io.github.sceneview.demo.demos.internal.ArDebugFormat
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.demos.internal.ScanPhase
import io.github.sceneview.demo.demos.internal.ScanScopeCopy
import io.github.sceneview.demo.demos.internal.ScanStep
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader

/*
 * The capture screen of 3D Scan (#4627, #4628): the scan growing full screen over the dimmed
 * camera, following the phone heading-up, one
 * compact line above it — "Scanning · 0:42" and the points captured so far — and, under it,
 * one coaching line over the record control: Pause beside the stop shutter, which opens a review that Keep scanning leaves for
 * the same capture; only Save scan builds and writes it.
 */

/**
 * The capture's status: the red dot and `Scanning · 0:42` (`Paused` while frozen), then the
 * points captured, counted off the scan from zero and never shown against a cap.
 *
 * [activity] says what the capture is doing ([ScanActivity]); a room's scan adds its [room] gauge:
 * the walls found and how far along the room is ([ScanRoomGauge]), [complete] once
 * [RoomCompletion] says so. [trackingLost] reads at the top as `Tracking lost`, not as a pause.
 */
@Composable
internal fun ScanHud(
    phase: ScanPhase,
    seconds: Float,
    points: Int,
    label: String? = null,
    activity: ScanActivity? = null,
    room: ScanWallsState? = null,
    floorShare: Float = 0f,
    covered: Float? = null,
    complete: Boolean = room != null && CaptureChrome.complete(room.done, floorShare),
    trackingLost: Boolean = false,
) {
    val clock = ArDebugFormat.clock(seconds)
    val share = room?.let { CaptureChrome.share(it.completion, it.done, floorShare, complete) }
    OverlayCard(testTag = SCAN_HUD_TAG) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Box(
                modifier = Modifier
                    .size(ScanDotSize)
                    .background(
                        color = if (phase.integrates) ArOverlay.accentRecord else ArOverlay.onScrimMuted,
                        shape = CircleShape,
                    ),
            )
            Spacer(Modifier.width(Space.sm))
            // What is being scanned leads (#4647), then the useful state, not "Scanning": how far
            // the room is, the floor covered, stuck or paused; past the capture, what it is doing.
            val now = activity ?: if (phase.integrates) ScanActivity.Working else ScanActivity.Paused
            Text(
                text = CaptureChrome.headline(label, phase, now, share, covered.takeIf { room == null }, trackingLost),
                style = ScanLineStyle.copy(color = ArOverlay.onScrim),
                modifier = Modifier.weight(1f),
            )
        }
        // One line under it (#4807): `3 of 4 walls · 0:42` on a room, `0:42 · 1,234 points captured`.
        Text(
            text = CaptureChrome.detail(clock, room?.wallCount, points),
            style = ScanDetailStyle.copy(color = ArOverlay.onScrimMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(SCAN_POINTS_TAG),
        )
        if (share != null) ScanRoomGauge(share, complete)
    }
}

/**
 * A room's scan so far (#4540 live walls): a ten-segment meter under the HUD's `· 3 walls` (DESIGN.md "Meters",
 * one segment per 10 %), lit in `accent-progress`, then `accent-success` once the room is complete
 * ([CaptureChrome.complete]: walls closed and floor covered) — the moment to tap stop. The share is the meter alone, said aloud to TalkBack: the one
 * figure on screen is the map's area, once two walls are seen.
 */
@Composable
internal fun ScanRoomGauge(share: Float, complete: Boolean) {
    val done = complete
    val lit = if (done) ArOverlay.accentSuccess else ArOverlay.accentProgress
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        modifier = Modifier
            .semantics(mergeDescendants = true) { stateDescription = ScanCopy.completion(share) }
            .testTag(SCAN_ROOM_GAUGE_TAG),
    ) {
        // The walls are counted on the HUD's points line (#4807): the meter is the gauge here.
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            modifier = Modifier.widthIn(max = ArOverlay.maxWidth).fillMaxWidth(),
        ) {
            val segments = if (done) GAUGE_SEGMENTS else (share * GAUGE_SEGMENTS).toInt()
            repeat(GAUGE_SEGMENTS) { i ->
                val color by animateColorAsState(
                    targetValue = if (i < segments) lit else ArOverlay.meterTrack,
                    animationSpec = tween(SceneViewTokens.Duration.mediumMillis),
                    label = "scanGauge$i",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(Space.sm)
                        .background(color, RoundedCornerShape(SceneViewTokens.Radius.xs)),
                )
            }
        }
    }
}

/**
 * The turn to the next gap of a room's scan (#4540, "arrows to say turn this way"): a glass disc at
 * the screen's edge on that side, an arrow in `accent-guidance`, the colour of the dashed gap in
 * the room. Behind the phone, it sits in the middle with a turning arrow; ahead, the gap is in
 * view and the dashes speak for it. [turn] is held by the caller ([rememberHeld]), once for the
 * arrow and the coaching line.
 */
@Composable
internal fun BoxScope.ScanTurnArrow(turn: ScanTurn?) {
    val shown = turn?.takeIf { it != ScanTurn.Ahead }
    // Kept through the fade-out, so the arrow leaves from where it was.
    var last by remember { mutableStateOf(ScanTurn.Left) }
    if (shown != null) last = shown
    // Left and right are the room's, not the reading direction's: the same side in RTL.
    val alignment = when (last) {
        ScanTurn.Left -> AbsoluteAlignment.CenterLeft
        ScanTurn.Right -> AbsoluteAlignment.CenterRight
        else -> Alignment.Center
    }
    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn(tween(SceneViewTokens.Duration.mediumMillis)),
        exit = fadeOut(tween(SceneViewTokens.Duration.mediumMillis)),
        modifier = Modifier.align(alignment).padding(Space.md).testTag(SCAN_TURN_TAG),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(SceneViewTokens.Layout.touchTarget + Space.md)
                .background(LocalStageChrome.current.card, CircleShape),
        ) {
            // Drawn left to right whatever the locale: a mirrored arrow would point at the wrong wall.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Icon(
                    imageVector = when (last) {
                        ScanTurn.Left -> Icons.AutoMirrored.Rounded.ArrowBack
                        ScanTurn.Right -> Icons.AutoMirrored.Rounded.ArrowForward
                        else -> Icons.Rounded.UTurnLeft
                    },
                    contentDescription = ScanCopy.turnHint(last),
                    tint = ArOverlay.accentGuidance,
                    modifier = Modifier.size(SceneViewTokens.Layout.touchTarget - Space.sm),
                )
            }
        }
    }
}

private const val GAUGE_SEGMENTS = 10

/**
 * Under a capture still open: the one cue — what blocks, or else the next step — then the camera
 * app's record control (#4807), its stop named "Stop and review", then the Scan › Review › Model row.
 * The red stop shutter — the one REC knows from every camera — sits in the middle from the first
 * frame to the last and ends the capture (Finish: the review opens); Pause, the secondary, is a
 * smaller disc beside it and turns into Resume (a play glyph) while paused. Nothing swaps places:
 * the 72 dp shutter is where the thumb found it, paused or not.
 */
@Composable
internal fun ScanControls(
    phase: ScanPhase,
    cue: CaptureCue?,
    onTogglePause: () -> Unit,
    onFinish: () -> Unit,
    resumeLabel: String = ScanCopy.RESUME,
    complete: Boolean = false,
) {
    CaptureChromeHaptics(cue, complete)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md).testTag(SCAN_CONTROLS_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (cue != null) ScanCueLine(cue)
        Row(
            modifier = Modifier.widthIn(max = ArOverlay.maxWidth).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val paused = phase == ScanPhase.Paused
                ScanPauseButton(
                    paused = paused,
                    label = if (paused) resumeLabel else ScanCopy.PAUSE,
                    onClick = onTogglePause,
                )
            }
            // On a solid disc: the white ring and the red square vanish on a sunlit wall otherwise.
            RecordShutter(
                isRecording = true,
                startEnabled = true,
                onStart = {},
                onStop = onFinish,
                modifier = Modifier.testTag(SCAN_FINISH_TAG).background(ScanControlFill, CircleShape),
                stopLabel = CaptureChrome.STOP_AND_REVIEW,
            )
            // The shutter's mirror keeps it centred, as a camera's is, and says what it does.
            Box(Modifier.weight(1f).padding(start = Space.sm), contentAlignment = Alignment.CenterStart) {
                Text(
                    text = CaptureChrome.STOP_AND_REVIEW,
                    style = ScanLineStyle,
                    color = ArOverlay.onScrim,
                    maxLines = 2,
                    modifier = Modifier
                        .background(ScanControlFill, RoundedCornerShape(SceneViewTokens.Radius.sm))
                        .padding(horizontal = Space.sm, vertical = Space.xs),
                )
            }
        }
        ScanStepRow(ScanStep.of(phase))
    }
}

/** Pause and Resume, the record control's secondary: a 56 dp disc on the coaching line's ground. */
@Composable
private fun ScanPauseButton(paused: Boolean, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = ArOverlay.scrimDark,
        contentColor = ArOverlay.onScrim,
        border = BorderStroke(SceneViewTokens.Layout.hairlineWidth, ArOverlay.onScrimMuted),
        modifier = Modifier
            .size(SceneViewTokens.Layout.touchTarget + Space.sm)
            .testTag(SCAN_PAUSE_TAG)
            .semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = null,
                modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize + Space.xs),
            )
        }
    }
}

/**
 * Finish's review: the capture frozen, nothing built yet. Keep scanning returns to the same
 * capture; Save scan builds and writes it. A capture with nothing in it offers only Keep
 * scanning — there is nothing to save.
 */
@Composable
internal fun ScanReviewCard(
    empty: Boolean,
    figures: String,
    onKeepScanning: () -> Unit,
    onSave: () -> Unit,
) {
    OverlayCard(testTag = SCAN_REVIEW_TAG) {
        ScanStepRow(ScanStep.Review)
        Text(text = if (empty) ScanCopy.EMPTY_TITLE else ScanCopy.REVIEW_TITLE, style = OnScrimTitle)
        Text(text = if (empty) ScanCopy.EMPTY_DETAIL else figures, style = ScanDetailStyle.copy(color = ArOverlay.onScrimMuted))
        if (!empty) Text(text = CaptureChrome.REVIEW_DETAIL, style = ScanDetailStyle.copy(color = ArOverlay.onScrim))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            if (empty) {
                Button(onClick = onKeepScanning, modifier = Modifier.weight(1f)) { Text(ScanCopy.KEEP_SCANNING) }
            } else {
                FilledTonalButton(onClick = onKeepScanning, modifier = Modifier.weight(1f)) {
                    Text(ScanCopy.KEEP_SCANNING)
                }
                Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text(CaptureChrome.BUILD_MODEL) }
            }
        }
    }
}

/**
 * Finish's review for one room of a whole home (#4647): Keep scanning returns to the same room,
 * Next room saves it and waits, paused, for the next one, Save and finish saves it and shows the
 * plan of every room. A room with nothing in it is not saved: Finish shows the rooms already
 * saved, when there are some.
 */
@Composable
@Suppress("LongParameterList") // the review's four answers
internal fun HomeReviewCard(
    roomNumber: Int,
    empty: Boolean,
    figures: String,
    onKeepScanning: () -> Unit,
    onNextRoom: () -> Unit,
    onSaveAndFinish: () -> Unit,
    onFinish: (() -> Unit)?,
) {
    OverlayCard(testTag = SCAN_REVIEW_TAG) {
        Text(
            text = if (empty) ScanCopy.EMPTY_TITLE else ScanScopeCopy.reviewTitle(roomNumber),
            style = OnScrimTitle,
        )
        Text(
            text = when {
                !empty -> figures
                onFinish != null -> ScanScopeCopy.EMPTY_DETAIL
                else -> ScanCopy.EMPTY_DETAIL
            },
            style = OnScrimBody,
        )
        if (!empty) Text(text = ScanScopeCopy.REVIEW_DETAIL, style = OnScrimBody)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            val keepScanning = Modifier.weight(1f).heightIn(min = SceneViewTokens.Layout.touchTarget)
            when {
                !empty -> {
                    FilledTonalButton(onClick = onKeepScanning, modifier = keepScanning) {
                        Text(ScanCopy.KEEP_SCANNING)
                    }
                    Button(onClick = onNextRoom, modifier = keepScanning) { Text(ScanScopeCopy.NEXT_ROOM) }
                }
                onFinish != null -> {
                    FilledTonalButton(onClick = onKeepScanning, modifier = keepScanning) {
                        Text(ScanCopy.KEEP_SCANNING)
                    }
                    Button(onClick = onFinish, modifier = keepScanning) { Text(ScanScopeCopy.FINISH_HOME) }
                }
                else -> Button(onClick = onKeepScanning, modifier = keepScanning) { Text(ScanCopy.KEEP_SCANNING) }
            }
        }
        if (!empty) {
            FilledTonalButton(
                onClick = onSaveAndFinish,
                modifier = Modifier.fillMaxWidth().heightIn(min = SceneViewTokens.Layout.touchTarget),
            ) {
                Text(ScanScopeCopy.SAVE_AND_FINISH)
            }
        }
    }
}

/**
 * Save scan, in progress: the card stays where the review was, with what it is doing — fusing the
 * capture's [points] (#4719), while the view turns round the cloud ([ScanLiveView]'s `saving`).
 */
@Composable
internal fun ScanSavingCard(points: Int = 0) {
    OverlayCard(testTag = SCAN_SAVING_TAG) {
        ScanStepRow(ScanStep.Model)
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(Space.md + Space.xs / 2),
                color = ArOverlay.onScrim,
                strokeWidth = Space.xs / 2,
            )
            Spacer(Modifier.width(Space.sm))
            Text(text = ScanCopy.fusing(points), style = OnScrimTitle)
        }
    }
}

/**
 * A write that failed stays on screen until it is answered: the built scan is still held, Try
 * again writes it again, Discard drops it. Never a toast, never "Saved". It says why ([failure],
 * [scanBytes]) and, when a file can still keep the scan, offers Save as file ([onSaveAsFile]) —
 * a way out when the same failure would only come back (#4815). A part scanned again that did
 * not line up ([refusal]) lines up no better a second time: its main action saves it as a
 * separate scan instead of Try again.
 */
@Composable
internal fun ScanSaveFailedCard(
    onDiscard: () -> Unit,
    onRetry: () -> Unit,
    failure: ScanSaveFailure? = null,
    scanBytes: Long = 0L,
    fileSaving: Boolean = false,
    fileSaveFailed: Boolean = false,
    onSaveAsFile: (() -> Unit)? = null,
    refusal: RescanRefusal? = null,
) {
    OverlayCard(testTag = SCAN_SAVE_FAILED_TAG) {
        Text(
            text = ScanCopy.saveFailedTitle(failure),
            style = SceneViewTokens.Type.title.copy(color = ArOverlay.accentGuidance),
        )
        Text(text = ScanCopy.saveFailedDetail(failure, scanBytes, refusal), style = OnScrimBody)
        if (fileSaveFailed) Text(text = ScanCopy.SAVE_AS_FILE_FAILED, style = OnScrimBody)
        if (failure == ScanSaveFailure.Refused) {
            // "Save as a separate scan" is too long for half the card: one action a row.
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag(SCAN_SAVE_RETRY_TAG)) {
                Text(ScanCopy.saveFailedAction(failure))
            }
            FilledTonalButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) { Text(ScanCopy.DISCARD) }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                FilledTonalButton(onClick = onDiscard, modifier = Modifier.weight(1f)) { Text(ScanCopy.DISCARD) }
                if (failure != ScanSaveFailure.Empty) {
                    Button(onClick = onRetry, modifier = Modifier.weight(1f).testTag(SCAN_SAVE_RETRY_TAG)) {
                        Text(ScanCopy.saveFailedAction(failure))
                    }
                }
            }
        }
        if (onSaveAsFile != null) {
            FilledTonalButton(
                onClick = onSaveAsFile,
                enabled = !fileSaving,
                modifier = Modifier.fillMaxWidth().testTag(SCAN_SAVE_AS_FILE_TAG),
            ) { Text(if (fileSaving) ScanCopy.SAVING_FILE else ScanCopy.SAVE_AS_FILE) }
        }
    }
}

/**
 * Back during a capture: keep scanning, save it, or discard it — never a silent loss. After a
 * failed write, Save scan reads Try again. A capture that caught nothing ([empty]) has nothing
 * to save: Keep scanning or Discard only.
 */
@Composable
internal fun ScanLeaveDialog(
    saveFailed: Boolean,
    empty: Boolean,
    saveFailure: ScanSaveFailure? = null,
    onKeepScanning: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    val discard: @Composable () -> Unit = {
        TextButton(onClick = onDiscard) {
            Text(ScanCopy.DISCARD, color = MaterialTheme.colorScheme.error)
        }
    }
    AlertDialog(
        onDismissRequest = onKeepScanning,
        title = { Text(ScanCopy.LEAVE_TITLE) },
        text = { Text(if (empty) ScanCopy.LEAVE_EMPTY_DETAIL else ScanCopy.LEAVE_DETAIL) },
        confirmButton = {
            if (empty) {
                TextButton(onClick = onKeepScanning) { Text(ScanCopy.KEEP_SCANNING) }
            } else {
                TextButton(onClick = onSave) { Text(if (saveFailed) ScanCopy.saveFailedAction(saveFailure) else ScanCopy.SAVE_SCAN) }
            }
        },
        dismissButton = {
            if (empty) {
                discard()
            } else {
                Row {
                    discard()
                    TextButton(onClick = onKeepScanning) {
                        Text(if (saveFailed) ScanCopy.CANCEL else ScanCopy.KEEP_SCANNING)
                    }
                }
            }
        },
        modifier = Modifier.testTag(SCAN_LEAVE_TAG),
    )
}

/**
 * Discard on the "Couldn't save" card: the scan was never written, so dropping it is final and
 * asks first.
 */
@Composable
internal fun ScanDiscardDialog(onCancel: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(ScanCopy.DISCARD_TITLE) },
        text = { Text(ScanCopy.DISCARD_DETAIL) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(ScanCopy.DISCARD, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(ScanCopy.CANCEL) }
        },
        modifier = Modifier.testTag(SCAN_DISCARD_TAG),
    )
}

/**
 * The scan growing in 3D while it records, full screen (#4628): the camera dimmed under it, so
 * what is not scanned yet still shows, and on top the points in the colours the camera saw —
 * no path, no photo frames, no plane grid. The view follows the phone like a navigation app,
 * heading-up ([ScanFollow]); a drag or a pinch takes it over, and [ScanRecenterPill] gives it
 * back. No marker stands on it: the map card says where you are and which way you face (#4718).
 *
 * While it follows a tracked phone, the view draws from the phone's own eye and lens (the
 * orbit's [ArDebugOrbitCamera.firstPerson], #4716), so each point sits on the camera pixel it was
 * taken from; without a tracked frame, or once a drag has taken it over, it falls back to the
 * heading-up follow view or the free orbit.
 *
 * [livePose] is the phone now, given the trace's last pose: that pose while it scans, the live one
 * while paused. Paused, the trace records nothing, and a view that followed it stayed glued to the
 * screen as the phone turned (#4718); following the live pose, the cloud stays put in the room.
 * The phone's eye is the live pose too, paused or not, so resuming does not move the view.
 *
 * [saving] (#4719): the camera fades out under an opaque veil and the view leaves the phone for a
 * slow dollhouse orbit round the whole cloud ([ScanSavingOrbit]) while the scan is fused and written:
 * it rises out of the room through its open top first, never through a wall nor past a point (#4742).
 * Its ceiling is cut off ([ScanOpenTop]) while the scan is [reviewing], ahead of the save.
 * It sets out from the phone's own eye ([ScanSavingOrbit], [EyeHandoff]): the first frame after the
 * stop tap is the camera's view of the frame before, eased from there onto the orbit.
 *
 * [walls] (#4540), a room's scan only: the walls recognised so far rise in the room, on the
 * camera, and the floor edges still open are dashed where the next wall should be; an arrow points
 * to the next one's [turn], as held by the caller.
 */
@Composable
@Suppress("LongParameterList") // the demo's shared engine, handed down once
internal fun ScanLiveView(
    session: ArDebugSession,
    orbit: ArDebugOrbitCamera,
    follow: ScanFollow,
    coverage: ScanCoverageState,
    media: RerunReplayMedia,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    livePose: (traced: DebugPose?) -> DebugPose? = { it },
    saving: Boolean = false,
    walls: ScanWallsState? = null,
    turn: ScanTurn? = walls?.turn,
    reviewing: Boolean = false,
) {
    // Saving, the view is handed back to the automatic framing, which the orbit then drives.
    LaunchedEffect(saving) { if (saving) orbit.recenter() }
    val veil by animateColorAsState(
        targetValue = if (saving) ArOverlay.cameraDim.copy(alpha = 1f) else ArOverlay.cameraDim,
        animationSpec = tween(SceneViewTokens.Duration.longMillis, easing = SceneViewTokens.Ease.expressive),
        label = "scanVeil",
    )
    val savingOrbit = remember(saving) { if (saving) ScanSavingOrbit() else null }
    // Saving, the room loses its lid (#4742): the orbit rises out through its top, past no ceiling.
    // Cut while the scan is reviewed, so it is ready by the save; the orbit holds on the phone's
    // eye until it is drawn.
    val cutting = reviewing || saving
    var openTop by remember { mutableStateOf<ReplayDenseLayer?>(null) }
    var lidOff by remember { mutableStateOf(false) }
    val savingNow by rememberUpdatedState(saving)
    LaunchedEffect(cutting) {
        openTop = null
        lidOff = false
        if (!cutting) return@LaunchedEffect
        media.liveDense?.invoke()?.let { live ->
            openTop = withContext(Dispatchers.Default) { live.openTop() }
        }
        lidOff = true
    }
    val drawn = remember(media) {
        // Over the camera its surfels are capped small; the save flight leaves the phone's eye.
        media.liveDense?.let { live ->
            media.withLiveDense(overCamera = { !savingNow }) { openTop?.takeIf { savingNow } ?: live() }
        } ?: media
    }
    val observed = remember(coverage, walls) { ScanObserveGate() }
    Box(modifier = Modifier.fillMaxSize().testTag(SCAN_STAGE_TAG)) {
        // Only the camera is dimmed: the cloud is drawn by its own view, above the veil.
        Box(Modifier.fillMaxSize().background(veil))
        ArDebugSceneView(
            session = session,
            orbit = orbit,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            modifier = Modifier.fillMaxSize(),
            replay = drawn,
            transparent = true,
            visible = { it in SCAN_LIVE_GROUPS },
            follow = { frame ->
                val phone = follow.poseFor(livePose(frame.camera))
                // Round the map as it stands, not the merged surfels the live view draws (#4792).
                val points = (drawn.liveDense?.invoke() ?: media.dense)?.whole ?: frame.mapPoints
                savingOrbit?.poseFor(phone, points, orbit, ready = lidOff) ?: phone
            },
            observe = { frame ->
                val pose = livePose(frame.camera)
                val dense = media.liveDense?.invoke()
                val cells = dense?.coveredCells
                // The view hands the same frame to every render until it rebuilds one (#4743).
                if (observed.isNew(frame, pose, cells)) {
                    coverage.observe(frame, pose = pose, snapshotCells = cells, record = media.trace)
                    // The depth cloud tells how much of each wall was seen: a wall is drawn that far.
                    walls?.observe(frame, coverage, pose, dense = dense)
                }
            },
            // The walls stand in the room while it is scanned; saving, the orbit shows the cloud.
            walls = walls,
            drawWalls = !saving,
            // The cloud moves with the phone's eye, which ARCore updates at the camera's 30 Hz: a
            // second view drawn at the display's 60 Hz redrew the same picture every other vsync,
            // one GPU pass and one swap too many on a mid-range phone (#4743). Saving, the orbit is
            // the view's own motion and gets the display's full rate back.
            maxFps = if (saving) null else SCAN_LIVE_FPS,
        )
        if (walls != null && !saving) ScanTurnArrow(turn)
    }
}

/** Shown once a drag or a pinch has left the follow view: back to following the phone. */
@Composable
internal fun ScanRecenterPill(onRecenter: () -> Unit) {
    GlassActionPill(
        icon = Icons.Rounded.MyLocation,
        label = stringResource(R.string.demo_ar_rerun_recenter),
        contentDescription = stringResource(R.string.demo_ar_rerun_recenter_description),
        onClick = onRecenter,
        modifier = Modifier.testTag(SCAN_RECENTER_TAG),
    )
}

/**
 * Before a capture: the shutter that starts one, once ARCore has found the space. A capture in
 * progress has [ScanControls] instead.
 */
@Composable
internal fun ScanShutter(startEnabled: Boolean, onStart: () -> Unit) {
    RecordShutter(isRecording = false, startEnabled = startEnabled, onStart = onStart, onStop = {})
}

/** What the live view draws: the points, in the colours the camera saw. */
private val SCAN_LIVE_GROUPS = setOf(DebugGroup.Points)

/** ARCore's camera rate, which paces the phone's eye the live view draws from (#4743). */
private const val SCAN_LIVE_FPS = 30

/** A notch above the capture card's 10 dp dot: this one is read from a metre away. */
private val ScanDotSize = Space.md - Space.xs / 2

internal const val SCAN_HUD_TAG = "ar_rerun_scan_hud"
internal const val SCAN_ROOM_GAUGE_TAG = "ar_rerun_scan_room_gauge"
internal const val SCAN_TURN_TAG = "ar_rerun_scan_turn"
internal const val SCAN_STAGE_TAG = "ar_rerun_scan_stage"
internal const val SCAN_RECENTER_TAG = "ar_rerun_scan_recenter"
internal const val SCAN_POINTS_TAG = "ar_rerun_scan_points"
internal const val SCAN_CONTROLS_TAG = "ar_rerun_scan_controls"
internal const val SCAN_PAUSE_TAG = "ar_rerun_scan_pause"
internal const val SCAN_FINISH_TAG = "ar_rerun_scan_finish"
internal const val SCAN_HINT_TAG = "ar_rerun_scan_hint"
internal const val SCAN_REVIEW_TAG = "ar_rerun_scan_review"
internal const val SCAN_SAVING_TAG = "ar_rerun_scan_saving"
internal const val SCAN_SAVE_FAILED_TAG = "ar_rerun_scan_save_failed"
internal const val SCAN_SAVE_AS_FILE_TAG = "ar_rerun_scan_save_as_file"
internal const val SCAN_SAVE_RETRY_TAG = "ar_rerun_scan_save_retry"
internal const val SCAN_LEAVE_TAG = "ar_rerun_scan_leave"
internal const val SCAN_DISCARD_TAG = "ar_rerun_scan_discard"
