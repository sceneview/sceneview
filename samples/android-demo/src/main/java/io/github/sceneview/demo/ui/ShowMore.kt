package io.github.sceneview.demo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.motionFade
import io.github.sceneview.demo.theme.motionSpring

/** Test tags of the Show more / Show less pattern. */
object ShowMoreTestTags {
    const val TOGGLE = "show_more_toggle"
}

/** Half a turn: the chevron points down to "more", up to "less". */
private const val CHEVRON_EXPANDED_DEGREES = 180f

/**
 * The "Show N more" / "Show less" button under a folded list (`motion-expand` in
 * `DESIGN.md`). Its chevron turns half a turn on `motion-spring` with the list, so the
 * control says which way the list is about to move before it moves.
 */
@Composable
fun ShowMoreToggle(
    expanded: Boolean,
    hiddenCount: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) CHEVRON_EXPANDED_DEGREES else 0f,
        animationSpec = motionSpring(),
        label = "show-more-chevron",
    )
    TextButton(
        onClick = onToggle,
        modifier = modifier
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .testTag(ShowMoreTestTags.TOGGLE),
        contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
    ) {
        Text(
            text = if (expanded) {
                stringResource(R.string.show_less)
            } else {
                pluralStringResource(R.plurals.show_more_count, hiddenCount, hiddenCount)
            },
            style = MaterialTheme.typography.labelLarge,
        )
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            modifier = Modifier
                .size(ButtonDefaults.IconSize)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

/**
 * A list that shows its first [collapsedCount] [items] and folds the rest behind a
 * [ShowMoreToggle]. The rest unfolds downwards (`AnimatedVisibility`): height on
 * `motion-spring`, so the rows under it are pushed rather than jumped, opacity on
 * `motion-fade`; it folds back the same way. A list no longer than [collapsedCount] draws no button at all.
 *
 * The fold state is saved per [stateKey], so a rotation keeps an unfolded list unfolded.
 */
@Composable
fun <T> ExpandableColumn(
    items: List<T>,
    collapsedCount: Int,
    stateKey: String,
    modifier: Modifier = Modifier,
    spacing: Dp = SceneViewTokens.Space.sm,
    item: @Composable (T) -> Unit,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    val spring = motionSpring<IntSize>()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        items.take(collapsedCount).forEach { item(it) }
        val rest = items.drop(collapsedCount)
        if (rest.isNotEmpty()) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring, expandFrom = Alignment.Top) + fadeIn(motionFade()),
                exit = shrinkVertically(spring, shrinkTowards = Alignment.Top) + fadeOut(motionFade()),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                    rest.forEach { item(it) }
                }
            }
            ShowMoreToggle(
                expanded = expanded,
                hiddenCount = rest.size,
                onToggle = { expanded = !expanded },
            )
        }
    }
}
