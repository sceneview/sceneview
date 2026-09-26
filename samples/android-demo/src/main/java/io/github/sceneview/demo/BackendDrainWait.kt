package io.github.sceneview.demo

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import com.google.android.filament.Engine
import com.google.android.filament.Fence

/**
 * "Has Filament's backend executed everything queued so far?", asked **without blocking the
 * main thread** (#3799).
 *
 * The demo used to answer it with `Engine.flushAndWait()`, which waits with no bound. On a
 * software GL, the backend links every material program the first frames need before it reaches
 * the fence. On `emulator-5554` that took long enough for the main thread to sit in
 * `Fence.wait(WAIT_FOR_EVER)` for seconds. A BACK press or focus change queued behind it
 * crossed the 5 s input-dispatch limit, and the system raised an ANR.
 *
 * This creates the same fence once, then polls it with a zero timeout every [DRAIN_POLL_MS] on
 * the main looper. `Fence.wait(FLUSH, 0)` flushes the command stream and returns at once. The
 * flush matters: a render-on-demand scene that has parked queues no frame that would flush the
 * fence for us. The answer is the same driver truth `flushAndWait` gave, delivered as a
 * callback instead of a stall.
 *
 * One-shot: [start] is a no-op after its first call. [cancel] runs when the owner leaves the
 * composition. Remembered after `rememberEngine()`, it is forgotten before the engine is
 * destroyed, so the fence is released first and a poll never touches a dead engine.
 */
internal class BackendDrainWait(
    private val newProbe: () -> DrainProbe?,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    private val cancelScheduled: () -> Unit = {},
) : RememberObserver {

    private var started = false
    private var probe: DrainProbe? = null

    /** `true` while a fence is out and not yet signalled. */
    val isWaiting: Boolean get() = probe != null

    /**
     * Starts waiting. [onDrained] runs on the main thread once the backend has executed
     * everything queued before this call, or at once if the engine can no longer answer.
     * Calls after the first one (and after [cancel]) do nothing.
     */
    fun start(onDrained: () -> Unit) {
        if (started) return
        started = true
        val current = newProbe()
        if (current == null) {
            onDrained()
            return
        }
        probe = current
        poll(current, onDrained)
    }

    private fun poll(current: DrainProbe, onDrained: () -> Unit) {
        if (probe !== current) return // cancelled meanwhile
        if (current.isDrained()) {
            probe = null
            current.release()
            onDrained()
        } else {
            schedule(DRAIN_POLL_MS) { poll(current, onDrained) }
        }
    }

    /** Stops waiting and releases the fence. A pending `onDrained` never runs. */
    fun cancel() {
        started = true
        val current = probe ?: return
        probe = null
        cancelScheduled()
        current.release()
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = cancel()

    override fun onAbandoned() = cancel()
}

/** One outstanding "has the backend caught up?" question. */
internal interface DrainProbe {
    /**
     * `true` once the backend has executed everything queued before the probe was made, or can
     * no longer tell (engine gone, fence error). Never blocks.
     */
    fun isDrained(): Boolean

    /** Frees whatever the probe holds. Called exactly once. */
    fun release()
}

/**
 * Poll interval of [BackendDrainWait]: one frame. Each poll is a zero-timeout fence check, so
 * the main thread never waits on the backend for longer than the call itself.
 */
internal const val DRAIN_POLL_MS = 16L

/**
 * Remembers a [BackendDrainWait] bound to [engine]. Call it **after** the `rememberEngine()` it
 * receives, so leaving the composition cancels it before the engine goes away.
 */
@Composable
internal fun rememberBackendDrainWait(engine: Engine): BackendDrainWait =
    remember(engine) { filamentBackendDrainWait(engine) }

/** A [BackendDrainWait] that polls a Filament fence from the main looper. */
internal fun filamentBackendDrainWait(engine: Engine): BackendDrainWait {
    val handler = Handler(Looper.getMainLooper())
    return BackendDrainWait(
        newProbe = { FenceDrainProbe.create(engine) },
        schedule = { delayMs, block -> handler.postDelayed(block, delayMs) },
        cancelScheduled = { handler.removeCallbacksAndMessages(null) },
    )
}

private class FenceDrainProbe(
    private val engine: Engine,
    private val fence: Fence,
) : DrainProbe {

    override fun isDrained(): Boolean {
        if (!engine.isValid) return true
        val status = runCatching { fence.wait(Fence.Mode.FLUSH, 0L) }.getOrNull()
        return status != Fence.FenceStatus.TIMEOUT_EXPIRED
    }

    override fun release() {
        // A destroyed engine has already reclaimed its fences.
        if (engine.isValid) runCatching { engine.destroyFence(fence) }
    }

    companion object {
        fun create(engine: Engine): FenceDrainProbe? {
            if (!engine.isValid) return null
            return runCatching { FenceDrainProbe(engine, engine.createFence()) }.getOrNull()
        }
    }
}
