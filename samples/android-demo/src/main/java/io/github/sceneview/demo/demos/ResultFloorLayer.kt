package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.ColorInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLocale
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import io.github.sceneview.EngineDestroyQueue
import io.github.sceneview.SceneScope
import io.github.sceneview.demo.demos.internal.ArDebugGeometry
import io.github.sceneview.demo.demos.internal.ArDebugStyle
import io.github.sceneview.demo.demos.internal.DebugLayer
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.GridFade
import io.github.sceneview.demo.demos.internal.MeasureDrawing
import io.github.sceneview.demo.demos.internal.MeasureSize
import io.github.sceneview.demo.demos.internal.RoomMeasure
import io.github.sceneview.demo.demos.internal.scanLabels
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setColor
import io.github.sceneview.material.setTexture
import io.github.sceneview.texture.ImageTexture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round

/**
 * The dimensioned grid every result view stands its room on (#4735), as the replay and the 3D
 * model draw it: a line every [ArDebugGeometry.GRID_CELL_M], a bolder one every metre, run out past
 * [bounds] (`[minX, y, minZ, maxX, y, maxZ]`) until it fades into the stage. Lines sized for
 * [style]'s camera, in the stage's grid colours, light or dark. Built off the main thread, handed
 * to Filament on it.
 */
@Composable
internal fun SceneScope.ResultGridLayer(
    bounds: FloatArray,
    style: ArDebugStyle,
    engine: Engine,
    materialLoader: MaterialLoader,
    priority: Int = GRID_PRIORITY,
) {
    val chrome = LocalStageChrome.current
    // Remembered before the node, so Compose releases the materials after the node destroyed the layers.
    val grid = remember(engine, materialLoader, priority) { ResultGridNodes(engine, materialLoader, priority) }
    DisposableEffect(grid) { onDispose { grid.destroy() } }
    val at = bounds.toList()
    LaunchedEffect(grid, at, style) {
        val meshes = withContext(Dispatchers.Default) { ResultGridNodes.meshes(bounds, style) }
        grid.upload(meshes, ArDebugGeometry.gridFade(bounds))
    }
    SideEffect { grid.paint(chrome.debug.gridMinor, chrome.debug.gridMajor) }
    key(grid) {
        Node(apply = { grid.nodes.forEach { addChildNode(it) } })
    }
}

/**
 * The room's dimensions on its grid (#4735), as the replay writes them on the floor: on the two
 * [sides] facing the viewer ([FacingSides]), a dimension line outside the wall with its length
 * beyond it, at height [y], in the floor outline's ink over a halo of the stage's ground. Sized for
 * [style]'s camera, rebuilt only past a 5 % step of zoom; [extraOffset] metres further out than the
 * replay's, for a room on a plinth.
 */
@Suppress("LongParameterList")
@Composable
internal fun SceneScope.ResultMeasureLayer(
    measure: RoomMeasure,
    y: Float,
    style: ArDebugStyle,
    sides: IntArray,
    engine: Engine,
    materialLoader: MaterialLoader,
    extraOffset: Float = 0f,
) {
    val chrome = LocalStageChrome.current
    // `LocalLocale`, observable: the labels follow a change of the device's language.
    val labels = measure.scanLabels(LocalLocale.current.platformLocale)
    val ink = chrome.debug.floorOutline.toArgb()
    val halo = chrome.ground.toArgb()
    val atlas = remember(labels, ink, halo) { MeasureAtlas.draw(labels, ink, halo) }
    // Remembered before the node, so Compose releases the material after the node destroyed the layer.
    val layer = remember(engine, materialLoader) { ResultMeasureNodes(engine, materialLoader, atlas.bitmap) }
    DisposableEffect(layer) { onDispose { layer.destroy() } }
    SideEffect { layer.retexture(atlas.bitmap) }
    val step = round(ln(style.metresPerPixel.coerceAtLeast(MIN_METRES_PER_PIXEL)) / MeasureSize.ZOOM_STEP)
    LaunchedEffect(layer, measure, y, step, sides.toList(), atlas, extraOffset, style.outlineHalfWidth) {
        val mesh = withContext(Dispatchers.Default) {
            val base = MeasureSize.at(exp(step * MeasureSize.ZOOM_STEP))
            val size = MeasureSize(base.textHeight, base.offset + extraOffset)
            DebugMesh().also { mesh ->
                for (side in sides) {
                    MeasureDrawing.addDimension(
                        mesh, measure, side, y, size, style.outlineHalfWidth, atlas.labelWidths[side % 2],
                    )
                }
            }
        }
        layer.upload(mesh)
    }
    key(layer) {
        Node(apply = { addChildNode(layer.node) })
    }
}

/**
 * The two sides of a room's dimensions a viewer reads, the width's and the depth's whose outside
 * faces the eye ([MeasureDrawing.sidesFacing]): changed only when the camera turns past a corner,
 * so a drag round the room rebuilds them twice a turn, not on every frame.
 */
@Stable
internal class FacingSides {
    var sides: IntArray by mutableStateOf(intArrayOf(0, 1))
        private set

