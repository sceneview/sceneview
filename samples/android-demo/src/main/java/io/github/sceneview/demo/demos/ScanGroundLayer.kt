package io.github.sceneview.demo.demos

import android.os.SystemClock
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import io.github.sceneview.SceneScope
import io.github.sceneview.demo.demos.internal.ArDebugGeometry
import io.github.sceneview.demo.demos.internal.ArDebugStyle
import io.github.sceneview.demo.demos.internal.DebugLayer
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.ScanGround
import io.github.sceneview.demo.demos.internal.ScanGroundMeshes
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setColor

/**
 * The colours of the ground a scan's result stands on (#4719), from the stage's own: the slab is
 * the stage's card, like the dollhouse's plinth.
 *
 * - On a light stage the edge is a shade darker than the slab.
 * - On a dark stage the card is near-black like the ground, so the slab would read as a hole in
 *   it: the slab takes the theme's `surface-container-high`, its edge `outline-variant`, so both
 *   lift off the dark ground.
 *
 * The floor the scan did not see is not marked on it: holes read as the slab, and the parts worth
 * scanning again have their own pill (#4736). Every colour opaque, composited over what it lies
 * on: unlit and linear-mapped (`configureForDebug`), each shows at its token's value, and a theme
 * switch repaints the same materials without changing their kind.
 */
internal data class ScanGroundColors(
    val slab: Color,
    val edge: Color,
    /** The live map's floor still to scan (#4810); the result draws none, so it is the slab there. */
    val missing: Color = slab,
    val hatch: Color = edge,
) {
    companion object {
        /** How far the slab's edge is shaded from its top: the dollhouse plinth's shade. */
        const val EDGE_SHADE = 0.28f

        /** How much amber a missing tile takes on a light stage: a tint, so the hatching reads on it. */
        const val MISSING_TINT = 0.2f

        /** How much of `on-surface` a missing tile takes on a dark stage: light, like the light stage's. */
        const val DARK_HOLE_VEIL = 0.55f

        private const val HALF = 0.5f

        /**
         * The colours on the stage of [card] over [ground]; [container] and [outline] are the
         * theme's `surface-container-high` and `outline-variant`, used on a dark stage only.
         */
        fun of(card: Color, ground: Color, container: Color, outline: Color): ScanGroundColors {
            val stage = card.compositeOver(ground)
            val dark = stage.luminance() < HALF
            val slab = if (dark) container.compositeOver(ground) else stage
            return ScanGroundColors(
                slab = slab,
                edge = if (dark) outline.compositeOver(slab) else lerp(slab, Color.Black, EDGE_SHADE),
            )
        }

        /**
         * The live 3D map's colours (#4810): [of]'s, and the floor still to scan hatched in the
         * guidance amber — on a light stage a tile is the slab tinted amber, on a dark one a light
         * [onSurface] veil over the slab, so it lifts off the dark ground.
         */
        fun live(card: Color, ground: Color, container: Color, outline: Color, onSurface: Color): ScanGroundColors {
            val base = of(card, ground, container, outline)
            val dark = card.compositeOver(ground).luminance() < HALF
            val amber = SceneViewTokens.ArOverlay.accentGuidance
            return base.copy(
                missing = if (dark) {
                    onSurface.copy(alpha = DARK_HOLE_VEIL).compositeOver(base.slab)
                } else {
                    lerp(base.slab, amber, MISSING_TINT)
                },
                hatch = amber,
            )
        }

        /** The colours of the stage and theme of the moment. */
        @Composable
        fun current(): ScanGroundColors {
            val chrome = LocalStageChrome.current
            val scheme = MaterialTheme.colorScheme
            return of(chrome.card, chrome.ground, scheme.surfaceContainerHigh, scheme.outlineVariant)
        }

        /** [live]'s colours for the stage and theme of the moment. */
        @Composable
        fun currentLive(): ScanGroundColors {
            val chrome = LocalStageChrome.current
            val scheme = MaterialTheme.colorScheme
            return live(chrome.card, chrome.ground, scheme.surfaceContainerHigh, scheme.outlineVariant, scheme.onSurface)
        }
    }
}

/** The four layers of [ScanGroundMeshes] and their materials. Main thread, as every Filament call. */
private class ScanGroundNodes(private val engine: Engine, private val materialLoader: MaterialLoader, colors: ScanGroundColors) {
    private val materials = mutableListOf<MaterialInstance>()
    private var colors = colors

    private fun node(color: Color): DebugLayerNode {
        val material = materialLoader.createUnlitColorInstance(color).also {
            it.setCullingMode(Material.CullingMode.NONE)
            materials += it
        }
        return DebugLayerNode(engine, material, PRIORITY)
    }

    val slab = node(colors.slab)
    val edge = node(colors.edge)
    val missing = node(colors.missing)
    val hatch = node(colors.hatch)
    val nodes = listOf(slab, edge, missing, hatch)

    /** The meshes last uploaded: the live ground uploads a new set only. */
    var shown: ScanGroundMeshes? = null

    fun upload(meshes: ScanGroundMeshes) {
        slab.upload(meshes.slab)
        edge.upload(meshes.edge)
        missing.upload(meshes.missingFill)
        hatch.upload(meshes.hatch)
    }

    /** A theme switch: the same nodes, repainted. Every colour is opaque, so no material changes kind. */
    fun paint(colors: ScanGroundColors) {
        if (colors == this.colors) return
        this.colors = colors
        materials[0].setColor(colors.slab)
        materials[1].setColor(colors.edge)
        materials[2].setColor(colors.missing)
        materials[3].setColor(colors.hatch)
    }

    fun destroy() = materials.forEach { materialLoader.destroyMaterialInstance(it) }

    companion object {
        const val PRIORITY = 1
    }
}

