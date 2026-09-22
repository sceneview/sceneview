@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
 * @param flow Phase holder; the CTA calls [PlacementFlowState.enterAr].
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
    wallMode: Boolean = false,
    onWallModeChange: (Boolean) -> Unit = {},
) {
    val armedModel = models.armed(picker)
    val ctaState = placementCtaState(arSupported = arSupported, hasArmedModel = armedModel != null)

    // Letting the catalogue scroll *under* the bar (see `contentPadding` below) is only an
    // improvement if the bar is something to slide under. A bare `TopAppBar` is `surface`
    // on a `surface` page — 1.00:1 in both schemes — which is the same "edge nothing
    // draws" this screen fixes at the bottom, recreated at the top.
    //
    // Two things draw it, the same two the CTA bar uses at the other end:
    //
    //  - **a tone**, through the Material 3 mechanism rather than a constant: a pinned
    //    scroll behaviour raises the bar to `scrolledContainerColor` exactly while
    //    content is underneath it, and leaves it flat with the page when there is
    //    nothing to separate. `surfaceContainerHigh` for the same reason the CTA bar
    //    picks it — it is the lowest role with a tone in BOTH schemes, since the light
    //    ramp collapses `surfaceContainerLowest`/`Low`/`Container` onto `#FFFFFF`.
    //  - **an edge**, the same hairline, always on, because tone alone is 1.11:1 in
    //    light and 1.54:1 in dark: real, but not a line you could point at.
    //
    // The hairline is `outline`, not `outlineVariant`: `outline` is the role that carries
    // WCAG 1.4.11 in this theme, and in dark it is 6.26:1 on the page and 4.08:1 on the
    // raised bar, i.e. conformant. In light, `outline` is `#D6DAE0` — 1.40:1 and 1.26:1 —
    // so the top boundary is *drawn* in both schemes but only *conformant* in dark. That
    // is the light ramp's gap, not this screen's: nothing in the light scale sits between
    // `#D6DAE0` and `onSurfaceVariant`'s `#3D4654`, and a 3:1 hairline on white needs
    // roughly `#8D8D8D`. Fixing it means moving the light `outline` token, which lands
    // on every control border in the app and belongs in the light-ramp PR, next to the
    // dark one #3681 already did.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                // The exact string the Maestro per-demo crash gate asserts
                                // on (`assertVisible: "Navigate back"`), so the flow keeps
                                // working now that the demo's first screen is no longer the
                                // scaffold.
                                contentDescription = stringResource(R.string.cd_back_button),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    scrollBehavior = scrollBehavior,
                )
                HorizontalDivider(
                    thickness = SceneViewTokens.Layout.hairlineWidth,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.testTag(PlacementChooserTestTags.TOP_BAR_EDGE),
                )
            }
        },
        bottomBar = {
            PlacementChooserCta(
                state = ctaState,
                modelName = if (wallMode) stringResource(R.string.ar_placement_wall_model) else armedModel?.displayName,
                onEnterAr = flow::enterAr,
            )
        },
    ) { contentPadding ->
        // `contentPadding` goes INSIDE the scroll, not outside it.
        //
        // Outside — `.padding(contentPadding).verticalScroll(…)`, which this was — shrinks
        // the scroll *viewport* to sit above the CTA bar. That is not a bug: nothing is
        // ever unreachable. But it means the catalogue stops dead at an edge nothing
        // draws, and a half-visible card row simply ends in mid-air, which is what reads
        // as "the CTA sliced the grid ~50 px above the bar". Inside, the viewport is the
        // whole screen and the padding is content: the row slides *under* an opaque bar
        // with a hairline on it, which is a boundary the eye can name, and the last row
        // still clears the bar because the padding is still there.
        //
        // It also absorbs the CTA's own height changes: the bar grows a line of help text
        // when the button is disabled, and that now re-pads content instead of resizing
        // the viewport under a running scroll.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag(PlacementChooserTestTags.CATALOGUE)
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
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

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(R.string.ar_placement_floor, R.string.ar_placement_wall).forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = wallMode == (index == 1),
                        onClick = { onWallModeChange(index == 1) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            Spacer(Modifier.height(SceneViewTokens.Space.md))
            if (wallMode) {
                Text(
                    stringResource(R.string.ar_placement_wall_help),
                    style = SceneViewTokens.Type.body,
                )
            } else {
            // The catalogue. Cards are the SAME composable the in-AR sheet draws, so a model
            // looks identical whichever surface you meet it on — the whole point of #3405.
            //
            // A plain Column of Rows, NOT a `LazyVerticalGrid`. A lazy grid inside a
            // scrolling column has to be given a bounded height, and any bound is a lie: the
            // catalogue is 6 bundled rows plus however many streamed ones `SampleAssets`
            // carries, so a `heightIn(max = …)` cap silently CLIPS the tail — with
            // `userScrollEnabled = false` the models past the cap become unreachable, and
            // with it enabled two scroll axes fight over the same vertical drag. Verified on
            // `emulator-5554`: the capped version cut the catalogue off mid-way through the
            // streamed rows. `ModelPickerSheet` reached the same conclusion for the same
            // reason (#3324) — the whole screen scrolls, the grid does not.
            models.chunked(CHOOSER_COLUMNS).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = SceneViewTokens.Space.sm),
                    horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                ) {
                    row.forEach { model ->
                        Box(modifier = Modifier.weight(1f)) {
                            PlacementModelCard(
                                model = model,
                                selected = model.id == picker.selectedId,
                                onClick = { picker.selectedId = model.id },
                            )
                        }
                    }
                    // A trailing odd row keeps its card at column width instead of stretching
                    // it across the screen.
                    repeat(CHOOSER_COLUMNS - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            }
            Spacer(Modifier.height(SceneViewTokens.Space.md))
        }
    }
}

