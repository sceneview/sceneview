@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * The **pre-AR half** of the one placement flow — "what are we putting in the room?"
 * answered on a still, themed screen before a camera is ever opened
 * ([#3405](https://github.com/sceneview/sceneview/issues/3405)).
 *
 * ## Why this is a screen and not a sheet
 *
 * The app already had a perfectly good picker — [PlacementModelPickerSheet] — but it opened
 * *over the live camera*, so choosing a model competed with plane discovery for the user's
 * attention in the one moment both needed it. AR Model Viewer hit the same wall and moved
 * its size entry off the viewfinder for exactly this reason; its rule is that AR is never
 * an entry point, only a destination you arrive at with a subject already chosen.
 *
 * There is a second, blunter reason. The AR half of this flow cannot be looked at on the
 * emulator at all — no camera HAL, no ARCore session
 * ([#2754](https://github.com/sceneview/sceneview/issues/2754)). A picker that only exists
 * over a camera is a picker nobody can screenshot in review. This one renders in light and
 * dark on `emulator-5554` with no AR at all.
 *
 * The sheet is *not* deleted: it is still how you swap models without leaving AR once you
 * are in there. This screen is how you arrive.
 *
 * @param models Catalogue offered. Same list the AR half will read at tap time.
 * @param picker Hoisted selection, shared with the in-AR sheet so the two cannot disagree.
 * @param flow Phase + options holder; the CTA calls [PlacementFlowState.enterAr].
 * @param arSupported `null` while `ArCoreApk.checkAvailability` is still resolving. Gates
 *   the CTA through the pure [placementCtaState].
 * @param onBack Leaves the demo. This screen is the flow's ground floor.
 * @param teaches One short paragraph naming the SDK concept the AR half demonstrates —
 *   this is a *demo*, and the thing it teaches should be readable before the camera opens,
 *   not buried in a settings sheet behind it.
 */
@Composable
fun PlacementChooserScreen(
    models: List<PlacementModel>,
    picker: PlacementPickerState,
    flow: PlacementFlowState,
    arSupported: Boolean?,
    onBack: () -> Unit,
    title: String,
    teaches: String,
    modifier: Modifier = Modifier,
) {
    val armedModel = models.armed(picker)
    val ctaState = placementCtaState(arSupported = arSupported, hasArmedModel = armedModel != null)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            // The exact string the Maestro per-demo crash gate asserts on
                            // (`assertVisible: "Navigate back"`), so the flow keeps working
                            // now that the demo's first screen is no longer the scaffold.
                            contentDescription = stringResource(R.string.cd_back_button),
                        )
                    }
                },
            )
        },
        bottomBar = {
            PlacementChooserCta(
                state = ctaState,
                modelName = armedModel?.displayName,
                onEnterAr = flow::enterAr,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SceneViewTokens.Space.md),
        ) {
            Text(
                text = stringResource(R.string.ar_placement_chooser_headline),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(SceneViewTokens.Space.xs))
            Text(
                text = teaches,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(SceneViewTokens.Space.md))

            // The catalogue. Cards are the SAME composable the in-AR sheet draws, so a model
            // looks identical whichever surface you meet it on — the whole point of #3405.
            // The grid is `heightIn`-capped rather than lazy-scrolling inside a scrolling
            // column: nesting two scroll axes on the same gesture is the bug that ships.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = CHOOSER_CARD_MIN_SIZE),
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                contentPadding = PaddingValues(bottom = SceneViewTokens.Space.sm),
                userScrollEnabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = CHOOSER_GRID_MAX_HEIGHT)
                    .semantics { testTag = "placement-chooser-grid" },
            ) {
                items(models.size) { index ->
                    val model = models[index]
                    PlacementModelCard(
                        model = model,
                        selected = model.id == picker.selectedId,
                        onClick = { picker.selectedId = model.id },
                    )
                }
            }

            Spacer(Modifier.height(SceneViewTokens.Space.md))
            PlacementModeSection(flow = flow)
            Spacer(Modifier.height(SceneViewTokens.Space.md))
        }
    }
}

/**
 * The "how does a tap resolve" control — the axis that used to be a whole second demo card
 * (`ar-instant-placement`), now two segments on the screen that already had to exist.
 *
 * It is on the chooser rather than in a settings sheet on purpose: it changes what the very
 * first tap does, so it is a *setup* decision, and a user who discovers it after placing
 * three models has already formed the wrong idea of what the demo does.
 */
