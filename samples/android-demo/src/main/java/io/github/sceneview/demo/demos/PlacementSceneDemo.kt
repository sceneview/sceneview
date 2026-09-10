@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.demos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sceneview.Scene
import io.github.sceneview.ar.PlacementController
import io.github.sceneview.ar.PlacementReticleVisual
import io.github.sceneview.ar.PlacementScene
import io.github.sceneview.ar.ReticlePhase
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * One-call AR — the [PlacementScene] showcase
 * ([#1765](https://github.com/sceneview/sceneview/issues/1765)).
 *
 * ## What #3568 changed, and why the demo still exists
 *
 * The maintainer used this screen on a shipped build and could not say what it was for; his
 * note was "il est peut-être à supprimer". The keep-or-delete question has one honest test —
 * does it show an SDK capability its neighbours do not? It does, and it is the only screen
 * that does: `placement-scene` is the catalogue's **only** exercise of the public one-call
 * [PlacementScene] composable. [ARPlacementDemo] deliberately hand-rolls the same flow out of
 * the low-level primitives (`TapToPlaceArSession`, ~600 lines) to show what they are, and
 * `wall-placement` demonstrates the vertical `WallPlacementScene`. `PlacementScene` is also
 * the first snippet `llms.txt` gives for AR placement. Deleting the demo would leave the
 * SDK's flagship AR entry point with nothing runnable behind it.
 *
 * So the code was never the problem — the screen was. It opened straight into a camera, with
 * the only explanation buried in a Settings sheet nobody opens before using a screen, and
 * from the outside it was indistinguishable from `Tap to Place`. It now follows the same
 * two-phase shape as its neighbour ([PlacementChooserScreen][io.github.sceneview.demo.common.placement.PlacementChooserScreen]):
 * a still, themed screen that states the subject — *this entire camera screen is one call* —
 * and only then opens the camera. That intro is also the one half of the demo that can be
 * looked at on an emulator, since there is no camera HAL there (#2754).
 */
@Composable
fun PlacementSceneDemo(onBack: () -> Unit) {
    var inAr by rememberSaveable { mutableStateOf(false) }

    // Back from the camera lands on the intro with the demo still open — the same back ladder
    // `Tap to Place` uses, so AR is a destination you arrive at, never an entry point.
    BackHandler(enabled = inAr) { inAr = false }

    if (!inAr) {
        PlacementSceneIntro(onBack = onBack, onOpenAr = { inAr = true })
    } else {
        PlacementSceneAr(onBack = { inAr = false })
    }
}

/**
 * The pre-AR half: what this screen demonstrates, in one screen, before the camera opens.
 *
 * Renders the **real** [PlacementReticleVisual] in two plain non-AR [Scene]s — one over a pale
 * ground, one over a dark one — so the cursor the user is about to meet is introduced, and so
 * the #3570 redesign has a surface that can be captured without a camera.
 */
@Composable
private fun PlacementSceneIntro(onBack: () -> Unit, onOpenAr: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.demo_placement_scene_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SceneViewTokens.Space.md)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
        ) {
            Text(
                text = stringResource(R.string.placement_scene_headline),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = SceneViewTokens.Space.md),
            )
            Text(
                text = stringResource(R.string.placement_scene_teaches),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The claim, shown rather than described.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.placement_scene_snippet),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(SceneViewTokens.Space.md),
                )
            }

            Text(
                text = stringResource(R.string.placement_scene_vs_neighbours),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.placement_scene_reticle_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                ReticleSwatch(
                    phase = ReticlePhase.SEARCHING,
                    label = stringResource(R.string.placement_scene_reticle_searching),
                    ground = PALE_GROUND,
                    modifier = Modifier.weight(1f),
                )
                ReticleSwatch(
                    phase = ReticlePhase.READY,
                    label = stringResource(R.string.placement_scene_reticle_ready),
                    ground = DARK_GROUND,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(R.string.placement_scene_reticle_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onOpenAr,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = SceneViewTokens.Space.lg),
            ) {
                Icon(Icons.Filled.ViewInAr, contentDescription = null)
                Text(
                    text = stringResource(R.string.placement_scene_cta),
                    modifier = Modifier.padding(start = SceneViewTokens.Space.sm),
                )
            }
        }
    }
}

