@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.ui.viewer

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.home.outlineSubtle

/**
 * A model the viewer ships in its APK.
 *
 * @param description one line saying what the model shows, under its name in the picker.
 * @param frontYaw yaw, in degrees, that turns the asset's front toward the viewer's camera. The
 *   viewer looks from +Z (the glTF front); an asset authored facing -Z needs 180 or the picker's
 *   thumbnail and the viewer disagree about which side of the model is shown (#3828).
 */
data class BundledViewerModel(
    val assetPath: String,
    val displayName: String,
    @StringRes val description: Int? = null,
    val frontYaw: Float = 0f,
) {
    val assetName get() = assetPath.substringAfterLast('/').substringBeforeLast('.')
}

/** The two scenes the picker opens, besides a single model. */
enum class ViewerScene(
    @StringRes val title: Int,
    @StringRes val description: Int,
    @DrawableRes val preview: Int,
) {
    Park(
        title = R.string.demo_multi_model_title,
        description = R.string.demo_model_picker_park_desc,
        preview = R.drawable.model_picker_park,
    ),
    Gallery(
        title = R.string.demo_scene_gallery_title,
        description = R.string.demo_model_picker_gallery_desc,
        preview = R.drawable.model_picker_gallery,
    ),
}

/**
 * The Models sheet — one picker for the whole viewer, opened from the Models dock item of all
 * three sections (#3828).
 *
 * Two sections, both made of the same card: **Scenes** first, so the two destinations that are not
 * a single model are seen before anything else (they used to be two text rows under the grid that
 * nobody scrolled to), then the **single models**. Every card is a picture of what it opens: a
 * render of the exact bundled GLB (see `tools/demo-previews/README.md`) or a capture of the scene.
 *
 * There is no "Surprise me" here any more. The viewer's floating pill is the one entry point — two
 * copies of the same action, one of them inside a sheet, was the confusion the issue reports.
 *
 * @param selectedPath the model on screen, outlined; `null` outside the single-model section.
 * @param currentScene the scene on screen, outlined; `null` in the single-model section.
 */
@Composable
fun ModelPickerSheet(
    models: List<BundledViewerModel>,
    selectedPath: String?,
    currentScene: ViewerScene?,
    onSelect: (BundledViewerModel) -> Unit,
    onScene: (ViewerScene) -> Unit,
    onDismiss: () -> Unit,
) {
    // Fully expanded from the start (`skipPartiallyExpanded`). The grid is a plain Column of
    // Rows rather than a LazyVerticalGrid: a lazy grid inside a sheet needs a bounded height, and
    // the `heightIn(max = …)` cap it had cut the last row's captions (QA round 3). The whole sheet
    // scrolls on short screens instead of a grid scrolling inside it.
    DemoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = SceneViewTokens.Radius.xl, topEnd = SceneViewTokens.Radius.xl),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SceneViewTokens.Space.md),
        ) {
            Text(
                stringResource(R.string.demo_model_picker_title),
                style = SceneViewTokens.Type.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PickerSectionHeader(R.string.demo_model_picker_scenes, top = SceneViewTokens.Space.md)
            CardRow(ViewerScene.entries) { scene ->
                PickerCard(
                    title = stringResource(scene.title),
                    subtitle = stringResource(scene.description),
                    selected = scene == currentScene,
                    onClick = { onScene(scene) },
                ) {
                    // A capture of the scene itself — its own stage, its own models.
                    Image(
                        painter = painterResource(scene.preview),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            PickerSectionHeader(R.string.demo_model_picker_models, top = SceneViewTokens.Space.lg)
            Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                models.chunked(2).forEach { row ->
                    CardRow(row) { model ->
                        PickerCard(
                            title = model.displayName,
                            subtitle = model.description?.let { stringResource(it) },
                            selected = model.assetPath == selectedPath,
                            onClick = { onSelect(model) },
                        ) {
                            // A transparent render of the exact GLB this card opens, on the card's
                            // own fill — so it sits right in both themes.
                            ModelThumbnails.resourceFor(model.assetName)?.let {
                                Image(
                                    painter = painterResource(it),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } ?: Icon(
                                Icons.Outlined.ViewInAr,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(SceneViewTokens.Space.md))
        }
    }
}

/** A section label, styled like the home catalogue's section headers (`DESIGN.md`). */
@Composable
private fun PickerSectionHeader(@StringRes text: Int, top: Dp) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = top, bottom = SceneViewTokens.Space.sm),
    )
}

/**
 * Two equal columns of equal height; a lone last item keeps its column width.
 *
 * A caption that wraps to a second line grows both cards of the row. The height is asked of each
 * card at its exact column width, gap included. A `Row` with `IntrinsicSize.Min` measured the
 * cards as if there were no gap, so a caption that only wraps at the real width was given one
 * line and ellipsized ("Wooden post, metal lante…", #3828 QA).
 */
@Composable
private fun <T> CardRow(items: List<T>, card: @Composable (T) -> Unit) {
    Layout(
        content = { items.forEach { card(it) } },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gap = SceneViewTokens.Space.sm.roundToPx()
        val width = (constraints.maxWidth - gap) / 2
        val height = measurables.maxOf { it.maxIntrinsicHeight(width) }
        val placeables = measurables.map { it.measure(Constraints.fixed(width, height)) }
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(index * (width + gap), 0) }
        }
    }
}

/**
 * The home catalogue's card anatomy (5:4 media over a `type-card` title and a `type-caption`
 * line), on `surface-container-high` — the `DESIGN.md` role for a tile on a container. The card
 * showing on screen gets the 2dp `primary` outline instead of the hairline.
 */
@Composable
private fun PickerCard(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    media: @Composable BoxScope.() -> Unit,
) {
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(SceneViewTokens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) {
            BorderStroke(SceneViewTokens.Layout.selectedOutlineWidth, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(SceneViewTokens.Layout.hairlineWidth, outlineSubtle())
        },
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(SceneViewTokens.Layout.mediaAspect),
                content = media,
            )
            Column(
                Modifier.fillMaxWidth().padding(
                    top = SceneViewTokens.Home.cardTextPaddingTop,
                    start = SceneViewTokens.Home.cardTextPaddingHorizontal,
                    end = SceneViewTokens.Home.cardTextPaddingHorizontal,
                    bottom = SceneViewTokens.Home.cardTextPaddingBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
            ) {
                Text(
                    title,
                    style = SceneViewTokens.Type.card,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = SceneViewTokens.Type.caption,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
