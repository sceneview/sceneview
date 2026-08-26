@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.viewer.ModelThumbnails
import kotlinx.coroutines.launch

/**
 * Where a placeable model's bytes come from — the only distinction the canonical picker
 * draws between two catalogue rows.
 *
 * It is surfaced (as a caption on the streamed cards only) because it is the one thing a
 * user cannot infer from a model's name and which changes what happens on a tap: a
 * streamed row may still be downloading, and until it lands the tap places that row's own
 * bundled stand-in rather than nothing at all.
 */
enum class PlacementModelSource { Bundled, Streamed }

/**
 * One row of the canonical tap-to-place model picker.
 *
 * Deliberately *flat* — a resolved [assetLocation] and a display name, never a slug plus
 * a resolver plus an index. Both hosts (the AR View tab and the `ar-placement` demo) build
 * the same list shape, so the picker, the status pill and the placement itself all read
 * the **same** row, which is what makes "what does the next tap place?" answerable with
 * one lookup ([#2476](https://github.com/sceneview/sceneview/issues/2476)).
 *
 * @param id Stable identity used for selection. Survives a list rebuild — an index does
 *   not, and a streamed row landing mid-session must not shift what is armed.
 * @param assetLocation `assets/`-relative path for a bundled GLB, or a `file://…` URI for
 *   a staged streamed one. NEVER null: a streamed row whose download is still in flight
 *   carries its own bundled fallback here, so a tap is never silently swallowed.
 * @param pending `true` while a streamed row's download is in flight — drives the
 *   "Streaming …" wording on the bar and on the card. The row stays placeable.
 */
@Immutable
data class PlacementModel(
    val id: String,
    val displayName: String,
    val assetLocation: String,
    /**
     * The object's **real-world size** in metres — its longest dimension as it would be if
     * you put the real thing in the room. Fed to `ModelNode(scaleToUnits = …)`, so this is
     * literally what 100 % means on the pinch readout (#3326).
     *
     * Every row used to carry the same `0.3f`, which is where "AR placement doesn't feel
     * right" starts: a soldier, a fox, a lantern and a toy car all arrived as 30 cm
     * objects, so nothing in the room was ever the size the room says it should be. The
     * numbers here are the physical objects' sizes, not a framing convenience — and they
     * are declared per row rather than trusted from the glTF because these assets are not
     * authored in metres (the Khronos Fox is ~140 units long).
     */
    val realWorldSizeMeters: Float = 0.3f,
    val source: PlacementModelSource = PlacementModelSource.Bundled,
    val pending: Boolean = false,
)

/**
 * The one bundled catalogue both tap-to-place entry points offer.
 *
 * Before this list existed the AR View tab carried `AR_MODELS` (6 entries, helmet first)
 * and `ARPlacementDemo` carried `MODEL_CYCLE` (5 entries) — two hand-maintained lists for
 * one feature, which is the divergence #2482 is about. The surviving content is
 * `MODEL_CYCLE`'s, because it is the audited one: #2023 removed the Damaged Helmet from it
 * on the grounds that a helmet hovering over a floor reads as a test payload rather than
 * as content someone meant to put in the room. That audit never reached the AR View tab's
 * own list. It does now.
 *
 * Every entry is a grounded object — a character, a piece of furniture or a household item
 * — with a distinct silhouette and material, so cycling the picker visibly changes the room.
 *
 * **What the catalogue offers, and why (#3324).** Two rows used to be untextured low-poly
 * animals (`khronos_fox`, `shiba`): flat vertex colours and a single base-colour map, which
 * is exactly what "the default models aren't great" describes — they say nothing about what
 * Filament can render, and a fox in a living room is not a thing anyone places. They are
 * replaced by three Khronos glTF-Sample-Assets pieces that each drive a *different* material
 * model — sheen (velvet), sheen + specular, and iridescence + transmission + volume — so the
 * six rows now read as a small showroom rather than as a conformance suite. Both GLBs stay
 * in the APK: `SampleAssets` still uses them as offline fallbacks and `ARTerrainAnchorDemo`
 * loads the fox directly, so nothing is deleted, only re-curated.
 */
