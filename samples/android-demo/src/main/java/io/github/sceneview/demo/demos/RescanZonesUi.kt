package io.github.sceneview.demo.demos

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import com.google.android.filament.Engine
import com.google.android.filament.Material
import io.github.sceneview.SceneScope
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.RescanKind
import io.github.sceneview.demo.demos.internal.RescanSources
import io.github.sceneview.demo.demos.internal.RescanZone
import io.github.sceneview.demo.demos.internal.RescanZoneMesh
import io.github.sceneview.demo.demos.internal.ScanGround
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.loaders.MaterialLoader

/** The pill that lists the parts of a scan worth scanning again (#4736). */
internal const val RERUN_MODEL_RESCAN_TAG = "rerun_model_rescan"

/** One part's "Re-scan this part" in the list. */
internal const val RERUN_MODEL_RESCAN_ACTION_TAG = "rerun_model_rescan_action"

/**
 * The parts of a scan worth scanning again, the frames the result draws them with, and the
 * scan's [cloud] they were read from, which a re-scan lines up on.
 */
internal class RescanBuild(val zones: List<RescanZone>, val mesh: DebugMesh, val cloud: DenseCloud) {
    /**
     * "Re-scan this part" for [onRescan], `null` when there is none or the cloud has no normals
     * to line a re-scan up on.
     */
    fun picker(onRescan: ((RescanPick) -> Unit)?): ((RescanZone) -> Unit)? =
        onRescan?.takeIf { cloud.normals != null }?.let { pick -> { zone -> pick(RescanPick(zone, zones, cloud)) } }
}

/**
 * The parts of [source]'s scan worth scanning again (#4736), [ground] the floor read off its
 * model, or `null` when there are none or no points to read them from. Off the main thread: it
 * reads the whole cloud.
 */
internal fun rescanOf(source: RerunModelSource?, ground: ScanGround?): RescanBuild? = runCatching {
    val cloud = (source as? RerunModelSource.Live)?.cloud
        ?: (source as? RerunModelSource.Surfels)?.cloud
        ?: if (source is RerunModelSource.Synthetic) syntheticCloud() else null
    if (cloud == null || cloud.count == 0) return@runCatching null
    val started = System.nanoTime()
    val zones = RescanSources.zones(cloud, ground)
    Log.i(TAG, "${zones.size} zones from ${cloud.count} points in ${(System.nanoTime() - started) / NS_PER_MS} ms")
    zones.forEach { Log.i(TAG, "  $it") }
    zones.takeIf { it.isNotEmpty() }?.let { RescanBuild(it, RescanZoneMesh.of(it), cloud) }
}.onFailure { Log.w(TAG, "zones failed", it) }.getOrNull()

/**
 * The parts to scan again framed in the scan's own world space (#4736): one amber frame per
 * part, the `warning` amber the live map marks "still to scan" with, opaque and unlit so it reads
 * the same on the light and the dark stage, from the side in 3D as from above on the plan.
 */
@Composable
internal fun SceneScope.RescanZoneLayer(mesh: DebugMesh, engine: Engine, materialLoader: MaterialLoader) {
    // Remembered before the node, so Compose releases the material after the node destroyed the layer.
    val layer = remember(engine, materialLoader, mesh) {
        val material = materialLoader.createUnlitColorInstance(SceneViewTokens.ArOverlay.accentGuidance).also {
            it.setCullingMode(Material.CullingMode.NONE)
        }
        material to DebugLayerNode(engine, material, PRIORITY).also { it.upload(mesh) }
    }
    DisposableEffect(layer) { onDispose { materialLoader.destroyMaterialInstance(layer.first) } }
    key(layer) {
        Node(apply = { addChildNode(layer.second) })
    }
}

/**
 * How many parts to scan again, a pill beside the tour's: the frame drawn on the model, and the
 * count. A tap lists them.
 */
@Composable
internal fun RescanZonesPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val chrome = LocalStageChrome.current
    val shape = RoundedCornerShape(SceneViewTokens.Radius.full)
    val label = pluralStringResource(R.plurals.scan_rescan_count, count, count)
    Row(
        modifier = modifier
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .clip(shape)
            .background(chrome.card, shape)
            .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm)
            // Tagged before the semantics are set: a tag after them would be cleared with the rest.
            .testTag(RERUN_MODEL_RESCAN_TAG)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        ZoneSwatch()
        Text(text = count.toString(), style = SceneViewTokens.Type.caption, color = chrome.onCard)
    }
}

/** The amber frame the 3D view draws a part with, at text size. */
@Composable
private fun ZoneSwatch() {
    val amber = SceneViewTokens.ArOverlay.accentGuidance
    Canvas(Modifier.size(Space.md)) {
        val stroke = size.width * SWATCH_STROKE
        val inset = stroke / 2f
        drawRect(amber, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
    }
}

/**
 * The parts to scan again, each with its kind, why, and its own "Re-scan this part", which
 * [onRescan] starts; without it, the button is shown but not usable.
 */
@Composable
internal fun RescanZonesDialog(zones: List<RescanZone>, onDismiss: () -> Unit, onRescan: ((RescanZone) -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_rescan_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                zones.forEach { zone ->
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            ZoneSwatch()
                            Text(stringResource(titleOf(zone.kind)), style = SceneViewTokens.Type.card)
                        }
                        Text(zone.reason, style = SceneViewTokens.Type.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            onClick = { onRescan?.invoke(zone) },
                            enabled = onRescan != null,
                            modifier = Modifier.testTag(RERUN_MODEL_RESCAN_ACTION_TAG),
                        ) { Text(stringResource(R.string.scan_rescan_action)) }
                    }
                }
                Text(
                    stringResource(if (onRescan != null) R.string.scan_rescan_how else R.string.scan_rescan_unavailable),
                    style = SceneViewTokens.Type.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scan_rescan_close)) } },
    )
}

private fun titleOf(kind: RescanKind): Int = when (kind) {
    RescanKind.MissingWall -> R.string.scan_rescan_missing_wall
    RescanKind.FloorHole -> R.string.scan_rescan_floor_hole
    RescanKind.LowDensity -> R.string.scan_rescan_low_density
}

/** Over the ground's layers, under nothing: the frames are thin and must not hide in the slab. */
private const val PRIORITY = 2

/** The swatch's stroke, a share of its side: the frame's weight on the model at text size. */
private const val SWATCH_STROKE = 0.18f

private const val TAG = "ScanRescan"
private const val NS_PER_MS = 1_000_000L
