package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import io.github.sceneview.demo.demos.internal.ScanAreaCopy
import io.github.sceneview.demo.demos.internal.ScanInstruction
import io.github.sceneview.demo.demos.internal.ScanLens

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.SideEffect
import io.github.sceneview.demo.demos.internal.scanPathLength
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.motionDuration
import io.github.sceneview.haptic.rememberHapticFeedback
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.Icon
import androidx.compose.ui.res.pluralStringResource
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
import io.github.sceneview.demo.demos.internal.RescanZone
import io.github.sceneview.demo.demos.internal.ScanReviewCopy
import io.github.sceneview.demo.demos.internal.ScanReviewResult
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.liveRegion
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader

/** Camera content fills the window; only Back, the figures and Done sit inside the safe insets. */
@Composable
internal fun ScanCaptureLayout(
    onBack: () -> Unit,
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().testTag(SCAN_CAPTURE_TAG)) {
        content()
        Row(
            modifier = Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .fillMaxWidth().padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.scan_capture_back))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { top() }
            Spacer(Modifier.width(SceneViewTokens.Layout.touchTarget))
        }
        Box(
            modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(Space.md),
        ) { bottom() }
    }
}

/** The same quiet duration and floor-area pill in every capture scope, including while saving. */
@Composable
internal fun ScanHud(seconds: Float, floorSquareMetres: Float) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(SCAN_HUD_TAG),
    ) {
        Text(
            text = stringResource(
                R.string.scan_capture_figures,
                ArDebugFormat.clock(seconds),
                ScanAreaCopy.area(floorSquareMetres.coerceAtLeast(0f)),
            ),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
        )
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
 * The guide's arrow (#4540, QA-24): a glass card at the screen's edge on the target's side — in
 * the middle when it is ahead or behind — with an arrow in `accent-guidance`, the colour of the
 * floor ribbon and the dashed gap, turned to the target's heading as the phone turns, and the
 * distance to it under it while it is not reached. It goes once the phone lines up with the
 * target, with one tick ([ScanWallsState.alignedTicks], at most once a second); the ribbon on the
 * floor stays. Said as the coaching line says it. Under reduced motion it appears and goes without a fade.
 */
@Composable
internal fun BoxScope.ScanGuideArrow(walls: ScanWallsState) {
    val hint = walls.hint
    val shown = walls.arrowShown && hint != null
    // Kept through the fade-out, so the arrow leaves from where it was.
    var last by remember { mutableStateOf(hint) }
    if (shown) last = hint
    val haptic = rememberHapticFeedback()
    // One tick per line-up felt here: none for those counted while the arrow was off screen.
    val ticks = walls.alignedTicks
    var felt by remember { mutableIntStateOf(ticks) }
    LaunchedEffect(ticks) {
        if (ticks > felt) haptic.selection()
        felt = ticks
    }
    // Left and right are the room's, not the reading direction's: the same side in RTL.
    val alignment = when (last?.turn) {
        ScanTurn.Left -> AbsoluteAlignment.CenterLeft
        ScanTurn.Right -> AbsoluteAlignment.CenterRight
        else -> Alignment.Center
    }
    val fade = motionDuration(SceneViewTokens.Duration.mediumMillis)
    val locale = LocalConfiguration.current.locales[0]
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(fade)),
        exit = fadeOut(tween(fade)),
        modifier = Modifier.align(alignment).padding(Space.md).testTag(SCAN_TURN_TAG),
    ) {
        val said = last
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(min = SceneViewTokens.Layout.touchTarget + Space.md)
                .background(LocalStageChrome.current.card, RoundedCornerShape(SceneViewTokens.Radius.lg))
                .padding(Space.sm)
                // One description, not the arrow's line then the distance again.
                .clearAndSetSemantics {
                    contentDescription = said?.let { ScanCopy.guideHint(it, locale) }.orEmpty()
                },
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowUpward,
                contentDescription = null,
                tint = ArOverlay.accentGuidance,
                // Read in the layer: the heading moves every frame, the card does not recompose.
                modifier = Modifier
                    .size(SceneViewTokens.Layout.touchTarget - Space.sm)
                    .graphicsLayer { rotationZ = walls.headingDegrees },
            )
            said?.metres?.let { metres ->
                Text(
                    text = scanPathLength(metres, locale),
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalStageChrome.current.onCard,
                    maxLines = 1,
                )
            }
        }
    }
}

