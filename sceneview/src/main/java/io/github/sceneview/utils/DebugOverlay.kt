package io.github.sceneview.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import android.os.SystemClock
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sceneview.ExperimentalSceneViewApi
import kotlinx.coroutines.delay

/**
 * Real-time performance statistics for a running [io.github.sceneview.SceneView].
 *
 * Create with [rememberDebugStats] and update from the `onFrame` callback.
 *
 * The properties are **plain fields, not Compose state**, and reading one does not subscribe
 * the caller to it. That is deliberate. Writing snapshot state from `onFrame` — which is what
 * this class used to do, six writes per frame — makes the fps counter recompose its host on
 * every rendered frame, and under [io.github.sceneview.FrameRatePolicy.OnDemand] a screen that
 * only ever recomposes to show its own fps is measuring itself. Read these from a composable
 * that already ticks on its own, as [DebugOverlay] does four times a second; a counter needs no
 * more resolution than that, and a parked scene then costs nothing at all.
 *
 * @property fps             Smoothed frames-per-second estimate, over the last 30 frames.
 * @property frameTimeMs     Duration of the last rendered frame in milliseconds.
 * @property nodeCount       Total number of nodes in the scene graph (user-managed count).
 */
@ExperimentalSceneViewApi
class DebugStats {
    /** Smoothed frames-per-second estimate, over the last 30 frames. */
    var fps: Float = 0f
        private set

    /** Duration of the last rendered frame in milliseconds. */
    var frameTimeMs: Float = 0f
        private set

    /** Total number of nodes in the scene (as last reported). */
    var nodeCount: Int = 0
        private set

    private var lastFrameNanos = 0L
    private var lastFrameUptimeMs = 0L
    private var frameCount = 0
    private var fpsAccumulator = 0f

    /**
     * Whether the scene has presented no frame for [IDLE_AFTER_MS] — it is parked, not slow.
     *
     * [fps] keeps the last value it measured, because there is no such thing as a rate over zero
     * frames. Showing that number on a parked scene is the counter lying: it reads "60 fps" on a
     * screen the renderer has not touched for a minute. Ask this first and say so.
     */
    fun isIdle(nowUptimeMillis: Long = SystemClock.uptimeMillis()): Boolean =
        lastFrameUptimeMs == 0L || nowUptimeMillis - lastFrameUptimeMs > IDLE_AFTER_MS

    /**
     * Call this once per frame from the `onFrame` callback to update timing stats.
     *
     * ```kotlin
     * val stats = rememberDebugStats()
     * SceneView(onFrame = { frameNanos -> stats.onFrame(frameNanos, nodeCount = 12) }) { ... }
     * ```
     */
    fun onFrame(frameTimeNanos: Long, nodeCount: Int = 0) {
        val lastNanos = lastFrameNanos
        if (lastNanos > 0) {
            val deltaMs = (frameTimeNanos - lastNanos) / 1_000_000f
            frameTimeMs = deltaMs
            fpsAccumulator += if (deltaMs > 0f) 1000f / deltaMs else 0f
            frameCount++

            // Update FPS as a rolling average every 30 frames.
            if (frameCount >= 30) {
                fps = fpsAccumulator / frameCount
                frameCount = 0
                fpsAccumulator = 0f
            }
        }
        lastFrameNanos = frameTimeNanos
        lastFrameUptimeMs = SystemClock.uptimeMillis()
        this.nodeCount = nodeCount
    }

    companion object {
        /**
         * How long without a presented frame counts as parked.
         *
         * Comfortably longer than the slowest cadence a scene can legitimately be running at —
         * [io.github.sceneview.FrameRatePolicy.maxFps] rejects anything below 1 fps at
         * construction — so a slow scene is never mislabelled idle.
         */
        const val IDLE_AFTER_MS = 1_200L
    }
}

/**
 * Creates and remembers a [DebugStats] instance.
 *
 * Wire it into the `SceneView` `onFrame` callback:
 * ```kotlin
 * val stats = rememberDebugStats()
 * SceneView(onFrame = { stats.onFrame(it, nodeCount = 5) }) { ... }
 * DebugOverlay(stats)
 * ```
 */
@ExperimentalSceneViewApi
@Composable
fun rememberDebugStats(): DebugStats = remember { DebugStats() }

/**
 * A semi-transparent overlay that displays real-time performance metrics.
 *
 * Place this composable alongside (not inside) a `SceneView { }` composable, typically in a `Box`:
 *
 * ```kotlin
 * Box {
 *     SceneView(onFrame = { stats.onFrame(it) }) { ... }
 *     DebugOverlay(stats, modifier = Modifier.align(Alignment.TopStart))
 * }
 * ```
 *
 * Displays:
 * - **FPS** — smoothed frames per second, or `idle` when the scene is parked
 * - **Frame** — last frame time in milliseconds, replaced by `rendering on demand` when parked
 * - **Nodes** — total scene node count
 *
 * @param stats    The [DebugStats] instance updated from `onFrame`.
 * @param modifier Modifier for positioning and sizing the overlay.
 */
@ExperimentalSceneViewApi
@Composable
fun DebugOverlay(
    stats: DebugStats,
    modifier: Modifier = Modifier
) {
    // This tick is the overlay's only recomposition driver: [DebugStats] holds plain fields and
    // notifies nothing, precisely so that a scene rendering on demand is not kept recomposing by
    // the counter watching it. Four readings a second is all a counter needs.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            tick++
        }
    }
    // Read tick to trigger recomposition
    @Suppress("UNUSED_EXPRESSION")
    tick

    val isIdle = stats.isIdle()

    Column(
        modifier = modifier
            .background(Color(0xAA000000.toInt()))
            .padding(8.dp)
    ) {
        val monoFamily = FontFamily.Monospace

        BasicText(
            text = if (isIdle) "FPS: idle" else "FPS: %.1f".format(stats.fps),
            style = TextStyle(
                color = when {
                    isIdle -> Color.Cyan
                    stats.fps >= 55f -> Color.Green
                    stats.fps >= 30f -> Color.Yellow
                    else -> Color.Red
                },
                fontSize = 12.sp,
                fontFamily = monoFamily
            )
        )
        BasicText(
            text = if (isIdle) "rendering on demand" else "Frame: %.1f ms".format(stats.frameTimeMs),
            style = TextStyle(
                color = if (isIdle) Color.Cyan else Color.White,
                fontSize = 12.sp,
                fontFamily = monoFamily
            )
        )
        BasicText(
            text = "Nodes: %d".format(stats.nodeCount),
            style = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = monoFamily
            )
        )
    }
}