@Composable
private fun PlacementChooserCta(
    state: PlacementCtaState,
    modelName: String?,
    onEnterAr: () -> Unit,
) {
    // `surfaceContainerHigh` + a 1 dp `outlineVariant` hairline, not `surface` on a
    // `surface` page. The bar was the same colour as the thing it sat on, so it had no
    // container at all: the catalogue simply stopped 50-odd pixels above the bottom, with
    // the last row sliced by an edge nothing drew. Giving the bar a ground *and* letting
    // the content scroll under it (see the caller) turns that into a boundary you can
    // point at.
    //
    // `surfaceContainerHigh` rather than `surfaceContainer`, for two reasons that agree:
    // it is the role every other container in this app already uses (`DemoMediaCard`,
    // `WhatsNewUi`, the model picker, the Explore tiles), and it is the lowest role that
    // has a tone in BOTH schemes. In the light scheme `surfaceContainerLowest`,
    // `surfaceContainerLow` and `surfaceContainer` are all literally `0xFFFFFFFF` — the
    // same collapse #3681 fixed for dark, still present at the bottom of the light ramp —
    // so `surfaceContainer` here would have shipped a bar that is visible in dark and
    // invisible in light, which is the defect this line claims to fix.
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // `outline`, not `outlineVariant`, and the same line the top bar now draws.
            // `outlineVariant` is the *separator inside a container* role; this is the
            // boundary between the page and a control surface, which is `outline`'s job
            // (WCAG 1.4.11) and what the dark ramp sized it for: 6.26:1 on the page and
            // 4.08:1 on this bar, against `outlineVariant`'s 2.38:1 and 1.55:1. The light
            // token is still `#D6DAE0` (1.40:1 / 1.26:1) — strictly better than the 1.17:1
            // / 1.05:1 it replaces, still short of 3:1, and only the light-ramp PR can
            // close that.
            HorizontalDivider(
                thickness = SceneViewTokens.Layout.hairlineWidth,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.testTag(PlacementChooserTestTags.CTA_BAR_EDGE),
            )
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
                // saying why — the "silent refusal" class AR Model Viewer catalogued (a
                // locked pinch that moved nothing and printed nothing). Every non-READY
                // state speaks.
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
}


private fun placementCtaHelpRes(state: PlacementCtaState): Int? = when (state) {
    PlacementCtaState.READY -> null
    PlacementCtaState.CHECKING -> R.string.ar_placement_cta_checking
    PlacementCtaState.AR_UNSUPPORTED -> R.string.ar_placement_cta_unsupported
    PlacementCtaState.NO_MODEL -> R.string.ar_placement_cta_no_model
}

/**
 * Test tags for the chooser's two chrome boundaries.
 *
 * Both are one hairline over a container, and both exist to be *seen*, so the test that
 * covers them renders the screen and measures the pixels either side rather than
 * asserting a token name — see `PlacementChooserBoundaryTest`.
 */
object PlacementChooserTestTags {
    /** Hairline under the top app bar — the edge the catalogue scrolls under. */
    const val TOP_BAR_EDGE = "placement-chooser-top-bar-edge"

    /** Hairline over the CTA bar — the edge the catalogue scrolls behind. */
    const val CTA_BAR_EDGE = "placement-chooser-cta-bar-edge"

    /** The scrolling catalogue itself. */
    const val CATALOGUE = "placement-chooser-catalogue"
}

// Chooser geometry. Two columns on a phone: the cards are a touch larger than the in-AR
// sheet's, because this screen has the whole viewport and a model you are choosing deserves
// more pixels than one you are swapping mid-session.
private const val CHOOSER_COLUMNS = 2
private val CTA_ICON_SIZE = 20.dp