private const val GAUGE_SEGMENTS = 10

/** One optional instruction and one action, in the same place from the first frame to saving. */
@Composable
internal fun ScanControls(
    instruction: ScanInstruction?,
    doneEnabled: Boolean,
    onFinish: () -> Unit,
    onNextRoom: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.widthIn(max = ArOverlay.maxWidth).fillMaxWidth().testTag(SCAN_CONTROLS_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (instruction != null) ScanInstructionLine(instruction)
        // A whole home is scanned room by room (#4677): Next room saves this one and goes on.
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
            if (onNextRoom != null) {
                FilledTonalButton(
                    onClick = onNextRoom,
                    enabled = doneEnabled,
                    modifier = Modifier.heightIn(min = SceneViewTokens.Layout.touchTarget).testTag(SCAN_NEXT_ROOM_TAG),
                ) { Text(stringResource(R.string.scan_capture_next_room)) }
            }
            Button(
                onClick = onFinish,
                enabled = doneEnabled,
                modifier = Modifier.heightIn(min = SceneViewTokens.Layout.touchTarget).testTag(SCAN_FINISH_TAG),
            ) { Text(stringResource(R.string.scan_capture_done)) }
        }
    }
}

/**
 * Finish's review: the capture frozen, nothing built yet. Keep scanning returns to the same
 * capture; Save scan builds and writes it. A capture with nothing in it offers only Keep
 * scanning — there is nothing to save.
 *
 * Review before you leave: a room's capture is read once for the parts it missed ([review],
 * `null` when there is no dense map to read; [checking] while it is read). With parts to fix the
 * card says how many, names the one the 3D view is on ([focus]), and offers Fix now — the same
 * capture, that part framed in amber ([onFixNow]) — or Build anyway. A finger on the card holds
 * the view on the part being read ([onHold]) so Fix now aims where the user looks; Next part
 * ([onNext]) moves on by hand, as a screen reader's user or reduced motion does.
 */
