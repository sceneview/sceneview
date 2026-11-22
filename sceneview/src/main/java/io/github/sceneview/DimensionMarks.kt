package io.github.sceneview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.node.Node as NodeImpl

/**
 * Where an app's own chrome sits on screen, so [DimensionMarks] keep their tags out from under it.
 *
 * @param screenY how high a world point appears on screen, -1 at the bottom edge and 1 at the top,
 *   or `null` when unknown (behind the camera, before tracking).
 * @param bottomLimit the top of the bottom controls, on the same scale (-1: none).
 * @param topLimit the bottom of the top bar, on the same scale (1: none).
 */
class DimensionTagClearance(
    val screenY: (Position) -> Float?,
    val bottomLimit: () -> Float,
    val topLimit: () -> Float = { 1f },
)

/** Defaults of [DimensionMarks]: white lines, white figures on a dark tag. */
object DimensionMarksDefaults {
    /** 88 % near-black: white figures on it stay above 7:1 contrast over any camera image. */
    val LabelBackground = Color(0xE0141218)

    /** The dark edging under the floor lines, so they read on a pale floor in the sun. */
    val Edging = Color(0x73000000)
}

/**
 * Dimension lines around a box of [size] metres standing at the origin (bottom at y = 0, centred on
 * X and Z), with their values written next to them: width along the front (+Z), depth along the
 * right side (+X), height up the right-front corner. The marks are drawn at the box's real size,
 * outside any node that stretches the object, so the text never deforms. Unlit: they cast and
 * receive no shadow (#5209).
 *
 * In AR, pass [facing] so the tags turn to the camera instead of reading backwards from behind,
 * and [clearance] so a tag hides while it is under the app's controls. With
 * `AutoPlacementState.guide`, show them when `guide.dimensions` is true; an app whose answer *is*
 * the size (a furniture fit) may keep them on.
 *
 * ```kotlin
 * AutoPlacementNode(placement, state, size = Size(0.9f, 0.85f, 0.6f)) { opacity ->
 *     Sofa(opacity)
 *     if (state.guide.dimensions) DimensionMarks(
 *         size = Size(0.9f, 0.85f, 0.6f),
 *         format = { metres -> "${(metres * 100).roundToInt()} cm" },
 *         facing = { cameraPosition },
 *     )
 * }
 * ```
 *
 * @param format writes one length, in metres.
 * @param labels the lengths the tags write; defaults to [size]. A view that animates [size] with
 *   an overshooting spring passes the target here, so the tags never read 91 cm on the way to 90.
 * @param selectable lines and tags take taps for the object they measure, as its own body does.
 * @param labelScale 1 for a 34 cm tag (AR); a 3D view of a large object passes more.
 * @param facing where the camera is, for the tags to turn to it; `null` keeps them facing +Z.
 * @param heightOffset how much further right the height line stands than the floor lines, so it
 *   never runs up an armrest in a 3D view; AR keeps it on the corner.
 * @param heightTagSide the screen's right, flat on the floor: given, the height tag starts at its
 *   line and runs right, off the object behind it; `null` keeps it centred on top.
 * @param clearance where the app's chrome is on screen, for the tags to keep out from under it.
 */
