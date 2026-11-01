package io.github.sceneview.demo.demos

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.Color
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.RenderableManager.PrimitiveType
import io.github.sceneview.SceneScope
import io.github.sceneview.demo.demos.internal.RevealWire
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setColor
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.model.ModelInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * The reveal a reconstructed model opens with: its own wireframe sweeps up from the floor in the
 * accent colour, the solid model then rises into it, and the wire fades once the model has filled
 * it — played alongside the camera's `CameraPath.reveal`, about as long. Real geometry only: the
 * wire is the model's own triangle edges ([RevealWire]), the rise a scale about the floor; no
 * material is edited, so any vertex-coloured or textured `.glb` reveals the same way.
 *
 * Self-contained so another viewer can call it as is: a [RevealWire] built off the main thread
 * ([rememberRevealWire]), a clock ([rememberRevealClock]) and the node ([RevealedModel]).
 */
internal object ModelReveal {
    /** The wire's sweep from the floor to the top. */
    const val WIRE_SWEEP_MS = 1200f

    /** The solid starts rising once the wire stands about half way. */
    const val SOLID_START_MS = 700f
    const val SOLID_RISE_MS = 1600f

    /** The wire fades once the solid is nearly whole: the whole reveal is [TOTAL_MS]. */
    const val WIRE_FADE_START_MS = 2100f
    const val WIRE_FADE_MS = 700f
    const val TOTAL_MS = WIRE_FADE_START_MS + WIRE_FADE_MS

    /** Peak opacity of the wire: bright enough to read as light, under the solid's colours. */
    const val WIRE_ALPHA = 0.85f

    /**
     * Above this many triangles no wire is built: its edges would cost tens of megabytes more.
     * Raw surfaces never get one ([buildsWire]), so this only bounds a finished model.
     */
    const val MAX_TRIANGLES = 1_200_000

    /**
     * Whether a model gets a wire: only a [final] model the result [opensOn], revealed in motion
     * ([animate]). Never a big scan's raw surface or a retry: their finish runs on the same heap
     * with a few megabytes to spare, and the wire's build would take them. Never under reduced
     * motion either: the wire is never drawn there, so building it only delays the first frame.
     */
    fun buildsWire(final: Boolean, opensOn: Boolean, animate: Boolean): Boolean = final && opensOn && animate

    /** Share of the height the wire stands at [elapsedMs] after the start, 0 to 1. */
    fun wireSweep(elapsedMs: Float): Float = easeOutCubic(elapsedMs / WIRE_SWEEP_MS)

    /** The solid's vertical scale about the floor at [elapsedMs], 0 (flat) to 1 (whole). */
    fun solidRise(elapsedMs: Float): Float = easeInOutCubic((elapsedMs - SOLID_START_MS) / SOLID_RISE_MS)

    /** The wire's opacity at [elapsedMs], [WIRE_ALPHA] until it fades, 0 once the reveal is over. */
    fun wireAlpha(elapsedMs: Float): Float =
        WIRE_ALPHA * (1f - easeInOutCubic((elapsedMs - WIRE_FADE_START_MS) / WIRE_FADE_MS))

    private fun easeOutCubic(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

    private fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    }
}

/** A [RevealWire] being built: [Pending] until it is, then [Done], its wire `null` when there is none. */
internal sealed interface RevealWireBuild {
    data object Pending : RevealWireBuild

    class Done(val wire: RevealWire?) : RevealWireBuild
}

/**
 * The wire of the `.glb` [glb] ([triangles] of them, `null`: nothing to reveal), built off the
 * main thread while [needed]. A model too big ([ModelReveal.MAX_TRIANGLES]) or unreadable gets no
 * wire: it shows at once, as before. Once [needed] turns false (the reveal is over) the wire is
 * let go, so its arrays do not stay on the heap for as long as the screen lives.
 */
