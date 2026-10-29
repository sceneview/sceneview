package io.github.sceneview.ar

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import io.github.sceneview.SceneScope
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How the placement ring draws its line. The ring is a flat band on the floor whose texture holds
 * the line's profile, a sharp light stroke over a soft dark edging, so it reads on any floor and
 * stays as sharp under a mug as under a sofa.
 */
enum class PlacementRingLook(
    /** The stroke's own opacity, the edging aside. */
    internal val strokeAlpha: Float,
    /** Half the stroke, in strokes. */
    internal val halfStroke: Float,
    internal val dashed: Boolean,
) {
    /** ARCore still estimates the spot: a dashed, dimmer line. */
    Estimated(strokeAlpha = 0.55f, halfStroke = 0.5f, dashed = true),

    /** Tracked: the line closes. */
    Anchored(strokeAlpha = 0.85f, halfStroke = 0.5f, dashed = false),

    /** Under the fingers: fully opaque and 1.4 times as wide. */
    Held(strokeAlpha = 1f, halfStroke = 0.7f, dashed = false),
}

/** The ring's look, from where the placement stands. */
fun placementRingLookFor(touching: Boolean, settled: Boolean): PlacementRingLook = when {
    touching -> PlacementRingLook.Held
    !settled -> PlacementRingLook.Estimated
    else -> PlacementRingLook.Anchored
}

/**
 * When the placement ring shows: while the object is under the fingers, and for a moment after it
 * lands or is let go, longer when the spot is only estimated so the dashed line can be read.
 */
object PlacementRingVisibility {
    /** How long the ring stays after landing or release on a tracked spot. */
    const val HOLD_MS = 1200L

    /** How long the ring stays on an estimated spot. */
    const val ESTIMATED_HOLD_MS = 3000L

    /** The ring fades in and out over this. */
    const val FADE_MS = 180

    /** The hold after landing or release. */
    fun holdFor(settled: Boolean): Long = if (settled) HOLD_MS else ESTIMATED_HOLD_MS

    /** Whether the ring shows now. */
    fun isVisible(placed: Boolean, touching: Boolean, holdElapsed: Boolean): Boolean =
        placed && (touching || !holdElapsed)
}

/**
 * Whether the placement ring shows, timing [PlacementRingVisibility] holds: true while
 * [touching], then for [PlacementRingVisibility.holdFor] after the last change of any input.
 * Bump [revision] when the object lands again (re-anchored, recentred) to show it once more.
 */
@Composable
fun rememberPlacementRingVisible(placed: Boolean, settled: Boolean, touching: Boolean, revision: Int = 0): Boolean {
    var holdElapsed by remember(placed, settled, touching, revision) { mutableStateOf(false) }
    LaunchedEffect(placed, settled, touching, revision) {
        if (placed && !touching) {
            delay(PlacementRingVisibility.holdFor(settled))
            holdElapsed = true
        }
    }
    return PlacementRingVisibility.isVisible(placed, touching, holdElapsed)
}

/**
 * A flat ring on the floor around an object, [radius] metres: unlit, a light stroke over a dark
 * edging (see [PlacementRingLook]). Dashed while ARCore estimates the spot, closed once tracked,
 * wider under the fingers; it pulses once as it lands and as its line closes, and fades in and
 * out with [visible]. Every look and fade step is a small texture built once, so the ring never
 * uploads while it animates; its mesh is rewritten in place only for a new radius.
 *
 * Size it with [PlacementMath.ringRadiusFor] from the object's footprint.
 */
