package io.github.sceneview.ar

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Animated AR coaching overlay — SceneView's counterpart of Apple's `ARCoachingOverlayView`:
 * a card with a phone sweeping over a surface drawn in perspective, its view cone lighting the
 * points it has seen, a short instruction under it, and a reason chip when ARCore says why
 * tracking is struggling ("Too dark", "Too fast", "Low detail"). When a surface is found the
 * card steps aside for a small "Surface found" pill under the centre, so the object landing
 * there is never hidden.
 *
 * [AutoPlacementScene] and [PlacementScene] show it by default. Use this overload next to your
 * own `ARSceneView` (see the tracking overload of [rememberArGuidanceState]) or with
 * `coaching = false` to position it yourself. It fills the enclosing [BoxScope] and keeps clear
 * of the system bars; pass the height of your own bottom chrome as [contentPadding]. Hide
 * non-essential chrome while [ArGuidanceState.isCoaching] is true.
 *
 * The card is one accessibility node announced politely by TalkBack. Its ground does not
 * follow the theme (the camera feed is the background) — only the scrim opacity does. With
 * animations turned off in system settings, watched live, the illustration holds a still pose
 * with a "move" chevron and the card only fades.
 */
@Composable
fun BoxScope.ARCoachingOverlay(
    guidance: ArGuidanceState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    ARCoachingOverlay(
        cue = guidance.cue,
        surface = guidance.surface,
        hint = guidance.hint,
        scanLingering = guidance.scanLingering,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

/**
 * Stateless renderer for one [cue] — for previews, screenshot tests, or a guidance state
 * machine of your own. [ArGuidanceCue.NONE] renders nothing (with an exit fade).
 *
 * @param hint why tracking struggles; shown as a chip on [ArGuidanceCue.INITIALIZING] and
 *   [ArGuidanceCue.TRACKING_LIMITED], ignored otherwise.
 * @param scanLingering the scan has gone on for a while: the secondary line suggests a better
 *   spot.
 * @param contentPadding space taken by the host's own chrome (a bottom bar, a sheet peek).
 */
@Composable
fun BoxScope.ARCoachingOverlay(
    cue: ArGuidanceCue,
    surface: PlacementSurface = PlacementSurface.SURFACE,
    hint: ArTrackingHint = ArTrackingHint.NONE,
    scanLingering: Boolean = false,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val motion = rememberCoachMotionEnabled()
    val cardCue = cue.takeUnless { it == ArGuidanceCue.NONE || it == ArGuidanceCue.SURFACE_FOUND }
    // Latch the last card so its exit fade keeps drawing it instead of blanking.
    var shown by remember { mutableStateOf(CardContent(ArGuidanceCue.INITIALIZING, ArTrackingHint.NONE, false)) }
    if (cardCue != null) {
        shown = CardContent(cardCue, hint.takeIf { cardCue.takesHint() } ?: ArTrackingHint.NONE, scanLingering)
    }
    val rise = with(LocalDensity.current) { CoachSpec.RISE.roundToPx() }
    BoxWithConstraints(
        modifier = modifier
            .matchParentSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(contentPadding)
            .padding(CoachSpec.EDGE),
    ) {
        val fontScale = LocalDensity.current.fontScale
        val layout = when {
            maxHeight < CoachSpec.ROW_BELOW -> CardLayout.ROW
            fontScale >= CoachSpec.LARGE_FONT_SCALE -> CardLayout.COMPACT
            else -> CardLayout.REGULAR
        }
        AnimatedVisibility(
            visible = cardCue != null,
            // 45 % down the free band: optically centred, a little above the middle of the scene.
            modifier = Modifier.align(BiasAlignment(0f, CoachSpec.CARD_BIAS)),
            enter = cardEnter(motion, rise),
            exit = cardExit(motion),
        ) {
            CoachCard(shown, surface, layout, motion)
        }
        AnimatedVisibility(
            visible = cue == ArGuidanceCue.SURFACE_FOUND,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = CoachSpec.FOUND_OFFSET),
            enter = if (motion) {
                fadeIn(tween(CoachSpec.ENTER_MS, easing = CoachSpec.Expressive)) +
                    scaleIn(tween(CoachSpec.ENTER_MS, easing = CoachSpec.Expressive), initialScale = 0.9f)
            } else {
                fadeIn(tween(CoachSpec.ENTER_MS))
            },
            exit = fadeOut(tween(CoachSpec.EXIT_MS)),
        ) {
            FoundPill(surface, motion)
        }
    }
}

/** Binary-compatibility shim for the pre-`contentPadding` descriptor. */
@Deprecated(
    "Binary-compatibility overload. Use the overload that takes `contentPadding`.",
    level = DeprecationLevel.HIDDEN,
)
@Composable
fun BoxScope.ARCoachingOverlay(
    guidance: ArGuidanceState,
    modifier: Modifier = Modifier,
) = ARCoachingOverlay(guidance = guidance, modifier = modifier, contentPadding = PaddingValues(0.dp))

/** Binary-compatibility shim for the pre-`hint` descriptor. */
@Deprecated(
    "Binary-compatibility overload. Use the overload that takes `hint` and `contentPadding`.",
    level = DeprecationLevel.HIDDEN,
)
@Composable
fun BoxScope.ARCoachingOverlay(
    cue: ArGuidanceCue,
    surface: PlacementSurface = PlacementSurface.SURFACE,
    modifier: Modifier = Modifier,
) = ARCoachingOverlay(cue = cue, surface = surface, hint = ArTrackingHint.NONE, modifier = modifier)

private fun ArGuidanceCue.takesHint() =
    this == ArGuidanceCue.INITIALIZING || this == ArGuidanceCue.TRACKING_LIMITED

@Immutable
private data class CardContent(val cue: ArGuidanceCue, val hint: ArTrackingHint, val lingering: Boolean)

private enum class CardLayout { REGULAR, COMPACT, ROW }

private fun cardEnter(motion: Boolean, rise: Int): EnterTransition =
    if (motion) {
        fadeIn(tween(CoachSpec.ENTER_MS, easing = CoachSpec.Expressive)) +
            slideInVertically(tween(CoachSpec.ENTER_MS, easing = CoachSpec.Expressive)) { rise } +
            scaleIn(tween(CoachSpec.ENTER_MS, easing = CoachSpec.Expressive), initialScale = 0.96f)
    } else {
        fadeIn(tween(CoachSpec.ENTER_MS))
    }

private fun cardExit(motion: Boolean): ExitTransition =
    if (motion) {
        fadeOut(tween(CoachSpec.EXIT_MS)) + scaleOut(tween(CoachSpec.EXIT_MS), targetScale = 0.96f)
    } else {
        fadeOut(tween(CoachSpec.EXIT_MS))
    }

@Composable
private fun CoachCard(content: CardContent, surface: PlacementSurface, layout: CardLayout, motion: Boolean) {
    val dark = isSystemInDarkTheme()
    val scrim = if (dark) CoachColors.ScrimDark else CoachColors.ScrimLight
    val border = if (dark) CoachColors.ScrimBorderDark else CoachColors.ScrimBorderLight
    val shape = RoundedCornerShape(CoachSpec.CARD_RADIUS)
    val text = cardText(content, surface)
    val chip = hintLabel(content.hint)
    val spoken = listOfNotNull(chip, text.headline, text.detail).joinToString(". ")
    val polite = content.cue != ArGuidanceCue.INITIALIZING
    val illustrationSize = if (layout == CardLayout.REGULAR) {
        CoachSpec.ILLUSTRATION_W to CoachSpec.ILLUSTRATION_H
    } else {
        CoachSpec.ILLUSTRATION_SMALL_W to CoachSpec.ILLUSTRATION_SMALL_H
    }
    val cardModifier = Modifier
        .widthIn(max = if (layout == CardLayout.ROW) CoachSpec.CARD_ROW_MAX_W else CoachSpec.CARD_MAX_W)
        .background(scrim, shape)
        .border(CoachSpec.HAIRLINE, border, shape)
        .padding(CoachSpec.CARD_PADDING)
    val illustration = @Composable {
        CoachCanvas(content.cue, surface, motion, Modifier.size(illustrationSize.first, illustrationSize.second))
    }
    // One accessibility node for the card and its chip.
    Box(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = spoken
            if (polite) liveRegion = LiveRegionMode.Polite
        },
        contentAlignment = Alignment.TopCenter,
    ) {
        if (layout == CardLayout.ROW) {
            Row(cardModifier, verticalAlignment = Alignment.CenterVertically) {
                illustration()
                Spacer(Modifier.width(CoachSpec.CARD_GAP))
                CoachText(text, TextAlign.Start, motion)
            }
        } else {
            Column(cardModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                illustration()
                Spacer(Modifier.size(CoachSpec.CARD_GAP))
                CoachText(text, TextAlign.Center, motion)
            }
        }
        // The chip straddles the card's top edge, a badge on the card: clear of the phone,
        // which stands at the top of the floor illustration, in every layout.
        AnimatedVisibility(
            visible = chip != null,
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, -placeable.height / 2) }
            },
            enter = fadeIn(tween(CoachSpec.EXIT_MS)),
            exit = fadeOut(tween(CoachSpec.EXIT_MS)),
        ) {
            var lastChip by remember { mutableStateOf("") }
            if (chip != null) lastChip = chip
            HintChip(lastChip)
        }
    }
}

