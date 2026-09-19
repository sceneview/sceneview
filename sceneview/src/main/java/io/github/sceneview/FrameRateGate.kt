package io.github.sceneview

import androidx.annotation.RestrictTo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.google.android.filament.Scene
import java.util.WeakHashMap

/**
 * How long frames keep being drawn after the last invalidation.
 *
 * Same reason as the web SDK's `RenderGate` settle window: Filament finalises texture uploads, IBL
 * prefiltering and shadow map work across several frames after the change that triggered them, so a
 * gate that stops on the first clean frame leaves an untextured model — or an unlit one — on screen
 * until something else happens to wake it. Half a second is cheap once, and it is the difference
 * between "render-on-demand" and "render-on-demand, mostly".
 *
 * **A duration, not a frame count.** The work being waited on is Filament's, and Filament's is
 * measured in wall-clock time — a texture upload does not get faster because the panel refreshes
 * faster. A budget of 30 *frames* was half a second on a 60 Hz panel and a quarter of a second on a
 * 120 Hz one, so the newer and faster the device, the shorter the tail it got; and with a
 * [FrameRatePolicy.maxFps] cap in play, 30 frames at 5 fps would have been a six-second tail. The
 * clock that drives it is the render loop's own `withFrameNanos` timestamp, so it stays testable on
 * a virtual clock and never reads the system clock behind the caller's back.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
const val SETTLE_DURATION_NANOS: Long = 500_000_000L

/**
 * Decides, once per frame, whether the GPU submit happens — the Android port of the web SDK's
 * `RenderGate` (`sceneview-web/src/jsMain/kotlin/io/github/sceneview/web/RenderGate.kt`).
 *
 * Two safety properties are deliberate, and both err towards drawing:
 *
 * - It starts **dirty**, so the very first frame after attach is always presented.
 * - Any doubt re-arms the whole settle budget rather than shortening it.
 *
 * The gate never decides to *stop the loop*; it only answers "submit this tick?". Parking the
 * coroutine is [SceneView]'s decision, taken only when [isSettled] reads `true` — i.e. the budget is
 * spent **and** nothing is pending. That split is what keeps `session.update()`, `updateLoad()` and
 * the node ticks running on a tick whose GPU work was skipped.
 *
 * Not thread-safe by design: every caller is the main/render thread.
 *
 * Library-group API, not app API: `arsceneview` builds one too, so that a virtual-content change
 * landing between two ARCore camera images is presented rather than waiting for the sensor.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
class FrameRateGate @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX) constructor(
    private val settleDurationNanos: Long = SETTLE_DURATION_NANOS
) {

    // Snapshot state, because parking reads it through `derivedStateOf` and a plain field would
    // never wake the suspended loop (#3108: the park must be woken by a snapshot apply, not polled).
    private val dirtyState = mutableStateOf(true)

    /**
     * The frame time at which the settle window closes. Only meaningful while [owesSettle] is
     * `true`, which is why the gate does not need a sentinel for "no tick seen yet": it starts owing
     * frames, and the first [shouldRender] sets the deadline from the first real frame time.
     */
    private var settleUntilNanos: Long = 0L

    /**
     * Whether the settle window is still open. A plain field, deliberately — the park site reads it
     * outside `withFrameNanos` and the loop is awake by definition for as long as it is `true`, so
     * making it snapshot state would apply a snapshot per frame on every rendering scene, which is
     * the exact per-frame cost this whole design exists to remove.
     */
    private var owesSettle: Boolean = true

    /** `true` while an invalidation is waiting to be consumed by the next [shouldRender]. */
    val isDirty: Boolean get() = dirtyState.value

    /** `true` when the window is closed and nothing is pending — the only state safe to park in. */
    val isSettled: Boolean get() = !owesSettle && !dirtyState.value

    /**
     * Marks the scene changed. Safe to call from any of the push sources, including several times
     * per frame — it is idempotent until the next [shouldRender] consumes it.
     */
    fun requestRender() {
        // Guarded write: a scene that invalidates every frame (a playing animation) would otherwise
        // publish a snapshot apply per frame, waking every observer of this state for nothing.
        if (!dirtyState.value) dirtyState.value = true
    }

    /**
     * Answers "submit this tick?", and re-arms the settle window when [active] or a pending
     * invalidation says the picture is still moving.
     *
     * @param active whether any *pull* source is live this tick — a gesture in flight, a coasting
     * manipulator, a playing animation, an async load, a recording. Continuous activity therefore
     * keeps the window open and renders continuously, without a per-frame [requestRender].
     * @param frameTimeNanos the loop's frame time for this tick (`withFrameNanos`). It is the only
     * clock the settle window uses, so a test drives it with whatever cadence it wants to model.
     */
    fun shouldRender(active: Boolean, frameTimeNanos: Long): Boolean {
        if (dirtyState.value || active) {
            settleUntilNanos = frameTimeNanos + settleDurationNanos
            owesSettle = true
            if (dirtyState.value) dirtyState.value = false
        }
        return owesSettle
    }

    /**
     * Closes the settle window once [frameTimeNanos] has reached its deadline. Called only when a
     * frame really reached the surface: it is presented frames that carry Filament's upload and
     * prefiltering work, so a window closed on ticks that drew nothing would not have waited for
     * anything.
     */
    fun didRender(frameTimeNanos: Long) {
        if (owesSettle && frameTimeNanos >= settleUntilNanos) owesSettle = false
    }
}