val BUNDLED_PLACEMENT_MODELS: List<PlacementModel> = listOf(
    // Sizes are the real objects', in metres, on their longest axis — see
    // PlacementModel.realWorldSizeMeters (#3326). The three Khronos furniture/tableware
    // rows are authored IN METRES and sit on y = 0, so their number here is the asset's
    // own measured bounding box rather than an estimate (checked against the GLB).
    PlacementModel(
        id = "soldier",
        displayName = "Soldier",
        assetLocation = "models/threejs_soldier.glb",
        realWorldSizeMeters = 1.8f, // an adult, standing
    ),
    PlacementModel(
        id = "velvet-sofa",
        displayName = "Velvet Sofa",
        assetLocation = "models/khronos_glam_velvet_sofa.glb",
        realWorldSizeMeters = 2.19f, // measured: 2.188 m wide
    ),
    PlacementModel(
        id = "sheen-chair",
        displayName = "Sheen Chair",
        assetLocation = "models/khronos_sheen_chair.glb",
        realWorldSizeMeters = 0.83f, // measured: 0.827 m wide
    ),
    PlacementModel(
        id = "lantern",
        displayName = "Lantern",
        assetLocation = "models/khronos_lantern.glb",
        realWorldSizeMeters = 1.6f, // the Khronos asset is a street lantern post
    ),
    PlacementModel(
        id = "olive-dish",
        displayName = "Olive Dish",
        assetLocation = "models/khronos_iridescent_dish.glb",
        realWorldSizeMeters = 0.53f, // measured: 0.532 m across
    ),
    PlacementModel(
        id = "toy-car",
        displayName = "Toy Car",
        assetLocation = "models/khronos_toy_car.glb",
        realWorldSizeMeters = 0.18f, // it is a toy — it should read as one
    ),
)

/**
 * Selection + sheet visibility for the canonical picker, hoisted out of both hosts.
 *
 * Hoisted rather than kept inside [TapToPlaceExperience] because the hosts read the
 * selection for their own chrome (the `ar-placement` demo's asset-source chip has to know
 * whether the armed row is streamed) and because it must survive the AR View tab's Reset,
 * which recreates the whole AR subtree.
 */
@Stable
class PlacementPickerState internal constructor(
    private val selectedIdState: MutableState<String>,
) {
    /** Id of the armed row — what the NEXT tap places. Read at tap time, never captured. */
    var selectedId: String
        get() = selectedIdState.value
        set(value) {
            selectedIdState.value = value
        }

    /** Whether the picker sheet is open. */
    var isSheetOpen: Boolean by mutableStateOf(false)
        private set

    fun openSheet() {
        isSheetOpen = true
    }

    fun dismissSheet() {
        isSheetOpen = false
    }
}

/**
 * Remembers a [PlacementPickerState]. The selected id is `rememberSaveable`, so a rotation
 * or a process death does not silently re-arm the first model behind the user's back.
 */
@Composable
fun rememberPlacementPickerState(initialSelectedId: String): PlacementPickerState {
    val selectedId = rememberSaveable { mutableStateOf(initialSelectedId) }
    return remember { PlacementPickerState(selectedId) }
}

/**
 * The armed row, or the first row when the saved selection is not in this catalogue (an id
 * can outlive a catalogue edit across a process death). Never throws, and never returns a
 * row the user cannot see in the sheet.
 */
fun List<PlacementModel>.armed(picker: PlacementPickerState): PlacementModel? =
    firstOrNull { it.id == picker.selectedId } ?: firstOrNull()

/**
 * The canonical bottom action bar of the tap-to-place experience: an extended FAB naming
 * exactly what the next tap will place (tapping it opens the picker) plus an optional
 * Reset that wipes every placement.
 *
 * Both hosts render *this* composable — the AR View tab floats it over the camera, the
 * `ar-placement` demo hands it to [io.github.sceneview.demo.DemoScaffold]'s `bottomOverlay`
 * slot so it stacks clear of the Settings FAB. The container differs because the two
 * screens *are* different containers; the control does not.
 */
@Composable
fun PlacementModelBar(
    model: PlacementModel?,
    onPickModel: () -> Unit,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtendedFloatingActionButton(
            onClick = onPickModel,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = PLACEMENT_BAR_HEIGHT),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            expanded = true,
            icon = {
                Icon(imageVector = Icons.Filled.ViewInAr, contentDescription = null)
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.ar_picker_model_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Text(
                        text = model?.let { modelBarLabel(it) }
                            ?: stringResource(R.string.ar_picker_no_model),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            },
        )

        if (onReset != null) {
            FilledIconButton(
                onClick = onReset,
                modifier = Modifier.size(PLACEMENT_BAR_HEIGHT),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.ar_reset_scene),
                )
            }
        }
    }
}