/**
 * The illustration. One canvas for the card's whole life: the sweep phase keeps running across
 * cue changes (only the words crossfade) and is read in the draw phase only, so the loop costs
 * a redraw per frame, never a recomposition.
 */
@Composable
private fun CoachCanvas(cue: ArGuidanceCue, surface: PlacementSurface, motion: Boolean, modifier: Modifier) {
    val loops = motion && cue != ArGuidanceCue.INITIALIZING
    val sweepMs = if (cue == ArGuidanceCue.TRACKING_LIMITED) CoachSpec.SWEEP_MS * 2 else CoachSpec.SWEEP_MS
    val phase = remember { mutableFloatStateOf(if (motion) 0f else CoachSpec.RESTING_SWEEP) }
    LaunchedEffect(loops, sweepMs) {
        if (!loops) {
            if (!motion) phase.floatValue = CoachSpec.RESTING_SWEEP
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (last != 0L) {
                    val step = (now - last) / NANOS_PER_MILLI / sweepMs
                    phase.floatValue = (phase.floatValue + step) % 1f
                }
                last = now
            }
        }
    }
    val rise = remember { Animatable(1f) }
    LaunchedEffect(cue == ArGuidanceCue.INITIALIZING, motion) {
        if (cue == ArGuidanceCue.INITIALIZING && motion) {
            rise.snapTo(0f)
            rise.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 120f))
        } else {
            rise.snapTo(1f)
        }
    }
    val dim = animateFloatAsState(
        targetValue = if (cue == ArGuidanceCue.TRACKING_LIMITED) CoachSpec.LIMITED_DIM else 1f,
        animationSpec = tween(CoachSpec.ENTER_MS),
        label = "coachDim",
    )
    Canvas(modifier) {
        drawCoachIllustration(
            cue = cue,
            surface = surface,
            pose = CoachPose(sweep = phase.floatValue, rise = rise.value, dim = dim.value, resting = !motion),
        )
    }
}

