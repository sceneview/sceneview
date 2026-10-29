@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.sceneview.demo.demos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Surface
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.CosmosChrome
import io.github.sceneview.demo.demos.internal.CosmosScene
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.ConnectedChoiceRow

/**
 * Cosmos's controls over the dock (#4601): the Star scene's two views while the star is on
 * screen, then the scene switch, always. Both are the opaque `mode-pill-*` palette of
 * `DESIGN.md`, theme-independent: they float over a scene that runs from a black sky to a lit
 * white-blue flow field, and must read on all of it.
 */
@Composable
internal fun CosmosBottomControls(
    scene: CosmosScene,
    spacetime: Boolean,
    onSpacetime: (Boolean) -> Unit,
    onPick: (CosmosScene) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
    ) {
        if (scene == CosmosScene.Star) SpacetimePill(spacetime, onSpacetime)
        CosmosSceneSwitch(scene, onPick)
    }
}

/**
 * The scene switch: Galaxy | Star | Burst | Flow as one segmented pill, its checked segment the
 * scene on screen — the voyage moves it as it travels, so the current scene always reads. It
 * lives apart from the dock: next to Settings, a near miss opened the settings sheet.
 */
@Composable
internal fun CosmosSceneSwitch(scene: CosmosScene, onPick: (CosmosScene) -> Unit) {
    ModePillSurface {
        ConnectedChoiceRow(
            options = CosmosChrome.switchOrder,
            selected = scene,
            onSelect = onPick,
            label = { it.label },
            modifier = Modifier.padding(horizontal = SceneViewTokens.Space.xs),
            optionTestTag = CosmosChrome::switchTestTag,
            colors = modePillColors(),
            fillWidth = false,
        )
    }
}

/** The Star scene's two views, Starlight and Spacetime, in the same pill as the scene switch. */
@Composable
internal fun SpacetimePill(spacetime: Boolean, onSelect: (Boolean) -> Unit) {
    val starlight = stringResource(R.string.demo_cosmos_starlight)
    val spacetimeLabel = stringResource(R.string.demo_cosmos_spacetime)
    ModePillSurface {
        ConnectedChoiceRow(
            options = listOf(false, true),
            selected = spacetime,
            onSelect = onSelect,
            label = { if (it) spacetimeLabel else starlight },
            modifier = Modifier.padding(horizontal = SceneViewTokens.Space.xs),
            optionTestTag = { if (it) "cosmos_spacetime" else "cosmos_starlight" },
            colors = modePillColors(),
            fillWidth = false,
        )
    }
}

@Composable
private fun ModePillSurface(content: @Composable () -> Unit) {
    val pill = SceneViewTokens.ModePill
    Surface(
        shape = CircleShape,
        color = pill.container,
        contentColor = pill.onContainer,
        border = BorderStroke(pill.outlineWidth, pill.outline),
        content = content,
    )
}

@Composable
private fun modePillColors(): ToggleButtonColors {
    val pill = SceneViewTokens.ModePill
    return ToggleButtonDefaults.colors(
        containerColor = pill.container,
        contentColor = pill.onContainer,
        checkedContainerColor = pill.selectedContainer,
        checkedContentColor = pill.onSelected,
    )
}
