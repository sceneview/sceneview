package io.github.sceneview.demo.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import io.github.sceneview.SceneScope
import io.github.sceneview.demo.demos.internal.ArDebugStyle
import io.github.sceneview.demo.demos.internal.SpaceFit
import io.github.sceneview.demo.demos.internal.SpaceFitDrawing
import io.github.sceneview.demo.demos.internal.SpaceFitMeshes
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.StageChrome
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a space's measure is drawn in, on its model and beside its figures: the [inside]'s edges
 * in the stage's plain ink, each thing in the way in the caution tone ([obstacle]), the box that
 * fits in the accent ([box]) over a wash of it ([boxFill]). Three hues, so none is taken for another.
 */
internal data class SpaceFitColors(val inside: Color, val obstacle: Color, val box: Color, val boxFill: Color) {
    companion object {
        /** The colours on [stage], the chrome of the ground the model stands on. */
        fun of(stage: StageChrome): SpaceFitColors {
            // As the part of a floor still to scan: `warning-strong` on a light stage, `warning` on a dark one.
            val caution = if (stage.ground.luminance() > HALF) ScanGroundColors.TO_SCAN_STRONG else SceneViewTokens.ArOverlay.accentGuidance
            return SpaceFitColors(
                inside = stage.debug.otherOutline,
                obstacle = caution,
                box = stage.accent,
                boxFill = stage.accent.copy(alpha = FILL_ALPHA),
            )
        }

        private const val HALF = 0.5f

        /** The box's faces let the model show through them: it stands inside it. */
        private const val FILL_ALPHA = 0.28f
    }
}

/**
 * A space's measure on its model ([SpaceFit]): the inside it found, what is in the way, and the
 * box that fits, in [SpaceFitColors]. Drawn through the model: the box is inside a cupboard,
 * whichever side the model is looked at from. Edges sized for [style]'s camera.
 */
@Composable
internal fun SceneScope.SpaceFitLayer(fit: SpaceFit, style: ArDebugStyle, engine: Engine, materialLoader: MaterialLoader) {
    val colors = SpaceFitColors.of(LocalStageChrome.current)
    // Remembered before the node, so Compose releases the materials after the node destroyed the layers.
    val layer = remember(engine, materialLoader) { SpaceFitNodes(engine, materialLoader, colors) }
    DisposableEffect(layer) { onDispose { layer.destroy() } }
    SideEffect { layer.paint(colors) }
    val radius = style.outlineHalfWidth
    LaunchedEffect(layer, fit, radius) {
        layer.upload(withContext(Dispatchers.Default) { SpaceFitDrawing.meshes(fit, radius) })
    }
    key(layer) {
        Node(apply = { layer.nodes.forEach { addChildNode(it) } })
    }
}

/** The four layers of [SpaceFitMeshes] and their materials. Main thread, as every Filament call. */
private class SpaceFitNodes(private val engine: Engine, private val materialLoader: MaterialLoader, colors: SpaceFitColors) {
    private val materials = mutableListOf<MaterialInstance>()
    private var colors = colors

    private fun node(color: Color, priority: Int): DebugLayerNode {
        val material = materialLoader.createUnlitColorInstance(color).also {
            it.setCullingMode(Material.CullingMode.NONE)
            // Over the model and through it, never cut by its walls.
            it.setDepthCulling(false)
            // An edge writes its depth: the stage's backdrop is drawn last, wherever nothing
            // was, and would take back every stretch of it seen through a hole in the scan.
            it.setDepthWrite(priority == EDGE_PRIORITY)
            materials += it
        }
        return DebugLayerNode(engine, material, priority)
    }

    private val boxFaces = node(colors.boxFill, FILL_PRIORITY)
    private val inside = node(colors.inside, EDGE_PRIORITY)
    private val obstacles = node(colors.obstacle, EDGE_PRIORITY)
    private val boxEdges = node(colors.box, EDGE_PRIORITY)
    val nodes = listOf(boxFaces, inside, obstacles, boxEdges)

    fun upload(meshes: SpaceFitMeshes) {
        boxFaces.upload(meshes.boxFaces)
        inside.upload(meshes.inside)
        obstacles.upload(meshes.obstacles)
        boxEdges.upload(meshes.boxEdges)
    }

    /** A theme switch: the same nodes, repainted. The wash stays a wash, so no material changes kind. */
    fun paint(colors: SpaceFitColors) {
        if (colors == this.colors) return
        this.colors = colors
        materials[0].setColor(colors.boxFill)
        materials[1].setColor(colors.inside)
        materials[2].setColor(colors.obstacle)
        materials[3].setColor(colors.box)
    }

    fun destroy() = materials.forEach { materialLoader.destroyMaterialInstance(it) }

    private companion object {
        /** After the model (Filament's default, 4), which these are drawn over: the wash, then the edges. */
        const val FILL_PRIORITY = 6
        const val EDGE_PRIORITY = 7
    }
}
