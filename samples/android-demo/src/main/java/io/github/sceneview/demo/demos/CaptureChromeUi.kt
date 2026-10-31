package io.github.sceneview.demo.demos

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.demos.internal.CaptureChrome
import io.github.sceneview.demo.demos.internal.CaptureChromeState
import io.github.sceneview.demo.demos.internal.CaptureCue
import io.github.sceneview.demo.demos.internal.ScanHaptic
import io.github.sceneview.demo.demos.internal.ScanStep
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.haptic.rememberHapticFeedback
import kotlinx.coroutines.delay

/*
 * The 3D Scan capture's chrome legible in sunlight: every line of it sits on a solid fill, so the
 * camera behind it no longer matters (CaptureChromeContrastTest measures it over a sunlit white
 * wall), in 17 sp at the least, each message with its pictogram.
 */

/** A line the chrome says: the state, a blocker's cause, the current step. */
internal val ScanLineStyle: TextStyle = SceneViewTokens.Type.card

/** A line under it, as large but lighter: the way out of a blocker, the next step, the counts. */
internal val ScanDetailStyle: TextStyle = SceneViewTokens.Type.card.copy(fontWeight = FontWeight.Normal)

/** Behind a blocker: the most solid fill the overlays have, for the most critical line. */
internal val ScanBannerFill: Color = ArOverlay.scrimLight

/** Behind the step row, the next step and the shutter. */
internal val ScanControlFill: Color = ArOverlay.scrimDark

private val PictogramSize = 24.dp
private val ChevronSize = 20.dp

/**
 * Scan › Review › Model: where the capture is, the steps done ticked, the current one bold. Said
 * aloud as `Step 2 of 3: Review`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScanStepRow(step: ScanStep, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(SceneViewTokens.Radius.lg),
        color = ScanControlFill,
        contentColor = ArOverlay.onScrim,
        modifier = modifier
            .testTag(SCAN_STEPS_TAG)
            .semantics(mergeDescendants = true) { stateDescription = CaptureChrome.stepDescription(step) },
    ) {
        // A row that wraps, step by step, rather than clip "Model" on a narrow screen at a large font.
        FlowRow(
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Space.md, vertical = Space.xs),
        ) {
            ScanStep.entries.forEach { each ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (each.ordinal > 0) {
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = ArOverlay.onScrimMuted,
                            modifier = Modifier.padding(horizontal = Space.xs).size(ChevronSize),
                        )
                    }
                    val current = each == step
                    if (each.ordinal < step.ordinal) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = ArOverlay.accentProgress,
                            modifier = Modifier.size(ChevronSize),
                        )
                        Spacer(Modifier.width(Space.xs))
                    }
                    Text(
                        text = each.label,
                        style = if (current) ScanLineStyle else ScanDetailStyle,
                        color = if (current) ArOverlay.onScrim else ArOverlay.onScrimMuted,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * The capture's one message: a blocker as a banner, its cause then its way out, announced as it
 * shows; otherwise `Next: …`, where to go and why. Never both.
 */
@Composable
internal fun ScanCueLine(cue: CaptureCue, modifier: Modifier = Modifier) {
    when (cue) {
        is CaptureCue.Blocked -> Surface(
            shape = RoundedCornerShape(SceneViewTokens.Radius.md),
            color = ScanBannerFill,
            contentColor = ArOverlay.onScrim,
            modifier = modifier
                .widthIn(max = ArOverlay.maxWidth)
                .fillMaxWidth()
                .testTag(SCAN_BLOCKER_TAG)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = ArOverlay.accentGuidance,
                    modifier = Modifier.size(PictogramSize),
                )
                Spacer(Modifier.width(Space.sm))
                Column {
                    Text(text = cue.blocker.cause, style = ScanLineStyle, color = ArOverlay.onScrim)
                    Text(text = cue.blocker.wayOut, style = ScanDetailStyle, color = ArOverlay.onScrim)
                }
            }
        }
        is CaptureCue.Next -> Surface(
            shape = RoundedCornerShape(SceneViewTokens.Radius.lg),
            color = ScanControlFill,
            contentColor = ArOverlay.onScrim,
            modifier = modifier
                .widthIn(max = ArOverlay.maxWidth)
                .heightIn(min = SceneViewTokens.Layout.touchTarget)
                .testTag(SCAN_HINT_TAG)
                .semantics(mergeDescendants = true) {},
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = ArOverlay.onScrim,
                    modifier = Modifier.size(PictogramSize),
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("${CaptureChrome.NEXT}: ") }
                        append(cue.text)
                    },
                    style = ScanDetailStyle,
                    color = ArOverlay.onScrim,
                )
            }
        }
    }
}

/**
 * A haptic on the changes that matter while the phone is held at arm's length: a blocker showing
 * (warning), clearing (light), the room completing (success). One a second at most, the room's
 * completion included: one due sooner waits out the second rather than being lost, and a newer
 * state cancels the wait and is weighed against the last one felt, so a cause flickering at its
 * threshold does not buzz. Never in QA mode.
 */
@Composable
internal fun CaptureChromeHaptics(cue: CaptureCue?, complete: Boolean) {
    val haptic = rememberHapticFeedback()
    val state = CaptureChromeState.of(cue, complete)
    val memory = remember { HapticMemory(state) }
    LaunchedEffect(state) {
        val felt = memory.felt
        if (CaptureChrome.haptic(felt, state) != null && !DemoSettings.qaMode) {
            delay(CaptureChrome.hapticWaitMs(SystemClock.uptimeMillis(), memory.playedAt))
        }
        val now = SystemClock.uptimeMillis()
        val play = CaptureChrome.hapticToPlay(felt, state, now, memory.playedAt, DemoSettings.qaMode)
        memory.felt = state
        if (play == null) return@LaunchedEffect
        memory.playedAt = now
        when (play) {
            ScanHaptic.Blocked -> haptic.warning()
            ScanHaptic.Cleared -> haptic.light()
            ScanHaptic.Complete -> haptic.success()
        }
    }
}

private class HapticMemory(var felt: CaptureChromeState, var playedAt: Long = Long.MIN_VALUE / 2)

internal const val SCAN_STEPS_TAG = "scan_steps"
internal const val SCAN_BLOCKER_TAG = "scan_blocker"