@Composable
fun SceneScope.PlacementRingNode(radius: Float, look: PlacementRingLook, visible: Boolean) {
    if (radius <= 0f) return
    val fade = animateFloatAsState(
        if (visible) 1f else 0f, tween(PlacementRingVisibility.FADE_MS), label = "placementRing",
    )
    // Read through the step, so a fade recomposes the ring at its few steps only.
    val step by remember { derivedStateOf { ringFadeStep(fade.value) } }
    val looks = remember(engine) { RingTextures(engine, materialLoader) }
    val mesh = remember(engine) { RingMesh(engine).also { it.size(radius) } }
    // Declared before the ring: Compose disposes in reverse order, so the renderable goes first.
    DisposableEffect(looks, mesh) { onDispose { looks.destroy(); mesh.destroy() } }
    SideEffect { mesh.size(radius) }
    val pulse = remember { Animatable(1f) }
    val shown = remember { arrayOfNulls<RingState>(1) }
    LaunchedEffect(visible, look) {
        val now = RingState(visible, look)
        val before = shown[0]
        shown[0] = now
        if (ringPulses(before, now) && ValueAnimator.areAnimatorsEnabled()) {
            pulse.snapTo(RING_PULSE_FROM)
            pulse.animateTo(1f, tween(RING_PULSE_MS, easing = FastOutSlowInEasing))
        } else {
            // A pulse cut short by this change would otherwise leave the ring enlarged.
            pulse.snapTo(1f)
        }
    }
    if (step > 0) {
        // A hair above the floor, drawn after the contact shadow it lies on.
        Node(position = Position(y = 0.001f), scale = Scale(pulse.value)) {
            MeshNode(
                primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer = mesh.vertices,
                indexBuffer = mesh.indices,
                // No bounds: the mesh changes size in place, so the ring is never culled.
                boundingBox = null,
                materialInstance = looks[look, step],
                apply = {
                    isTouchable = false
                    setPriority(RING_PRIORITY)
                },
            )
        }
    }
}

/** After the contact shadow's default priority, so the ring blends over it. */
private const val RING_PRIORITY = 5

/** The stroke's width in metres: 2.4 % of the radius, from 2.5 mm under a mug to 1 cm under a sofa. */
internal fun ringStrokeFor(radius: Float): Float = (radius * 0.024f).coerceIn(0.0025f, 0.010f)

/** Half the band, in strokes: the widest stroke ([PlacementRingLook.Held]) and the edging. */
internal const val RING_BAND_HALF = 1.5f

/** The edging's opacity, the same in every look so the ring reads on a light floor. */
internal const val RING_EDGING_ALPHA = 0.45f

/** Segments around the ring. */
internal const val RING_SEGMENTS = 96

/** Dash periods around the ring. */
internal const val RING_DASHES = 16

/** The lit share of a dash period. */
internal const val RING_DASH_SHARE = 0.6f

/** Fade steps: a fade swaps between these textures instead of uploading one per frame. */
internal const val RING_FADE_STEPS = 8

/** Texels across the band (u) and along one dash period (v). */
internal const val RING_TEXTURE_WIDTH = 64
internal const val RING_TEXTURE_HEIGHT = 32

/** The landing and the closing of the line pulse once, down from this scale. */
internal const val RING_PULSE_FROM = 1.06f
internal const val RING_PULSE_MS = 240

/** Floats per vertex in [ringVertices]: position then uv. */
internal const val RING_VERTEX_FLOATS = 5

/** The step a fade from 0 to 1 is drawn with, 0 being hidden. */
internal fun ringFadeStep(fade: Float): Int = (fade.coerceIn(0f, 1f) * RING_FADE_STEPS).roundToInt()

/** What the ring showed or shows, for [ringPulses]. */
internal data class RingState(val visible: Boolean, val look: PlacementRingLook)

/** A pulse when the ring lands (it was hidden) or its estimated line closes; never under the fingers. */
internal fun ringPulses(before: RingState?, now: RingState): Boolean =
    now.visible && now.look != PlacementRingLook.Held &&
        (before?.visible != true ||
            (before.look == PlacementRingLook.Estimated && now.look == PlacementRingLook.Anchored))