@Immutable
private data class CoachText(val headline: String, val detail: String)

@Composable
private fun cardText(content: CardContent, surface: PlacementSurface): CoachText {
    val wall = surface == PlacementSurface.WALL
    val headline = when (content.cue) {
        ArGuidanceCue.SCAN -> stringResource(
            if (wall) R.string.sceneview_coaching_scan_wall else R.string.sceneview_coaching_scan_surface,
        )
        ArGuidanceCue.TRACKING_LIMITED -> stringResource(R.string.sceneview_coaching_limited)
        ArGuidanceCue.RELOCALIZING -> stringResource(R.string.sceneview_coaching_relocalizing)
        else -> stringResource(R.string.sceneview_coaching_initializing)
    }
    val detail = when (content.cue) {
        ArGuidanceCue.SCAN -> stringResource(
            when {
                content.lingering -> R.string.sceneview_coaching_scan_lingering_detail
                wall -> R.string.sceneview_coaching_scan_wall_detail
                else -> R.string.sceneview_coaching_scan_surface_detail
            },
        )
        ArGuidanceCue.TRACKING_LIMITED -> stringResource(R.string.sceneview_coaching_limited_detail)
        ArGuidanceCue.RELOCALIZING -> stringResource(R.string.sceneview_coaching_relocalizing_detail)
        else -> stringResource(R.string.sceneview_coaching_initializing_detail)
    }
    // With a reason, the fix leads and the state steps down to the secondary line.
    val action = when (content.hint) {
        ArTrackingHint.TOO_DARK -> stringResource(R.string.sceneview_coaching_hint_too_dark_action)
        ArTrackingHint.TOO_FAST -> stringResource(R.string.sceneview_coaching_hint_too_fast_action)
        ArTrackingHint.LOW_DETAIL -> stringResource(R.string.sceneview_coaching_hint_low_detail_action)
        ArTrackingHint.NONE -> null
    }
    return if (action != null) CoachText(action, headline) else CoachText(headline, detail)
}

