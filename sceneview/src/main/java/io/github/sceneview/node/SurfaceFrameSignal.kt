package io.github.sceneview.node

/**
 * The rule a node follows when its picture is produced by something outside the library.
 *
 * [VideoNode] and [ViewNode] share one shape: a `SurfaceTexture` fed by a producer the library does
 * not drive — a `MediaPlayer` decoding, or an Android `View` hierarchy redrawing on its own
 * schedule. Nothing in the scene graph changes when a new picture lands, so under
 * [io.github.sceneview.FrameRatePolicy.OnDemand] nothing would invalidate and the scene would keep
 * presenting a stale frame with no error, no cadence anomaly and a correctly parked loop.
 *
 * Both nodes first answered this by never parking at all — `player.isPlaying`, or a flat `true`.
 * Both answers were wrong in the same direction, and silently:
 *
 *  * `isPlaying` misses a **seek** or a **frame-step** on a paused player: exactly one new frame,
 *    with `isPlaying` false from beginning to end, so the scene kept the picture from before it.
 *  * a flat `true` never misses a frame, but it also never lets a scene settle — a single decorative
 *    label hosted in a [ViewNode] held a whole scene at full cadence for as long as it existed.
 *
 * The signal that is neither is the **surface's own**: `setOnFrameAvailableListener` fires once per
 * buffer the producer queues, which is the definition of "there is a new picture to draw". It is
 * *latched*, because the callback can land at any point in a tick — including after the gate has
 * already been asked — and one frame must not be lost to that race. [onFrameAvailable] both wakes
 * the loop immediately (push) and arms one tick of [isActive] (pull), the belt-and-braces pairing
 * the rest of the gate uses.
 *
 * [isActive]'s `forcedActive` is for what the latch alone would under-report: a playing video
 * produces frames continuously, and reading that from the player keeps the scene at full cadence
 * even on a device whose callback delivery lags behind the decoder. A hosted view has no such
 * question to ask and passes the default.
 */
internal class SurfaceFrameSignal(private val requestRender: () -> Unit) {

    private var frameSinceLastTick = false

    /** A frame reached the surface. Call on the main thread. */
    fun onFrameAvailable() {
        frameSinceLastTick = true
        requestRender()
    }

    /**
     * Whether the node should report itself active this tick. Consuming: the latch is cleared on
     * read, so one seek — or one ripple — holds the scene awake for one tick rather than forever.
     */
    fun isActive(forcedActive: Boolean = false): Boolean {
        val hadFrame = frameSinceLastTick
        frameSinceLastTick = false
        return forcedActive || hadFrame
    }
}