@Composable
fun SceneScope.DimensionMarks(
    size: Size,
    format: (metres: Float) -> String,
    labels: Size = size,
    lineColor: Color = Color.White,
    labelText: Color = Color.White,
    labelBackground: Color = DimensionMarksDefaults.LabelBackground,
    selectable: Boolean = false,
    labelScale: Float = 1f,
    facing: (() -> Position)? = null,
    heightOffset: Float = 0f,
    heightTagSide: Direction? = null,
    clearance: DimensionTagClearance? = null,
) {
    val w = size.x; val h = size.y; val d = size.z
    val g = DimensionMarksGeometry
    val line = remember(materialLoader) { materialLoader.createUnlitColorInstance(lineColor) }
    val edging = remember(materialLoader) { materialLoader.createUnlitColorInstance(DimensionMarksDefaults.Edging) }
    SideEffect { line.setColor(lineColor) }
    DisposableEffect(line, edging) {
        onDispose {
            materialLoader.destroyMaterialInstance(line)
            materialLoader.destroyMaterialInstance(edging)
        }
    }
    val fixed: NodeImpl.() -> Unit = {
        if (selectable) {
            isEditable = true
            isPositionEditable = false
            isRotationEditable = false
            isScaleEditable = false
        }
        isShadowCaster = false
        isShadowReceiver = false
    }
    val front = d / 2f + g.OFFSET
    val right = w / 2f + g.OFFSET
    val t = g.THICKNESS
    val tick = g.TICK
    // Width along the floor in front, depth along the floor on the right, each on a dark edging
    // flat on the floor: a white line alone vanishes on a pale parquet in the sun.
    CubeNode(size = Size(w + g.EDGING, g.EDGING_HEIGHT, g.EDGING), position = Position(0f, g.EDGING_HEIGHT / 2f, front), materialInstance = edging, apply = fixed)
    CubeNode(size = Size(g.EDGING, g.EDGING_HEIGHT, d + g.EDGING), position = Position(right, g.EDGING_HEIGHT / 2f, 0f), materialInstance = edging, apply = fixed)
    CubeNode(size = Size(w, t, t), position = Position(0f, t / 2f, front), materialInstance = line, apply = fixed)
    CubeNode(size = Size(t, tick, t), position = Position(-w / 2f, tick / 2f, front), materialInstance = line, apply = fixed)
    CubeNode(size = Size(t, tick, t), position = Position(w / 2f, tick / 2f, front), materialInstance = line, apply = fixed)
    CubeNode(size = Size(t, t, d), position = Position(right, t / 2f, 0f), materialInstance = line, apply = fixed)
    CubeNode(size = Size(t, tick, t), position = Position(right, tick / 2f, -d / 2f), materialInstance = line, apply = fixed)
    // Height, up the right-front corner.
    val hx = right + heightOffset
    CubeNode(size = Size(t, h, t), position = Position(hx, h / 2f, front), materialInstance = line, apply = fixed)
    CubeNode(size = Size(tick, t, t), position = Position(hx, h, front), materialInstance = line, apply = fixed)
    CubeNode(size = Size(tick, t, t), position = Position(hx, t / 2f, front), materialInstance = line, apply = fixed)

    // Tags in the 4:1 bitmap TextNode draws, the figure filling most of it.
    val lw = g.TAG_WIDTH; val lh = g.TAG_HEIGHT
    val fg = labelText.toArgb(); val bg = labelBackground.toArgb()
    val s = Scale(labelScale)
    val half = lh * labelScale / 2f
    @Composable
    fun Tag(metres: Float) = TextNode(
        text = format(metres), fontSize = g.FONT_SIZE, textColor = fg, backgroundColor = bg,
        widthMeters = lw, heightMeters = lh, scale = s, cameraPositionProvider = facing, apply = fixed,
    )
    TagSlot(clearance, half, Position(0f, half + 0.03f, front + 0.02f)) { Tag(labels.x) }
    // Outside the depth line by half a tag, so the object never hides it.
    TagSlot(clearance, half, Position(right + 0.03f + lw * labelScale / 2f, half + 0.03f, 0f), Rotation(y = 90f)) {
        Tag(labels.z)
    }
    if (heightTagSide != null) {
        // On top of the height line like a flag, starting just left of it: centred over it, the
        // tag ran across the back of a sofa; fully beside it, it left the frame on the right.
        val side = lw * labelScale / 2f - 0.07f
        TagSlot(clearance, half, Position(hx + heightTagSide.x * side, h + half + 0.04f, front + heightTagSide.z * side)) {
            Tag(labels.y)
        }
    } else {
        // Above the top of the height line, clear of the depth tag on the floor below it.
        TagSlot(clearance, half, Position(hx + 0.02f, h + half + 0.04f, front + 0.02f), Rotation(y = 45f)) {
            Tag(labels.y)
        }
    }
}

/** Geometry of [DimensionMarks], metres. */
internal object DimensionMarksGeometry {
    /** Gap between the box and its lines. */
    const val OFFSET = 0.07f
    const val THICKNESS = 0.008f
    const val TICK = 0.06f
    /** Width of the dark edging under a floor line: 1 cm of it each side of the line. */
    const val EDGING = 0.028f
    const val EDGING_HEIGHT = 0.001f
    const val TAG_WIDTH = 0.34f
    const val TAG_HEIGHT = 0.085f
    const val FONT_SIZE = 82f

    /**
     * Whether a tag spanning [bottom] to [top] on screen (-1 at the bottom edge, 1 at the top)
     * shows between the controls at [bottomLimit] and the top bar at [topLimit]. A shown tag hides
     * as soon as an edge reaches either; a hidden one waits until it is clear by [margin], so it
     * does not flicker on the edge.
     */
    fun tagShown(
        wasShown: Boolean, bottom: Float, top: Float, bottomLimit: Float, topLimit: Float,
        margin: Float = 0.06f,
    ): Boolean = if (wasShown) bottom >= bottomLimit && top <= topLimit
    else bottom > bottomLimit + margin && top < topLimit - margin
}

/** One tag's place; with a [clearance], it hides while under the app's chrome. */
@Composable
private fun SceneScope.TagSlot(
    clearance: DimensionTagClearance?,
    halfHeight: Float,
    position: Position,
    rotation: Rotation = Rotation(),
    content: @Composable NodeScope.() -> Unit,
) {
    Node(
        position = position,
        rotation = rotation,
        apply = {
            if (clearance != null) onFrame = {
                val c = worldPosition
                val bottom = clearance.screenY(Position(c.x, c.y - halfHeight, c.z))
                val top = clearance.screenY(Position(c.x, c.y + halfHeight, c.z))
                val show = bottom == null || top == null || DimensionMarksGeometry.tagShown(
                    isVisible, bottom, top, clearance.bottomLimit(), clearance.topLimit(),
                )
                if (show != isVisible) isVisible = show
            }
        },
        content = content,
    )
}
