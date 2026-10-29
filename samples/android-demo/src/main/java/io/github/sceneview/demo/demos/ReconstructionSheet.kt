@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.demos.internal.ReconstructionMethod
import io.github.sceneview.demo.theme.SceneViewTokens
import kotlinx.coroutines.launch

/*
 * The end of a scan's choice of reconstruction (#4630): the two the phone can run, what each
 * gives, and how long the filled one takes against the other, as the benchmark measured it —
 * with what "Filled" can make of this scan within its memory ([FillPlan]).
 */

/**
 * The sheet that asks how to finish the model: [ReconstructionChoices] in the demo's sheet.
 * [onFinish] gets the method picked once the sheet is down; dismissing it ([onDismiss])
 * finishes the model as scanned.
 */
@Composable
internal fun ReconstructionSheet(
    fill: FillPlan,
    initial: ReconstructionMethod,
    onFinish: (ReconstructionMethod) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    DemoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = SceneViewTokens.Radius.xl, topEnd = SceneViewTokens.Radius.xl),
    ) {
        ReconstructionChoices(
            fill,
            initial,
            onFinish = { method -> scope.launch { sheetState.hide() }.invokeOnCompletion { onFinish(method) } },
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

/**
 * The sheet's content: the two methods, one picked, and the button that finishes with it.
 * "Filled" is picked only when [fill] fills; when only on a coarser grid, it says so first.
 */
@Composable
internal fun ReconstructionChoices(
    fill: FillPlan,
    initial: ReconstructionMethod,
    onFinish: (ReconstructionMethod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fills = fill is FillPlan.Fills
    var picked by remember(fill, initial) {
        mutableStateOf(if (fills) initial else ReconstructionMethod.Fused)
    }
    // Scrolls, so that in landscape or with large text "Finish model" is still reached: dismissing
    // the sheet instead would finish as scanned without the user seeing "Filled" was dropped.
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(SceneViewTokens.Space.md),
        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
    ) {
        Text(stringResource(R.string.scan_finish_method_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.scan_finish_method_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.selectableGroup()) {
            Choice(
                method = ReconstructionMethod.Fused,
                title = stringResource(R.string.scan_finish_method_fused),
                detail = stringResource(R.string.scan_finish_method_fused_detail),
                selected = picked == ReconstructionMethod.Fused,
                enabled = true,
                onPick = { picked = it },
            )
            Choice(
                method = ReconstructionMethod.Poisson,
                title = stringResource(R.string.scan_finish_method_filled),
                detail = stringResource(
                    when (fill) {
                        is FillPlan.Fills -> R.string.scan_finish_method_filled_detail
                        FillPlan.TooBig -> R.string.scan_finish_method_filled_too_big
                        FillPlan.NoSurfels -> R.string.scan_finish_method_filled_unavailable
                    },
                ),
                note = coarserNote(fill)?.let { stringResource(it) },
                selected = picked == ReconstructionMethod.Poisson,
                enabled = fills,
                onPick = { picked = it },
            )
        }
        Button(
            onClick = { onFinish(picked) },
            modifier = Modifier.fillMaxWidth().padding(top = SceneViewTokens.Space.xs).testTag(FINISH_TAG),
        ) { Text(stringResource(R.string.scan_finish_method_finish)) }
    }
}

/** What filling on a grid coarser than the voxel's costs, said before "Filled" is picked. */
private fun coarserNote(fill: FillPlan): Int? = when ((fill as? FillPlan.Fills)?.coarser ?: 1) {
    1 -> null
    2 -> R.string.scan_finish_method_filled_half
    else -> R.string.scan_finish_method_filled_quarter
}

@Composable
private fun Choice(
    method: ReconstructionMethod,
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onPick: (ReconstructionMethod) -> Unit,
    note: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onPick(method) })
            .padding(vertical = SceneViewTokens.Space.sm)
            .testTag(choiceTag(method)),
        verticalAlignment = Alignment.Top,
    ) {
        // Selection is the Row's: the button itself is inert.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = SceneViewTokens.Space.sm)) {
            val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
            Text(title, style = MaterialTheme.typography.titleMedium, color = color)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            note?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = SceneViewTokens.Space.xs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

internal const val FINISH_TAG = "reconstruction-finish"

internal fun choiceTag(method: ReconstructionMethod) = "reconstruction-${method.name.lowercase()}"

private const val DISABLED_ALPHA = 0.38f
