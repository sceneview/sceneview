package io.github.sceneview.demo.demos

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.AreaSurface
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.RerunGlbMesh
import io.github.sceneview.demo.demos.internal.RerunScanModel
import io.github.sceneview.demo.demos.internal.RoomMeasure
import io.github.sceneview.demo.demos.internal.ScanGround
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.SurveyFormat
import io.github.sceneview.demo.demos.internal.formatMeasureArea
import io.github.sceneview.demo.demos.internal.formatMeasureLength
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * What the result of an outdoor area's scan says (ScanScope.Area): how far the scan spans and
 * how much ground it holds, as the scan's own figures, and, when the surface built from it came
 * out in pieces, that the result stays on the points the scan took (AreaSurface).
 */

private const val TAG = "ScanArea"

/**
 * What an area's result knows of its scan: the ground its points cover ([groundM2], `null` when
 * none was found), and whether the model built from them is in pieces beside it ([inPieces]).
 */
internal class AreaResult(val groundM2: Float?, val inPieces: Boolean)

/**
 * [model] against the [surfels] it was built from, `null` for any other [scope] and until both
 * are there: read once for each model, off the main thread.
 */
@Composable
internal fun rememberAreaResult(scope: ScanScope, model: RerunScanModel?, surfels: DenseCloud?): AreaResult? {
    if (scope != ScanScope.Area) return null
    val result by produceState<AreaResult?>(null, model, surfels) {
        val cloud = surfels ?: return@produceState
        val built = model ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                val scanned = ScanGround.of(cloud.positions, cloud.normals, cloud.count)?.let(AreaSurface::groundM2) ?: 0f
                val held = RerunGlbMesh.read(built.glb)?.let(AreaSurface::levelM2) ?: 0f
                Log.i(TAG, "model holds %.1f m² of ground, the scan covers %.1f m²".format(held, scanned))
                AreaResult(scanned.takeIf { it > 0f }, AreaSurface.inPieces(held, scanned))
            }.onFailure { Log.w(TAG, "area not read", it) }
                // Not read is not "in pieces": the result goes on to its model as any other.
                .getOrDefault(AreaResult(groundM2 = null, inPieces = false))
        }
    }
    return result
}

/**
 * [result] on its card with the scan's [span], the rectangle its points stand in: the figures
 * named as what was scanned, and one sentence when the result stays on the points. Nothing when
 * there is nothing to say.
 */
@Composable
internal fun AreaResultCard(result: AreaResult, span: RoomMeasure?, modifier: Modifier = Modifier) {
    if (span == null && result.groundM2 == null && !result.inPieces) return
    val chrome = LocalStageChrome.current
    val locale = LocalLocale.current.platformLocale
    val units = SurveyFormat.units(locale)
    OverlayCard(testTag = AREA_CARD_TAG, modifier = modifier.semantics(mergeDescendants = true) { }) {
        span?.let {
            Text(
                stringResource(R.string.scan_area_span, formatMeasureLength(it.width, units, locale), formatMeasureLength(it.depth, units, locale)),
                style = Type.card,
                color = chrome.onCard,
            )
        }
        result.groundM2?.let {
            Text(stringResource(R.string.scan_area_ground, formatMeasureArea(it, units, locale)), style = Type.body, color = chrome.onCardMuted)
        }
        if (result.inPieces) {
            Text(stringResource(R.string.scan_area_in_pieces), style = Type.body, color = chrome.onCard)
        }
    }
}

internal const val AREA_CARD_TAG = "rerun_area_card"
