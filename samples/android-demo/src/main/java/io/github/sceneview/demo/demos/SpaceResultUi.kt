package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.MeasureUnitSystem
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.SpaceFace
import io.github.sceneview.demo.demos.internal.SpaceFit
import io.github.sceneview.demo.demos.internal.SpaceFitter
import io.github.sceneview.demo.demos.internal.SpaceView
import io.github.sceneview.demo.demos.internal.SurveyFormat
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens.Glass
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.theme.SceneViewTokens.Type
import io.github.sceneview.demo.theme.StageChrome
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * What the result of a space's scan is for (ScanScope.Space): its inside measurements, what is in
 * the way, and the biggest box that still slides in, on one card under the model. The same
 * figures leave the phone as JSON from the export sheet (SpaceAnswer).
 */

/** What a space's result knows of its inside. */
internal sealed interface SpaceMeasure {
    /** Not read yet: the card waits, it does not say "couldn't". */
    data object Measuring : SpaceMeasure

    /** The scan shows no space: no back with a bottom or a side before it. */
    data object None : SpaceMeasure

    /** The scan holds far more than one space, a room walked round: no figure of it means anything. */
    data object TooWide : SpaceMeasure

    class Found(val fit: SpaceFit) : SpaceMeasure
}

/**
 * The measure of the space [surfels] show, `null` for any other [scope]: read once, off the main
 * thread, and again only for other points. [view] is how they were filmed ([SpaceFitter.viewOf]):
 * the space opens towards the phone, round what it aimed at. [voxelM] is the grid [surfels] were kept
 * on, the scan's own when it says one.
 */
@Composable
internal fun rememberSpaceMeasure(scope: ScanScope, surfels: DenseCloud?, view: SpaceView? = null, voxelM: Float? = null): SpaceMeasure? {
    if (scope != ScanScope.Space) return null
    val measure by produceState<SpaceMeasure>(SpaceMeasure.Measuring, surfels, view, voxelM) {
        // No points yet is not "no space": a kept scan's are read after its model is shown.
        val cloud = surfels ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val fit = runCatching { SpaceFitter.of(cloud, voxelM ?: SpaceFitter.DEFAULT_VOXEL_M, view) }.getOrNull()
            when {
                fit == null -> SpaceMeasure.None
                SpaceFitter.isOneSpace(fit, view) -> SpaceMeasure.Found(fit)
                else -> SpaceMeasure.TooWide
            }
        }
    }
    return measure
}

/**
 * [measure] on its card: nothing while it is read, then the figures, or what to film again.
 * [stage] is the chrome of the ground its model stands on, which the keys beside the figures are
 * cut from: a result's stage is media, whatever the theme.
 * The figures are in the language's [units], as everywhere else in a scan ([SurveyFormat.units]):
 * inches in US English, centimetres in every other language.
 */
