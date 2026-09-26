@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.sceneview.demo.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
import kotlin.math.abs

/**
 * A status line that says what the app is doing *right now* — "Downloading Nikon F3…",
 * "Decoding the model…" — with its trailing ellipsis breathing, the way a streaming
 * assistant narrates its work (#3825).
 *
 * The sentence is the caller's: this composable only animates. When [text] ends in an
 * ellipsis (`…` or `...`), that ellipsis is drawn as three dots whose opacity runs left
 * to right on `motion-narration` (`DESIGN.md` → App Motion), so a slow step visibly
 * lives instead of reading as a frozen label. A sentence without an ellipsis is drawn
 * as is: a finished or blocked state has nothing in flight to animate.
 *
 * **Never narrate a step the code is not in.** Each caller maps a real stage of its
 * pipeline (a network call, a byte stream, a decode, a texture upload) to one line;
 * a timer that walks through reassuring sentences on its own is exactly what #3825
 * forbids.
 *
 * Only opacity moves — the dots are always laid out, so the line never changes width
 * and nothing around it reflows. Under reduced motion (and in QA mode and previews, see
 * [LocalMotionEnabled]) the line is drawn exactly as given, with its static `…`.
 *
 * Accessibility and test finders see the caller's sentence verbatim, not the three
 * separate dots it is drawn with.
 */
@Composable
fun NarrationText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    val base = narrationBase(text)
    if (base == null || !LocalMotionEnabled.current) {
        Text(
            text = text,
            modifier = modifier,
            color = color,
            style = style,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    val phase by rememberInfiniteTransition(label = "narration").animateFloat(
        initialValue = 0f,
        targetValue = NARRATION_DOTS.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = NARRATION_CYCLE_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "narration-dots",
    )
    // The same resolution `Text` applies: explicit colour, then the style's, then the content colour.
    val resolved = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val annotated = buildAnnotatedString {
        append(base)
        repeat(NARRATION_DOTS) { index ->
            withStyle(SpanStyle(color = resolved.copy(alpha = resolved.alpha * narrationDotAlpha(index, phase)))) {
                append('.')
            }
        }
    }
    Text(
        text = annotated,
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
        color = color,
        style = style,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** [text] without its trailing ellipsis, or `null` when it has none. */
internal fun narrationBase(text: String): String? {
    val trimmed = text.trimEnd()
    return when {
        trimmed.endsWith('…') -> trimmed.dropLast(1)
        trimmed.endsWith("...") -> trimmed.dropLast(3)
        else -> null
    }?.takeIf { it.isNotBlank() }
}

/**
 * Opacity of dot [index] at [phase] (`0 until NARRATION_DOTS`, wrapping): the dot under
 * the phase is fully lit and the light falls off over one dot either side, so the
 * brightness travels left to right and wraps. Never below [NARRATION_DOT_FLOOR] — a dot
 * that vanished would read as a two-dot ellipsis, not a moving one.
 */
internal fun narrationDotAlpha(index: Int, phase: Float): Float {
    val raw = abs(phase - index - 0.5f)
    val distance = minOf(raw, NARRATION_DOTS - raw)
    val lit = (1f - distance).coerceIn(0f, 1f)
    return NARRATION_DOT_FLOOR + (1f - NARRATION_DOT_FLOOR) * lit
}

private const val NARRATION_DOTS = 3

/** `motion-narration` in `DESIGN.md`: one sweep across the three dots. */
private const val NARRATION_CYCLE_MILLIS = 1_200

/** The dimmest a dot gets. */
private const val NARRATION_DOT_FLOOR = 0.25f

/**
 * The determinate ring a narrated download shows once its byte count is known (#3825): the
 * M3 Expressive wavy ring, in the same family as the `LoadingIndicator` it replaces (#3857).
 *
 * Sized for the 22 dp icon slot of a pill or a button row. The Material defaults are drawn
 * for a 48 dp container — a 4 dp stroke and a 15 dp wavelength — and at 22 dp they read as a
 * thick pentagon rather than a ring, so both are scaled down here.
 */
@Composable
fun NarrationProgressRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = WavyProgressIndicatorDefaults.trackColor,
) {
    // The pill ring's stroke before #3857, `over-media-edge` doubled.
    val strokeWidth = SceneViewTokens.Glass.borderWidth * 2
    val stroke = with(LocalDensity.current) { Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round) }
    CircularWavyProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        stroke = stroke,
        trackStroke = stroke,
        wavelength = NARRATION_RING_WAVELENGTH,
    )
}

/** Wavelength of [NarrationProgressRing]: the Material 15 dp scaled from 48 dp to 22 dp. */
private val NARRATION_RING_WAVELENGTH = 7.dp
