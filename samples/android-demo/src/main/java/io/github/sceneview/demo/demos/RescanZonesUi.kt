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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
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
import io.github.sceneview.demo.demos.internal.ResultFloor
import io.github.sceneview.demo.demos.internal.RoomMeasure
import io.github.sceneview.demo.demos.internal.RoomSurvey
import io.github.sceneview.demo.demos.internal.ScanGround
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.theme.StageChrome
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.loaders.MaterialLoader

/** The pill that lists the parts of a scan worth scanning again (#4736). */
internal const val RERUN_MODEL_RESCAN_TAG = "rerun_model_rescan"

/** One part's "Re-scan this part" in the list. */
internal const val RERUN_MODEL_RESCAN_ACTION_TAG = "rerun_model_rescan_action"

/**
 * The parts of a scan worth scanning again, the dashed frames the result draws them with over
 * their translucent [veil], the scan's [cloud] they were read from, which a re-scan lines up on,
 * and the room's [measure] read off the same cloud's walls, `null` when none stand (#4735).
 */
internal class RescanBuild(
    val zones: List<RescanZone>,
    val mesh: DebugMesh,
    val cloud: DenseCloud,
    val veil: DebugMesh = RescanZoneMesh.veil(zones),
    val measure: RoomMeasure? = null,
) {
    /**
     * "Re-scan this part" for [onRescan], `null` when there is none or the cloud has no normals
     * to line a re-scan up on.
     */
    fun picker(onRescan: ((RescanPick) -> Unit)?): ((RescanZone) -> Unit)? =
        onRescan?.takeIf { cloud.normals != null }?.let { pick -> { zone -> pick(RescanPick(zone, zones, cloud)) } }
}

/**
 * The parts of [source]'s scan worth scanning again (#4736), [ground] the floor read off its
 * model, and the room's dimensions read off the same walls (#4735); `null` when there is neither,
 * or no points to read them from. Off the main thread: it reads the whole cloud.
 */
internal fun rescanOf(source: RerunModelSource?, ground: ScanGround?): RescanBuild? = runCatching {
    val cloud = (source as? RerunModelSource.Live)?.cloud
        ?: (source as? RerunModelSource.Surfels)?.cloud
        ?: if (source is RerunModelSource.Synthetic) syntheticCloud() else null
    if (cloud == null || cloud.count == 0) return@runCatching null
    val started = System.nanoTime()
    // One survey for both: the zones' walls are the walls the dimensions are read off.
    val survey = RescanSources.surveyOf(cloud)
    val zones = RescanSources.zones(cloud, ground, survey)
    val measure = survey?.let(ResultFloor::measureOf)
    Log.i(TAG, "${zones.size} zones from ${cloud.count} points in ${(System.nanoTime() - started) / NS_PER_MS} ms")
    zones.forEach { Log.i(TAG, "  $it") }
    if (zones.isEmpty() && measure == null) null else RescanBuild(zones, RescanZoneMesh.of(zones), cloud, measure = measure)
}.onFailure { Log.w(TAG, "zones failed", it) }.getOrNull()

/**
 * The parts of a recording's scan still to scan, for the views that draw them without a re-scan —
 * the plan and the dollhouse (#4735): read off its dense [cloud], [survey] the room the view
 * already measured off it. Empty without a cloud, or when they cannot be read. Off the main thread.
 */
internal fun zonesOf(cloud: DenseCloud?, survey: RoomSurvey?): List<RescanZone> = runCatching {
    if (cloud == null || cloud.count == 0) return@runCatching emptyList()
    val ground = ScanGround.of(cloud.positions, cloud.normals, cloud.count)
    RescanSources.zones(cloud, ground, survey)
}.onFailure { Log.w(TAG, "zones failed", it) }.getOrDefault(emptyList())