@Composable
internal fun SpaceMeasureCard(
    measure: SpaceMeasure,
    modifier: Modifier = Modifier,
    units: MeasureUnitSystem = SurveyFormat.units(LocalLocale.current.platformLocale),
    stage: StageChrome = StageChrome.Media,
) {
    val chrome = LocalStageChrome.current
    when (measure) {
        SpaceMeasure.Measuring -> Unit
        SpaceMeasure.None -> OverlayCard(testTag = SPACE_CARD_TAG, modifier = modifier.semantics(mergeDescendants = true) { }) {
            Text(stringResource(R.string.scan_space_none), style = Type.card, color = chrome.onCard)
            Text(stringResource(R.string.scan_space_none_hint), style = Type.body, color = chrome.onCardMuted)
        }
        SpaceMeasure.TooWide -> OverlayCard(testTag = SPACE_CARD_TAG, modifier = modifier.semantics(mergeDescendants = true) { }) {
            Text(stringResource(R.string.scan_space_wide), style = Type.card, color = chrome.onCard)
            Text(stringResource(R.string.scan_space_wide_hint), style = Type.body, color = chrome.onCardMuted)
        }
        is SpaceMeasure.Found -> OverlayCard(testTag = SPACE_CARD_TAG, modifier = modifier.semantics(mergeDescendants = true) { }) {
            val fit = measure.fit
            val key = SpaceFitColors.of(stage)
            val unit = stringResource(
                if (units == MeasureUnitSystem.Imperial) R.string.scan_space_unit_in else R.string.scan_space_unit_cm,
            )
            SpaceLine(
                stringResource(R.string.scan_space_inside, side(fit.width, units), side(fit.depth, units), side(fit.height, units), unit),
                style = Type.card,
                color = chrome.onCard,
                key = SpaceKey(stage.ground, key.inside),
            )
            SpaceLine(stringResource(R.string.scan_space_order), style = Type.caption, color = chrome.onCardMuted)
            val box = fit.fits
            SpaceLine(
                text = when {
                    box != null -> stringResource(
                        R.string.scan_space_fits,
                        side(box.width, units), side(box.depth, units), side(box.height, units), unit,
                    )
                    // No bottom seen is a bottom not filmed: what to do, not a verdict on the space.
                    SpaceFace.Bottom in fit.open -> stringResource(R.string.scan_space_fits_no_bottom)
                    else -> stringResource(R.string.scan_space_fits_none)
                },
                style = Type.body,
                color = chrome.onCard,
                key = box?.let { SpaceKey(stage.ground, key.box, key.boxFill) },
            )
            if (fit.obstacles.isNotEmpty()) {
                SpaceLine(
                    pluralStringResource(R.plurals.scan_space_obstacles, fit.obstacles.size, fit.obstacles.size),
                    style = Type.body,
                    color = chrome.onCardMuted,
                    key = SpaceKey(stage.ground, key.obstacle),
                )
            }
            // A crate has no top; a cupboard filmed from above has one the phone never saw, and
            // its height is then only as far as its back was filmed.
            if (SpaceFace.Top in fit.open) {
                SpaceLine(stringResource(R.string.scan_space_no_top), style = Type.body, color = chrome.onCardMuted)
            }
        }
    }
}

/**
 * How one of a space's boxes is drawn on its model ([SpaceFitLayer]): its edges, and the wash of
 * its faces if it has one, over the [ground] the model stands on.
 */
internal class SpaceKey(val ground: Color, val edge: Color, val fill: Color = Color.Transparent)

/**
 * One line of a space's figures beside its [key]: a square of the model's own stage, outlined
 * and washed as the box the line speaks of is drawn there, so the key is the drawing's colours
 * whatever the card's own. A line with no box keeps the same inset: every line starts at the
 * same place.
 */
@Composable
internal fun SpaceLine(text: String, style: TextStyle, color: Color, key: SpaceKey? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Box(
            Modifier
                .size(Space.md)
                .then(
                    if (key == null) {
                        Modifier
                    } else {
                        Modifier.background(key.ground).background(key.fill).border(Glass.borderWidth * 2, key.edge)
                    },
                ),
        )
        Text(text, style = style, color = color, modifier = Modifier.weight(1f))
    }
}

/**
 * [metres] in whole centimetres, rounded down: a box cut to the figure on the card still goes in.
 * A millimetre short of the next centimetre counts as it, so 0.60 m measured 0.5996 reads 60.
 */
internal fun cm(metres: Float): Int = ((metres * MM_PER_M).roundToInt() / MM_PER_CM)

/**
 * [metres] as the card writes a side in [units], rounded down for the same reason: whole
 * centimetres, or inches to the half inch as a tape reads them ("23½").
 */
internal fun side(metres: Float, units: MeasureUnitSystem): String = when (units) {
    MeasureUnitSystem.Metric -> cm(metres).toString()
    MeasureUnitSystem.Imperial -> {
        val halves = ((metres * MM_PER_M).roundToInt() / MM_PER_HALF_INCH).toInt()
        val whole = halves / 2
        when {
            halves % 2 == 0 -> whole.toString()
            whole == 0 -> HALF
            else -> "$whole$HALF"
        }
    }
}

private const val MM_PER_M = 1000f
private const val MM_PER_CM = 10
private const val MM_PER_HALF_INCH = 12.7f
private const val HALF = "½"

internal const val SPACE_CARD_TAG = "rerun_space_card"