/**
 * The band's vertices, interleaved position then uv: [RING_SEGMENTS] + 1 columns of an inner and
 * an outer vertex (the seam is doubled so v runs on to [RING_DASHES]). u goes across the band.
 */
internal fun ringVertices(radius: Float): FloatArray {
    val half = ringStrokeFor(radius) * RING_BAND_HALF
    val inner = (radius - half).coerceAtLeast(0f)
    val outer = radius + half
    val out = FloatArray((RING_SEGMENTS + 1) * 2 * RING_VERTEX_FLOATS)
    var i = 0
    for (s in 0..RING_SEGMENTS) {
        val t = s / RING_SEGMENTS.toFloat()
        val angle = 2 * PI * t
        val x = cos(angle).toFloat()
        val z = sin(angle).toFloat()
        val v = t * RING_DASHES
        for ((r, u) in listOf(inner to 0f, outer to 1f)) {
            out[i++] = x * r; out[i++] = 0f; out[i++] = z * r; out[i++] = u; out[i++] = v
        }
    }
    return out
}

/** Two triangles per segment, over [ringVertices], facing up. */
internal fun ringIndices(): ShortArray {
    val out = ShortArray(RING_SEGMENTS * 6)
    var i = 0
    for (s in 0 until RING_SEGMENTS) {
        val a = (s * 2).toShort()
        val b = (s * 2 + 1).toShort()
        val c = (s * 2 + 2).toShort()
        val d = (s * 2 + 3).toShort()
        out[i++] = a; out[i++] = b; out[i++] = c
        out[i++] = c; out[i++] = b; out[i++] = d
    }
    return out
}

/**
 * The texture of [look] at fade [step] (1 to [RING_FADE_STEPS]), RGBA bytes, straight alpha (the
 * material premultiplies). The GPU derives the mip levels.
 */
internal fun ringTexels(look: PlacementRingLook, step: Int): ByteArray {
    val fade = step.coerceIn(0, RING_FADE_STEPS) / RING_FADE_STEPS.toFloat()
    val w = RING_TEXTURE_WIDTH
    val h = RING_TEXTURE_HEIGHT
    // Premultiplied while composed, straightened when written.
    val texels = FloatArray(w * h * 4)
    val texelU = 2 * RING_BAND_HALF / w
    val texelV = 1f / h
    for (y in 0 until h) {
        val along = (y + 0.5f) / h
        val dash = if (look.dashed) dashCoverage(along, texelV) else 1f
        for (x in 0 until w) {
            val across = abs((x + 0.5f) / w - 0.5f) * 2 * RING_BAND_HALF
            val stroke = ((look.halfStroke - across) / texelU + 0.5f).coerceIn(0f, 1f) * look.strokeAlpha * dash
            val edging = RING_EDGING_ALPHA * dash * edgingAt(across, look.halfStroke)
            // The stroke over its edging: white over black.
            val alpha = (stroke + edging * (1 - stroke)) * fade
            val white = stroke * fade
            val o = (y * w + x) * 4
            texels[o] = white; texels[o + 1] = white; texels[o + 2] = white; texels[o + 3] = alpha
        }
    }
    return straighten(texels, w, h)
}

/** Mip levels of the ring's texture, down to 1 x 1. */
internal val RING_TEXTURE_LEVELS: Int = run {
    var n = 1
    var size = maxOf(RING_TEXTURE_WIDTH, RING_TEXTURE_HEIGHT)
    while (size > 1) {
        size /= 2
        n++
    }
    n
}

private fun dashCoverage(along: Float, texel: Float): Float {
    val center = RING_DASH_SHARE / 2
    val distance = abs(along - center)
    return ((RING_DASH_SHARE / 2 - distance) / texel + 0.5f).coerceIn(0f, 1f)
}

private fun edgingAt(across: Float, halfStroke: Float): Float {
    if (across <= halfStroke) return 1f
    val t = ((across - halfStroke) / (RING_BAND_HALF - halfStroke)).coerceIn(0f, 1f)
    return (1 - t) * (1 - t)
}