@Composable
internal fun rememberRevealWire(glb: ByteArray?, triangles: Int, needed: Boolean): State<RevealWireBuild> {
    // Pending in the very composition [needed] turns true, not a frame later: a caller that waits
    // for the wire never sees the last build's result instead.
    val build = remember(glb, needed) {
        mutableStateOf<RevealWireBuild>(if (glb == null || !needed) RevealWireBuild.Done(null) else RevealWireBuild.Pending)
    }
    LaunchedEffect(build) {
        if (glb == null || build.value !is RevealWireBuild.Pending) return@LaunchedEffect
        val wire = if (triangles > ModelReveal.MAX_TRIANGLES) {
            null
        } else {
            withContext(Dispatchers.Default) {
                runCatching {
                    val started = System.nanoTime()
                    io.github.sceneview.demo.demos.internal.RerunGlbMesh.read(glb)
                        ?.also { ensureActive() }
                        ?.let { mesh -> RevealWire.of(mesh.positions, mesh.vertexCount, mesh.indices) }
                        ?.also { Log.i(REVEAL_TAG, "wire: ${it.edgeCount} edges in ${(System.nanoTime() - started) / 1_000_000} ms") }
                }.onFailure { if (it is CancellationException) throw it else Log.w(REVEAL_TAG, "wire failed", it) }.getOrNull()
            }
        }
        build.value = RevealWireBuild.Done(wire)
    }
    return build
}

/**
 * Milliseconds since [started] turned true, per frame (read it where it is drawn, so only that
 * recomposes), for the reveal keyed by [key]; past
 * [ModelReveal.TOTAL_MS] it stops ticking. Without [animate] (reduced motion) the reveal is
 * over from the start: the model is simply there.
 */
@Composable
internal fun rememberRevealClock(key: Any?, started: Boolean, animate: Boolean): State<Float> {
    val clock = remember(key) { mutableFloatStateOf(if (animate) 0f else ModelReveal.TOTAL_MS) }
    var elapsed by clock
    LaunchedEffect(key, started, animate) {
        if (!animate) {
            elapsed = ModelReveal.TOTAL_MS
            return@LaunchedEffect
        }
        if (!started || elapsed >= ModelReveal.TOTAL_MS) return@LaunchedEffect
        val origin = withFrameMillis { it } - elapsed.toLong()
        while (elapsed < ModelReveal.TOTAL_MS) {
            elapsed = withFrameMillis { (it - origin).toFloat() }.coerceAtMost(ModelReveal.TOTAL_MS)
        }
    }
    return clock
}

/**
 * [instance] revealed by [wire] at [elapsedMs] of the reveal ([rememberRevealClock]): the wire in
 * [color] sweeping up, the model rising into it from the floor, the wire fading. Without a wire
 * the model is drawn whole. The model in its own world space, as `ModelNode` draws it unmoved.
 */
@Composable
internal fun SceneScope.RevealedModel(
    instance: ModelInstance,
    wire: RevealWire?,
    elapsedMs: Float,
    color: Color,
    engine: Engine,
    materialLoader: MaterialLoader,
) {
    val rise = if (wire == null) 1f else ModelReveal.solidRise(elapsedMs)
    // Scaled about the floor, never to a singular zero: a height of a few microns reads as nothing.
    val floor = wire?.minY ?: 0f
    val height = rise.coerceAtLeast(MIN_RISE)
    ModelNode(
        modelInstance = instance,
        autoAnimate = false,
        position = Position(0f, floor * (1f - height), 0f),
        scale = Scale(1f, height, 1f),
        isVisible = rise > 0f,
    )
    if (wire == null) return
    val alpha = ModelReveal.wireAlpha(elapsedMs)
    val sweep = ModelReveal.wireSweep(elapsedMs)
    if (alpha <= 0f) return
    // Remembered before the node, so Compose releases the material after the node destroyed its layer.
    val layer = remember(engine, materialLoader, wire) {
        // Created translucent (alpha under 1 picks the blended unlit material) so it can fade.
        val material = materialLoader.createUnlitColorInstance(color.copy(alpha = ModelReveal.WIRE_ALPHA))
            .also { it.setCullingMode(Material.CullingMode.NONE) }
        material to DebugLayerNode(engine, material, WIRE_PRIORITY, primitive = PrimitiveType.LINES)
            .also { it.upload(wire.toMesh()) }
    }
    DisposableEffect(layer) { onDispose { materialLoader.destroyMaterialInstance(layer.first) } }
    SideEffect {
        layer.first.setColor(color.copy(alpha = alpha))
        layer.second.isVisible = sweep > 0f
        layer.second.showIndices(wire.indicesBelow(sweep))
    }
    key(layer) { Node(apply = { addChildNode(layer.second) }) }
}

private const val REVEAL_TAG = "ModelReveal"
private const val MIN_RISE = 0.002f

/** Over the ground and its grid (1–2), under nothing else: the wire is the scene's only light. */
private const val WIRE_PRIORITY = 3