/** The name the bar and the status pill agree on — "Streaming X…" while X is downloading. */
@Composable
private fun modelBarLabel(model: PlacementModel): String =
    if (model.pending) {
        stringResource(R.string.ar_picker_streaming, model.displayName)
    } else {
        model.displayName
    }

/**
 * The canonical model picker sheet — one grid of cards, one selected state, applied the
 * moment it is tapped.
 *
 * This is the AR View tab's sheet (the richer of the two variants #2482 compared: a
 * scannable grid rather than a horizontally-scrolling chip strip), now shared, plus the
 * one thing the chip strip did better — saying which rows stream and which ship in the APK.
 */
@Composable
fun PlacementModelPickerSheet(
    models: List<PlacementModel>,
    picker: PlacementPickerState,
) {
    if (!picker.isSheetOpen) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = { picker.dismissSheet() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = SceneViewTokens.Space.md,
                vertical = SceneViewTokens.Space.sm,
            ),
        ) {
            Text(
                text = stringResource(R.string.ar_pick_a_model),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = SceneViewTokens.Space.sm),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = PICKER_CARD_MIN_SIZE),
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = PICKER_GRID_MAX_HEIGHT),
            ) {
                items(models.size) { index ->
                    val model = models[index]
                    PlacementModelCard(
                        model = model,
                        selected = model.id == picker.selectedId,
                        onClick = {
                            picker.selectedId = model.id
                            scope.launch {
                                sheetState.hide()
                                picker.dismissSheet()
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(SceneViewTokens.Space.md))
        }
    }
}

@Composable
private fun PlacementModelCard(
    model: PlacementModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.md)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        modifier = Modifier.border(
            width = if (selected) PICKER_CARD_SELECTED_BORDER else 0.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = shape,
        ),
        shape = shape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SceneViewTokens.Space.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PICKER_CARD_MEDIA_HEIGHT)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(SceneViewTokens.Radius.sm),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // The generated thumbnail of the row's own asset when there is one, the
                // generic AR glyph otherwise. Before #3324 every card rendered the same
                // glyph, so the grid was six identical tiles under six labels and the only
                // way to know what a row looked like was to place it. Streamed rows keep
                // the glyph: their bytes are not in the APK, so there is no thumbnail to
                // show that would be honest about what lands.
                val thumbnail = ModelThumbnails.resourceFor(
                    model.assetLocation.substringAfterLast('/').substringBeforeLast('.'),
                )
                if (thumbnail != null) {
                    Image(
                        painter = painterResource(thumbnail),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PICKER_CARD_MEDIA_HEIGHT)
                            .clip(RoundedCornerShape(SceneViewTokens.Radius.sm)),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.ViewInAr,
                        contentDescription = null,
                        modifier = Modifier.size(PICKER_CARD_ICON_SIZE),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            // Only the streamed rows carry a caption: "bundled" is the unremarkable
            // default, and a caption on every card would be noise the eye has to filter
            // before it can compare two models.
            if (model.source == PlacementModelSource.Streamed) {
                Text(
                    text = stringResource(
                        if (model.pending) {
                            R.string.ar_picker_source_streaming
                        } else {
                            R.string.ar_picker_source_streamed
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Height of the bar's two controls — the M3 minimum touch target for a primary action,
 * and the diameter that makes the Reset button read as its equal rather than as an
 * afterthought beside it.
 */
private val PLACEMENT_BAR_HEIGHT = 56.dp

// Picker-card geometry. Not `DESIGN.md` tokens, because none of these is one: they are the
// size of a model thumbnail and the height at which the grid stops growing and starts
// scrolling — component measurements, kept named and in one place rather than sprinkled as
// literals through the layout.
private val PICKER_CARD_MIN_SIZE = 110.dp
private val PICKER_CARD_MEDIA_HEIGHT = 80.dp
private val PICKER_CARD_ICON_SIZE = 36.dp
private val PICKER_CARD_SELECTED_BORDER = 2.dp
private val PICKER_GRID_MAX_HEIGHT = 480.dp