/**
 * The parts to scan again in the scan's own world space (#4736, #4735): each part a dashed frame
 * over a translucent veil of its box, in the `warning` amber the live map marks "still to scan"
 * with ([ZoneInk]): the frame `warning-strong` on the light stage, where `warning` is too faint
 * for a thin line. Unlit; the frame opaque and drawn from both sides, the veil faint enough to
 * leave the scan under it readable. A part marked "not scanned yet", not a hatch on the model.
 */
@Composable
internal fun SceneScope.RescanZoneLayer(mesh: DebugMesh, veil: DebugMesh, engine: Engine, materialLoader: MaterialLoader) {
    val ink = ZoneInk.frame(LocalStageChrome.current)
    // Remembered before the nodes, so Compose releases the materials after the nodes destroyed the layers.
    val frame = remember(engine, materialLoader, mesh, ink) {
        val material = materialLoader.createUnlitColorInstance(ink).also {
            it.setCullingMode(Material.CullingMode.NONE)
        }
        material to DebugLayerNode(engine, material, PRIORITY).also { it.upload(mesh) }
    }
    DisposableEffect(frame) { onDispose { materialLoader.destroyMaterialInstance(frame.first) } }
    // Translucent: its outer faces only, so a box never doubles its own amber.
    val fill = remember(engine, materialLoader, veil) {
        val material = materialLoader.createUnlitColorInstance(ZoneInk.veil)
            .also { it.setCullingMode(Material.CullingMode.BACK) }
        material to DebugLayerNode(engine, material, PRIORITY).also { it.upload(veil) }
    }
    DisposableEffect(fill) { onDispose { materialLoader.destroyMaterialInstance(fill.first) } }
    key(frame, fill) {
        Node(apply = {
            addChildNode(fill.second)
            addChildNode(frame.second)
        })
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

/** The dashed amber frame over its veil the result views draw a part with, at text size. */
@Composable
internal fun ZoneSwatch() {
    val frame = ZoneInk.frame(LocalStageChrome.current)
    Canvas(Modifier.size(Space.md)) {
        val stroke = size.width * SWATCH_STROKE
        val inset = stroke / 2f
        val side = size.width - stroke
        drawRect(ZoneInk.veil, Offset(inset, inset), Size(side, side))
        // Each side a dash, its middle a gap: a frame left open, as on the model.
        val dash = side * SWATCH_DASH
        drawRect(
            frame, Offset(inset, inset), Size(side, side),
            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, side - dash), dash / 2f)),
        )
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

/** The swatch's dash, a share of its side, centred on each corner. */
private const val SWATCH_DASH = 0.6f

/**
 * The veil's opacity over the scan: enough to tint the part amber in light and in dark, little
 * enough to leave what was caught of it readable (DESIGN.md, `warning`).
 */
internal const val VEIL_ALPHA = 0.22f

/**
 * The amber a part "not scanned yet" is drawn in, on the stage it stands on (#4735): the veil over
 * it, and the frame round it and round its swatch.
 */
internal object ZoneInk {
    /** The veil over a part: `warning` at [VEIL_ALPHA], on either stage. */
    val veil: Color = SceneViewTokens.ArOverlay.accentGuidance.copy(alpha = VEIL_ALPHA)

    /**
     * `warning-strong` (DESIGN.md): `warning` darkened for a thin line on a light surface, where
     * `warning` itself is 1.9:1 on the ground.
     */
    val warningStrong = Color(0xFFB45309)

    /**
     * The frame round a part on [chrome]'s stage: `warning` on the dark one, `warning-strong` on
     * the light one. 3:1 or more there against the ground, the plan's glass, the pill's card and
     * the veil over each (WCAG 1.4.11): 3.9:1 at the least in light, 4:1 in dark.
     */
    fun frame(chrome: StageChrome): Color =
        if (chrome.ground.luminance() > LIGHT_GROUND) warningStrong else SceneViewTokens.ArOverlay.accentGuidance

    /** Over this luminance a stage is a light one, as [io.github.sceneview.demo.theme.themedStageChrome] tells them. */
    private const val LIGHT_GROUND = 0.5f
}

private const val TAG = "ScanRescan"
private const val NS_PER_MS = 1_000_000L