@Composable
@Suppress("LongParameterList") // the review's answers, and what it found
internal fun ScanReviewCard(
    empty: Boolean,
    figures: String,
    onKeepScanning: () -> Unit,
    onSave: () -> Unit,
    review: ScanReviewResult? = null,
    checking: Boolean = false,
    focus: Int = 0,
    onFixNow: ((RescanZone) -> Unit)? = null,
    onHold: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    val zones = review?.zones.orEmpty().takeUnless { empty || checking }.orEmpty()
    val focusIndex = focus.coerceIn(0, (zones.size - 1).coerceAtLeast(0))
    val focused = zones.getOrNull(focusIndex)
    val holdOnTouch = Modifier.pointerInput(onHold) {
        // The initial pass: the view stops on the part before any button sees the tap.
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onHold()
        }
    }
    OverlayCard(testTag = SCAN_REVIEW_TAG, modifier = holdOnTouch) {
        ScanStepRow(ScanStep.Review)
        val title = when {
            empty -> ScanCopy.EMPTY_TITLE
            zones.isNotEmpty() -> ScanReviewCopy.title(zones.size)
            // Set aside is not fixed: the card still says there are parts, and why none is offered.
            review?.clear == true && !checking && review.setAside > 0 -> ScanReviewCopy.title(review.setAside)
            review?.clear == true && !checking -> ScanReviewCopy.CLEAR_TITLE
            else -> ScanCopy.REVIEW_TITLE
        }
        Text(text = title, style = OnScrimTitle, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(text = if (empty) ScanCopy.EMPTY_DETAIL else figures, style = ScanDetailStyle.copy(color = ArOverlay.onScrimMuted))
        val detail = when {
            empty -> null
            checking -> ScanReviewCopy.CHECKING
            focused != null -> ScanReviewCopy.focus(focused, focusIndex, zones.size)
            review?.clear == true && review.setAside > 0 -> ScanReviewCopy.setAside(review.setAside)
            review?.clear == true -> ScanReviewCopy.CLEAR_DETAIL
            else -> CaptureChrome.REVIEW_DETAIL
        }
        if (detail != null) {
            Text(
                text = detail,
                style = ScanDetailStyle.copy(color = ArOverlay.onScrim),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (focused != null && zones.size > 1) {
            TextButton(onClick = onNext, modifier = Modifier.testTag(SCAN_NEXT_PART_TAG)) { Text(ScanReviewCopy.NEXT_PART) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            if (empty) {
                Button(onClick = onKeepScanning, modifier = Modifier.weight(1f)) { Text(ScanCopy.KEEP_SCANNING) }
            } else if (focused != null && onFixNow != null) {
                FilledTonalButton(onClick = onSave, modifier = Modifier.weight(1f).testTag(SCAN_BUILD_ANYWAY_TAG)) {
                    Text(ScanReviewCopy.BUILD_ANYWAY)
                }
                val label = ScanReviewCopy.fixLabel(focused, focusIndex, zones.size)
                Button(
                    onClick = { onFixNow(focused) },
                    modifier = Modifier.weight(1f).testTag(SCAN_FIX_NOW_TAG).semantics { contentDescription = label },
                ) {
                    Text(ScanReviewCopy.FIX_NOW)
                }
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
 * Captured colour points through the phone's exact lens, in both Room and Object.
 * Saving keeps this view mounted with the last dense snapshot and the live camera underneath.
 * Only the point layer is translucent; no surface is painted behind it. Coverage is still
 * observed for the m² figure and saved trace, without drawing its map or inferred geometry.
 */
@Composable
internal fun ScanLiveView(
    session: ArDebugSession,
    orbit: ArDebugOrbitCamera,
    lens: ScanLens,
    coverage: ScanCoverageState,
    media: RerunReplayMedia,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
) {
    val drawn = remember(media) {
        media.liveDense?.let { live -> media.withLiveDense(overCamera = { true }, liveDense = live) } ?: media
    }
    val observed = remember(coverage) { ScanObserveGate() }
    ArDebugSceneView(
        session = session,
        orbit = orbit,
        captureLens = lens,
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = CAPTURE_POINTS_ALPHA }.testTag(SCAN_STAGE_TAG),
        replay = drawn,
        transparent = true,
        measuresRoom = false,
        visible = { lens.ready && it in SCAN_LIVE_GROUPS },
        observe = { frame ->
            val cells = media.liveDense?.invoke()?.coveredCells
            if (observed.isNew(frame, frame.camera, cells)) {
                coverage.observe(frame, snapshotCells = cells, record = media.trace)
            }
        },
        maxFps = SCAN_LIVE_FPS,
    )
}

/** Enough of the real camera texture remains visible between and beneath the colour points. */
internal const val CAPTURE_POINTS_ALPHA = 0.8f
internal const val SCAN_CAPTURE_TAG = "scan_capture"

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
internal const val SCAN_NEXT_ROOM_TAG = "ar_rerun_scan_next_room"
internal const val SCAN_HINT_TAG = "ar_rerun_scan_hint"
internal const val SCAN_REVIEW_TAG = "ar_rerun_scan_review"
internal const val SCAN_FIX_NOW_TAG = "ar_rerun_scan_fix_now"
internal const val SCAN_BUILD_ANYWAY_TAG = "ar_rerun_scan_build_anyway"
internal const val SCAN_NEXT_PART_TAG = "ar_rerun_scan_next_part"
internal const val SCAN_SAVING_TAG = "ar_rerun_scan_saving"
internal const val SCAN_SAVE_FAILED_TAG = "ar_rerun_scan_save_failed"
internal const val SCAN_SAVE_AS_FILE_TAG = "ar_rerun_scan_save_as_file"
internal const val SCAN_SAVE_RETRY_TAG = "ar_rerun_scan_save_retry"
internal const val SCAN_LEAVE_TAG = "ar_rerun_scan_leave"
internal const val SCAN_DISCARD_TAG = "ar_rerun_scan_discard"
