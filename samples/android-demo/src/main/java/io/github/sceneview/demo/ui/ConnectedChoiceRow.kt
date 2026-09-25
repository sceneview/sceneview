@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.sceneview.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * Single-choice picker drawn as an M3 Expressive *connected button group*: one
 * [ToggleButton] per option, joined by [ButtonGroupDefaults.ConnectedSpaceBetween], with the
 * leading / middle / trailing connected shapes so the pressed button morphs its inner corners
 * and the checked one rounds out into a pill.
 *
 * It replaces the `SingleChoiceSegmentedButtonRow` and `FilterChip` rows the demo used for
 * mutually exclusive modes. Accessibility is the same contract: the row is a
 * [selectableGroup], every option exposes [Role.RadioButton] and its checked state, and the
 * visible label is the accessible name. Picking a *new* option plays
 * [HapticFeedbackType.SegmentTick]; re-tapping the current one does nothing, so the control
 * never reads as a toggle that could leave the group with no selection.
 *
 * @param options The choices, in display order. Each option gets an equal share of the width.
 * @param selected The option currently checked. Must be one of [options].
 * @param onSelect Called with the newly picked option — never with [selected].
 * @param label Visible (and accessible) text for an option.
 * @param optionTestTag Optional UI-test tag for an option's button.
 */
@Composable
fun <T> ConnectedChoiceRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    optionTestTag: ((T) -> String)? = null,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            ToggleButton(
                checked = isSelected,
                onCheckedChange = {
                    if (!isSelected) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(option)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton }
                    .then(optionTestTag?.let { Modifier.testTag(it(option)) } ?: Modifier),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                // Three labels share a phone's width: the default 24 dp side padding would
                // ellipsize "Icosa Gallery" at 1.0× font scale.
                contentPadding = PaddingValues(horizontal = SceneViewTokens.Space.sm),
                colors = demoToggleButtonColors(),
            ) {
                Text(
                    text = label(option),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * [ToggleButtonDefaults.colors] with the unchecked container on
 * `surfaceContainerHighest`. The M3 default is `surfaceContainer`, which the demo's light
 * theme sets to white — on a white screen the unchecked buttons had no visible shape and the
 * group read as loose text next to one blue pill.
 */
@Composable
internal fun demoToggleButtonColors(): ToggleButtonColors =
    ToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
