package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.github.sceneview.SceneScope
import io.github.sceneview.demo.demos.internal.DrawingPlan
import io.github.sceneview.demo.demos.internal.PlanProp
import io.github.sceneview.demo.demos.internal.PropKind
import io.github.sceneview.demo.demos.internal.PropMotion
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.rememberModelInstance

/**
 * The real things a drawn answer brings into the room, each built on the plan's clock once the
 * pen has finished: a framed picture on the wall, a sofa on its footprint, a radiator key on the
 * screw. Every one is composed from the start (so a model is loaded before it is needed) and
 * shows nothing until its turn.
 */
@Composable
internal fun SceneScope.PlanProps(plan: DrawingPlan, elapsed: Float) {
    plan.props.forEach { prop ->
        key(prop) {
            val t = prop.progress(elapsed)
            when (prop.kind) {
                PropKind.PictureFrame -> PictureFrameProp(prop, t)
                PropKind.Sofa -> SofaProp(prop, t)
                PropKind.BleedKey -> BleedKeyProp(prop, t)
            }
        }
    }
}

/**
 * The frame drawn bar by bar as a pen goes round it (top, right, bottom, left), then the canvas
 * let down from the top bar, then the whole thing settling on its nails.
 */
@Composable
private fun SceneScope.PictureFrameProp(prop: PlanProp, t: Float) {
    if (t <= 0f) return
    val wood = remember(materialLoader) { materialLoader.createColorInstance(WALNUT, metallic = 0f, roughness = 0.55f) }
    val painting = remember { paintingBitmap() }
    val w = prop.size.x
    val h = prop.size.y
    val d = prop.size.z
    val b = FRAME_BAR
    val settle = PropMotion.phase(t, 0.85f, 1f)
    val pulse = 1f + 0.04f * kotlin.math.sin(settle * Math.PI.toFloat())
    Node(position = Position(prop.offset.x, prop.offset.y, prop.offset.z), scale = Scale(pulse)) {
        // Each bar grows from the corner the pen reached last.
        val bars = listOf(
            // Top, left to right.
            Triple(Position(0f, h / 2f - b / 2f, 0f), Size(w, b, d), Position(-1f, 0f, 0f)),
            // Right, top to bottom.
            Triple(Position(w / 2f - b / 2f, 0f, 0f), Size(b, h, d), Position(0f, 1f, 0f)),
            // Bottom, right to left.
            Triple(Position(0f, -h / 2f + b / 2f, 0f), Size(w, b, d), Position(1f, 0f, 0f)),
            // Left, bottom to top.
            Triple(Position(-w / 2f + b / 2f, 0f, 0f), Size(b, h, d), Position(0f, -1f, 0f)),
        )
        bars.forEachIndexed { i, (centre, size, from) ->
            val p = PropMotion.easeOut(PropMotion.phase(t, i * BAR_SHARE, (i + 1) * BAR_SHARE))
            if (p > 0f) {
                // Grown p of the way from the [from] end: scaled along its length, its far end fixed at that corner.
                val along = if (from.x != 0f) size.x else size.y
                val shift = (1f - p) * along / 2f
                CubeNode(
                    size = size,
                    materialInstance = wood,
                    position = Position(centre.x + from.x * shift, centre.y + from.y * shift, centre.z),
                    scale = if (from.x != 0f) Scale(p, 1f, 1f) else Scale(1f, p, 1f),
                )
            }
        }
        // The canvas, let down from the top bar.
        val reveal = PropMotion.easeOut(PropMotion.phase(t, 4 * BAR_SHARE, 0.85f))
        if (reveal > 0f) {
            val cw = w - 2f * b + CANVAS_OVERLAP
            val ch = h - 2f * b + CANVAS_OVERLAP
            ImageNode(
                bitmap = painting,
                size = Size(cw, ch),
                normal = Direction(z = 1f),
                position = Position(0f, ch / 2f * (1f - reveal), d / 2f - CANVAS_INSET),
                scale = Scale(1f, reveal, 1f),
            )
        }
    }
}

/** The sofa, rising out of the dashed footprint and settling with a little weight. */
@Composable
private fun SceneScope.SofaProp(prop: PlanProp, t: Float) {
    val model = rememberModelInstance(modelLoader, SOFA_MODEL)
    if (t <= 0f || model == null) return
    val rise = PropMotion.easeOutBack(t).coerceAtLeast(MIN_SCALE)
    val spread = 0.9f + 0.1f * PropMotion.easeOut(t)
    // True size: the model is 2.14 m long, the footprint 2 m.
    val k = prop.size.x / SOFA_MODEL_LENGTH
    ModelNode(
        modelInstance = model,
        autoAnimate = false,
        position = Position(prop.offset.x, prop.offset.y, prop.offset.z),
        scale = Scale(k * spread, k * rise, k * spread),
        apply = {
            isShadowCaster = true
            isShadowReceiver = false
        },
    )
}

