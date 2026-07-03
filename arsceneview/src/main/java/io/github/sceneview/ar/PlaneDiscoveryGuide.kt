/*
 * AR plane-discovery onboarding guide (#2241).
 *
 * UX state machine ported from Google ARCore Elements' `PlaneDiscoveryGuide.cs`
 * (arcore-unity-sdk, Apache 2.0, Copyright 2018 Google LLC), re-expressed as a
 * declarative Jetpack Compose overlay.
 */
package io.github.sceneview.ar

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.TrackingFailureReason
import kotlinx.coroutines.delay

/**
 * Timing configuration for [PlaneDiscoveryGuide].
 *
 * Defaults are the user-tested values from Google ARCore Elements'
 * `PlaneDiscoveryGuide` (`DisplayGuideDelay = 3 s`, `OfferDetailedInstructionsDelay = 8 s`,
 * `_hideGuideDelay = 0.75 s`, `_animationFadeDuration = 0.15 s`) — see #2241.
 *
 * @param handHintAfterMs Silent period after camera tracking starts before the animated
 *   hand hint + message appear. Default 3 s — most users find a plane before this fires.
 * @param helpAfterMs Elapsed time (from tracking start, not from the hand hint) after which
 *   a "Need help?" affordance is offered next to the hint message. Default 8 s.
 * @param fadeOutMs Fade-out duration once the first plane reaches `TrackingState.TRACKING`.
 *   Default 750 ms.
 * @param fadeInMs Fade-in duration for the hand hint. Default 150 ms.
 */
@Immutable
data class PlaneDiscoveryGuideDurations(
    val handHintAfterMs: Long = 3_000L,
    val helpAfterMs: Long = 8_000L,
    val fadeOutMs: Long = 750L,
    val fadeInMs: Long = 150L,
)

/**
 * The observable phases of the [PlaneDiscoveryGuide] onboarding state machine.
 *
 * Phase is a pure function of `(cameraReady, isTracking, anyPlaneTracked,
 * trackingFailureReason, elapsed time)` — see [PlaneDiscoveryGuideState.update].
 */
enum class PlaneDiscoveryPhase {
    /** No camera frame yet, or camera not tracking without an explicit failure reason. */
    WAITING,

    /** Camera tracking, no plane yet, still inside the initial silent window (0–3 s). */
    SILENT,

    /** No plane after [PlaneDiscoveryGuideDurations.handHintAfterMs] — hand animation + message. */
    HAND_HINT,

    /** Still no plane after [PlaneDiscoveryGuideDurations.helpAfterMs] — "Need help?" offered. */
    HELP_OFFERED,

    /** Tracking failure with a reason — contextual message shown, onboarding timers reset. */
    LOST,

    /** First plane found while the guide was visible — fading out over [PlaneDiscoveryGuideDurations.fadeOutMs]. */
    FADING_OUT,

    /**
     * Latched terminal phase: a plane was found. The guide never re-onboards within one
     * session (recreate the state — e.g. via `key(...)` — to restart it). [LOST] messages
     * still surface after [DONE] when tracking degrades, matching the ARCore Elements
     * behavior.
     */
    DONE,
}

private const val UNSET = Long.MIN_VALUE

/**
 * Hoisted, headless-testable state machine behind [PlaneDiscoveryGuide].
 *
 * Owns no ARCore object and reads no clock by itself: every [update] takes the caller's
 * `nowMillis`, so unit tests can drive transitions deterministically with a fake clock.
 * Inside a composition, prefer [rememberPlaneDiscoveryGuideState].
 *
 * Transition rules (ported from ARCore Elements `PlaneDiscoveryGuide.cs`):
 *  - A tracking failure with a reason always wins → [PlaneDiscoveryPhase.LOST], and the
 *    onboarding timer resets (recovery restarts the silent window from zero).
 *  - Once [isDone] latches, only [PlaneDiscoveryPhase.LOST] and
 *    [PlaneDiscoveryPhase.DONE] can be produced.
 *  - A plane found while nothing is visible (WAITING/SILENT) latches [isDone]
 *    immediately — users who find a plane fast never see the guide at all.
 */
