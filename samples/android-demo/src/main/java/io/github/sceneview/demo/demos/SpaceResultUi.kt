package io.github.sceneview.demo.demos

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.SpaceFace
import io.github.sceneview.demo.demos.internal.SpaceFit
import io.github.sceneview.demo.demos.internal.SpaceFitter
import io.github.sceneview.demo.demos.internal.Vec3
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens.Type
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
 * thread, and again only for other points. [eye] is where the phone stood as it filmed them
 * ([SpaceFitter.eyeOf]): the space opens towards it. [voxelM] is the grid [surfels] were kept
 * on, the scan's own when it says one.
 */
@Composable
internal fun rememberSpaceMeasure(scope: ScanScope, surfels: DenseCloud?, eye: Vec3? = null, voxelM: Float? = null): SpaceMeasure? {
    if (scope != ScanScope.Space) return null
    val measure by produceState<SpaceMeasure>(SpaceMeasure.Measuring, surfels, eye, voxelM) {
        // No points yet is not "no space": a kept scan's are read after its model is shown.
        val cloud = surfels ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val fit = runCatching { SpaceFitter.of(cloud, voxelM ?: SpaceFitter.DEFAULT_VOXEL_M, eye) }.getOrNull()
            when {
                fit == null -> SpaceMeasure.None
                SpaceFitter.isOneSpace(fit) -> SpaceMeasure.Found(fit)
                else -> SpaceMeasure.TooWide
            }
        }
    }
    return measure
}

/** [measure] on its card: nothing while it is read, then the figures, or what to film again. */
@Composable
internal fun SpaceMeasureCard(measure: SpaceMeasure, modifier: Modifier = Modifier) {
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
            Text(
                stringResource(R.string.scan_space_inside, cm(fit.width), cm(fit.depth), cm(fit.height)),
                style = Type.card,
                color = chrome.onCard,
            )
            Text(stringResource(R.string.scan_space_order), style = Type.caption, color = chrome.onCardMuted)
            val box = fit.fits
            Text(
                text = when {
                    box != null -> stringResource(R.string.scan_space_fits, cm(box.width), cm(box.depth), cm(box.height))
                    // No bottom seen is a bottom not filmed: what to do, not a verdict on the space.
                    SpaceFace.Bottom in fit.open -> stringResource(R.string.scan_space_fits_no_bottom)
                    else -> stringResource(R.string.scan_space_fits_none)
                },
                style = Type.body,
                color = chrome.onCard,
            )
            if (fit.obstacles.isNotEmpty()) {
                Text(
                    pluralStringResource(R.plurals.scan_space_obstacles, fit.obstacles.size, fit.obstacles.size),
                    style = Type.body,
                    color = chrome.onCardMuted,
                )
            }
        }
    }
}

/**
 * [metres] in whole centimetres, rounded down: a box cut to the figure on the card still goes in.
 * A millimetre short of the next centimetre counts as it, so 0.60 m measured 0.5996 reads 60.
 */
internal fun cm(metres: Float): Int = ((metres * MM_PER_M).roundToInt() / MM_PER_CM)

private const val MM_PER_M = 1000f
private const val MM_PER_CM = 10

internal const val SPACE_CARD_TAG = "rerun_space_card"