/** A pale floor and a dark one — the two grounds an AR cursor actually has to survive. */
private val PALE_GROUND = Color(0xFFE7E3DC)
private val DARK_GROUND = Color(0xFF2A2E33)

/** Uniform blow-up that brings the life-sized reticle into a thumbnail-sized viewport. */
private const val SWATCH_SCALE = 16f

/**
 * One reticle phase, rendered by the production composable in a non-AR [Scene] over [ground].
 *
 * The reticle lies in the XZ plane with +Y up, so the node is tilted back 60° to read as a
 * cursor on a receding floor rather than an edge-on line.
 */
@Composable
private fun ReticleSwatch(
    phase: ReticlePhase,
    label: String,
    ground: Color,
    modifier: Modifier = Modifier,
) {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = ground,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.35f),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Scene(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    materialLoader = materialLoader,
                    isOpaque = false,
                ) {
                    // The reticle is life-sized (an 11 cm ring), and the default camera sits
                    // metres away, so at 1:1 it is a two-pixel speck in a thumbnail. Scale is
                    // uniform, so this is the real geometry — proportions, ring thickness and
                    // halo ratio are exactly what AR draws, just held closer to the lens.
                    Node(rotation = Rotation(x = -60f), scale = Scale(SWATCH_SCALE)) {
                        PlacementReticleVisual(
                            materialLoader = materialLoader,
                            phase = phase,
                        )
                    }
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = SceneViewTokens.Space.xs),
        )
    }
}

/** The camera half — the single [PlacementScene] call the intro just claimed it was. */
@Composable
private fun PlacementSceneAr(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo with
    // `--es ar_playback_file <path>` (#1576). `null` for every normal launch, so live AR is
    // completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Keep the controller in the parent so the "Clear all" button (outside the AR scope) can
    // reach it. PlacementScene also `remember`s its own controller when none is passed via
    // `content`; here we surface it so the demo can drive a counter + clear button.
    val controllerHolder = remember { mutableStateOf<PlacementController?>(null) }

    DemoScaffold(
        title = stringResource(R.string.demo_placement_scene_title),
        onBack = onBack,
        controls = {
            Text(
                text = stringResource(R.string.placement_scene_teaches),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = { controllerHolder.value?.clear() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = SceneViewTokens.Space.sm),
            ) {
                Text(stringResource(R.string.placement_scene_clear))
            }
        },
        topOverlay = {
            // Placed-count pill, driven by the controller's Compose-observable count.
            // The bottom onboarding hint is handled by PlacementScene's built-in coaching
            // guide (`coaching = true`), so the demo only surfaces the placed-count.
            PlacedCountPill(controllerHolder.value?.count ?: 0)
        },
    ) {
        PlacementScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            playbackDataset = arPlaybackDataset,
            // Modern consumer-AR placement UX: onboarding guide while searching, a ring
            // reticle that flips to "ready" on a surface, and a contact shadow under each
            // placed model. The plane grid fades after the first placement (default).
            coaching = true,
            groundShadows = true,
            onPlaced = { anchor ->
                // Declare what rides each placed anchor. PlacementScene already created the
                // Anchor from the tapped hit — the caller just attaches content to it.
                AnchorNode(anchor = anchor) {
                    val instance =
                        rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")
                    instance?.let {
                        ModelNode(
                            modelInstance = it,
                            scaleToUnits = 0.3f,
                        )
                    }
                }
            },
            content = { controller ->
                // Surface the controller so the parent's Clear all button can reach it.
                controllerHolder.value = controller
            },
        )
    }
}

/** "X models placed" pill, rendered in the scaffold's `topOverlay` slot. */
@Composable
private fun PlacedCountPill(count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.7f),
        contentColor = Color.White,
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = if (count == 1) {
                stringResource(R.string.placement_scene_count_one)
            } else {
                stringResource(R.string.placement_scene_count_other, count)
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