@Composable
private fun hintLabel(hint: ArTrackingHint): String? = when (hint) {
    ArTrackingHint.TOO_DARK -> stringResource(R.string.sceneview_coaching_hint_too_dark)
    ArTrackingHint.TOO_FAST -> stringResource(R.string.sceneview_coaching_hint_too_fast)
    ArTrackingHint.LOW_DETAIL -> stringResource(R.string.sceneview_coaching_hint_low_detail)
    ArTrackingHint.NONE -> null
}

/**
 * Headline over a secondary line, in a block of fixed height (two headline lines, two secondary
 * lines) so the card never resizes when the words change. Only the words crossfade.
 */
@Composable
private fun CoachText(text: CoachText, align: TextAlign, motion: Boolean) {
    val density = LocalDensity.current
    val minHeight = with(density) {
        CoachSpec.HEADLINE_LINE.toDp() * 2 + CoachSpec.DETAIL_LINE.toDp() * 2 + CoachSpec.TEXT_GAP
    }
    val rise = with(density) { CoachSpec.TEXT_RISE.roundToPx() }
    AnimatedContent(
        targetState = text,
        modifier = Modifier.heightIn(min = minHeight),
        contentAlignment = Alignment.Center,
        transitionSpec = {
            val enter = fadeIn(tween(CoachSpec.TEXT_IN_MS, delayMillis = CoachSpec.TEXT_OUT_MS))
            val rising = if (motion) {
                enter + slideInVertically(tween(CoachSpec.TEXT_IN_MS, delayMillis = CoachSpec.TEXT_OUT_MS)) { rise }
            } else {
                enter
            }
            rising togetherWith fadeOut(tween(CoachSpec.TEXT_OUT_MS))
        },
        label = "coachText",
    ) { shownText ->
        Column(
            modifier = Modifier.heightIn(min = minHeight),
            horizontalAlignment = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(CoachSpec.TEXT_GAP, Alignment.CenterVertically),
        ) {
            BasicText(
                text = shownText.headline,
                maxLines = 2,
                style = TextStyle(
                    color = CoachColors.OnScrim,
                    fontSize = 18.sp,
                    lineHeight = CoachSpec.HEADLINE_LINE,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = align,
                ),
            )
            BasicText(
                text = shownText.detail,
                maxLines = 2,
                style = TextStyle(
                    color = CoachColors.OnScrimDim,
                    fontSize = 15.sp,
                    lineHeight = CoachSpec.DETAIL_LINE,
                    textAlign = align,
                ),
            )
        }
    }
}