/**
 * The radiator key: it comes in toward the screw, seats on it, turns the quarter turn the arrow
 * shows (anticlockwise, as seen from the room), and three drops run out.
 */
@Composable
private fun SceneScope.BleedKeyProp(prop: PlanProp, t: Float) {
    if (t <= 0f) return
    val brass = remember(materialLoader) { materialLoader.createColorInstance(BRASS, metallic = 1f, roughness = 0.32f) }
    val water = remember(materialLoader) { materialLoader.createColorInstance(WATER, metallic = 0f, roughness = 0.05f) }
    val come = PropMotion.easeOut(PropMotion.phase(t, 0f, 0.28f))
    val seat = PropMotion.easeOut(PropMotion.phase(t, 0.28f, 0.38f))
    val turn = PropMotion.phase(t, 0.42f, 0.72f).let { it * it * (3f - 2f * it) }
    val z = KEY_APPROACH * (1f - come) + KEY_SEAT_GAP * (1f - seat)
    val handle = prop.size.x
    val length = prop.size.z
    Node(
        position = Position(prop.offset.x, prop.offset.y, prop.offset.z + z),
        // Comes in a little crooked, straightens as it seats, then turns.
        rotation = Rotation(x = 0f, y = 0f, z = KEY_TILT * (1f - come) + 90f * turn),
    ) {
        // The barrel, its socket on the screw, along +z out of the radiator.
        CylinderNode(
            radius = KEY_BARREL,
            height = length,
            materialInstance = brass,
            position = Position(0f, 0f, length / 2f),
            rotation = Rotation(x = 90f),
        )
        // The flat handle a thumb and finger turn.
        CubeNode(
            size = Size(handle, KEY_GRIP, KEY_THICKNESS),
            materialInstance = brass,
            position = Position(0f, 0f, length),
        )
    }
    // The drops: one after the other, out of the screw and down the radiator's face.
    for (i in 0 until DROPS) {
        val p = PropMotion.phase(t, 0.72f + i * 0.08f, 0.72f + i * 0.08f + 0.16f)
        if (p <= 0f || p >= 1f) continue
        val fall = DROP_FALL * p * p
        SphereNode(
            radius = DROP_RADIUS,
            materialInstance = water,
            position = Position(prop.offset.x, prop.offset.y - KEY_BARREL - fall, prop.offset.z + DROP_LIFT),
            scale = Scale(1f - 0.4f * p, 1f + 0.5f * p, 1f - 0.4f * p),
        )
    }
}

/**
 * The picture in the frame: a warm evening over hills, painted on the spot, so the demo needs
 * no image of anyone's.
 */
private fun paintingBitmap(): Bitmap {
    val w = 640
    val h = 480
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, 0f, h * 0.7f, 0xFFF6D08A.toInt(), 0xFFE07A55.toInt(), Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null
    paint.color = 0xFFFFF2CF.toInt()
    canvas.drawCircle(w * 0.68f, h * 0.42f, h * 0.11f, paint)
    val hills = listOf(0xFFB0694E.toInt() to 0.55f, 0xFF7B5A4A.toInt() to 0.66f, 0xFF3F4A3C.toInt() to 0.8f)
    hills.forEachIndexed { i, (colour, level) ->
        paint.color = colour
        val path = Path()
        path.moveTo(0f, h.toFloat())
        val steps = 48
        for (s in 0..steps) {
            val x = w * s / steps.toFloat()
            val y = h * level + h * 0.05f * kotlin.math.sin(x / w * (3f + i) * Math.PI.toFloat() + i * 1.7f)
            path.lineTo(x, y)
        }
        path.lineTo(w.toFloat(), h.toFloat())
        path.close()
        canvas.drawPath(path, paint)
    }
    return bitmap
}

private val WALNUT = Color(0xFF5A3A22)
private val BRASS = Color(0xFFC9A04A)
private val WATER = Color(0xFF9CC8EE)

private const val SOFA_MODEL = "models/rivet_sloane_sofa.glb"
private const val SOFA_MODEL_LENGTH = 2.138f
private const val MIN_SCALE = 0.01f

private const val FRAME_BAR = 0.035f
private const val BAR_SHARE = 0.1f
private const val CANVAS_OVERLAP = 0.004f
private const val CANVAS_INSET = 0.004f

private const val KEY_APPROACH = 0.22f
private const val KEY_SEAT_GAP = 0.015f
private const val KEY_TILT = -35f
private const val KEY_BARREL = 0.0055f
private const val KEY_GRIP = 0.014f
private const val KEY_THICKNESS = 0.004f

private const val DROPS = 3
private const val DROP_RADIUS = 0.0035f
private const val DROP_FALL = 0.14f
private const val DROP_LIFT = 0.008f
