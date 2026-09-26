package io.github.sceneview.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A slider with its name and current value on one line above the track.
 *
 * Every demo needs this control and, before this existed, every demo re-typed it: 37 sliders
 * across 21 files, each pairing a hand-written `Text("Name: ${"%.2f".format(...)}")` with a
 * bare [Slider]. They drifted — different decimal counts for the same kind of quantity,
 * different typography, a unit sometimes inside the format string and sometimes appended, and
 * a label that grows into the track's width on a small screen because nothing constrained it.
 *
 * The value sits on the trailing edge rather than inside the label so that a column of these
 * reads as a table: names align left, values align right, and a changing value never reflows
 * the name. That is the difference between a settings panel and a stack of sentences.
 *
 * @param label The control's name, e.g. `"Density"`. Rendered on the leading edge.
 * @param value Current value. Must lie within [valueRange].
 * @param onValueChange Called continuously as the user drags.
 * @param valueRange Inclusive bounds of the track.
 * @param modifier Applied to the whole control (label row + track).
 * @param enabled Whether the user can move the track. A disabled control dims both rows, so a
 * value that no longer applies does not read as active.
 * @param steps Number of discrete stops *between* the ends, as in [Slider]. `0` is continuous.
 * @param decimals Digits after the decimal point in the rendered value. Ignored when
 * [valueText] is supplied.
 * @param unit Appended to the rendered value after a thin space, e.g. `"m"` or `"m/s²"`.
 * Ignored when [valueText] is supplied.
 * @param valueText Fully-formatted value, for the cases [decimals]/[unit] cannot express —
 * an enum-like readout, or a value plus a derived word (`"6.0 h · Morning"`).
 * @param onValueChangeFinished Called once the drag gesture ends, as in [Slider].
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: Int = 0,
    decimals: Int = 2,
    unit: String? = null,
    valueText: String? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val rendered = valueText ?: formatSliderValue(value, decimals, unit)
    // The whole control is one node to a screen reader: the track already announces its value,
    // so leaving the label row focusable would read the number twice.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "$label, $rendered" }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else DISABLED_ALPHA
                ),
                modifier = Modifier.weight(1f, fill = true),
            )
            Text(
                text = rendered,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.End,
                color = LocalContentColor.current.copy(
                    alpha = if (enabled) 1f else DISABLED_ALPHA
                ),
            )
        }
        // Semantic haptics (M3 Expressive): one SegmentTick per stop crossed on a stepped
        // track, and one when a continuous track hits either end — never a buzz per pixel.
        val haptics = LocalHapticFeedback.current
        var lastStop by remember(steps, valueRange) {
            mutableIntStateOf(sliderHapticStop(value, valueRange, steps))
        }
        Slider(
            value = value,
            onValueChange = { new ->
                val stop = sliderHapticStop(new, valueRange, steps)
                if (stop != lastStop) {
                    if (steps > 0 || stop != 0) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                    lastStop = stop
                }
                onValueChange(new)
            },
            valueRange = valueRange,
            enabled = enabled,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

private const val DISABLED_ALPHA = 0.38f

/**
 * The haptic "detent" [value] sits in. On a stepped track ([steps] > 0) it is the index of the
 * nearest stop, so every stop crossed is a change. On a continuous track it is `-1` at the start
 * of [valueRange], `1` at its end and `0` anywhere between, so only reaching an end is a change
 * worth a tick.
 */
internal fun sliderHapticStop(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Int {
    val span = valueRange.endInclusive - valueRange.start
    if (span <= 0f) return 0
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    return if (steps > 0) {
        (fraction * (steps + 1)).roundToInt()
    } else {
        when {
            fraction <= 0f -> -1
            fraction >= 1f -> 1
            else -> 0
        }
    }
}

/** Thin space: keeps `12.5 m` from breaking across the value/unit boundary. */
private const val THIN_SPACE = '\u2009'

/**
 * Formats [value] for a slider readout.
 *
 * [Locale.US] rather than the device locale on purpose: these are engineering readouts sitting
 * next to API values a reader is meant to copy into code, and a decimal comma would not
 * round-trip through `toFloat()`.
 */
internal fun formatSliderValue(value: Float, decimals: Int, unit: String?): String {
    val number = String.format(Locale.US, "%.${decimals.coerceAtLeast(0)}f", value)
    return if (unit.isNullOrEmpty()) number else "$number$THIN_SPACE$unit"
}

@Preview(name = "LabeledSlider", showBackground = true)
@Composable
private fun LabeledSliderPreview() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LabeledSlider("Density", 0.42f, {}, 0f..1f)
        LabeledSlider("Start", 3.5f, {}, 0f..20f, decimals = 1, unit = "m")
        LabeledSlider("Intensity", 120_000f, {}, 10_000f..500_000f, decimals = 0)
        LabeledSlider("Time of Day", 6f, {}, 0f..24f, valueText = "6.0 h · Morning")
        LabeledSlider("End", 12f, {}, 0f..30f, decimals = 1, unit = "m", enabled = false)
    }
}