    /** [measure]'s sides facing the eye at ([eyeX], [eyeZ]), in the measure's frame. On a frame. */
    fun look(measure: RoomMeasure?, eyeX: Float, eyeZ: Float) {
        measure ?: return
        val next = MeasureDrawing.sidesFacing(measure, eyeX, eyeZ)
        if (!next.contentEquals(sides)) sides = next
    }
}

/** Under everything the result draws on its floor: the grid is the stage. */
internal const val GRID_PRIORITY = 0

/** Over the grid, the floor and the parts still to scan, as the replay draws them. */
private const val MEASURE_PRIORITY = 4

private const val MIN_METRES_PER_PIXEL = 1e-6f

/** The grid's two layers on `replay_grid.mat`, as the replay draws them. Main thread. */
private class ResultGridNodes(engine: Engine, private val materialLoader: MaterialLoader, priority: Int) {
    private val material = materialLoader.createMaterial(GRID_MATERIAL)
    private val minorMaterial = materialLoader.createInstance(material)
    private val majorMaterial = materialLoader.createInstance(material)
    private val minor = DebugLayerNode(engine, minorMaterial, priority)
    private val major = DebugLayerNode(engine, majorMaterial, priority)
    val nodes = listOf(minor, major)
    private var colors: Pair<Color, Color>? = null

    fun upload(meshes: Map<DebugLayer, DebugMesh>, fade: GridFade) {
        minor.upload(meshes[DebugLayer.GridMinor])
        major.upload(meshes[DebugLayer.GridMajor])
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

        /** The grid's lines round [bounds], at their height. Plain geometry: off the main thread. */
        fun meshes(bounds: FloatArray, style: ArDebugStyle): Map<DebugLayer, DebugMesh> {
            val meshes = HashMap<DebugLayer, DebugMesh>()
            // buildStage also lays the axes' gizmo: only the grid's two layers are drawn.
            ArDebugGeometry.buildStage(bounds, bounds[1], style) { meshes.getOrPut(it) { DebugMesh() } }
            return meshes
        }
    }
}

/**
 * The dimensions' atlas, as the replay draws it: the width's label on row 0 and the depth's on
 * row 1, bold, in the ink with a halo of the ground, and the solid strip the lines sample; and
 * each label's width in it.
 */
private class MeasureAtlas(val bitmap: Bitmap, val labelWidths: FloatArray) {
    companion object {
        fun draw(labels: Pair<String, String>, @ColorInt ink: Int, @ColorInt halo: Int): MeasureAtlas {
            val width = MeasureDrawing.ATLAS_WIDTH
            val height = MeasureDrawing.ATLAS_HEIGHT
            val row = MeasureDrawing.ROW_HEIGHT
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink
                textSize = MeasureDrawing.FONT_PX
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val haloPaint = Paint(inkPaint).apply {
                color = halo
                style = Paint.Style.STROKE
                strokeWidth = HALO_PX
                strokeJoin = Paint.Join.ROUND
            }
            val widths = FloatArray(2)
            listOf(labels.first, labels.second).forEachIndexed { index, text ->
                val baseline = index * row + (row - inkPaint.ascent() - inkPaint.descent()) / 2f
                canvas.drawText(text, MeasureDrawing.PAD_PX, baseline, haloPaint)
                canvas.drawText(text, MeasureDrawing.PAD_PX, baseline, inkPaint)
                widths[index] = (inkPaint.measureText(text) + 2 * MeasureDrawing.PAD_PX).coerceAtMost(width.toFloat())
            }
            val solid = height - MeasureDrawing.SOLID_HEIGHT
            canvas.drawRect(0f, solid.toFloat(), width.toFloat(), height.toFloat(), Paint().apply { color = ink })
            return MeasureAtlas(bitmap, widths)
        }

        /** The halo round the figures ([MeasureDrawing.FONT_PX] bold in a 128 px row), as the replay's. */
        const val HALO_PX = 14f
    }
}

/** The dimensions' layer over its atlas, swapped for another when the figures or the theme change. Main thread. */
private class ResultMeasureNodes(private val engine: Engine, private val materialLoader: MaterialLoader, atlas: Bitmap) {
    private val sampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    ).apply { anisotropy = ANISOTROPY }
    private var drawn: Bitmap = atlas
    private var texture: Texture = texture(atlas)
    private val material = materialLoader.createImageInstance(texture, sampler).also { it.setDepthWrite(false) }
    val node = DebugLayerNode(engine, material, MEASURE_PRIORITY, textured = true)

    fun retexture(atlas: Bitmap) {
        if (atlas === drawn) return
        drawn = atlas
        val next = texture(atlas)
        material.setTexture(next, sampler)
        retire(texture)
        texture = next
    }

    fun upload(mesh: DebugMesh) = node.upload(mesh)

    /** After the node left the scene: its material, then its atlas. */
    fun destroy() {
        materialLoader.destroyMaterialInstance(material)
        retire(texture)
    }

    private fun texture(bitmap: Bitmap): Texture = ImageTexture.Builder().bitmap(bitmap).build(engine)

    private fun retire(texture: Texture) = EngineDestroyQueue.of(engine).enqueueTexture(texture)

    private companion object {
        /** The replay's filtering on its atlases: the figures lie on a floor seen at a slant. */
        const val ANISOTROPY = 8f
    }
}