/**
 * The ground under a scan's result (#4719): [meshes] — the slab and its edge — in [colors], in
 * the scan's own world space next to the model.
 */
@Composable
internal fun SceneScope.ScanGroundLayer(
    meshes: ScanGroundMeshes,
    engine: Engine,
    materialLoader: MaterialLoader,
    colors: ScanGroundColors,
) {
    // Remembered before the node, so Compose releases the materials after the node destroyed the layers.
    val layers = remember(engine, materialLoader, meshes) {
        val started = SystemClock.elapsedRealtimeNanos()
        ScanGroundNodes(engine, materialLoader, colors).also {
            it.upload(meshes)
            // The ground's cost on the main thread, once, and what it adds to every frame.
            Log.i(
                TAG,
                "ground: ${meshes.triangles} triangles, uploaded in " +
                    "${(SystemClock.elapsedRealtimeNanos() - started) / NS_PER_US} µs",
            )
        }
    }
    DisposableEffect(layers) { onDispose { layers.destroy() } }
    SideEffect { layers.paint(colors) }
    key(layers) {
        Node(apply = { layers.nodes.forEach { addChildNode(it) } })
    }
}

/**
 * The live 3D map's ground: the same four layers as [ScanGroundLayer], made once and refilled with
 * each new [meshes] instead of rebuilt, since the coverage grows all through a capture. `null`
 * meshes draw nothing.
 */
@Composable
internal fun SceneScope.ScanLiveGroundLayer(
    meshes: ScanGroundMeshes?,
    engine: Engine,
    materialLoader: MaterialLoader,
    colors: ScanGroundColors,
) {
    val layers = remember(engine, materialLoader) { ScanGroundNodes(engine, materialLoader, colors) }
    DisposableEffect(layers) { onDispose { layers.destroy() } }
    SideEffect {
        layers.paint(colors)
        if (meshes != null && meshes !== layers.shown) {
            layers.upload(meshes)
            layers.shown = meshes
        }
        layers.nodes.forEach { it.isVisible = meshes != null }
    }
    key(layers) {
        Node(apply = { layers.nodes.forEach { addChildNode(it) } })
    }
}

private const val TAG = "ScanGround"
private const val NS_PER_US = 1_000L

/**
 * The replay's floor grid under a scan's result (QA 2026-10-22): the grid of the replay's 3D view
 * — Polycam's, Reality Composer's — round the slab, fading out with the distance from the room, so
 * the result shows the room's scale as the replay does. Just under the slab's top: the slab hides
 * it inside the room, the grid carries on outside. In the stage's grid colours, light and dark.
 *
 * [style] sizes the lines for the camera the result opens on.
 */
@Composable
internal fun SceneScope.ScanGridLayer(
    ground: ScanGround,
    style: ArDebugStyle,
    engine: Engine,
    materialLoader: MaterialLoader,
) {
    val chrome = LocalStageChrome.current
    val layers = remember(engine, materialLoader) { ScanGridNodes(engine, materialLoader) }
    DisposableEffect(layers) { onDispose { layers.destroy() } }
    // On the main dispatcher, as Filament needs; again whenever the ground or the style changes.
    LaunchedEffect(layers, ground, style) { layers.upload(ground, style) }
    SideEffect { layers.paint(chrome.debug.gridMinor, chrome.debug.gridMajor) }
    key(layers) {
        Node(apply = { layers.nodes.forEach { addChildNode(it) } })
    }
}

/** The grid's two layers on `replay_grid.mat`, as the replay draws them. Main thread. */
private class ScanGridNodes(engine: Engine, private val materialLoader: MaterialLoader) {
    private val material = materialLoader.createMaterial(GRID_MATERIAL)
    private val minorMaterial = materialLoader.createInstance(material)
    private val majorMaterial = materialLoader.createInstance(material)
    private val minor = DebugLayerNode(engine, minorMaterial, ScanGroundNodes.PRIORITY)
    private val major = DebugLayerNode(engine, majorMaterial, ScanGroundNodes.PRIORITY)
    val nodes = listOf(minor, major)
    private var colors: Pair<Color, Color>? = null

    fun upload(ground: ScanGround, style: ArDebugStyle) {
        val bounds = floatArrayOf(
            ground.originX, ground.floorY, ground.originZ,
            ground.originX + ground.columns * ground.cellM, ground.floorY, ground.originZ + ground.rows * ground.cellM,
        )
        val meshes = HashMap<DebugLayer, DebugMesh>()
        val y = ground.floorY - ScanGroundMeshes.SLAB_DROP_M - UNDER_SLAB_M
        ArDebugGeometry.buildStage(bounds, y, style) { meshes.getOrPut(it) { DebugMesh() } }
        minor.upload(meshes[DebugLayer.GridMinor])
        major.upload(meshes[DebugLayer.GridMajor])
        val fade = ArDebugGeometry.gridFade(bounds)
        listOf(minorMaterial, majorMaterial).forEach {
            it.setParameter("fade", fade.centreX, fade.centreZ, fade.start, fade.end)
        }
    }

    fun paint(minorColor: Color, majorColor: Color) {
        if (colors == minorColor to majorColor) return
        colors = minorColor to majorColor
        minorMaterial.setColor(minorColor)
        majorMaterial.setColor(majorColor)
    }

    /** After the nodes left the scene: the instances, then their material. */
    fun destroy() {
        materialLoader.destroyMaterialInstance(minorMaterial)
        materialLoader.destroyMaterialInstance(majorMaterial)
        materialLoader.destroyMaterial(material)
    }

    companion object {
        const val GRID_MATERIAL = "materials/replay_grid.filamat"

        /** How far under the slab's top the grid lies, so the slab hides it with no z-fighting. */
        const val UNDER_SLAB_M = 0.005f
    }
}