/** The reason chip, astride the card's top edge: `warning` ground, near-black text. */
@Composable
private fun HintChip(label: String) {
    BasicText(
        text = label,
        modifier = Modifier
            .background(CoachColors.Warning, RoundedCornerShape(percent = 50))
            .padding(horizontal = CoachSpec.CHIP_PADDING_H, vertical = CoachSpec.CHIP_PADDING_V),
        style = TextStyle(
            color = CoachColors.OnAccent,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

/** The found beat: a check that pops in and two words, under the centre where the object lands. */
@Composable
private fun FoundPill(surface: PlacementSurface, motion: Boolean) {
    val dark = isSystemInDarkTheme()
    val scrim = if (dark) CoachColors.ScrimDark else CoachColors.ScrimLight
    val border = if (dark) CoachColors.ScrimBorderDark else CoachColors.ScrimBorderLight
    val label = stringResource(
        if (surface == PlacementSurface.WALL) R.string.sceneview_coaching_found_wall
        else R.string.sceneview_coaching_found_surface,
    )
    val check = remember { Animatable(if (motion) CoachSpec.CHECK_FROM else 1f) }
    LaunchedEffect(motion) {
        if (motion) check.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 400f))
    }
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .clearAndSetSemantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            }
            .background(scrim, shape)
            .border(CoachSpec.HAIRLINE, border, shape)
            .padding(horizontal = CoachSpec.PILL_PADDING_H, vertical = CoachSpec.PILL_PADDING_V),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CoachSpec.PILL_GAP),
    ) {
        Canvas(Modifier.size(CoachSpec.CHECK)) { drawCheckDisc(check.value) }
        BasicText(
            text = label,
            style = TextStyle(
                color = CoachColors.OnScrim,
                fontSize = 15.sp,
                lineHeight = CoachSpec.DETAIL_LINE,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

/**
 * Animations off in system settings (any of the three scales at 0), watched live; or a
 * one-frame preview/screenshot.
 */
@Composable
private fun rememberCoachMotionEnabled(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun read(): Boolean = MOTION_SCALES.all { Settings.Global.getFloat(resolver, it, 1f) != 0f }
    var enabled by remember(context) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = read()
            }
        }
        MOTION_SCALES.forEach { resolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer) }
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

private val MOTION_SCALES = listOf(
    Settings.Global.ANIMATOR_DURATION_SCALE,
    Settings.Global.TRANSITION_ANIMATION_SCALE,
    Settings.Global.WINDOW_ANIMATION_SCALE,
)

private const val NANOS_PER_MILLI = 1_000_000f

/** `DESIGN.md` → *AR Coaching Card*. Kept together so a token change is one edit. */
internal object CoachSpec {
    /** `motion-coach-sweep`: one out-and-back sweep of the phone (twice as long when limited). */
    const val SWEEP_MS = 2_800

    /** `duration-medium` enter (fade + 8 dp rise + scale .96 → 1, `ease-expressive`). */
    const val ENTER_MS = 350

    /** `duration-short` exit. */
    const val EXIT_MS = 200

    /** Words fade through: out, then in with a 4 dp rise. */
    const val TEXT_OUT_MS = 90
    const val TEXT_IN_MS = 210

    /** `ease-expressive`, cubic-bezier(0.2, 0, 0, 1). */
    val Expressive = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Reduce motion: phone in the middle, the left half of the points lit. */
    const val RESTING_SWEEP = 0.25f
    const val SWEEP_FROM = 0.22f
    const val SWEEP_TO = 0.78f
    const val LIMITED_DIM = 0.6f
    const val CHECK_FROM = 0.6f
    const val LARGE_FONT_SCALE = 1.5f

    /** Vertical bias of the card in its band: 45 % from the top. */
    const val CARD_BIAS = -0.1f

    val RISE = 8.dp
    val TEXT_RISE = 4.dp
    val EDGE = 16.dp
    val ROW_BELOW = 420.dp
    val FOUND_OFFSET = 120.dp

    val CARD_MAX_W = 320.dp
    val CARD_ROW_MAX_W = 480.dp
    val CARD_RADIUS = 24.dp
    val CARD_PADDING = 20.dp
    val CARD_GAP = 16.dp
    val TEXT_GAP = 4.dp
    val HEADLINE_LINE = 24.sp
    val DETAIL_LINE = 20.sp

    val CHIP_PADDING_H = 10.dp
    val CHIP_PADDING_V = 4.dp
    val PILL_PADDING_H = 16.dp
    val PILL_PADDING_V = 10.dp
    val PILL_GAP = 8.dp
    val CHECK = 20.dp

    val ILLUSTRATION_W = 200.dp
    val ILLUSTRATION_H = 120.dp
    val ILLUSTRATION_SMALL_W = 160.dp
    val ILLUSTRATION_SMALL_H = 96.dp
    val PHONE_W = 26.dp
    val PHONE_H = 44.dp
    val PHONE_CORNER = 6.dp
    val PHONE_STROKE = 2.dp
    val CAMERA_DOT = 2.dp
    val DOT_UNIT = 1.dp
    val HAIRLINE = 1.dp
    val CHEVRON = 12.dp
    val GHOST_EDGE = 28.dp
}

/** `DESIGN.md` AR tokens. Accents are the dark-scheme values in both themes (read on `ar-scrim`). */
internal object CoachColors {
    val ScrimLight = Color(0xF0000000) // ar-scrim light, black at 0.94
    val ScrimDark = Color(0xE0000000) // ar-scrim dark, black at 0.88
    val ScrimBorderLight = Color(0x29FFFFFF) // ar-scrim-border light, white at 0.16
    val ScrimBorderDark = Color(0x1AFFFFFF) // ar-scrim-border dark, white at 0.10
    val OnScrim = Color.White // on-ar-scrim
    val OnScrimDim = Color(0xB8FFFFFF) // on-ar-scrim-dim, white at 0.72
    val Primary = Color(0xFFA4C1FF) // primary, dark-scheme value
    val Warning = Color(0xFFF59E0B) // warning
    val OnAccent = Color(0xFF101014) // text on `warning` / `primary` accents
}