@Composable
private fun PlacementModeSection(flow: PlacementFlowState) {
    val modes = listOf(PlacementMode.PLANE, PlacementMode.INSTANT)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.ar_placement_mode_heading),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(SceneViewTokens.Space.sm))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == flow.mode,
                    onClick = { flow.mode = mode },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    label = { Text(stringResource(placementModeLabelRes(mode))) },
                )
            }
        }
        Spacer(Modifier.height(SceneViewTokens.Space.xs))
        Text(
            text = stringResource(placementModeHelpRes(flow.mode)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // #1883 / #1882 — the two dev toggles the `ar-placement` demo already had, moved
        // out of the in-AR settings sheet and onto the setup screen with everything else
        // that decides how the session behaves before it starts.
        Spacer(Modifier.height(SceneViewTokens.Space.sm))
        ChooserToggle(
            label = stringResource(R.string.ar_placement_snap_to_plane),
            help = stringResource(
                if (flow.snapToPlane) {
                    R.string.ar_placement_snap_to_plane_on
                } else {
                    R.string.ar_placement_snap_to_plane_off
                }
            ),
            checked = flow.snapToPlane,
            onCheckedChange = { flow.snapToPlane = it },
        )
        ChooserToggle(
            label = stringResource(R.string.ar_placement_show_reticle),
            help = stringResource(R.string.ar_placement_show_reticle_help),
            checked = flow.showReticle,
            onCheckedChange = { flow.showReticle = it },
        )
    }
}

@Composable
private fun ChooserToggle(
    label: String,
    help: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(top = SceneViewTokens.Space.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        Text(
            text = help,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one way into AR, and the one place that says why you cannot go.
 *
 * The button names the model it will place ("Place Velvet Sofa in AR"). A generic "Start AR"
 * would hand the user back the question this whole screen exists to answer — and it is the
 * label a screen-reader user hears, where the visible grid selection is not available as
 * context.
 */
@Composable
private fun PlacementChooserCta(
    state: PlacementCtaState,
    modelName: String?,
    onEnterAr: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(SceneViewTokens.Space.md),
        ) {
            Button(
                onClick = onEnterAr,
                enabled = state == PlacementCtaState.READY,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SceneViewTokens.Layout.touchTarget),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Icon(
                    imageVector = Icons.Filled.ViewInAr,
                    contentDescription = null,
                    modifier = Modifier.size(CTA_ICON_SIZE),
                )
                Spacer(Modifier.size(SceneViewTokens.Space.sm))
                Text(
                    text = if (modelName != null) {
                        stringResource(R.string.ar_placement_cta_named, modelName)
                    } else {
                        stringResource(R.string.ar_placement_cta)
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // A disabled button with no sentence under it is the app refusing without
            // saying why — the "silent refusal" class AR Model Viewer catalogued (a locked
            // pinch that moved nothing and printed nothing). Every non-READY state speaks.
            placementCtaHelpRes(state)?.let { helpRes ->
                Spacer(Modifier.height(SceneViewTokens.Space.xs))
                Text(
                    text = stringResource(helpRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun placementModeLabelRes(mode: PlacementMode): Int = when (mode) {
    PlacementMode.PLANE -> R.string.ar_placement_mode_plane
    PlacementMode.INSTANT -> R.string.ar_placement_mode_instant
}

private fun placementModeHelpRes(mode: PlacementMode): Int = when (mode) {
    PlacementMode.PLANE -> R.string.ar_placement_mode_plane_help
    PlacementMode.INSTANT -> R.string.ar_placement_mode_instant_help
}

private fun placementCtaHelpRes(state: PlacementCtaState): Int? = when (state) {
    PlacementCtaState.READY -> null
    PlacementCtaState.CHECKING -> R.string.ar_placement_cta_checking
    PlacementCtaState.AR_UNSUPPORTED -> R.string.ar_placement_cta_unsupported
    PlacementCtaState.NO_MODEL -> R.string.ar_placement_cta_no_model
}

// Chooser geometry. Cards are a touch larger than the in-AR sheet's: this screen has the
// whole viewport, and a model you are choosing deserves more pixels than one you are
// swapping mid-session.
private val CHOOSER_CARD_MIN_SIZE = 132.dp
private val CHOOSER_GRID_MAX_HEIGHT = 560.dp
private val CTA_ICON_SIZE = 20.dp