@Stable
class PlaneDiscoveryGuideState(
    val durations: PlaneDiscoveryGuideDurations = PlaneDiscoveryGuideDurations(),
) {
    /** Current phase. Snapshot-state backed — reading it in composition subscribes to changes. */
    var phase: PlaneDiscoveryPhase by mutableStateOf(PlaneDiscoveryPhase.WAITING)
        private set

    /** Latched once a plane has been found. See [PlaneDiscoveryPhase.DONE]. */
    var isDone: Boolean = false
        private set

    private var scanStartMillis = UNSET
    private var fadeOutStartMillis = UNSET

    /**
     * Recomputes [phase] from the current AR signals at time [nowMillis].
     *
     * @param cameraReady true once ARCore has delivered its first camera frame.
     * @param isTracking `frame.camera.trackingState == TrackingState.TRACKING`.
     * @param anyPlaneTracked ≥1 ARCore `Plane` in `TrackingState.TRACKING`.
     * @param trackingFailureReason latest failure reason, or null while tracking is healthy.
     * @param nowMillis any monotonic millisecond clock (e.g. `SystemClock.uptimeMillis()`);
     *   only differences between successive calls matter.
     */
    fun update(
        cameraReady: Boolean,
        isTracking: Boolean,
        anyPlaneTracked: Boolean,
        trackingFailureReason: TrackingFailureReason?,
        nowMillis: Long,
    ): PlaneDiscoveryPhase {
        phase = derivePhase(
            cameraReady, isTracking, anyPlaneTracked, trackingFailureReason, nowMillis
        )
        return phase
    }

    /**
     * Milliseconds until the next purely time-driven transition, or `null` when the current
     * phase only changes on an input change. Lets the host sleep exactly until the next
     * deadline instead of polling every frame.
     */
    fun nextTransitionDelayMillis(nowMillis: Long): Long? = when (phase) {
        PlaneDiscoveryPhase.SILENT ->
            (scanStartMillis + durations.handHintAfterMs - nowMillis).coerceAtLeast(MIN_DELAY_MS)
        PlaneDiscoveryPhase.HAND_HINT ->
            (scanStartMillis + durations.helpAfterMs - nowMillis).coerceAtLeast(MIN_DELAY_MS)
        PlaneDiscoveryPhase.FADING_OUT ->
            (fadeOutStartMillis + durations.fadeOutMs - nowMillis).coerceAtLeast(MIN_DELAY_MS)
        else -> null
    }

    private fun derivePhase(
        cameraReady: Boolean,
        isTracking: Boolean,
        anyPlaneTracked: Boolean,
        trackingFailureReason: TrackingFailureReason?,
        nowMillis: Long,
    ): PlaneDiscoveryPhase {
        // 1. A tracking failure with an actionable reason always wins — even after DONE
        //    (ARCore Elements keeps surfacing lost-tracking copy for the whole session).
        //    The onboarding timer resets so a recovery restarts the silent window.
        if (cameraReady && !isTracking &&
            trackingFailureReason != null && trackingFailureReason != TrackingFailureReason.NONE
        ) {
            scanStartMillis = UNSET
            fadeOutStartMillis = UNSET
            return PlaneDiscoveryPhase.LOST
        }

        // 2. DONE is latched — never re-onboard within one session.
        if (isDone) return PlaneDiscoveryPhase.DONE

        // 3. Camera warming up, or paused without an explicit failure — stay quiet.
        if (!cameraReady || !isTracking) {
            scanStartMillis = UNSET
            fadeOutStartMillis = UNSET
            return PlaneDiscoveryPhase.WAITING
        }

        // 4. First plane found → fade out whatever is visible, then latch DONE.
        if (anyPlaneTracked) {
            scanStartMillis = UNSET
            val wasVisible = phase == PlaneDiscoveryPhase.HAND_HINT ||
                    phase == PlaneDiscoveryPhase.HELP_OFFERED ||
                    phase == PlaneDiscoveryPhase.FADING_OUT
            if (!wasVisible) {
                // Nothing on screen (WAITING/SILENT) — no fade needed.
                isDone = true
                return PlaneDiscoveryPhase.DONE
            }
            if (fadeOutStartMillis == UNSET) fadeOutStartMillis = nowMillis
            if (nowMillis - fadeOutStartMillis >= durations.fadeOutMs) {
                isDone = true
                fadeOutStartMillis = UNSET
                return PlaneDiscoveryPhase.DONE
            }
            return PlaneDiscoveryPhase.FADING_OUT
        }

        // 5. Tracking with no plane yet — the timed onboarding ramp.
        //    (A plane lost mid-fade lands here too: the timer restarts from zero, matching
        //    the ARCore Elements `_notDetectedPlaneElapsed = 0` reset while planes existed.)
        fadeOutStartMillis = UNSET
        if (scanStartMillis == UNSET) scanStartMillis = nowMillis
        val elapsed = nowMillis - scanStartMillis
        return when {
            elapsed >= durations.helpAfterMs -> PlaneDiscoveryPhase.HELP_OFFERED
            elapsed >= durations.handHintAfterMs -> PlaneDiscoveryPhase.HAND_HINT
            else -> PlaneDiscoveryPhase.SILENT
        }
    }

    private companion object {
        /**
         * Floor for [nextTransitionDelayMillis] — guards against a zero-delay spin if the
         * host's clock lands exactly on (or a hair before) a deadline.
         */
        const val MIN_DELAY_MS = 16L
    }
}

