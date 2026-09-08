@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.sceneview.core.threemf.ThreeMfUnit
import io.github.sceneview.demo.theme.SceneViewTokens
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The scale question for a unit-less file — #3543.
 *
 * STL, OBJ and PLY record no length unit, so SceneView reads them in millimetres like the slicers
 * that write most of them. A mesh a couple of units across is almost certainly a metre-authored
 * export instead, and reading it as millimetres puts a 2 mm object on screen: technically the
 * documented default, practically an empty-looking room. Rather than assume in silence, the viewer
 * says what it did and offers the other reading, with both sizes spelled out so the answer is
 * obvious without knowing what a "unit" is.
 *
 * Asked once per opened file, and only when [io.github.sceneview.core.threemf.ModelUnitGuess] has
 * something to offer. Declining is a real answer: the framing works at either size (the viewer
 * frames whatever it loads, at any order of magnitude), so nothing is broken by keeping
 * millimetres.
 *
 * @param loadedExtentMeters Longest side of what is currently on screen, in metres.
 * @param suggested          The unit being offered.
 * @param loadedUnit         The unit the file was read in.
 */
@Composable
fun ModelUnitSheet(
    loadedExtentMeters: Float,
    suggested: ThreeMfUnit,
    loadedUnit: ThreeMfUnit,
    onOpenAt: (ThreeMfUnit) -> Unit,
    onDismiss: () -> Unit,
) {
    val suggestedExtent = loadedExtentMeters / loadedUnit.meters * suggested.meters
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = SceneViewTokens.Radius.xl, topEnd = SceneViewTokens.Radius.xl),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(SceneViewTokens.Space.md).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        ) {
            Text("Looks like metres", style = MaterialTheme.typography.titleLarge)
            Text(
                "This file carries no unit, so it opened in millimetres — ${formatSize(loadedExtentMeters)} across. " +
                    "A mesh this size is usually authored in metres.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onOpenAt(suggested) },
                modifier = Modifier.fillMaxWidth().padding(top = SceneViewTokens.Space.xs),
            ) { Text("Open at real size (${formatSize(suggestedExtent)})") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Keep ${formatSize(loadedExtentMeters)}")
            }
        }
    }
}

/**
 * A length in the unit a person would say it in: millimetres below a centimetre, centimetres below
 * a metre, metres above. Two significant figures at most — this is a label on a button, not a
 * measurement, and "1.9999 m" reads as a bug.
 */
internal fun formatSize(meters: Float): String {
    if (!meters.isFinite() || meters <= 0f) return "0 mm"
    val (value, suffix) = when {
        meters < 0.01f -> meters * 1000f to "mm"
        meters < 1f -> meters * 100f to "cm"
        else -> meters to "m"
    }
    val rounded = if (value < 10f) (value * 10f).roundToInt() / 10f else value.roundToInt().toFloat()
    return if (abs(rounded - rounded.roundToInt()) < 0.05f) "${rounded.roundToInt()} $suffix"
    else "${String.format(Locale.US, "%.1f", rounded)} $suffix"
}