/**
 * Wakes a [FrameRatePolicy.OnDemand] scene that changed in a way the library cannot observe.
 *
 * Everything the SDK owns — node transforms, animations, gestures, loads, surface changes — already
 * invalidates on its own. This is the escape hatch for the rest: a Filament material parameter or
 * light property written directly, an external simulation stepping the scene, or a `PixelCopy` or
 * screenshot that needs a fresh frame on the surface first — request it, then wait for your next
 * `onFrame` before reading the surface, because the request itself is fire-and-forget and there is
 * no "await one frame" API to hand you.
 *
 * ```kotlin
 * val invalidator = rememberRenderInvalidator()
 * SceneView(renderInvalidator = invalidator, modifier = Modifier.fillMaxSize()) { … }
 *
 * // later, after writing straight into Filament:
 * materialInstance.setParameter("baseColorFactor", color)
 * invalidator.requestRender()
 * ```
 *
 * Calls made before the view is attached are remembered and applied on attach, so an invalidation
 * racing composition is never lost.
 */
class RenderInvalidator {

    // Volatile, unlike [FrameRateGate]'s own state: this is the *public* escape hatch, and the
    // migration guide points it at "a texture updated off-thread". The visibility of the handoff
    // is therefore guaranteed here; what still has to happen on the main thread is the gate's own
    // `requestRender`, which is why the KDoc below says so rather than leaving it to be found.
    @Volatile
    private var gate: FrameRateGate? = null

    @Volatile
    private var pending: Boolean = false

    /**
     * Requests one more rendered frame. Cheap, idempotent, safe to over-call.
     *
     * **Call it on the main thread.** A background thread that finished a texture upload or a
     * simulation step should post here rather than call across — the gate it forwards to is the
     * render loop's own state and is not thread-safe. The request itself is never lost either way:
     * an invalidator that has not been attached yet remembers one and replays it on attach.
     */
    fun requestRender() {
        val currentGate = gate
        if (currentGate != null) currentGate.requestRender() else pending = true
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun attach(gate: FrameRateGate) {
        this.gate = gate
        if (pending) {
            pending = false
            gate.requestRender()
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    fun detach(gate: FrameRateGate) {
        if (this.gate === gate) this.gate = null
    }
}

/**
 * Remembers a [RenderInvalidator] for the current composition, to pass to
 * [SceneView]`(renderInvalidator = …)`.
 */
@Composable
fun rememberRenderInvalidator(): RenderInvalidator = remember { RenderInvalidator() }

/**
 * Maps a Filament [Scene] to the [RenderInvalidator] of the view rendering it, so a
 * [io.github.sceneview.node.Node] can reach its view's gate from
 * [io.github.sceneview.node.Node.attachedScene] alone — the one link every managed node has,
 * including the sub-nodes a glTF hierarchy creates.
 *
 * Mirrors `EngineDestroyQueue.of(engine)`: a [WeakHashMap] keyed on the Filament object, so a
 * destroyed scene's entry disappears with it and no view is kept alive by this registry.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
object SceneRenderInvalidators {

    private val invalidators = WeakHashMap<Scene, RenderInvalidator>()

    @Synchronized
    fun register(scene: Scene, invalidator: RenderInvalidator) {
        invalidators[scene] = invalidator
    }

    @Synchronized
    fun unregister(scene: Scene) {
        invalidators.remove(scene)
    }

    @Synchronized
    fun of(scene: Scene): RenderInvalidator? = invalidators[scene]
}