/**
 * Remembers a [PlaneDiscoveryGuideState] for [PlaneDiscoveryGuide], keyed on [durations].
 */
@Composable
fun rememberPlaneDiscoveryGuideState(
    durations: PlaneDiscoveryGuideDurations = PlaneDiscoveryGuideDurations(),
): PlaneDiscoveryGuideState = remember(durations) { PlaneDiscoveryGuideState(durations) }

/**
 * Onboarding overlay that guides a user to find an AR plane, then gets out of the way (#2241).
 *
 * A Compose port of the Google ARCore Elements `PlaneDiscoveryGuide` UX state machine:
 *  - **0–3 s** after camera tracking starts, no plane → silent (let the user just look).
 *  - **3 s** → animated hand sweep ([PlaneDiscoveryHandHint]) + "Move your phone to find
 *    a surface" message pill (150 ms fade-in).
 *  - **8 s** → a "Need help?" affordance. Tapping it invokes [onHelp], or — when [onHelp]
 *    is null — expands the built-in tip card ([PlaneDiscoveryHelpCard]).
 *  - **first plane `TRACKING`** → 750 ms fade-out, then the guide latches
 *    [PlaneDiscoveryPhase.DONE] and stops composing children (it never re-onboards within
 *    one session).
 *  - **tracking lost with a reason** → one of the 5 contextual messages (the existing
 *    user-tested `sceneview_*_message` strings via [TrackingFailureReason.getDescription]),
 *    and the onboarding timers reset.
 *
 * Pure UI: it owns no ARCore session and runs no hit test — it consumes three booleans and
 * a failure reason the host already produces. Render it as a **sibling of [ARSceneView]
 * inside the same `Box`**, above the viewport:
 *
 * ```kotlin
 * var cameraReady by remember { mutableStateOf(false) }
 * var isTracking by remember { mutableStateOf(false) }
 * var anyPlaneTracked by remember { mutableStateOf(false) }
 * var failure by remember { mutableStateOf<TrackingFailureReason?>(null) }
 *
 * Box(Modifier.fillMaxSize()) {
 *     ARSceneView(
 *         onSessionUpdated = { session, frame ->
 *             cameraReady = true
 *             isTracking = frame.camera.trackingState == TrackingState.TRACKING
 *             if (!anyPlaneTracked) {
 *                 anyPlaneTracked = session.getAllTrackables(Plane::class.java)
 *                     .any { it.trackingState == TrackingState.TRACKING }
 *             }
 *         },
 *         onTrackingFailureChanged = { failure = it },
 *     ) { /* scene content */ }
 *
 *     PlaneDiscoveryGuide(
 *         cameraReady = cameraReady,
 *         isTracking = isTracking,
 *         anyPlaneTracked = anyPlaneTracked,
 *         trackingFailureReason = failure,
 *     )
 * }
 * ```
 *
 * For full visual control, hoist the state machine ([rememberPlaneDiscoveryGuideState] +
 * [PlaneDiscoveryGuideState.update]) and render [PlaneDiscoveryGuideOverlay] — or your own
 * visuals — from [PlaneDiscoveryGuideState.phase].
 *
 * @param cameraReady true once ARCore has delivered its first camera frame (set it in
 *   `onSessionUpdated`). Drives "wait vs guide".
 * @param isTracking `frame.camera.trackingState == TrackingState.TRACKING`.
 * @param anyPlaneTracked ≥1 ARCore `Plane` in `TrackingState.TRACKING` — the fade-out trigger.
 * @param trackingFailureReason latest ARCore failure reason, or null. Wire it from
 *   `onTrackingFailureChanged`.
 * @param modifier applied to the guide's own overlay elements (not to a root container —
 *   the guide composes directly into the parent `Box`).
 * @param state hoisted timing config + derived phase; see [rememberPlaneDiscoveryGuideState].
 * @param hintMessage overrides the "Move your phone to find a surface" copy. Null = default.
 * @param helpLabel overrides the "Need help?" affordance label. Null = default.
 * @param onHelp custom "Need help?" tap handler (e.g. open your own docs/sheet). Null =
 *   expand the built-in [PlaneDiscoveryHelpCard].
 * @param handHint slot for the animated hint visual. Default = [PlaneDiscoveryHandHint].
 * @param helpCard slot for the expanded help content; receives the dismiss callback.
 *   Default = [PlaneDiscoveryHelpCard]. Only used while [onHelp] is null.
 */
@Composable
fun BoxScope.PlaneDiscoveryGuide(
    cameraReady: Boolean,
    isTracking: Boolean,
    anyPlaneTracked: Boolean,
    trackingFailureReason: TrackingFailureReason? = null,
    modifier: Modifier = Modifier,
    state: PlaneDiscoveryGuideState = rememberPlaneDiscoveryGuideState(),
    hintMessage: String? = null,
    helpLabel: String? = null,
    onHelp: (() -> Unit)? = null,
    handHint: @Composable () -> Unit = { PlaneDiscoveryHandHint() },
    helpCard: @Composable (onDismiss: () -> Unit) -> Unit = {
        PlaneDiscoveryHelpCard(onDismiss = it)
    },
) {
    // Event-driven clock: recompute on any input change, then sleep exactly until the next
    // time-driven deadline (hand hint at 3 s, help at 8 s, fade-out end). No per-frame poll.
    LaunchedEffect(state, cameraReady, isTracking, anyPlaneTracked, trackingFailureReason) {
        state.update(
            cameraReady, isTracking, anyPlaneTracked, trackingFailureReason,
            SystemClock.uptimeMillis()
        )
        while (true) {
            val delayMs = state.nextTransitionDelayMillis(SystemClock.uptimeMillis()) ?: break
            delay(delayMs)
            state.update(
                cameraReady, isTracking, anyPlaneTracked, trackingFailureReason,
                SystemClock.uptimeMillis()
            )
        }
    }

    PlaneDiscoveryGuideOverlay(
        phase = state.phase,
        trackingFailureReason = trackingFailureReason,
        modifier = modifier,
        durations = state.durations,
        hintMessage = hintMessage,
        helpLabel = helpLabel,
        onHelp = onHelp,
        handHint = handHint,
        helpCard = helpCard,
    )
}

/**
 * Stateless renderer for a given [PlaneDiscoveryPhase] — the visual half of
 * [PlaneDiscoveryGuide].
 *
 * Use it directly when you drive [PlaneDiscoveryGuideState] (or your own state machine)
 * yourself, in `@Preview`s, or in UI tests: every phase is renderable deterministically
 * without an ARCore session.
 *
 * Parameters mirror [PlaneDiscoveryGuide]; see there for the semantics.
 */
@Composable
fun BoxScope.PlaneDiscoveryGuideOverlay(
    phase: PlaneDiscoveryPhase,
    trackingFailureReason: TrackingFailureReason? = null,
    modifier: Modifier = Modifier,
    durations: PlaneDiscoveryGuideDurations = PlaneDiscoveryGuideDurations(),
    hintMessage: String? = null,
    helpLabel: String? = null,
    onHelp: (() -> Unit)? = null,
    handHint: @Composable () -> Unit = { PlaneDiscoveryHandHint() },
    helpCard: @Composable (onDismiss: () -> Unit) -> Unit = {
        PlaneDiscoveryHelpCard(onDismiss = it)
    },
) {
    val context = LocalContext.current
    var showTips by remember { mutableStateOf(false) }
    // The tip card only makes sense while help is on offer.
    LaunchedEffect(phase) {
        if (phase != PlaneDiscoveryPhase.HELP_OFFERED) showTips = false
    }

    val onboardingVisible =
        phase == PlaneDiscoveryPhase.HAND_HINT || phase == PlaneDiscoveryPhase.HELP_OFFERED
    val lostVisible = phase == PlaneDiscoveryPhase.LOST
    val tipsOpen = showTips && phase == PlaneDiscoveryPhase.HELP_OFFERED

    // Animated hand hint, centered. Fades in over `fadeInMs` (ARCore Elements' 0.15 s
    // CrossFadeAlpha) and out over `fadeOutMs` (the 0.75 s hide on plane-found).
    AnimatedVisibility(
        visible = onboardingVisible && !tipsOpen,
        modifier = modifier.align(Alignment.Center),
        enter = fadeIn(tween(durations.fadeInMs.toInt())),
        exit = fadeOut(tween(durations.fadeOutMs.toInt())),
    ) {
        handHint()
    }

    // Bottom message pill — onboarding hint or contextual tracking-lost copy.
    AnimatedVisibility(
        visible = (onboardingVisible && !tipsOpen) || lostVisible,
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 24.dp)
            .padding(bottom = 40.dp),
        enter = fadeIn(tween(durations.fadeInMs.toInt())),
        exit = fadeOut(tween(durations.fadeOutMs.toInt())),
    ) {
        val message = if (lostVisible) {
            trackingFailureReason?.getDescription(context)
                ?: stringResource(R.string.sceneview_plane_discovery_hint)
        } else {
            hintMessage ?: stringResource(R.string.sceneview_plane_discovery_hint)
        }
        GuideMessagePill(
            message = message,
            helpLabel = if (phase == PlaneDiscoveryPhase.HELP_OFFERED) {
                helpLabel ?: stringResource(R.string.sceneview_plane_discovery_help)
            } else null,
            onHelp = { onHelp?.invoke() ?: run { showTips = true } },
        )
    }

    // Expanded built-in tip card (the ARCore Elements `_moreHelpWindow` equivalent).
    if (tipsOpen) {
        Box(modifier = modifier.align(Alignment.Center)) {
            helpCard { showTips = false }
        }
    }
}

/**
 * The built-in "Can't find a surface?" tip card shown when the user taps "Need help?" and
 * no custom `onHelp` handler is set. Public so custom hosts and previews can reuse it.
 *
 * @param onDismiss invoked when the user taps "Got it".
 */
@Composable
fun PlaneDiscoveryHelpCard(
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .background(CardBackground, RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        BasicText(
            text = stringResource(R.string.sceneview_plane_discovery_tips_title),
            style = TextStyle(
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(modifier = Modifier.padding(top = 12.dp))
        listOf(
            R.string.sceneview_plane_discovery_tip_move_slowly,
            R.string.sceneview_plane_discovery_tip_lighting,
            R.string.sceneview_plane_discovery_tip_texture,
        ).forEach { tipRes ->
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                BasicText(text = "•", style = TipTextStyle)
                Spacer(modifier = Modifier.width(8.dp))
                BasicText(text = stringResource(tipRes), style = TipTextStyle)
            }
        }
        Spacer(modifier = Modifier.padding(top = 12.dp))
        BasicText(
            text = stringResource(R.string.sceneview_plane_discovery_got_it),
            modifier = Modifier
                .align(Alignment.End)
                .clickable { onDismiss?.invoke() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
private fun GuideMessagePill(
    message: String,
    helpLabel: String?,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(max = 480.dp)
            .background(PillBackground, RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = message,
            modifier = Modifier.weight(1f, fill = false),
            style = TextStyle(
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            ),
        )
        if (helpLabel != null) {
            Spacer(modifier = Modifier.width(16.dp))
            BasicText(
                text = helpLabel,
                modifier = Modifier
                    .clickable(onClick = onHelp)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                style = TextStyle(
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

// Translucent scrim colors — fixed (not theme-derived) because the overlay always sits on a
// live camera feed, where a dark pill + white text is legible in both light and dark themes.
private val PillBackground = Color.Black.copy(alpha = 0.70f)
private val CardBackground = Color.Black.copy(alpha = 0.85f)
private val TipTextStyle = TextStyle(color = Color.White, fontSize = 14.sp)