/**
 * Straight alpha from premultiplied texels. A fully transparent texel takes the colour of the most
 * opaque one in its column, so mip levels filtered into a dash gap fade the stroke instead of
 * darkening its ends.
 */
private fun straighten(premultiplied: FloatArray, w: Int, h: Int): ByteArray {
    val out = ByteArray(premultiplied.size)
    for (x in 0 until w) {
        val densest = (0 until h).maxBy { premultiplied[(it * w + x) * 4 + 3] }
        for (y in 0 until h) {
            val i = (y * w + x) * 4
            val from = if (premultiplied[i + 3] > 0f) i else (densest * w + x) * 4
            val a = premultiplied[from + 3]
            for (c in 0 until 3) out[i + c] = byteOf(if (a > 0f) premultiplied[from + c] / a else 0f)
            out[i + 3] = byteOf(premultiplied[i + 3])
        }
    }
    return out
}

private fun byteOf(value: Float): Byte = (value.coerceIn(0f, 1f) * 255f).roundToInt().toByte()

/** The ring's band: a fixed count of vertices, rewritten in place when the radius changes. */
private class RingMesh(private val engine: Engine) {
    val vertices: VertexBuffer = VertexBuffer.Builder()
        .bufferCount(1)
        .vertexCount((RING_SEGMENTS + 1) * 2)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, RING_VERTEX_FLOATS * 4)
        .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, RING_VERTEX_FLOATS * 4)
        .build(engine)
    val indices: IndexBuffer = IndexBuffer.Builder()
        .indexCount(RING_SEGMENTS * 6)
        .bufferType(IndexBuffer.Builder.IndexType.USHORT)
        .build(engine)
        .also {
            val data = ringIndices()
            it.setBuffer(engine, directBuffer(data.size * 2).asShortBuffer().put(data).rewind())
        }
    private var radius = 0f

    fun size(radius: Float) {
        if (radius == this.radius) return
        this.radius = radius
        val data = ringVertices(radius)
        // A fresh buffer each time: Filament reads it after this call returns.
        vertices.setBufferAt(engine, 0, directBuffer(data.size * 4).asFloatBuffer().put(data).rewind())
    }

    fun destroy() {
        engine.destroyVertexBuffer(vertices)
        engine.destroyIndexBuffer(indices)
    }
}

/** One material per look and fade step, each on its own small texture, built when first shown. */
private class RingTextures(private val engine: Engine, private val materialLoader: MaterialLoader) {
    private val built = HashMap<Pair<PlacementRingLook, Int>, Pair<Texture, MaterialInstance>>()
    private val sampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    ).apply {
        // Across the band the profile stops at its edges; along it the dash period repeats.
        wrapModeT = TextureSampler.WrapMode.REPEAT
        anisotropy = 4f
    }

    operator fun get(look: PlacementRingLook, step: Int): MaterialInstance = built.getOrPut(look to step) {
        val texels = ringTexels(look, step)
        val texture = Texture.Builder()
            .width(RING_TEXTURE_WIDTH)
            .height(RING_TEXTURE_HEIGHT)
            .levels(RING_TEXTURE_LEVELS)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine)
        // Only the base level goes up; the GPU derives the rest.
        texture.setImage(
            engine, 0,
            Texture.PixelBufferDescriptor(
                directBuffer(texels.size).put(texels).rewind(), Texture.Format.RGBA, Texture.Type.UBYTE,
            ),
        )
        texture.generateMipmaps(engine)
        texture to materialLoader.createImageInstance(texture, sampler)
    }.second

    fun destroy() {
        built.values.forEach { (texture, material) ->
            materialLoader.destroyMaterialInstance(material)
            engine.destroyTexture(texture)
        }
        built.clear()
    }
}

private fun directBuffer(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
